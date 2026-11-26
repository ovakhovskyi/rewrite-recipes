package io.github.arazei.rewrite.recipes.migration.boot4;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.text.PlainText;
import org.openrewrite.text.PlainTextParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.arazei.rewrite.recipes.migration.boot4.MigrateSpringBootDockerfile.fromPattern;
import static io.github.arazei.rewrite.recipes.migration.boot4.MigrateSpringBootDockerfile.instructions;

public final class MigrateSpringBootLayeredDockerfile extends Recipe {
    private static final Pattern ALIAS = Pattern.compile("(?i)\\bAS[ \\t]+([\\w.-]+)[ \\t]*$");
    private static final Pattern JAR_COPY = Pattern.compile("(?i:^[ \\t]*COPY)[ \\t]+\\$\\{JAR_FILE}[ \\t]+(app\\.jar)[ \\t]*");
    private static final Pattern EXTRACT = Pattern.compile("(?i:^[ \\t]*RUN)[ \\t]+(java[ \\t]+-Djarmode=layertools[ \\t]+-jar[ \\t]+app\\.jar[ \\t]+extract)[ \\t]*");
    private static final Pattern TOOLS_EXTRACT = Pattern.compile("(?i:^[ \\t]*RUN)[ \\t]+java[ \\t]+-Djarmode=tools[ \\t]+-jar[ \\t]+" +
            "([\\w.-]+\\.jar)[ \\t]+extract[ \\t]+--layers[ \\t]+--destination[ \\t]+([\\w-][\\w.-]*)/?[ \\t]*");
    private static final Pattern LAYER_COPY = Pattern.compile("(?i:^[ \\t]*COPY)[ \\t]+--from=([\\w.-]+)[ \\t]+(/app/)(dependencies|spring-boot-loader|snapshot-dependencies|application)/[ \\t]+\\./[ \\t]*");
    private static final Pattern STANDARD_LAYER_COPY = Pattern.compile("(?i:^[ \\t]*COPY)[ \\t]+--from=([\\w.-]+)[ \\t]+" +
            "(/app/(?:[\\w.-]+/)?)(dependencies|spring-boot-loader|snapshot-dependencies|application)/[ \\t]+\\./[ \\t]*");
    private static final Pattern COPY_FROM = Pattern.compile("(?i:^[ \\t]*COPY\\b).*?--from=([\\w.-]+)(?=\\s)", Pattern.DOTALL);
    private static final Pattern OLD_LAYER_PATH = Pattern.compile("/app/(?:dependencies|spring-boot-loader|snapshot-dependencies|application)(?=/|[\"\\s]|$)");
    private static final Pattern WORKDIR = Pattern.compile("(?i)^[ \\t]*WORKDIR[ \\t]+");
    private static final Pattern ENTRYPOINT = Pattern.compile("(?i)^[ \\t]*ENTRYPOINT[ \\t]+");

    @Override
    public String getDisplayName() {
        return "Migrate Spring Boot layered Dockerfiles from layertools to tools";
    }

    @Override
    public String getDescription() {
        return "Migrate matching layered Dockerfiles to tools, including fixing runtime entrypoints for existing tools layouts, while retaining JVM arguments.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof SourceFile file) || !file.getSourcePath().endsWith("Dockerfile")) {
                    return tree;
                }
                PlainText text = PlainTextParser.convert(file);
                String original = text.getText();
                String updated = migrate(original);
                return original.equals(updated) ? tree : text.withText(updated);
            }
        };
    }

    private static String migrate(String text) {
        List<Stage> stages = new ArrayList<>();
        Stage stage = null;
        for (var instruction : instructions(text)) {
            Matcher from = fromPattern(instruction.escape()).matcher(instruction.text());
            if (from.find()) {
                Matcher alias = ALIAS.matcher(instruction.text().substring(from.end(2)));
                stage = new Stage(alias.find() ? alias.group(1) : null, new ArrayList<>());
                stages.add(stage);
            } else if (stage != null) {
                stage.body().add(instruction);
            }
        }

        List<Edit> edits = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            Stage builder = stages.get(i);
            if (builder.alias() == null || stages.stream().filter(s -> builder.alias().equalsIgnoreCase(s.alias())).count() != 1) {
                continue;
            }
            Edit jar = singleEdit(builder, JAR_COPY, "application.jar");
            Edit extract = singleEdit(builder, EXTRACT,
                    "java -Djarmode=tools -jar application.jar extract --layers --destination extracted");
            if (jar == null || extract == null || jar.start() >= extract.start() || !usesAppWorkdir(builder, jar.start())) {
                edits.addAll(toolsEntrypoints(builder, stages.subList(i + 1, stages.size())));
                continue;
            }

            List<Edit> consumers = new ArrayList<>();
            boolean complete = true;
            for (Stage runtime : stages.subList(i + 1, stages.size())) {
                List<Edit> layerCopies = new ArrayList<>();
                Set<String> layers = new HashSet<>();
                for (var instruction : runtime.body()) {
                    Matcher copy = LAYER_COPY.matcher(instruction.text());
                    if (copy.matches() && builder.alias().equalsIgnoreCase(copy.group(1))) {
                        layers.add(copy.group(3));
                        layerCopies.add(edit(instruction, copy, 2, "/app/extracted/"));
                    } else {
                        Matcher from = COPY_FROM.matcher(instruction.text());
                        if (from.find() && builder.alias().equalsIgnoreCase(from.group(1)) &&
                                OLD_LAYER_PATH.matcher(instruction.text()).find()) {
                            complete = false;
                            break;
                        }
                    }
                }
                if (!complete) {
                    break;
                }
                if (!layerCopies.isEmpty()) {
                    Edit launch = entrypoint(runtime, "application.jar");
                    if (layers.size() != 4 || layerCopies.size() != 4 || launch == null ||
                            !usesAppWorkdir(runtime, layerCopies.getFirst().start())) {
                        complete = false;
                        break;
                    }
                    consumers.addAll(layerCopies);
                    consumers.add(launch);
                }
            }
            if (complete && !consumers.isEmpty()) {
                edits.add(jar);
                edits.add(extract);
                edits.addAll(consumers);
            }
        }

        StringBuilder updated = new StringBuilder(text);
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        for (Edit edit : edits) {
            updated.replace(edit.start(), edit.end(), edit.replacement());
        }
        return updated.toString();
    }

    private static List<Edit> toolsEntrypoints(Stage builder, List<Stage> runtimes) {
        Matcher extract = null;
        for (var instruction : builder.body()) {
            Matcher match = TOOLS_EXTRACT.matcher(instruction.text());
            if (match.matches()) {
                if (extract != null || !usesAppWorkdir(builder, instruction.start())) {
                    return List.of();
                }
                extract = match;
            }
        }
        if (extract == null) {
            return List.of();
        }

        String jar = extract.group(1);
        String directory = "/app/" + extract.group(2) + "/";
        List<Edit> edits = new ArrayList<>();
        for (Stage runtime : runtimes) {
            Edit launch = entrypoint(runtime, jar);
            if (launch == null) {
                continue;
            }
            Set<String> layers = new HashSet<>();
            boolean matches = true;
            for (var instruction : runtime.body()) {
                Matcher copy = STANDARD_LAYER_COPY.matcher(instruction.text());
                if (copy.matches() && (!builder.alias().equalsIgnoreCase(copy.group(1)) ||
                        !directory.equals(copy.group(2)) || !usesAppWorkdir(runtime, instruction.start()) ||
                        !layers.add(copy.group(3)))) {
                    matches = false;
                    break;
                }
            }
            if (matches && layers.size() == 4) {
                edits.add(launch);
            }
        }
        return edits;
    }

    private static boolean usesAppWorkdir(Stage stage, int before) {
        var workdirs = stage.body().stream().filter(instruction -> WORKDIR.matcher(instruction.text()).find()).toList();
        return workdirs.isEmpty() || (workdirs.size() == 1 && workdirs.getFirst().start() < before &&
                workdirs.getFirst().text().matches("(?i:^[ \\t]*WORKDIR)[ \\t]+/app/?[ \\t]*"));
    }

    private static Edit singleEdit(Stage stage, Pattern pattern, String replacement) {
        Edit result = null;
        for (var instruction : stage.body()) {
            Matcher match = pattern.matcher(instruction.text());
            if (match.matches()) {
                if (result != null) {
                    return null;
                }
                result = edit(instruction, match, 1, replacement);
            }
        }
        return result;
    }

    private static Edit entrypoint(Stage stage, String jar) {
        var entries = stage.body().stream().filter(instruction -> ENTRYPOINT.matcher(instruction.text()).find()).toList();
        if (entries.size() != 1) {
            return null;
        }
        var instruction = entries.getFirst();
        String space = "(?:[ \\t]|" + Pattern.quote(String.valueOf(instruction.escape())) + "[ \\t]*\\R)*";
        String argument = "\"(?:[^\"\\\\\\r\\n]|\\\\[^\\r\\n])*\"";
        Matcher match = Pattern.compile("(?i:^[ \\t]*ENTRYPOINT)" + space + "\\[" + space + "\"java\"" + space + "," + space +
                "(?:" + argument + space + "," + space + ")*(\"org\\.springframework\\.boot\\.loader\\.launch\\.JarLauncher\")" +
                space + "\\][ \\t]*").matcher(instruction.text());
        return match.matches() ? edit(instruction, match, 1, "\"-jar\", \"" + jar + "\"") : null;
    }

    private static Edit edit(MigrateSpringBootDockerfile.Instruction instruction, Matcher match, int group, String replacement) {
        return new Edit(instruction.start() + match.start(group), instruction.start() + match.end(group), replacement);
    }

    private record Stage(String alias, List<MigrateSpringBootDockerfile.Instruction> body) {
    }

    private record Edit(int start, int end, String replacement) {
    }
}
