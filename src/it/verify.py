"""Run the installed recipe on an isolated Maven reactor and check its complete diff."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile

PLUGIN = "org.openrewrite.maven:rewrite-maven-plugin:6.25.0"
RECIPE = "dev.myraif.rewrite.ConfigureAvroTrust"
COORDINATES = "dev.myraif.rewrite:rewrite-avro-trust:1.0.2"
HERE = Path(__file__).resolve().parent
APP = Path("src/main/java/example/orders")
FACTORIES = Path("src/main/resources/META-INF/spring.factories")
INITIALIZER = APP / "config/AvroTrustInitializer.java"
AVRO = "org.apache.avro"


def dep(group, artifact, version="", scope=""):
    version_tag = f"<version>{version}</version>" if version else ""
    scope_tag = f"<scope>{scope}</scope>" if scope else ""
    return (f"<dependency><groupId>{group}</groupId><artifactId>{artifact}</artifactId>"
            f"{version_tag}{scope_tag}</dependency>")


def snapshot(root):
    return {p.relative_to(root): hashlib.sha256(p.read_bytes()).digest()
            for p in root.rglob("*") if p.is_file() and "target" not in p.parts}


def fixture(root):
    modules = {
        "service": dep(AVRO, "avro-ipc", "1.12.2"),
        "model-only": dep(AVRO, "avro"),
        "old": dep(AVRO, "avro", "1.11.5"),
        "previous": dep(AVRO, "avro", "1.12.1"),
        "managed-only": "",
        "test-only": dep(AVRO, "avro", scope="test"),
        "provided": dep(AVRO, "avro", scope="provided"),
        "runtime": dep(AVRO, "avro", scope="runtime"),
        "schema-free": dep(AVRO, "avro"),
        "ambiguous": dep(AVRO, "avro"),
        "conflict": dep(AVRO, "avro"),
    }
    parent = ("<groupId>test.fixture</groupId><artifactId>reactor</artifactId>"
              "<version>1</version>")
    (root / "pom.xml").write_text(
        '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
        + parent + "<packaging>pom</packaging><modules>"
        + "".join(f"<module>{name}</module>" for name in modules)
        + "</modules><properties><maven.compiler.release>17</maven.compiler.release>"
        "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>"
        "<dependencyManagement><dependencies>" + dep(AVRO, "avro", "1.12.2") +
        "</dependencies></dependencyManagement></project>")
    for name, avro in modules.items():
        target = root / name
        shutil.copytree(HERE / "service", target)
        plugins = ""
        deps = avro + dep("org.springframework.boot", "spring-boot-autoconfigure", "4.1.2")
        if name == "service":
            (target / "src/main/avro").rename(target / "src/avro")
            deps += dep("org.junit.jupiter", "junit-jupiter", "5.12.2", "test")
            plugins = """
              <build><plugins><plugin><groupId>org.apache.avro</groupId>
                <artifactId>avro-maven-plugin</artifactId><version>1.12.2</version>
                <configuration><sourceDirectory>src/avro</sourceDirectory></configuration>
                <executions><execution><goals><goal>schema</goal></goals></execution></executions>
              </plugin><plugin><groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version>
              </plugin></plugins></build>"""
        else:
            shutil.rmtree(target / "src/test")
        if name == "model-only":
            # A checked-in generated model is sufficient evidence without a schema file.
            for path in (target / APP).glob("*.java"):
                path.write_text(path.read_text().replace("example.orders", "example.payments"))
            shutil.rmtree(target / "src/main/avro")
            (target / FACTORIES).unlink()
            payments = target / "src/main/java/example/payments"
            (target / APP).rename(payments)
            (payments / "avro").mkdir()
            (payments / "avro/Payment.java").write_text(
                "package example.payments.avro; @org.apache.avro.specific.AvroGenerated public class Payment {}")
        if name == "ambiguous":
            (target / APP / "SecondApp.java").write_text((target / APP / "OrdersService.java")
                .read_text().replace("OrdersService", "SecondApp"))
        if name == "schema-free":
            (target / "src/main/avro/OrderEvent.avsc").rename(target / "TestEvent.avsc")
            test_schemas = target / "src/test/avro"
            test_schemas.mkdir(parents=True)
            (target / "TestEvent.avsc").rename(test_schemas / "TestEvent.avsc")
        if name == "conflict":
            (target / INITIALIZER).parent.mkdir(parents=True)
            (target / INITIALIZER).write_text(
                "package example.orders.config; public final class AvroTrustInitializer {}")
        (target / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
            f"<parent>{parent}</parent><artifactId>{name}</artifactId><dependencies>"
            f"{deps}</dependencies>{plugins}</project>")


def verify(root, mvn, release):
    fixture(root)
    args = [mvn, "-B", "-ntp", f"-Dmaven.compiler.release={release}"]
    run = [PLUGIN + ":run", "-Drewrite.recipeArtifactCoordinates=" + COORDINATES,
           "-Drewrite.activeRecipes=" + RECIPE, "-Drewrite.additionalPlainTextMasks=**/*.avsc"]
    before = snapshot(root)
    subprocess.run(args + run, cwd=root, check=True)
    after = snapshot(root)
    changed = {path for path in before.keys() | after.keys() if before.get(path) != after.get(path)}
    expected = {Path("service") / p for p in (INITIALIZER, FACTORIES)}
    expected.update({Path("model-only") / FACTORIES,
                     Path("model-only/src/main/java/example/payments/config/AvroTrustInitializer.java")})
    assert changed == expected, f"Unexpected first diff: {changed ^ expected}"
    subprocess.run(args + run, cwd=root, check=True)
    assert snapshot(root) == after, "Second run changed files"
    subprocess.run(args + ["-pl", "service", "clean", "verify"], cwd=root, check=True)
    service = root / "service"
    (service / FACTORIES).unlink()
    subprocess.run(args + ["-pl", "service"] + run, cwd=root, check=True)
    assert (service / FACTORIES).read_text().splitlines() == [
        "org.springframework.context.ApplicationContextInitializer=example.orders.config.AvroTrustInitializer"]
    assert snapshot(root)[Path("service") / INITIALIZER] == after[Path("service") / INITIALIZER]
    # Discover a different namespace before Maven generates Java, using the direct recipe.
    custom = root / "external-namespace"
    custom.mkdir()
    fixture(custom)
    app = custom / "service"
    namespace = "ua.aval.clients.diiagateway.myraifbffauth.avro"
    for path in (app / "src/avro/OrderEvent.avsc", app / "src/test/java/example/orders/OrderEventTest.java"):
        path.write_text(path.read_text().replace("example.orders.avro", namespace))
    options = ["-pl", "service", *run[1:]]
    subprocess.run(args + options + [PLUGIN + ":dryRunNoFork"], cwd=custom, check=True)
    patch, = custom.rglob("rewrite.patch")
    assert namespace in patch.read_text(), "Missing external namespace in dry run"
    subprocess.run(args + options + [PLUGIN + ":runNoFork"], cwd=custom, check=True)

    # Existing generated source gains exact packages from other src directories without duplicating registration.
    second_namespace = "example.custom.extra"
    second_schema = app / "src/events/example/Extra.avsc"
    second_schema.parent.mkdir(parents=True)
    second_schema.write_text('{"type":"record","name":"Extra","namespace":"' + second_namespace + '","fields":[]}')
    test_schema = app / "src/test/avro/Fixture.avsc"
    test_schema.parent.mkdir(parents=True)
    test_schema.write_text('{"type":"record","name":"Fixture","namespace":"example.test.fixture","fields":[]}')
    registration = (app / FACTORIES).read_bytes()
    subprocess.run(args + options + [PLUGIN + ":runNoFork"], cwd=custom, check=True)
    initializer = (app / INITIALIZER).read_text()
    assert all(('"' + name + '"') in initializer for name in (namespace, second_namespace))
    assert "example.test.fixture" not in initializer
    assert (app / FACTORIES).read_bytes() == registration
    before = snapshot(custom)
    subprocess.run(args + options + [PLUGIN + ":runNoFork"], cwd=custom, check=True)
    assert snapshot(custom) == before, "Repeat run changed a generated initializer"
    subprocess.run(args + ["-pl", "service", "clean", "verify"], cwd=custom, check=True)
    print("Verified direct discovery, namespace updates, exact package checks, idempotence, and Spring Boot/Avro runtime.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mvn", default="mvn", help="Maven executable or wrapper (absolute path or on PATH)")
    parser.add_argument("--workdir", type=Path, help="Keep the generated fixture in a new directory")
    parser.add_argument("--release", choices=["17", "21", "25"], default="17", help="Fixture Java target")
    options = parser.parse_args()
    if options.workdir:
        options.workdir.mkdir(parents=True, exist_ok=False)
        verify(options.workdir.resolve(), options.mvn, options.release)
    else:
        with tempfile.TemporaryDirectory() as tmp:
            verify(Path(tmp), options.mvn, options.release)
