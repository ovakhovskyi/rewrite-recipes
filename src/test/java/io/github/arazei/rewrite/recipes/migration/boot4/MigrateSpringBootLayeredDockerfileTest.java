package io.github.arazei.rewrite.recipes.migration.boot4;

import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.text.PlainText;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MigrateSpringBootLayeredDockerfileTest {
    private static final String LEGACY = """
            FROM eclipse-temurin:25 AS compile
            USER myraif
            ARG JAR_FILE=target/*.jar
            COPY ${JAR_FILE} app.jar
            # Keep this comment
            RUN java -Djarmode=layertools -jar app.jar extract

            FROM eclipse-temurin:25
            VOLUME /tmp
            COPY --from=compile /app/dependencies/ ./
            COPY --from=compile /app/snapshot-dependencies/ ./
            COPY --from=compile /app/spring-boot-loader/ ./
            COPY --from=compile /app/application/ ./
            ENTRYPOINT [ "java", \\
                "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",\\
                "org.springframework.boot.loader.launch.JarLauncher"]
            """;
    private static final String PARTIALLY_MIGRATED = LEGACY
            .replace("FROM eclipse-temurin:25 AS compile", "FROM eclipse-temurin:25 as compile\nWORKDIR /app")
            .replace("VOLUME /tmp", "VOLUME /tmp\nWORKDIR /app")
            .replace("-Djarmode=layertools -jar app.jar extract",
                    "-Djarmode=tools -jar app.jar extract --layers --destination extracted")
            .replace("--from=compile /app/", "--from=compile /app/extracted/");

    @Test
    void migratesAllPartsTogetherAndPreservesFormattingAndOptions() {
        String expected = """
                FROM eclipse-temurin:25 AS compile
                USER myraif
                ARG JAR_FILE=target/*.jar
                COPY ${JAR_FILE} application.jar
                # Keep this comment
                RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

                FROM eclipse-temurin:25
                VOLUME /tmp
                COPY --from=compile /app/extracted/dependencies/ ./
                COPY --from=compile /app/extracted/snapshot-dependencies/ ./
                COPY --from=compile /app/extracted/spring-boot-loader/ ./
                COPY --from=compile /app/extracted/application/ ./
                ENTRYPOINT [ "java", \\
                    "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",\\
                    "-jar", "application.jar"]
                """;
        PlainText migrated = migrate(dockerfile(LEGACY.stripTrailing().replace("\n", "\r\n")));
        assertEquals(expected.stripTrailing().replace("\n", "\r\n"), migrated.getText());
        assertSame(migrated, migrate(migrated));
    }

    @Test
    void fixesOnlyTheEntrypointForAnAlreadyExtractedToolsLayout() {
        String before = PARTIALLY_MIGRATED.stripTrailing() + " \r\n";
        PlainText migrated = migrate(dockerfile(before));
        assertEquals(before.replace("\"org.springframework.boot.loader.launch.JarLauncher\"", "\"-jar\", \"app.jar\""),
                migrated.getText());
        assertSame(migrated, migrate(migrated));

        String custom = PARTIALLY_MIGRATED.replace("app.jar", "service.jar").replace("extracted", "boot-layers");
        assertEquals(custom.replace("\"org.springframework.boot.loader.launch.JarLauncher\"", "\"-jar\", \"service.jar\""),
                migrate(dockerfile(custom)).getText());
    }

    @Test
    void skipsToolsLayoutsWithMissingOrMismatchedLayers() {
        for (String text : new String[]{
                PARTIALLY_MIGRATED.replace("COPY --from=compile /app/extracted/application/ ./\n", ""),
                PARTIALLY_MIGRATED.replace("/app/extracted/application/", "/app/elsewhere/application/"),
                PARTIALLY_MIGRATED.replace("--from=compile /app/extracted/application/", "--from=other /app/extracted/application/"),
                PARTIALLY_MIGRATED.replace("--destination extracted", "--destination extracted --launcher")}) {
            PlainText input = dockerfile(text);
            assertSame(input, migrate(input));
        }
    }

    @Test
    void leavesTheBuilderAloneWhenTheRuntimeLayoutDoesNotMatch() {
        PlainText incomplete = dockerfile(LEGACY.replace("COPY --from=compile /app/application/ ./\n", ""));
        assertSame(incomplete, migrate(incomplete));

        PlainText wrongLauncher = dockerfile(LEGACY.replace(".JarLauncher", ".PropertiesLauncher"));
        assertSame(wrongLauncher, migrate(wrongLauncher));

        PlainText otherConsumer = dockerfile(LEGACY + "\nFROM scratch\nCOPY --from=compile /app/dependencies/ /deps\n");
        assertSame(otherConsumer, migrate(otherConsumer));
    }

    private static PlainText dockerfile(String text) {
        return PlainText.builder().sourcePath(Path.of("service/Dockerfile")).text(text).build();
    }

    private static PlainText migrate(PlainText text) {
        return (PlainText) new MigrateSpringBootLayeredDockerfile().getVisitor().visit(text, new InMemoryExecutionContext());
    }
}
