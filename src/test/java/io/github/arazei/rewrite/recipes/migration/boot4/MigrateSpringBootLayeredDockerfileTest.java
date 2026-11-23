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
