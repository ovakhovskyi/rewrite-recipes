# rewrite-avro-trust

A Maven project containing an OpenRewrite recipe for migrating Spring Boot services to Apache Avro 1.12.2.

## Build

From the project directory:

```bash
mvn clean install
```

A JDK 25 is required to build and run the recipe. The `mvn -version` command shows which JDK Maven uses. `mvn clean install` installs the JAR with coordinates `io.github.arazei:rewrite-recipes:1.0.0-SNAPSHOT` and Java 25 bytecode.

## Running in a service

From the root of the target Maven project, activate the Java recipe directly:

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:6.25.0:dryRunNoFork \
  -Drewrite.recipeArtifactCoordinates=io.github.arazei:rewrite-recipes:1.0.0-SNAPSHOT \
  -Drewrite.activeRecipes=io.github.arazei.rewrite.recipes.ConfigureAvroTrust \
  -Drewrite.exportDatatables=true
```

Review `target/rewrite/rewrite.patch`. To apply the changes, replace `:dryRunNoFork` with `:runNoFork` and rerun the command. If you use a corporate `settings.xml`, pass `-s /path/to/settings.xml` during both installation and execution. Both commands must use the same local Maven repository.

No additional `.avsc` mask is needed: if the plugin passes only the schema path, the recipe reads it from disk. Only files included in the plugin's source set are considered. If `plainTextMasks` is overridden in the plugin configuration, ensure the list includes `**/META-INF/spring.factories`. Run the recipe against the complete main source and resource set, including any existing initializer and `spring.factories`. If the recipe is part of a migration that updates the BOM, run it **after** the update, when Avro 1.12.2 is resolved on the classpath.

### Migrate to Java 25 and Spring Boot 4

The [composite recipe](examples/migration-boot4.yml) upgrades to Java 25 and the latest Spring Boot `4.1.x` release. Arconia's Spring Boot migration includes the Jackson 2 → 3 recipe. The composite also updates OpenAPI Generator's `spring` configuration for Boot 4 and Jackson 3, runs OpenRewrite's Testcontainers 2 migration, and configures Avro trust for the package prefixes listed in the YAML. Install the recipe JAR as described above, then run this from the root of the target service on JDK 25:

```bash
mvn -s settings.xml -U \
  org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates="org.openrewrite.recipe:rewrite-migrate-java:RELEASE,io.arconia.migrations:rewrite-spring:RELEASE,org.openrewrite.recipe:rewrite-spring:RELEASE,org.openrewrite.recipe:rewrite-testing-frameworks:RELEASE,io.github.arazei:rewrite-recipes:1.0.0-SNAPSHOT" \
  -Drewrite.activeRecipes=io.github.arazei.rewrite.recipes.UpgradeToJava25AndSpringBoot4 \
  '-Drewrite.configLocation=/path/to/openrewrite/examples/migration-boot4.yml' \
  '-Drewrite.additionalPlainTextMasks=**/Dockerfile' \
  -Drewrite.failOnInvalidActiveRecipes=true \
  -Drewrite.exportDatatables=true
```

Review `target/rewrite/rewrite.patch`, replace `:dryRun` with `:run`, then verify with `mvn -s settings.xml verify`. The composite does not upgrade Avro itself. If the trust changes are missing because the module did not yet resolve Avro 1.12.2, rerun after the module resolves that version on both production classpaths.

The composite runs OpenRewrite's Spring Kafka 4 migration. It replaces fully qualified Spring Kafka class names such as `JsonSerializer` and `JsonDeserializer` with their `JacksonJson*` equivalents in Java code and in Spring Boot configuration values, including `json-producer.value-serializer` in `application.yml`.

After Arconia's Boot upgrade, the composite removes the direct Maven dependencies `org.springframework.boot:spring-boot-starter-zipkin` and `org.springframework.boot:spring-boot-starter-zipkin-test` to avoid conflicts with OpenTelemetry.

The composite uses the resolved Maven dependency tree to exclude `io.github.resilience4j:resilience4j-spring-boot3` from the direct dependencies that bring it in transitively. Verify the result with `mvn -s settings.xml dependency:tree -Dincludes=io.github.resilience4j:resilience4j-spring-boot3`. With Spring Cloud Circuit Breaker 5.0.x, the exclusion can prevent startup if the service relies on that module to provide beans such as `CircuitBreakerRegistry`. Ensure the service has compatible Resilience4j configuration and verify that it starts.

For Testcontainers, check any `DockerComposeContainer` replacements: the recipe can leave `<?>` or constructor `<>` on `ComposeContainer`, which is not generic. Remove those type arguments if present.

`io.github.arazei.rewrite.recipes.migration.boot4.MigrateCompanyDependencies` imports `ua.raiffeisen.myraif:myraif-platform-dependencies` at `4.1.3-v.1`. It uses either `myraif.platform.dependencies.version` or `myraif-platform-dependencies.version`, preferring the property already referenced by the BOM, then a property already declared in the POM. If both or neither are declared and the BOM does not reference either, it uses `myraif.platform.dependencies.version`. It moves `myraif-bff-error-handling-lib` from `ua.aval.myraif` to `ua.raiffeisen.myraif` and removes explicit versions from direct dependencies whose group IDs start with `ua.aval.myraif` or `ua.raiffeisen.myraif` (no dot is required after the prefix). To run just this step, use its recipe name and `io.github.arazei:rewrite-recipes:1.0.0-SNAPSHOT` as the only recipe artifact and omit `rewrite.configLocation` and the Dockerfile mask. After applying the patch, verify that the BOM manages the affected dependencies with `mvn -s settings.xml verify`.

`io.github.arazei.rewrite.recipes.migration.boot4.MigrateCompanyJavaApi` updates imports and references to `ErrorCode`, `MyRaifException`, `CurrentUserContext`, and `DeviceFingerprint` to their new MyRaif Common API packages. It also replaces `RaiffeisenFeignException` with `WrapperFeignException` in both the import and Java type usages, without adapting constructor or method arguments. To preview just this step, run:

```bash
mvn -s settings.xml org.openrewrite.maven:rewrite-maven-plugin:6.46.1:dryRun \
  -Drewrite.recipeArtifactCoordinates=io.github.arazei:rewrite-recipes:1.0.0-SNAPSHOT \
  -Drewrite.activeRecipes=io.github.arazei.rewrite.recipes.migration.boot4.MigrateCompanyJavaApi \
  -Drewrite.failOnInvalidActiveRecipes=true
```

Run the Java API migration on the original service while Maven can still resolve the old types. The composite updates the POM and Java sources in the same patch, but Java is parsed against the original classpath. After applying the composite patch, run `mvn -s settings.xml verify` to check that the new libraries resolve and the existing calls compile against the replacement API.

`io.github.arazei.rewrite.recipes.migration.boot4.MigrateSpringBootDockerfile` replaces the image in every `FROM` in files named `Dockerfile` with `harbor.shared.avalaunch.rbua/infra-resources/eclipse-temurin-base-image:25-jre-ubi10-minimal`, including `FROM` references to earlier stages. It preserves `--platform` and `AS` aliases and adds `WORKDIR /app` immediately after `FROM` only when that stage has no `WORKDIR`. To run just this step, use its recipe name and `io.github.arazei:rewrite-recipes:1.0.0-SNAPSHOT` as the only recipe artifact, omit `rewrite.configLocation`, and keep the Dockerfile mask. Reinstall the recipe JAR to pick up changes to this step.

`io.github.arazei.rewrite.recipes.migration.boot4.MigrateSpringBootLayeredDockerfile` migrates the legacy layered layout as a unit: it renames the builder JAR to `application.jar`, uses `jarmode=tools` to extract into `extracted`, updates all four layer copies, and changes the exec-form `ENTRYPOINT` from `JarLauncher` to `-jar application.jar` while retaining JVM options. If a Dockerfile already uses `tools extract --layers --destination` and copies all four layers from that destination, it updates the `ENTRYPOINT` using the existing JAR filename. The layout assumes `/app` on both stages, which the preceding recipe sets if absent.

`io.github.arazei.rewrite.recipes.migration.boot4.MigrateGitHubWorkflows` updates `.github/workflows/*.yml` and `.github/workflows/*.yaml`. It replaces `image` values starting with `harbor.shared.avalaunch.rbua/infra-resources/maven-` with `harbor.shared.avalaunch.rbua/infra-resources/maven-3.10:25`. When a mapping contains both a `tool` starting with `maven-` and a scalar `version`, it sets them to `maven-3.10` and `25`. For a standalone preview, use this recipe name in the Java API preview command above.

`io.github.arazei.rewrite.recipes.migration.boot4.RemoveLegacyClasses` removes direct and indirect subclasses of `ua.aval.myraif.error.advice.GeneralAdvice` and `ua.aval.myraif.error.advice.WebExceptionHandler`. It keeps subclasses referenced by surviving Java code, including tests, and explains why in the `RemoveLegacyClasses$RetainedClasses` data table (`-Drewrite.exportDatatables=true`). Review the patch while the old types are still available on the classpath: the recipe relies on Java type attribution and does not inspect configuration or reflective string references.

`io.github.arazei.rewrite.recipes.migration.boot4.ConfigureApplicationProperties` sets `management.opentelemetry.tracing.export.otlp.transport` to `GRPC` and `management.otlp.metrics.export.enabled` to the YAML boolean `false` in the base document of existing `src/main/resources/application.yml` files. It adds missing keys and updates existing nested or dotted keys in place; profile documents and separate profile files are left untouched.

## Applicability conditions

- Each Maven module is checked independently. The regular JAR `org.apache.avro:avro:1.12.2` must be present on the compile and runtime classpaths. Transitive dependencies and versions from dependency management are supported. Other versions, including 1.11.5 and 1.12.1, are skipped.
- There must be exactly one top-level class annotated with `@SpringBootApplication` in `src/main/java`. Its name can be arbitrary. The base package is taken from the Java declaration.
- The recipe searches for record, enum, and fixed definitions in `.avsc` files under `src/` in each module, including `src/avro`, `src/main/avro`, and arbitrary subdirectories. It also supports the production `sourceDirectory` configured in `avro-maven-plugin` and recognizes Java models annotated with `@AvroGenerated`. Trusted packages are derived from confirmed namespaces. The `src/test`, `src/it`, and `src/testFixtures` directories are excluded.
- The result is `com.example.orders.config.AvroTrustInitializer` and its registration in `src/main/resources/META-INF/spring.factories`. The initializer extends Avro's existing global predicate with exact checks for all discovered model packages and any explicitly configured packages or prefixes.
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

## Adding trusted packages

The recipe always adds exact checks for discovered Avro model packages. To add another exact package, set `trustedPackage` in a declarative recipe, as shown in [the exact-package example](examples/avro-trust-exact-package.yml).

To trust whole package hierarchies, run this from the root of the target project. Replace `/path/to/openrewrite` with the path to this repository:

```bash
mvn org.openrewrite.maven:rewrite-maven-plugin:6.25.0:dryRunNoFork \
  -Drewrite.recipeArtifactCoordinates=io.github.arazei:rewrite-recipes:1.0.0-SNAPSHOT \
  -Drewrite.activeRecipes=io.github.arazei.rewrite.recipes.ConfigureAvroTrustByPrefix \
  '-Drewrite.configLocation=/path/to/openrewrite/examples/avro-trust-prefixes.yml'
```

The active recipe is the `name:` in [the YAML file](examples/avro-trust-prefixes.yml). Set `trustedPackagePrefixes` to a non-empty list of Java package names (no wildcards or trailing dots). Both options can be set together under the same `ConfigureAvroTrust` entry. To apply the changes, use `runNoFork` instead of `dryRunNoFork`.

Explicit packages and prefixes are added even when the module has no discovered models, provided it meets the Spring Boot and Avro requirements above. A prefix trusts **all classes** in the named package and its subpackages, including classes from dependencies and classes without `@AvroGenerated`.
