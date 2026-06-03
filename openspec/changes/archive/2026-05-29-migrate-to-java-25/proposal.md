## Why

Java 25 is the current LTS release, succeeding Java 21. Moving the toolchain, build images, and runtime image to Java 25 keeps the service on a supported LTS line (longer security/patch horizon), unlocks newer language and JVM capabilities, and aligns the project with the rest of the DIAL platform's base-image strategy. The work is purely tooling/runtime; no application features change.

## What Changes

- Bump the Gradle Java toolchain from `JavaLanguageVersion.of(21)` to `JavaLanguageVersion.of(25)` in `build.gradle`.
- Update the Dockerfile builder stage to `gradle:9.5.1-jdk25-alpine` and the runtime stage to `amazoncorretto:25-alpine`.
- Update the GitLab CI build image from `gradle:9.5.1-jdk21` to `gradle:9.5.1-jdk25`.
- Confirm the existing Gradle wrapper (9.5.1) executes a JDK 25 toolchain and that `./gradlew --warning-mode all clean build` stays deprecation-warning-free.
- Verify `generateJooq` codegen output is byte-identical under JDK 25 (no `src/main/java-generated/` diff).
- Update documented tech-stack version everywhere it appears: `AGENTS.md` Quick Reference, `openspec/config.yaml` Tech Stack, `.cursorrules`, `README.md`, and `docs/dev-setup/jdtls-setup.md`.
- Update the `build-tooling` spec's "Java toolchain pinned to JDK 21" requirement and its single-source-of-truth requirement to reference JDK 25.

No application code, API contract, DB schema, or runtime configuration property changes are expected. If a dependency or compiler flag (`-Werror`) surfaces a JDK 25 incompatibility, it is resolved within this change.

## Capabilities

### New Capabilities

_None._ This change modifies an existing tooling capability rather than introducing a new feature.

### Modified Capabilities
- `build-tooling`: The "Java toolchain pinned to JDK 21" requirement changes to pin JDK 25, and the "Documented Gradle version matches the wrapper" single-source-of-truth requirement is extended so the documented **Java** version (AGENTS.md, config.yaml, README, .cursorrules, dev-setup docs) and the CI/Docker `jdk25`/`corretto:25` image tags stay in lockstep with the declared toolchain.

## Impact

- **Build**: `build.gradle` (toolchain `languageVersion`); `-Werror` JavaCompile flag must still pass under JDK 25.
- **Containers**: `Dockerfile` (builder `gradle:9.5.1-jdk25-alpine`, runtime `amazoncorretto:25-alpine`).
- **CI**: `.gitlab-ci.yml` (`build` job image `gradle:9.5.1-jdk25`).
- **Docs / metadata**: `AGENTS.md`, `openspec/config.yaml`, `.cursorrules`, `README.md`, `docs/dev-setup/jdtls-setup.md`.
- **Spec**: `openspec/specs/build-tooling/spec.md` (delta).
- **Dependencies / runtime**: Spring Boot 3.5.14, jOOQ 3.20.4, Flyway 11.19.1, Zonky embedded-postgres 2.0.7, Testcontainers, MapStruct/Lombok annotation processors, and `bucket4j_jdk17` must run/compile under JDK 25 — verified by a clean `./gradlew clean build` and the full test suite (including Testcontainers + `JooqSchemaDriftTest`). Risk is concentrated in annotation processors (Lombok/MapStruct) and bytecode-manipulation libs that gate on the JDK class-file version.
- **No impact**: REST API contracts, OpenAPI examples, DB schema/Flyway migrations, security model, configuration properties.
