package io.github.arazei.rewrite.recipes.migration.boot4;

import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.yaml.YamlParser;
import org.openrewrite.yaml.tree.Yaml;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MigrateGitHubWorkflowsTest {
    @Test
    void migratesCorporateImagesAndMavenPairsOnly() {
        String before = """
                jobs:
                  build:
                    container:
                      image: 'harbor.shared.avalaunch.rbua/infra-resources/maven-3.9:21' # build image
                    services:
                      builder:
                        image: harbor.shared.avalaunch.rbua/infra-resources/maven-4:latest
                      database:
                        image: postgres:17
                    steps:
                      - uses: company/setup@v1
                        with: { version: "21", tool: maven-3.8 }
                      - uses: company/setup@v1
                        with:
                          tool: node
                          version: 24
                      - uses: company/setup@v1
                        with:
                          tool: maven-3.9
                      - run: |
                          # image: harbor.shared.avalaunch.rbua/infra-resources/maven-3.9:21
                          echo 'tool: maven-3.8'
                """;
        String after = """
                jobs:
                  build:
                    container:
                      image: 'harbor.shared.avalaunch.rbua/infra-resources/maven-3.10:25' # build image
                    services:
                      builder:
                        image: harbor.shared.avalaunch.rbua/infra-resources/maven-3.10:25
                      database:
                        image: postgres:17
                    steps:
                      - uses: company/setup@v1
                        with: { version: 25, tool: maven-3.10 }
                      - uses: company/setup@v1
                        with:
                          tool: node
                          version: 24
                      - uses: company/setup@v1
                        with:
                          tool: maven-3.9
                      - run: |
                          # image: harbor.shared.avalaunch.rbua/infra-resources/maven-3.9:21
                          echo 'tool: maven-3.8'
                """;

        for (String name : new String[]{"build.yml", "checks.yaml"}) {
            String path = ".github/workflows/" + name;
            assertEquals(after, migrate(parse(before, path)).printAll());
            var reparsed = parse(after, path);
            assertSame(reparsed, migrate(reparsed));
        }
    }

    @Test
    void skipsFilesOutsideTheWorkflowDirectoryAndNonYamlExtensions() {
        for (String path : new String[]{"application.yml", "docs/.github/workflows/build.yaml",
                ".github/actions/build.yml", ".github/workflows/sub/build.yml", ".github/workflows/build.txt"}) {
            var document = parse("tool: maven-3.9\nversion: 21\n", path);
            assertSame(document, migrate(document), path);
        }
    }

    private static Yaml.Documents parse(String source, String path) {
        return ((Yaml.Documents) YamlParser.builder().build().parse(source).findFirst().orElseThrow())
                .withSourcePath(Path.of(path));
    }

    private static Yaml.Documents migrate(Yaml.Documents document) {
        return (Yaml.Documents) new MigrateGitHubWorkflows().getVisitor()
                .visit(document, new InMemoryExecutionContext());
    }
}
