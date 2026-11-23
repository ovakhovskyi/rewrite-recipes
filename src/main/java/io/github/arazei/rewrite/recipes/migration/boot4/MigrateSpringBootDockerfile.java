package io.github.arazei.rewrite.recipes.migration.boot4;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.text.PlainText;
import org.openrewrite.text.PlainTextParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MigrateSpringBootDockerfile extends Recipe {
    private static final String BASE_IMAGE =
            "harbor.shared.avalaunch.rbua/infra-resources/eclipse-temurin-base-image:21-jre-ubi10-minimal";
    private static final Pattern LINE = Pattern.compile("([^\\r\\n]*)(\\r\\n|\\n|\\r|\\z)");
    private static final Pattern ESCAPE = Pattern.compile("(?i)^#[ \\t]*escape[ \\t]*=[ \\t]*([`\\\\])[ \\t]*$");
    private static final Pattern HEREDOC_START = Pattern.compile("(?i)^[ \\t]*(?:RUN|COPY|ADD)[ \\t]+(?:--\\S+[ \\t]+)*<<");
    private static final Pattern HEREDOC = Pattern.compile("<<(-?)['\"]?([\\w.-]+)['\"]?");
    private static final Pattern WORKDIR = Pattern.compile("(?i)^[ \\t]*WORKDIR(?=[ \\t])");

    @Override
    public String getDisplayName() {
        return "Set the corporate base image and working directory in Dockerfiles";
    }

    @Override
    public String getDescription() {
        return "Set every FROM instruction to the corporate Java 21 image and add WORKDIR /app to stages without a WORKDIR.";
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
        for (Instruction instruction : instructions(text)) {
            Matcher from = fromPattern(instruction.escape()).matcher(instruction.text());
            if (from.find()) {
                stage = new Stage(instruction.start() + from.start(2), instruction.start() + from.end(2),
                        instruction.end() + instruction.lineEnding().length(), instruction.lineEnding(), from.group(1));
                stages.add(stage);
            } else if (stage != null && WORKDIR.matcher(instruction.text()).find()) {
                stage.hasWorkdir = true;
            }
        }

        StringBuilder updated = new StringBuilder(text);
        for (Stage entry : stages.reversed()) {
            if (!entry.hasWorkdir) {
                String workdir = entry.indent + "WORKDIR /app";
                updated.insert(entry.end, entry.lineEnding.isEmpty() ? lastLineEnding(text) + workdir : workdir + entry.lineEnding);
            }
            updated.replace(entry.imageStart, entry.imageEnd, BASE_IMAGE);
        }
        return updated.toString();
    }

    static List<Instruction> instructions(String text) {
        List<Instruction> instructions = new ArrayList<>();
        Deque<Heredoc> heredocs = new ArrayDeque<>();
        char escape = '\\';
        boolean directives = true;
        int instructionStart = -1;
        Matcher line = LINE.matcher(text);
        while (line.find() && line.start() < text.length()) {
            String content = line.group(1);
            if (!heredocs.isEmpty()) {
                Heredoc current = heredocs.peekFirst();
                if (current.delimiter().equals(current.stripTabs() ? content.replaceFirst("^\\t+", "") : content)) {
                    heredocs.removeFirst();
                }
                continue;
            }
            if (directives) {
                Matcher directive = ESCAPE.matcher(content);
                if (directive.matches()) {
                    escape = directive.group(1).charAt(0);
                }
                directives = content.matches("^#[ \\t]*[a-zA-Z]+[ \\t]*=.*");
            }
            if (content.isBlank() || content.stripLeading().startsWith("#")) {
                continue;
            }
            if (instructionStart < 0) {
                instructionStart = line.start();
            }
            if (content.stripTrailing().endsWith(String.valueOf(escape))) {
                continue;
            }

            String instruction = text.substring(instructionStart, line.end(1));
            instructions.add(new Instruction(instruction, instructionStart, line.group(2), escape));
            if (HEREDOC_START.matcher(instruction).find()) {
                Matcher marker = HEREDOC.matcher(instruction);
                while (marker.find()) {
                    heredocs.addLast(new Heredoc(marker.group(2), !marker.group(1).isEmpty()));
                }
            }
            instructionStart = -1;
        }

        return instructions;
    }

    record Instruction(String text, int start, String lineEnding, char escape) {
        int end() {
            return start + text.length();
        }
    }

    static Pattern fromPattern(char escape) {
        String separator = "(?:[ \\t]|" + Pattern.quote(String.valueOf(escape)) +
                "[ \\t]*\\R(?:[ \\t]*(?:#[^\\r\\n]*)?\\R)*)+";
        return Pattern.compile("(?i)^([ \\t]*)FROM" + separator + "(?:--platform=[^\\s]+" + separator + ")?([^\\s]+)");
    }

    private static String lastLineEnding(String text) {
        int newline = Math.max(text.lastIndexOf('\n'), text.lastIndexOf('\r'));
        if (newline < 0) {
            return "\n";
        }
        if (text.charAt(newline) == '\n' && newline > 0 && text.charAt(newline - 1) == '\r') {
            return "\r\n";
        }
        return String.valueOf(text.charAt(newline));
    }

    private record Heredoc(String delimiter, boolean stripTabs) {
    }

    private static final class Stage {
        final int imageStart;
        final int imageEnd;
        final int end;
        final String lineEnding;
        final String indent;
        boolean hasWorkdir;

        Stage(int imageStart, int imageEnd, int end, String lineEnding, String indent) {
            this.imageStart = imageStart;
            this.imageEnd = imageEnd;
            this.end = end;
            this.lineEnding = lineEnding;
            this.indent = indent;
        }
    }
}
