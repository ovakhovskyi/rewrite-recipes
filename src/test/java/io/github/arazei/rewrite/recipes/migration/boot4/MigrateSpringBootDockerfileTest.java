package io.github.arazei.rewrite.recipes.migration.boot4;

import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.text.PlainText;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MigrateSpringBootDockerfileTest {
    private static final String IMAGE =
            "harbor.shared.avalaunch.rbua/infra-resources/eclipse-temurin-base-image:25-jre-ubi10-minimal";

    @Test
    void replacesEveryStageImageRegardlessOfTheOriginalAndPreservesExistingInstructions() {
        PlainText original = file("service/Dockerfile", """
                # Service-specific Dockerfile
                ARG IMAGE_TAG=25
                FROM --platform=$BUILDPLATFORM eclipse-temurin:${IMAGE_TAG} AS builder
                USER myraif
                RUN java -version

                from builder AS runtime
                WORKDIR /srv
                COPY --from=builder /tmp/example ./

                FROM harbor.shared.avalaunch.rbua/infra-resources/eclipse-temurin-base-image:21-jre-ubi10-minimal AS legacy
                WORKDIR /legacy

                FROM scratch
                ENTRYPOINT ["/bin/example"]
                """);

        PlainText migrated = migrate(original);
        assertEquals("""
                # Service-specific Dockerfile
                ARG IMAGE_TAG=25
                FROM --platform=$BUILDPLATFORM %s AS builder
                WORKDIR /app
                USER myraif
                RUN java -version

                from %s AS runtime
                WORKDIR /srv
                COPY --from=builder /tmp/example ./

                FROM %s AS legacy
                WORKDIR /legacy

                FROM %s
                WORKDIR /app
                ENTRYPOINT ["/bin/example"]
                """.formatted(IMAGE, IMAGE, IMAGE, IMAGE), migrated.getText());
        assertSame(migrated, migrate(migrated));
    }

    @Test
    void addsWorkdirToAnAlreadyUpdatedImageAndPreservesCrLfWithoutFinalNewline() {
        assertEquals("# comment\r\nFROM " + IMAGE + "\r\nWORKDIR /app",
                migrate(file("Dockerfile", "# comment\r\nFROM " + IMAGE)).getText());
    }

    @Test
    void handlesContinuedFromAndLeavesInstructionsInHeredocUntouched() {
        PlainText original = file("Dockerfile", """
                # escape=`
                FROM --platform=$BUILDPLATFORM `
                  alpine AS builder
                RUN <<'SCRIPT'
                FROM scratch
                WORKDIR /tmp
                SCRIPT
                """);
        assertEquals("""
                # escape=`
                FROM --platform=$BUILDPLATFORM `
                  %s AS builder
                WORKDIR /app
                RUN <<'SCRIPT'
                FROM scratch
                WORKDIR /tmp
                SCRIPT
                """.formatted(IMAGE), migrate(original).getText());
    }

    @Test
    void skipsOtherFileNames() {
        PlainText example = file("Dockerfile.example", "FROM alpine\n");
        assertSame(example, migrate(example));
    }

    private static PlainText file(String path, String text) {
        return PlainText.builder().sourcePath(Path.of(path)).text(text).build();
    }

    private static PlainText migrate(PlainText text) {
        return (PlainText) new MigrateSpringBootDockerfile().getVisitor().visit(text, new InMemoryExecutionContext());
    }
}
