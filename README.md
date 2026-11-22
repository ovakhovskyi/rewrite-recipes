# rewrite-avro-trust

A Maven project containing an OpenRewrite recipe for migrating Spring Boot services to Apache Avro 1.12.2.

## Build

From the project directory:

```bash
mvn clean install
```

A JDK 25 is required to build and run the recipe. The `mvn -version` command shows which JDK Maven uses. `mvn clean install` installs the JAR with coordinates `org.arazei:openrewrite:1.0-SNAPSHOT` and Java 25 bytecode.

## Running in a service

From the root of the target Maven project, activate the Java recipe directly:

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:6.25.0:dryRunNoFork \
  -Drewrite.recipeArtifactCoordinates=org.arazei:openrewrite:1.0-SNAPSHOT \
  -Drewrite.activeRecipes=io.github.arazei.rewrite.recipes.ConfigureAvroTrust \
  -Drewrite.exportDatatables=true
```

Review `target/rewrite/rewrite.patch`. To apply the changes, replace `:dryRunNoFork` with `:runNoFork` and rerun the command. If you use a corporate `settings.xml`, pass `-s /path/to/settings.xml` during both installation and execution. Both commands must use the same local Maven repository.

No additional `.avsc` mask is needed: if the plugin passes only the schema path, the recipe reads it from disk. Only files included in the plugin's source set are considered. If `plainTextMasks` is overridden in the plugin configuration, ensure the list includes `**/META-INF/spring.factories`. Run the recipe against the complete main source and resource set, including any existing initializer and `spring.factories`. If the recipe is part of a migration that updates the BOM, run it **after** the update, when Avro 1.12.2 is resolved on the classpath.

## Applicability conditions

- Each Maven module is checked independently. The regular JAR `org.apache.avro:avro:1.12.2` must be present on the compile and runtime classpaths. Transitive dependencies and versions from dependency management are supported. Other versions, including 1.11.5 and 1.12.1, are skipped.
- There must be exactly one top-level class annotated with `@SpringBootApplication` in `src/main/java`. Its name can be arbitrary. The base package is taken from the Java declaration.
- The recipe searches for record, enum, and fixed definitions in `.avsc` files under `src/` in each module, including `src/avro`, `src/main/avro`, and arbitrary subdirectories. It also supports the production `sourceDirectory` configured in `avro-maven-plugin` and recognizes Java models annotated with `@AvroGenerated`. Trusted packages are derived from confirmed namespaces. The `src/test`, `src/it`, and `src/testFixtures` directories are excluded.
- The result is `com.example.orders.config.AvroTrustInitializer` and its registration in `src/main/resources/META-INF/spring.factories`. The initializer extends Avro's existing global predicate with exact package-match checks.
- Existing registrations and comments are preserved. For a previously generated class, the recipe updates the package list and restores the registration if needed. A different implementation in the target class, a duplicate initializer key, or an ambiguous application causes the module to be skipped. Comparisons with the expected source account for CRLF/LF differences and surrounding whitespace. Other user modifications are treated as conflicts.
- Skip reasons are exported to the `ConfigureAvroTrust$Decisions` table in the plugin's report directory when `-Drewrite.exportDatatables=true` is set. The recipe project and target service use separate POM files. The recipe does not change the service's dependencies.

## Verification

`mvn clean verify` builds the JAR and runs the unit tests. Testing the installed JAR against a real multi-module project requires Maven, a JDK, Python 3, and Git:

```bash
mvn clean install
python3 src/it/verify.py
# On JDK 25, you can test a service targeting Java 25 bytecode:
python3 src/it/verify.py --release 25
```

The script creates a separate temporary Maven project and compares the contents of all source files before and after two recipe runs. In one service, Avro is a transitive dependency and `avro-maven-plugin` generates the model. The test starts Spring Boot 4.1.2, verifies both registrations, and decodes a binary event into the generated class, checking a field value. A second eligible module uses a different application package and a model annotated with `@AvroGenerated`. The remaining modules verify that the recipe skips old versions, unsupported scopes, dependencies declared only in dependency management, schemas found only in test directories, source conflicts, and modules with two application classes. An additional scenario runs the recipe without a mask in a Maven project nested inside a Git repository. It checks two namespaces (including one from a custom `sourceDirectory`), skips test and excluded schemas, and updates an existing initializer.

## Explicit package selection

The main command automatically detects all packages. To select one of the detected packages, use the `trustedPackage` option through a declarative recipe, as shown in `examples/myraif-bff-auth.yml`.
