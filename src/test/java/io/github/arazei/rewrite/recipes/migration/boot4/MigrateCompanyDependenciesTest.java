package io.github.arazei.rewrite.recipes.migration.boot4;

import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.internal.XmlPrinter;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MigrateCompanyDependenciesTest {
    private static final String PROPERTY = "myraif.platform.dependencies.version";

    @Test
    void createsMissingSectionsAndIsIdempotent() {
        Xml.Document migrated = migrate(pom(""));
        assertBom(migrated);
        assertSame(migrated, migrate(migrated));
        Xml.Document reparsed = parse(migrated.printAll());
        assertSame(reparsed, migrate(reparsed));
    }

    @Test
    void fillsEmptySections() {
        assertBom(migrate(pom("<properties/><dependencyManagement/>")));
        assertBom(migrate(pom("<properties><myraif.platform.dependencies.version/></properties>" +
                "<dependencyManagement><dependencies/></dependencyManagement>")));
    }

    @Test
    void updatesExistingImportAndOnlyTheTargetedDirectDependencies() {
        String managed = dependency("ua.aval.myraif.platform", "managed", "6");
        String unrelated = dependency("example.ua.aval.myraif", "external", "7");
        String alreadyManaged = dependency("ua.aval.myraif", "configured", null);
        String nestedDependency = dependency("ua.aval.myraif.profile", "nested", "8");
        String profiles = "<profiles><profile><id>test</id><dependencies>" + nestedDependency +
                "</dependencies></profile></profiles>";
        String build = "<build><plugins><plugin><artifactId>test-plugin</artifactId><dependencies>" +
                nestedDependency + "</dependencies></plugin></plugins></build>";
        String fields = "<!-- retain this comment --><scope>test</scope><classifier>tests</classifier>";
        String before = "<properties><other.version>1</other.version><" + PROPERTY + ">4.1.3-v.1-SNAPSHOT</" + PROPERTY + ">" +
                "</properties><dependencyManagement><dependencies>" + managed +
                dependency("ua.raiffeisen.myraif", "myraif-platform-dependencies", "3.5.16-v.4") +
                "</dependencies></dependencyManagement><dependencies>" +
                dependency("ua.aval.myraif", "myraif-bff-error-handling-lib", "4.0.0-SNAPSHOT")
                        .replace("</dependency>", fields + "</dependency>") +
                dependency("ua.aval.myraif.extension", "extension", "${other.version}") +
                dependency("ua.aval.myraifcustom", "custom", "2") +
                dependency("ua.raiffeisen.myraif.services", "service", "3") +
                alreadyManaged + unrelated + "</dependencies>" + build + profiles;

        Xml.Document migrated = migrate(pom(before));
        assertBom(migrated);
        Xml.Tag root = migrated.getRoot();
        assertEquals("1", child(root, "properties").getChildValue("other.version").orElseThrow());
        var management = child(child(root, "dependencyManagement"), "dependencies").getChildren("dependency");
        assertEquals(2, management.size());
        assertEquals(managed, print(management.getFirst()));
        assertEquals("<dependencies>" + dependency("ua.raiffeisen.myraif", "myraif-bff-error-handling-lib", null)
                        .replace("</dependency>", fields + "</dependency>") +
                        dependency("ua.aval.myraif.extension", "extension", null) +
                        dependency("ua.aval.myraifcustom", "custom", null) +
                        dependency("ua.raiffeisen.myraif.services", "service", null) +
                        alreadyManaged + unrelated + "</dependencies>", print(child(root, "dependencies")));
        assertEquals(profiles, print(child(root, "profiles")));
        assertEquals(build, print(child(root, "build")));
        assertSame(migrated, migrate(migrated));
    }

    @Test
    void ignoresOtherXmlDocuments() {
        Xml.Document document = pom("").withSourcePath(Path.of("config.xml"));
        assertSame(document, migrate(document));
    }

    private static void assertBom(Xml.Document migrated) {
        Xml.Tag root = migrated.getRoot();
        assertEquals("4.1.3-v.1", child(root, "properties").getChildValue(PROPERTY).orElseThrow());
        var imports = child(child(root, "dependencyManagement"), "dependencies").getChildren("dependency").stream()
                .filter(tag -> tag.getChildValue("artifactId").orElse("").equals("myraif-platform-dependencies")).toList();
        assertEquals(1, imports.size());
        Xml.Tag bom = imports.getFirst();
        assertEquals("ua.raiffeisen.myraif", bom.getChildValue("groupId").orElseThrow());
        assertEquals("${" + PROPERTY + "}", bom.getChildValue("version").orElseThrow());
        assertEquals("pom", bom.getChildValue("type").orElseThrow());
        assertEquals("import", bom.getChildValue("scope").orElseThrow());
    }

    private static Xml.Tag child(Xml.Tag tag, String name) {
        return tag.getChild(name).orElseThrow();
    }

    private static String print(Xml.Tag tag) {
        var output = new PrintOutputCapture<>(0);
        new XmlPrinter<Integer>().visit(tag, output);
        return output.getOut().strip();
    }

    private static String dependency(String group, String artifact, String version) {
        return "<dependency><groupId>" + group + "</groupId><artifactId>" + artifact + "</artifactId>" +
                (version == null ? "" : "<version>" + version + "</version>") + "</dependency>";
    }

    private static Xml.Document pom(String body) {
        return parse("""
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>example</groupId>
                    <artifactId>service</artifactId>
                    <version>1</version>
                """ + body + "\n</project>\n");
    }

    private static Xml.Document parse(String source) {
        return ((Xml.Document) XmlParser.builder().build().parse(source).findFirst().orElseThrow())
                .withSourcePath(Path.of("pom.xml"));
    }

    private static Xml.Document migrate(Xml.Document pom) {
        return (Xml.Document) new MigrateCompanyDependencies().getVisitor().visit(pom, new InMemoryExecutionContext());
    }
}
