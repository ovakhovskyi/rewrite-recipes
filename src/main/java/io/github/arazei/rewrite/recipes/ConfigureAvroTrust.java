package io.github.arazei.rewrite.recipes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.openrewrite.Column;
import org.openrewrite.Option;
import org.openrewrite.Validated;
import org.openrewrite.DataTable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.Scope;
import org.openrewrite.maven.tree.Plugin;
import org.openrewrite.maven.tree.ResolvedPom;
import org.openrewrite.quark.Quark;
import org.openrewrite.text.PlainText;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public final class ConfigureAvroTrust extends ScanningRecipe<ConfigureAvroTrust.Scan> {
    private static final Path SRC = Path.of("src");
    private static final Set<Path> TEST_SOURCES = Set.of(Path.of("src/test"), Path.of("src/it"), Path.of("src/testFixtures"));
    private static final Path JAVA = Path.of("src/main/java");
    private static final Path FACTORIES = Path.of("src/main/resources/META-INF/spring.factories");
    private static final String APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication";
    private static final String AVRO_GENERATED = "org.apache.avro.specific.AvroGenerated";
    private final transient Decisions decisions = new Decisions(this);

    @Option(displayName = "Trusted model package",
            description = "Exact Java package of the production Avro models. Defaults to all discovered production Avro model packages.",
            example = "com.example.events.avro", required = false)
    private final String trustedPackage;

    @Option(displayName = "Trusted package prefixes",
            description = "Allow the named Java packages and their subpackages if at least one production Avro model matches. Cannot be combined with trustedPackage.",
            example = "[ua.raif, ua.raiffeisen]", required = false)
    private final List<String> trustedPackagePrefixes;

    public ConfigureAvroTrust() {
        this(null);
    }

    public ConfigureAvroTrust(String trustedPackage) {
        this(trustedPackage, null);
    }

    @JsonCreator
    public ConfigureAvroTrust(@JsonProperty("trustedPackage") String trustedPackage,
                              @JsonProperty("trustedPackagePrefixes") List<String> trustedPackagePrefixes) {
        this.trustedPackage = trustedPackage;
        this.trustedPackagePrefixes = trustedPackagePrefixes == null ? null :
                Collections.unmodifiableList(new ArrayList<>(trustedPackagePrefixes));
    }

    public String getTrustedPackage() {
        return trustedPackage;
    }

    public List<String> getTrustedPackagePrefixes() {
        return trustedPackagePrefixes;
    }

    @Override public Validated<Object> validate() {
        return super.validate()
                .and(Validated.test("trustedPackage", "must be one exact Java package name",
                        trustedPackage, value -> value == null || SourceVersion.isName(value)))
                .and(Validated.test("trustedPackagePrefixes", "must be a non-empty list of Java package names",
                        trustedPackagePrefixes, ConfigureAvroTrust::validPrefixes))
                .and(Validated.test("packageSelection", "trustedPackage and trustedPackagePrefixes are mutually exclusive",
                        trustedPackagePrefixes, value -> value == null || trustedPackage == null));
    }

    private static boolean validPrefixes(List<String> prefixes) {
        return prefixes == null || (!prefixes.isEmpty() &&
                prefixes.stream().allMatch(name -> name != null && SourceVersion.isName(name)));
    }

    @Override public String getDisplayName() { return "Register the application's Avro model packages"; }
    @Override public String getDescription() {
        return "Register exact Avro model packages or explicitly configured package prefixes for Spring Boot modules using Apache Avro 1.12.2.";
    }
    @Override public Scan getInitialValue(ExecutionContext ctx) { return new Scan(); }

    static final class Scan {
        final Map<Path, Module> modules = new LinkedHashMap<>();
        final Map<Path, SourceFile> sources = new HashMap<>();
        final Map<Path, SourceFile> edits = new HashMap<>();
    }

    @Override public TreeVisitor<?, ExecutionContext> getScanner(Scan scan) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile file) {
                    Path path = file.getSourcePath().normalize();
                    if (path.getFileName().toString().equals("pom.xml")) {
                        Module module = file.getMarkers().findFirst(MavenResolutionResult.class)
                                .filter(model -> supported(model, Scope.Compile) && supported(model, Scope.Runtime))
                                .map(model -> new Module(file, model.getPom()))
                                .orElse(null);
                        scan.modules.put(path.resolveSibling("").normalize(), module);
                    } else if (path.endsWith(FACTORIES) || path.toString().endsWith(".java") ||
                            path.toString().endsWith(".avsc")) {
                        scan.sources.put(path, file);
                    }
                }
                return tree;
            }
        };
    }

    private static boolean supported(MavenResolutionResult model, Scope scope) {
        return model.findDependencies("org.apache.avro", "avro", scope).stream()
                .anyMatch(dep -> "1.12.2".equals(dep.getVersion()) && "jar".equals(dep.getType()) &&
                        (dep.getClassifier() == null || dep.getClassifier().isEmpty()));
    }

    private static final class Module {
        final List<String> applications = new ArrayList<>();
        final Set<String> models = new TreeSet<>();
        final Set<Path> schemaRoots = new HashSet<>(Set.of(SRC));
        final Map<String, Path> classes = new HashMap<>();
        final SourceFile pomSource;
        Optional<Path> localDirectory;
        boolean opaqueSchema;

        Module(SourceFile pomSource, ResolvedPom pom) {
            this.pomSource = pomSource;
            for (Plugin plugin : pom.getPlugins()) {
                if (!"org.apache.avro".equals(plugin.getGroupId()) || !"avro-maven-plugin".equals(plugin.getArtifactId())) {
                    continue;
                }
                if (plugin.getConfiguration() != null) {
                    addSchemaRoot(pom, plugin.getConfiguration().path("sourceDirectory").asText(null));
                }
                for (Plugin.Execution execution : plugin.getExecutions()) {
                    if (execution.getConfiguration() != null && execution.getGoals() != null &&
                            execution.getGoals().contains("schema")) {
                        addSchemaRoot(pom, execution.getConfiguration().path("sourceDirectory").asText(null));
                    }
                }
            }
        }

        private void addSchemaRoot(ResolvedPom pom, String configured) {
            if (configured == null || configured.isBlank()) {
                return;
            }
            String expression = configured.strip().replaceFirst("^\\$\\{(?:project\\.)?basedir}/", "");
            String resolved = pom.getValue(expression);
            if (resolved == null || resolved.contains("${")) {
                return;
            }
            Path dir = Path.of(resolved).normalize();
            if (!dir.isAbsolute() && !dir.startsWith("..") && !dir.toString().isEmpty()) {
                schemaRoots.add(dir);
            }
        }

        void inspect(Path path, SourceFile file) {
            if (TEST_SOURCES.stream().anyMatch(path::startsWith)) {
                return;
            }
            if (path.toString().endsWith(".avsc") && schemaRoots.stream().anyMatch(path::startsWith)) {
                inspectSchema(path, file);
            }
            boolean main = path.startsWith(JAVA);
            if (!(file instanceof J.CompilationUnit unit) || !(path.startsWith(SRC) || path.startsWith("target/generated-sources"))) {
                return;
            }
            String pkg = unit.getPackageDeclaration() == null ? "" : unit.getPackageDeclaration().getPackageName();
            for (J.ClassDeclaration type : unit.getClasses()) {
                classes.put(pkg + "." + type.getSimpleName(), file.getSourcePath());
                for (J.Annotation annotation : type.getLeadingAnnotations()) {
                    if (main && TypeUtils.isOfClassType(annotation.getType(), APPLICATION)) {
                        applications.add(pkg);
                    }
                    if (TypeUtils.isOfClassType(annotation.getType(), AVRO_GENERATED)) {
                        models.add(pkg);
                    }
                }
            }
        }

        private void inspectSchema(Path path, SourceFile file) {
            try {
                if (file instanceof PlainText text) {
                    models.addAll(AvroSchemaPackages.read(text.getText()));
                    return;
                }
                if (file instanceof Quark schema) {
                    if (localDirectory == null) {
                        localDirectory = LocalAvroSchemas.moduleDirectory(pomSource);
                    }
                    if (localDirectory.isPresent()) {
                        models.addAll(LocalAvroSchemas.read(localDirectory.get(), path, schema));
                        return;
                    }
                }
            } catch (IOException unreadable) {
                // Keep inspecting other schemas and generated models in this module.
            }
            opaqueSchema = true;
        }
    }

    @Override public Collection<? extends SourceFile> generate(Scan scan, ExecutionContext ctx) {
        scan.modules.forEach((root, module) -> {
            if (module == null) {
                report(ctx, root, "No resolved Avro 1.12.2 JAR on both production classpaths");
            }
        });
        scan.sources.forEach((path, file) -> {
            Path root = path;
            while (root != null && !scan.modules.containsKey(root)) {
                root = root.getParent();
            }
            Module module = scan.modules.get(root == null ? Path.of("") : root);
            if (module != null) {
                module.inspect(root == null ? path : root.relativize(path), file);
            }
        });
        List<SourceFile> created = new ArrayList<>();
        scan.modules.forEach((root, module) -> {
            if (module != null) {
                plan(root, module, scan, ctx, created);
            }
        });
        return created;
    }

    private void plan(Path root, Module module, Scan scan, ExecutionContext ctx, List<SourceFile> created) {
        if (!validPrefixes(trustedPackagePrefixes) || (trustedPackagePrefixes != null && trustedPackage != null)) {
            report(ctx, root, "Invalid trustedPackagePrefixes configuration");
            return;
        }
        if (module.applications.size() != 1 || module.applications.get(0).isEmpty()) {
            report(ctx, root, "Expected one production @SpringBootApplication in a named package");
            return;
        }
        String base = module.applications.get(0);
        List<String> modelPackages = module.models.stream()
                .filter(SourceVersion::isName)
                .filter(name -> trustedPackage == null || trustedPackage.equals(name))
                .filter(name -> trustedPackagePrefixes == null || trustedPackagePrefixes.stream()
                        .anyMatch(prefix -> name.equals(prefix) || name.startsWith(prefix + ".")))
                .toList();
        if (modelPackages.isEmpty()) {
            String reason = module.opaqueSchema ? "Unable to read local Avro schema; check the schema and project paths" :
                    "No matching production Avro model packages; found packages: " + module.models;
            report(ctx, root, reason);
            return;
        }
        String name = base + ".config.AvroTrustInitializer";
        Path javaPath = root.resolve(JAVA).resolve(name.replace('.', '/') + ".java");
        Path factoriesPath = root.resolve(FACTORIES);
        String source = trustedPackagePrefixes == null ? Initializer.source(base, modelPackages) :
                Initializer.prefixSource(base, trustedPackagePrefixes.stream().distinct().sorted().toList());
        SourceFile existing = scan.sources.get(javaPath);
        String previous = existing == null ? "" : existing.printAll().replace("\r\n", "\n").strip();
        Path declaration = module.classes.get(name);
        if ((declaration != null && !declaration.equals(javaPath)) ||
                (existing != null && (!(existing instanceof J.CompilationUnit || existing instanceof PlainText) ||
                        !Initializer.isGeneratedSource(base, previous)))) {
            report(ctx, root, "Conflicting implementation of " + name);
            return;
        }
        SourceFile factories = scan.sources.get(factoriesPath);
        if (factories != null && !(factories instanceof PlainText)) {
            report(ctx, root, "spring.factories is not plain text; check parser masks");
            return;
        }
        String original = factories == null ? "" : ((PlainText) factories).getText();
        String updated;
        try {
            updated = Factories.register(original, name);
        } catch (IllegalArgumentException conflict) {
            report(ctx, root, conflict.getMessage());
            return;
        }
        if (!previous.equals(source.strip())) {
            String javaText = existing != null && existing.printAll().contains("\r\n")
                    ? source.replace("\n", "\r\n") : source;
            SourceFile generated = JavaParser.fromJavaVersion().build().parse(ctx, javaText).findFirst().orElseThrow();
            if (!(generated instanceof J.CompilationUnit unit)) {
                throw new IllegalStateException("Cannot parse generated " + name);
            }
            unit = unit.withSourcePath(javaPath);
            if (existing == null) {
                created.add(unit);
            } else {
                scan.edits.put(javaPath, unit.withId(existing.getId()).withMarkers(existing.getMarkers())
                        .withCharset(existing.getCharset()).withCharsetBomMarked(existing.isCharsetBomMarked())
                        .withFileAttributes(existing.getFileAttributes()));
            }
        }
        if (factories == null) {
            created.add(PlainText.builder().sourcePath(factoriesPath).text(updated).build());
        } else if (!updated.equals(original)) {
            scan.edits.put(factoriesPath, ((PlainText) factories).withText(updated));
        }
    }

    @Override public TreeVisitor<?, ExecutionContext> getVisitor(Scan scan) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile file) {
                    return scan.edits.getOrDefault(file.getSourcePath().normalize(), file);
                }
                return tree;
            }
        };
    }

    private void report(ExecutionContext ctx, Path root, String reason) {
        decisions.insertRow(ctx, new Decisions.Row(root.resolve("pom.xml").toString(), reason));
    }

    public static final class Decisions extends DataTable<Decisions.Row> {
        Decisions(ConfigureAvroTrust recipe) {
            super(recipe, "Skipped Avro trust modules", "Maven modules skipped and the reason for each decision.");
        }
        public record Row(
                @Column(displayName = "POM", description = "Module POM path.") String pom,
                @Column(displayName = "Reason", description = "Reason for skipping the module.") String reason) {}
    }
}
