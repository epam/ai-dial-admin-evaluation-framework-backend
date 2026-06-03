## RENAMED Requirements

- FROM: `### Requirement: Java toolchain pinned to JDK 21`
- TO: `### Requirement: Java toolchain pinned to JDK 25`

- FROM: `### Requirement: Documented Gradle version matches the wrapper`
- TO: `### Requirement: Documented Java and Gradle versions match the wrapper and toolchain`

## MODIFIED Requirements

### Requirement: Java toolchain pinned to JDK 25
The build SHALL declare a Java toolchain of `JavaLanguageVersion.of(25)` in `build.gradle`. The Gradle wrapper version chosen MUST be compatible with executing a JDK 25 toolchain (Gradle 9.5.1 supports this, as evidenced by the published `gradle:9.5.1-jdk25-alpine` build image).

#### Scenario: build.gradle declares the toolchain
- **WHEN** a developer reads `build.gradle`
- **THEN** the `java { toolchain { ... } }` block SHALL set `languageVersion = JavaLanguageVersion.of(25)`
- **AND THEN** the build SHALL provision a JDK 25 toolchain automatically when the host lacks one

#### Scenario: Build and tests pass on JDK 25
- **WHEN** `./gradlew --warning-mode all clean build` runs with the JDK 25 toolchain
- **THEN** compilation SHALL succeed under the `-Werror` JavaCompile flag with no errors or warnings
- **AND THEN** the full test suite (unit + Testcontainers functional, including `JooqSchemaDriftTest`) SHALL pass

### Requirement: Documented Java and Gradle versions match the wrapper and toolchain
The Gradle version recorded in `AGENTS.md` (Quick Reference table), `openspec/config.yaml` (Tech Stack section), and the build-tooling image tags in `.gitlab-ci.yml` and `Dockerfile` SHALL match the version pinned in `gradle/wrapper/gradle-wrapper.properties`. The Java version recorded in `AGENTS.md` (Quick Reference table), `openspec/config.yaml` (Tech Stack section), `README.md`, `.cursorrules`, `docs/dev-setup/jdtls-setup.md`, and the JDK image tags in `.gitlab-ci.yml` (`gradle:<gradle>-jdk25`) and `Dockerfile` (builder `gradle:<gradle>-jdk25-alpine`, runtime `amazoncorretto:25-alpine`) SHALL match the Java toolchain declared in `build.gradle`. The wrapper is the source of truth for the Gradle version and the `build.gradle` toolchain is the source of truth for the Java version; docs, config metadata, and CI/Docker base images follow both.

#### Scenario: Single source of truth for Gradle version
- **WHEN** any of `AGENTS.md`, `openspec/config.yaml`, `gradle/wrapper/gradle-wrapper.properties`, `.gitlab-ci.yml`, or `Dockerfile` is updated to change the Gradle version
- **THEN** the other four SHALL be updated in the same change to match

#### Scenario: Single source of truth for Java version
- **WHEN** the Java toolchain in `build.gradle` is changed
- **THEN** `AGENTS.md`, `openspec/config.yaml`, `README.md`, `.cursorrules`, and `docs/dev-setup/jdtls-setup.md` SHALL be updated in the same change to state the same Java version
- **AND THEN** the `.gitlab-ci.yml` `jdk<version>` image tag and the `Dockerfile` builder (`gradle:<gradle>-jdk<version>-alpine`) and runtime (`amazoncorretto:<version>-alpine`) image tags SHALL be updated to the same Java version
