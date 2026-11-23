package io.github.arazei.rewrite.recipes.migration.boot4;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.maven.MavenTagInsertionComparator;
import org.openrewrite.xml.AddToTagVisitor;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Xml;

public final class MigrateCompanyDependencies extends Recipe {
    private static final String OLD_GROUP = "ua.aval.myraif";
    private static final String COMPANY_GROUP = "ua.raiffeisen.myraif";
    private static final String BOM_ARTIFACT = "myraif-platform-dependencies";
    private static final String BOM_PROPERTY = "myraif.platform.dependencies.version";
    private static final String ALTERNATE_BOM_PROPERTY = "myraif-platform-dependencies.version";
    private static final String SELECTED_PROPERTY = "myraif.bom.selectedProperty";
    private static final String BOM_VERSION = "4.1.3-v.1";
    private static final String BOM = """
            <dependency>
                <groupId>%s</groupId>
                <artifactId>%s</artifactId>
                <version>%s</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>""";

    private static final XPathMatcher PROJECT = new XPathMatcher("/project");
    private static final XPathMatcher PROPERTIES = new XPathMatcher("/project/properties");
    private static final XPathMatcher MANAGEMENT = new XPathMatcher("/project/dependencyManagement");
    private static final XPathMatcher MANAGED_DEPENDENCIES = new XPathMatcher("/project/dependencyManagement/dependencies");
    private static final XPathMatcher MANAGED_DEPENDENCY = new XPathMatcher("/project/dependencyManagement/dependencies/dependency");
    private static final XPathMatcher DIRECT_DEPENDENCY = new XPathMatcher("/project/dependencies/dependency");

    @Override
    public String getDisplayName() {
        return "Migrate MyRaif Maven dependencies to the Boot 4 platform";
    }

    @Override
    public String getDescription() {
        return "Import the MyRaif platform BOM and let it manage direct MyRaif dependencies, including the relocated BFF error handling library.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        // Match declarations in the POM so edits do not require resolving the new BOM first.
        return new XmlIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                if (!document.getSourcePath().endsWith("pom.xml")) {
                    return document;
                }
                getCursor().putMessage(SELECTED_PROPERTY, selectBomProperty(document.getRoot()));
                return super.visitDocument(document, ctx);
            }

            @Override
            public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
                Xml.Tag t = super.visitTag(tag, ctx);
                String property = getCursor().getNearestMessage(SELECTED_PROPERTY);
                if (PROJECT.matches(getCursor())) {
                    if (t.getChild("properties").isEmpty()) {
                        t = add(t, wrap("properties", wrap(property, BOM_VERSION)), ctx);
                    }
                    if (t.getChild("dependencyManagement").isEmpty()) {
                        t = add(t, wrap("dependencyManagement", wrap("dependencies", bom(property))), ctx);
                    }
                } else if (PROPERTIES.matches(getCursor())) {
                    t = setValue(t, property, BOM_VERSION, ctx);
                } else if (MANAGEMENT.matches(getCursor()) && t.getChild("dependencies").isEmpty()) {
                    t = add(t, wrap("dependencies", bom(property)), ctx);
                } else if (MANAGED_DEPENDENCIES.matches(getCursor()) &&
                        t.getChildren("dependency").stream().noneMatch(MigrateCompanyDependencies::isCompanyBom)) {
                    t = add(t, bom(property), ctx);
                } else if (MANAGED_DEPENDENCY.matches(getCursor()) && isCompanyBom(t)) {
                    t = setValue(t, "version", reference(property), ctx);
                    t = setValue(t, "type", "pom", ctx);
                    t = setValue(t, "scope", "import", ctx);
                } else if (DIRECT_DEPENDENCY.matches(getCursor())) {
                    String group = t.getChildValue("groupId").orElse("").strip();
                    if (OLD_GROUP.equals(group) && hasValue(t, "artifactId", "myraif-bff-error-handling-lib")) {
                        t = t.withChildValue("groupId", COMPANY_GROUP);
                    }
                    if (group.startsWith(OLD_GROUP) || group.startsWith(COMPANY_GROUP)) {
                        t = t.withContent(ListUtils.map(t.getContent(), child ->
                                child instanceof Xml.Tag element && "version".equals(element.getName()) ? null : child));
                    }
                }
                return t;
            }

            private Xml.Tag setValue(Xml.Tag parent, String name, String value, ExecutionContext ctx) {
                return parent.getChild(name).map(child -> child.getValue().filter(value::equals).isPresent()
                        ? parent : parent.withChildValue(name, value)).orElseGet(() -> add(parent, wrap(name, value), ctx));
            }

            private Xml.Tag add(Xml.Tag parent, String child, ExecutionContext ctx) {
                Xml.Tag updated = (Xml.Tag) new AddToTagVisitor<ExecutionContext>(parent, Xml.Tag.build(child),
                        PROJECT.matches(getCursor()) ? new MavenTagInsertionComparator(parent.getContent()) : null)
                        .visitNonNull(parent, ctx, getCursor().getParentOrThrow());
                // AddToTagVisitor expands inline sections; align their new closing line with the opening tag.
                String prefix = parent.getPrefix();
                int lineStart = prefix.lastIndexOf('\n');
                if (lineStart >= 0 && updated.getClosing() != null && "\n".equals(updated.getClosing().getPrefix())) {
                    int lineEndingStart = lineStart > 0 && prefix.charAt(lineStart - 1) == '\r' ? lineStart - 1 : lineStart;
                    updated = updated.withClosing(updated.getClosing().withPrefix(prefix.substring(lineEndingStart)));
                }
                return updated;
            }
        };
    }

    private static String selectBomProperty(Xml.Tag project) {
        String version = project.getChild("dependencyManagement")
                .flatMap(management -> management.getChild("dependencies"))
                .flatMap(dependencies -> dependencies.getChildren("dependency").stream()
                        .filter(MigrateCompanyDependencies::isCompanyBom).findFirst())
                .flatMap(bom -> bom.getChildValue("version"))
                .map(String::strip)
                .orElse("");
        if (reference(ALTERNATE_BOM_PROPERTY).equals(version)) {
            return ALTERNATE_BOM_PROPERTY;
        }
        if (reference(BOM_PROPERTY).equals(version)) {
            return BOM_PROPERTY;
        }
        return project.getChild("properties")
                .filter(properties -> properties.getChild(BOM_PROPERTY).isEmpty())
                .filter(properties -> properties.getChild(ALTERNATE_BOM_PROPERTY).isPresent())
                .map(properties -> ALTERNATE_BOM_PROPERTY)
                .orElse(BOM_PROPERTY);
    }

    private static String reference(String property) {
        return "${" + property + "}";
    }

    private static String bom(String property) {
        return BOM.formatted(COMPANY_GROUP, BOM_ARTIFACT, reference(property));
    }

    private static boolean isCompanyBom(Xml.Tag tag) {
        return hasValue(tag, "groupId", COMPANY_GROUP) && hasValue(tag, "artifactId", BOM_ARTIFACT);
    }

    private static boolean hasValue(Xml.Tag tag, String child, String value) {
        return tag.getChildValue(child).map(String::strip).filter(value::equals).isPresent();
    }

    private static String wrap(String name, String content) {
        return "<" + name + ">" + content + "</" + name + ">";
    }
}
