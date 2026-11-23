package io.github.arazei.rewrite.recipes.migration.boot4;

import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.config.Environment;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrateCompanyJavaApiTest {
    private final Recipe recipe = Environment.builder().scanYamlResources().build()
            .activateRecipes("io.github.arazei.rewrite.recipes.migration.boot4.MigrateCompanyJavaApi");

    @Test
    void updatesImportsAndTypeReferencesInMainSources() {
        assertTrue(recipe.validateAll().stream().allMatch(validation -> validation.isValid()));
        assertRewrite("""
                package example;

                import ua.aval.myraif.error.code.ErrorCode;
                import ua.aval.myraif.error.exception.MyRaifException;
                import ua.aval.myraiff.security.context.CurrentUserContext;
                import ua.aval.myraiff.security.context.DeviceFingerprint;

                class Service {
                    ErrorCode code;
                    MyRaifException failure;
                    CurrentUserContext user;
                    DeviceFingerprint fingerprint;
                    ua.aval.myraiff.security.context.DeviceFingerprint otherFingerprint;
                }
                """, """
                package example;

                import ua.raiffeisen.myraif.common.api.error.ErrorCode;
                import ua.raiffeisen.myraif.common.api.exception.MyRaifException;
                import ua.raiffeisen.myraif.common.api.security.context.CurrentUserContext;
                import ua.raiffeisen.myraif.common.api.security.context.DeviceFingerprint;

                class Service {
                    ErrorCode code;
                    MyRaifException failure;
                    CurrentUserContext user;
                    DeviceFingerprint fingerprint;
                    DeviceFingerprint otherFingerprint;
                }
                """, "src/main/java/example/Service.java");

    }

    @Test
    void renamesFeignExceptionInTestSources() {
        assertRewrite("""
                package example;

                import ua.aval.myraif.error.exception.RaiffeisenFeignException;

                class ServiceTest {
                    Class<?> type = RaiffeisenFeignException.class;
                    ua.aval.myraif.error.exception.RaiffeisenFeignException fullName;
                    another.RaiffeisenFeignException unrelated;
                    // RaiffeisenFeignException is the old name.
                    String legacyName = "ua.aval.myraif.error.exception.RaiffeisenFeignException";

                    void exercise() {
                        try {
                            RaiffeisenFeignException failure = new RaiffeisenFeignException("message");
                            throw failure;
                        } catch (RaiffeisenFeignException expected) {
                        }
                    }
                }
                """, """
                package example;

                import ua.raiffeisen.myraif.common.api.exception.WrapperFeignException;

                class ServiceTest {
                    Class<?> type = WrapperFeignException.class;
                    WrapperFeignException fullName;
                    another.RaiffeisenFeignException unrelated;
                    // RaiffeisenFeignException is the old name.
                    String legacyName = "ua.aval.myraif.error.exception.RaiffeisenFeignException";

                    void exercise() {
                        try {
                            WrapperFeignException failure = new WrapperFeignException("message");
                            throw failure;
                        } catch (WrapperFeignException expected) {
                        }
                    }
                }
                """, "src/test/java/example/ServiceTest.java");
    }

    private void assertRewrite(String before, String after, String path) {
        var ctx = new InMemoryExecutionContext(error -> { throw new AssertionError(error); });
        var input = parse(before, path, ctx);
        var changes = recipe.run(new InMemoryLargeSourceSet(List.of(input)), ctx).getChangeset().getAllResults();
        assertEquals(1, changes.size());
        assertEquals(after, changes.getFirst().getAfter().printAll());

        var reparsed = parse(after, path, ctx);
        assertTrue(recipe.run(new InMemoryLargeSourceSet(List.of(reparsed)), ctx)
                .getChangeset().getAllResults().isEmpty());
    }

    private static SourceFile parse(String source, String path, InMemoryExecutionContext ctx) {
        var parser = JavaParser.fromJavaVersion().dependsOn(
                "package ua.aval.myraif.error.code; public enum ErrorCode { INVALID }",
                "package ua.aval.myraif.error.exception; public class MyRaifException extends RuntimeException {}",
                exception("ua.aval.myraif.error.exception", "RaiffeisenFeignException"),
                "package ua.aval.myraiff.security.context; public class CurrentUserContext {}",
                "package ua.aval.myraiff.security.context; public class DeviceFingerprint {}",
                "package ua.raiffeisen.myraif.common.api.error; public enum ErrorCode { INVALID }",
                "package ua.raiffeisen.myraif.common.api.exception; public class MyRaifException extends RuntimeException {}",
                exception("ua.raiffeisen.myraif.common.api.exception", "WrapperFeignException"),
                "package ua.raiffeisen.myraif.common.api.security.context; public class CurrentUserContext {}",
                "package ua.raiffeisen.myraif.common.api.security.context; public class DeviceFingerprint {}",
                exception("another", "RaiffeisenFeignException")
        ).build();
        return assertInstanceOf(J.CompilationUnit.class, parser.parse(ctx, source).findFirst().orElseThrow())
                .withSourcePath(Path.of(path));
    }

    private static String exception(String packageName, String name) {
        return "package %s; public class %s extends RuntimeException { public %s(String message) { super(message); } }"
                .formatted(packageName, name, name);
    }
}
