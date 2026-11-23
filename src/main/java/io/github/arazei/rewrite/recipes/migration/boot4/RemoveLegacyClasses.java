package io.github.arazei.rewrite.recipes.migration.boot4;

import org.openrewrite.Column;
import org.openrewrite.Cursor;
import org.openrewrite.DataTable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.RemoveUnusedImports;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class RemoveLegacyClasses extends ScanningRecipe<RemoveLegacyClasses.Scan> {
    private static final Set<String> LEGACY_BASES = Set.of(
            "ua.aval.myraif.error.advice.GeneralAdvice",
            "ua.aval.myraif.error.advice.WebExceptionHandler");

    private final transient RetainedClasses retainedClasses = new RetainedClasses(this);

    @Override
    public String getDisplayName() {
        return "Remove legacy MyRaif advice subclasses";
    }

    @Override
    public String getDescription() {
        return "Remove direct and indirect subclasses of legacy MyRaif advice types when no surviving Java code references them.";
    }

    @Override
    public Scan getInitialValue(ExecutionContext ctx) {
        return new Scan();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Scan scan) {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitClassDeclaration(J.ClassDeclaration declaration, ExecutionContext ctx) {
                JavaType.FullyQualified type = declaration.getType();
                if (type != null) {
                    String name = type.getFullyQualifiedName();
                    if (!LEGACY_BASES.contains(name) && extendsLegacyBase(declaration)) {
                        scan.candidates.put(declaration.getId(), new Candidate(name, sourcePath()));
                    }
                    // Removing an enclosing class also removes its nested types.
                    for (Cursor cursor = getCursor(); cursor != null; cursor = cursor.getParent()) {
                        if (cursor.getValue() instanceof J.ClassDeclaration enclosing
                                && scan.candidates.containsKey(enclosing.getId())) {
                            scan.removedBy.computeIfAbsent(name, ignored -> new HashSet<>()).add(enclosing.getId());
                        }
                    }
                }
                return super.visitClassDeclaration(declaration, ctx);
            }

            @Override
            public J visitImport(J.Import anImport, ExecutionContext ctx) {
                // Imports are cleaned up after deletion; an unused import does not keep a type alive.
                return anImport;
            }

            @Override
            public JavaType visitType(JavaType type, ExecutionContext ctx) {
                if (type == null || type instanceof JavaType.Primitive || type instanceof JavaType.Unknown) {
                    return type;
                }
                UUID owner = null;
                for (Cursor cursor = getCursor(); cursor != null; cursor = cursor.getParent()) {
                    if (cursor.getValue() instanceof J.ClassDeclaration enclosing
                            && scan.candidates.containsKey(enclosing.getId())) {
                        owner = enclosing.getId();
                        break;
                    }
                }
                collectNames(type, scan.references.computeIfAbsent(new ReferenceSite(owner, sourcePath()),
                        ignored -> new HashSet<>()));
                return type;
            }

            private String sourcePath() {
                return getCursor().firstEnclosingOrThrow(J.CompilationUnit.class).getSourcePath().toString();
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Scan scan) {
        return new JavaVisitor<ExecutionContext>() {
            @Override
            public J visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                if (scan.plan == null) {
                    scan.plan = planRemoval(scan, ctx);
                }
                if (scan.plan.ids().isEmpty()) {
                    return cu;
                }
                J.CompilationUnit updated = (J.CompilationUnit) super.visitCompilationUnit(cu, ctx);
                if (updated.getClasses().isEmpty() && !cu.getClasses().isEmpty()) {
                    return null;
                }
                if (updated != cu) {
                    doAfterVisit(new RemoveUnusedImports().getVisitor());
                }
                return updated;
            }

            @Override
            public J visitClassDeclaration(J.ClassDeclaration declaration, ExecutionContext ctx) {
                return scan.plan.ids().contains(declaration.getId()) ? null : super.visitClassDeclaration(declaration, ctx);
            }

            @Override
            public J visitImport(J.Import anImport, ExecutionContext ctx) {
                String importedType = anImport.getTypeName().replace('$', '.');
                if (importedType.endsWith(".*")) {
                    importedType = importedType.substring(0, importedType.length() - 2);
                }
                String staticOwner = anImport.isStatic() ? anImport.getQualid().getTarget().printTrimmed(getCursor()) : "";
                return scan.plan.importNames().contains(importedType) || scan.plan.importNames().contains(staticOwner)
                        ? null : anImport;
            }
        };
    }

    private RemovalPlan planRemoval(Scan scan, ExecutionContext ctx) {
        Set<UUID> deletions = new HashSet<>(scan.candidates.keySet());
        var surviving = new ArrayDeque<ReferenceSite>();
        scan.references.keySet().stream().filter(site -> site.owner() == null).forEach(surviving::add);
        // References in a retained candidate become live, which may keep its own dependencies alive.
        while (!surviving.isEmpty()) {
            ReferenceSite site = surviving.removeFirst();
            for (String name : scan.references.getOrDefault(site, Set.of())) {
                for (UUID target : scan.removedBy.getOrDefault(name, Set.of())) {
                    if (deletions.remove(target)) {
                        Candidate retained = scan.candidates.get(target);
                        String reference = site.owner() == null ? site.path()
                                : scan.candidates.get(site.owner()).name() + " in " + site.path();
                        retainedClasses.insertRow(ctx, new RetainedClasses.Row(retained.name(), retained.path(), reference));
                        surviving.add(new ReferenceSite(target, retained.path()));
                    }
                }
            }
        }
        Set<String> importNames = new HashSet<>();
        scan.removedBy.forEach((name, owners) -> {
            if (owners.stream().anyMatch(deletions::contains)) {
                importNames.add(name.replace('$', '.'));
            }
        });
        return new RemovalPlan(deletions, importNames);
    }

    private static boolean extendsLegacyBase(J.ClassDeclaration declaration) {
        if (declaration.getExtends() == null) {
            return false;
        }
        JavaType.FullyQualified ancestor = TypeUtils.asFullyQualified(declaration.getExtends().getType());
        Set<String> seen = new HashSet<>();
        while (ancestor != null && seen.add(ancestor.getFullyQualifiedName())) {
            if (LEGACY_BASES.contains(ancestor.getFullyQualifiedName())) {
                return true;
            }
            ancestor = ancestor.getSupertype();
        }
        return false;
    }

    private static void collectNames(JavaType type, Set<String> names) {
        switch (type) {
            case JavaType.Parameterized parameterized -> {
                names.add(parameterized.getFullyQualifiedName());
                parameterized.getTypeParameters().forEach(parameter -> collectNames(parameter, names));
            }
            case JavaType.FullyQualified qualified -> names.add(qualified.getFullyQualifiedName());
            case JavaType.Array array -> collectNames(array.getElemType(), names);
            case JavaType.Method method -> {
                collectNames(method.getDeclaringType(), names);
                collectNames(method.getReturnType(), names);
                method.getParameterTypes().forEach(parameter -> collectNames(parameter, names));
            }
            case JavaType.Variable variable -> {
                collectNames(variable.getOwner(), names);
                collectNames(variable.getType(), names);
            }
            case null, default -> { }
        }
    }

    static final class Scan {
        final Map<UUID, Candidate> candidates = new HashMap<>();
        final Map<String, Set<UUID>> removedBy = new HashMap<>();
        final Map<ReferenceSite, Set<String>> references = new LinkedHashMap<>();
        RemovalPlan plan;
    }

    private record Candidate(String name, String path) {}
    private record ReferenceSite(UUID owner, String path) {}
    private record RemovalPlan(Set<UUID> ids, Set<String> importNames) {}

    public static final class RetainedClasses extends DataTable<RetainedClasses.Row> {
        RetainedClasses(RemoveLegacyClasses recipe) {
            super(recipe, "Retained legacy advice subclasses", "Legacy advice subclasses kept because surviving Java code references them.");
        }

        public record Row(
                @Column(displayName = "Class", description = "Fully qualified name of the retained class.") String className,
                @Column(displayName = "Source", description = "Source path of the retained class.") String source,
                @Column(displayName = "Referenced from", description = "Surviving source path or class that keeps this class alive.") String reference) {}
    }
}
