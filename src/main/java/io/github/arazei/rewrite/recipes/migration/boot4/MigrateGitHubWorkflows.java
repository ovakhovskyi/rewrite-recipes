package io.github.arazei.rewrite.recipes.migration.boot4;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.yaml.YamlIsoVisitor;
import org.openrewrite.yaml.tree.Yaml;

import java.nio.file.Path;

public class MigrateGitHubWorkflows extends Recipe {
    private static final Path WORKFLOWS = Path.of(".github", "workflows");
    private static final String IMAGE_PREFIX = "harbor.shared.avalaunch.rbua/infra-resources/maven-";
    private static final String MAVEN_TOOL = "maven-3.10";
    private static final String JAVA_VERSION = "25";
    private static final String MAVEN_IMAGE = IMAGE_PREFIX + "3.10:" + JAVA_VERSION;

    @Override
    public String getDisplayName() {
        return "Migrate company GitHub workflows to Java 25";
    }

    @Override
    public String getDescription() {
        return "Update corporate Maven images and Maven tool/version pairs in GitHub Actions workflows.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new YamlIsoVisitor<ExecutionContext>() {
            @Override
            public Yaml.Documents visitDocuments(Yaml.Documents documents, ExecutionContext ctx) {
                Path path = documents.getSourcePath();
                String name = path.getFileName().toString();
                if (!WORKFLOWS.equals(path.getParent()) || !(name.endsWith(".yml") || name.endsWith(".yaml"))) {
                    return documents;
                }
                return super.visitDocuments(documents, ctx);
            }

            @Override
            public Yaml.Mapping visitMapping(Yaml.Mapping mapping, ExecutionContext ctx) {
                Yaml.Mapping visited = super.visitMapping(mapping, ctx);
                boolean mavenTool = visited.getEntries().stream().anyMatch(entry ->
                        entry.getKey().getValue().equals("tool") && entry.getValue() instanceof Yaml.Scalar scalar
                                && scalar.getValue().startsWith("maven-"));
                boolean hasVersion = visited.getEntries().stream().anyMatch(entry ->
                        entry.getKey().getValue().equals("version") && entry.getValue() instanceof Yaml.Scalar);

                return visited.withEntries(ListUtils.map(visited.getEntries(), entry -> {
                    if (!(entry.getValue() instanceof Yaml.Scalar scalar)) {
                        return entry;
                    }
                    String key = entry.getKey().getValue();
                    if (key.equals("image") && scalar.getValue().startsWith(IMAGE_PREFIX)
                            && !MAVEN_IMAGE.equals(scalar.getValue())) {
                        return entry.withValue(scalar.withValue(MAVEN_IMAGE));
                    }
                    if (mavenTool && hasVersion) {
                        if (key.equals("tool") && scalar.getValue().startsWith("maven-")
                                && !MAVEN_TOOL.equals(scalar.getValue())) {
                            return entry.withValue(scalar.withValue(MAVEN_TOOL));
                        }
                        if (key.equals("version") && (!JAVA_VERSION.equals(scalar.getValue())
                                || scalar.getStyle() != Yaml.Scalar.Style.PLAIN)) {
                            return entry.withValue(scalar.withValue(JAVA_VERSION).withStyle(Yaml.Scalar.Style.PLAIN));
                        }
                    }
                    return entry;
                }));
            }
        };
    }
}
