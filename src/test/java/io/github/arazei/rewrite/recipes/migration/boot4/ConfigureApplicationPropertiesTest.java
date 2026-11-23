package io.github.arazei.rewrite.recipes.migration.boot4;

import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.yaml.YamlParser;
import org.openrewrite.yaml.tree.Yaml;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConfigureApplicationPropertiesTest {
    private static final String APPLICATION = "service/src/main/resources/application.yml";

    @Test
    void addsNestedPropertiesWithoutChangingProfilesOrExistingSettings() {
        String base = """
                spring:
                  profiles:
                    active: local
                management:
                  endpoints:
                    enabled-by-default: true
                """;
        String profiles = """
                ---
                spring.config.activate.on-profile: prod
                management.otlp.metrics.export.enabled: true
                """;
        assertMigration(base + profiles, base + """
                  opentelemetry:
                    tracing:
                      export:
                        otlp:
                          transport: GRPC
                  otlp:
                    metrics:
                      export:
                        enabled: false
                """ + profiles);
    }

    @Test
    void forcesValuesInMixedNestedAndDottedConfiguration() {
        assertMigration("""
                management.opentelemetry.tracing.export.otlp.transport: 'HTTP' # protocol
                management:
                    otlp.metrics:
                        export: {enabled: "true", step: 30s}
                """, """
                management.opentelemetry.tracing.export.otlp.transport: GRPC # protocol
                management:
                    otlp.metrics:
                        export: {enabled: false, step: 30s}
                """);
    }

    @Test
    void mergesIntoExistingFlowMappingAndDottedGroup() {
        assertMigration("""
                management.opentelemetry.tracing.export.otlp: {endpoint: collector}
                management: {otlp: {metrics: {export: {step: 30s}}}}
                """, """
                management.opentelemetry.tracing.export.otlp: {endpoint: collector, transport: GRPC}
                management: {otlp: {metrics: {export: {step: 30s, enabled: false}}}}
                """);
    }

    @Test
    void leavesProfileFilesAndProfileOnlyDocumentsUntouched() {
        for (String path : new String[]{"application.yml", "src/main/resources/application-prod.yml",
                "src/test/resources/application.yml", "src/main/resources/application.yaml"}) {
            var input = parse("spring.application.name: test\n", path);
            assertSame(input, migrate(input));
        }
        var profile = parse("spring.config.activate.on-profile: prod\n", APPLICATION);
        assertSame(profile, migrate(profile));
    }

    private static void assertMigration(String before, String after) {
        String actual = migrate(parse(before, APPLICATION)).printAll();
        assertEquals(after, actual);
        var reparsed = parse(actual, APPLICATION);
        assertSame(reparsed, migrate(reparsed));
    }

    private static Yaml.Documents parse(String source, String path) {
        return assertInstanceOf(Yaml.Documents.class, YamlParser.builder().build().parse(source).findFirst().orElseThrow())
                .withSourcePath(Path.of(path));
    }

    private static Yaml.Documents migrate(Yaml.Documents input) {
        return (Yaml.Documents) new ConfigureApplicationProperties().getVisitor()
                .visit(input, new InMemoryExecutionContext());
    }
}
