package io.github.arazei.rewrite.recipes.migration.boot4;

import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoveLegacyClassesTest {
    @Test
    void deletesBothInheritanceChainsAndKeepsOtherDeclarations() {
        var ctx = context();
        var sources = parse(ctx,
                "package app; public class Parent extends ua.aval.myraif.error.advice.GeneralAdvice {}",
                "package app; class Child extends Parent {}",
                """
                package app;

                import ua.aval.myraif.error.advice.WebExceptionHandler;

                class Keep extends other.GeneralAdvice {}

                class OldHandler extends WebExceptionHandler {
                    Child child;
                }
                """,
                """
                package app;

                import app.Parent;

                class UnusedImport {}
                """);
        var recipe = new RemoveLegacyClasses();
        var results = results(recipe, sources, ctx);
        assertEquals(4, results.size());
        assertNull(results.get("app/Parent.java").getAfter());
        assertNull(results.get("app/Child.java").getAfter());
        var mixed = results.get("app/Keep.java").getAfter().printAll();
        assertEquals("package app;\n\nclass Keep extends other.GeneralAdvice {}\n", mixed);
        var unusedImport = results.get("app/UnusedImport.java").getAfter().printAll();
        assertEquals("package app;\n\nclass UnusedImport {}\n", unusedImport);
        assertTrue(results(recipe, parse(ctx, mixed, unusedImport), ctx).isEmpty());
    }

    @Test
    void keepsCandidatesReferencedFromTestsAndPropagatesRetention() {
        var ctx = context();
        var sources = parse(ctx,
                "package app; public class Parent extends ua.aval.myraif.error.advice.GeneralAdvice {}",
                "package app; public class Child extends Parent {}",
                "package app; class Unused extends ua.aval.myraif.error.advice.WebExceptionHandler {}",
                "package app; class HandlerTest { Class<?> type = Child.class; }").stream().map(source ->
                source.getSourcePath().endsWith("HandlerTest.java")
                        ? source.withSourcePath(Path.of("src/test/java/app/HandlerTest.java")) : source).toList();
        var run = new RemoveLegacyClasses().run(new InMemoryLargeSourceSet(sources), ctx);
        var results = run.getChangeset().getAllResults();
        assertEquals(1, results.size());
        assertEquals(Path.of("app/Unused.java"), results.getFirst().getBefore().getSourcePath());
        assertNull(results.getFirst().getAfter());
        assertEquals(List.of(
                new RemoveLegacyClasses.RetainedClasses.Row("app.Child", "app/Child.java", "src/test/java/app/HandlerTest.java"),
                new RemoveLegacyClasses.RetainedClasses.Row("app.Parent", "app/Parent.java", "app.Child in app/Child.java")
        ), run.getDataTableRows(RemoveLegacyClasses.RetainedClasses.class.getName()));
    }

    @Test
    void removesNestedCandidateButProtectsAnEnclosingCandidateWhoseNestedTypeIsUsed() {
        var ctx = context();
        var sources = parse(ctx, """
                package app;

                import ua.aval.myraif.error.advice.GeneralAdvice;

                class Container {
                    static class DeleteMe extends GeneralAdvice {}
                }

                class KeptAdvice extends GeneralAdvice {
                    static class Result {}
                }

                class Caller {
                    KeptAdvice.Result result;
                }
                """);
        var updated = results(new RemoveLegacyClasses(), sources, ctx).get("app/Container.java").getAfter().printAll();
        assertEquals(sources.getFirst().printAll().replace("    static class DeleteMe extends GeneralAdvice {}\n", ""), updated);
    }

    private static Map<String, Result> results(RemoveLegacyClasses recipe, List<SourceFile> sources, InMemoryExecutionContext ctx) {
        return recipe.run(new InMemoryLargeSourceSet(sources), ctx).getChangeset().getAllResults().stream()
                .collect(Collectors.toMap(result -> result.getBefore().getSourcePath().toString(), result -> result));
    }

    private static List<SourceFile> parse(InMemoryExecutionContext ctx, String... sources) {
        var parsed = JavaParser.fromJavaVersion().dependsOn(
                "package ua.aval.myraif.error.advice; public class GeneralAdvice {}",
                "package ua.aval.myraif.error.advice; public class WebExceptionHandler {}",
                "package other; public class GeneralAdvice {}"
        ).build().parse(ctx, sources).toList();
        parsed.forEach(source -> assertInstanceOf(J.CompilationUnit.class, source));
        return parsed;
    }

    private static InMemoryExecutionContext context() {
        return new InMemoryExecutionContext(error -> { throw new AssertionError(error); });
    }
}
