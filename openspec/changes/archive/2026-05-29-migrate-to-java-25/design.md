## Context

The service currently pins a Java 21 toolchain (`build.gradle`), builds in `gradle:9.5.1-jdk21-alpine`, and runs on `amazoncorretto:21-alpine`. The Gradle wrapper is 9.5.1 and `generateJooq` produces committed sources under `src/main/java-generated/`. JavaCompile runs with `-Werror`, so any new compiler warning under a newer JDK fails the build. The `build-tooling` spec encodes the toolchain version and a single-source-of-truth rule binding documented versions to the wrapper/toolchain.

Java 25 is the new LTS. The build images requested by the user already exist on the registries (`gradle:9.5.1-jdk25-alpine`, `amazoncorretto:25-alpine`), confirming Gradle 9.5.1 ships JDK 25 toolchain support. This change is tooling/runtime only — no application logic, API, schema, or config-property changes.

## Goals / Non-Goals

**Goals:**
- Compile, test, and run the service on Java 25.
- Builder image `gradle:9.5.1-jdk25-alpine`; runtime image `amazoncorretto:25-alpine`; CI image `gradle:9.5.1-jdk25`.
- Keep `./gradlew --warning-mode all clean build` green and deprecation-warning-free under JDK 25.
- Keep `generateJooq` output byte-identical (no `src/main/java-generated/` diff).
- Keep all documented Java-version references and the `build-tooling` spec in lockstep with the toolchain.

**Non-Goals:**
- Bumping the Gradle wrapper version (stays 9.5.1).
- Upgrading Spring Boot, jOOQ, Flyway, or any application dependency unless a JDK 25 incompatibility forces a minimal bump.
- Adopting new Java 25 language features in application code (separate future work).
- Changing API contracts, DB schema, security, or configuration properties.

## Decisions

**1. Toolchain bump only, wrapper unchanged.**
Set `languageVersion = JavaLanguageVersion.of(25)` in `build.gradle`. Gradle 9.5.1 supports a JDK 25 toolchain (the published `gradle:9.5.1-jdk25-alpine` image is the proof). Keeping the wrapper at 9.5.1 minimizes blast radius and avoids triggering the spec's "no new deprecation warnings on wrapper bump" surface. _Alternative considered:_ also bump Gradle — rejected as unnecessary scope.

**2. Pin exact image tags requested by the user.**
Builder `gradle:9.5.1-jdk25-alpine`, runtime `amazoncorretto:25-alpine`, CI `gradle:9.5.1-jdk25`. These mirror the existing tag scheme (only the `jdk21`→`jdk25` / `:21`→`:25` segment changes), so the diff is mechanical and the spec's single-source-of-truth rule keeps them aligned. _Alternative considered:_ Temurin/Eclipse base images — rejected to stay consistent with the current Corretto runtime.

**3. Treat `-Werror` as the compatibility gate.**
Because JavaCompile uses `-Werror`, the clean build is itself the regression detector for JDK 25 source/annotation-processor incompatibilities (Lombok, MapStruct, jOOQ codegen). No separate compatibility shim is added up front; if a real warning/error appears, fix it minimally within this change (e.g. a targeted dependency constraint bump). _Alternative considered:_ relax `-Werror` temporarily — rejected; it would hide exactly the problems we want to catch.

**4. Verify codegen stability rather than regenerate blindly.**
Run `./gradlew generateJooq` once on JDK 25 and assert `git diff --exit-code src/main/java-generated/` is clean. jOOQ codegen output is JDK-version-independent given the same migrations and jOOQ version, so the expectation is zero diff; a non-empty diff is a signal to investigate, not to commit.

**5. Spec delta uses RENAME + MODIFY for the version-named requirement.**
The existing requirement name literally contains "JDK 21", so the delta renames it to "JDK 25" and modifies its body. The single-source-of-truth requirement is version-neutral in name, so it is a plain MODIFIED that additionally binds the documented **Java** version and the `jdk25`/`corretto:25` image tags.

## Risks / Trade-offs

- **Annotation processors reject JDK 25 class-file version** (Lombok 9.2.0 plugin / MapStruct 1.6.3) → Mitigation: full `./gradlew clean build` is the gate; if it fails, bump the offending processor to the minimal JDK-25-compatible version within this change and record it in the proposal Impact.
- **Spring Boot 3.5.14 not officially certified on JDK 25** → Mitigation: run the full Testcontainers functional suite (app context boot + DB integration); a green suite is the acceptance signal. Escalate to a Spring Boot patch bump only if context startup fails.
- **Bytecode libs (ByteBuddy via Mockito/Testcontainers, gRPC-netty-shaded, bucket4j_jdk17)** may need newer versions for JDK 25 → Mitigation: surfaced by the test run; fix with a targeted constraint if needed.
- **`generateJooq` diff under JDK 25** (unexpected) → Mitigation: investigate before committing; do not blindly commit a regenerated diff.
- **Documentation drift** (a Java-21 reference left behind) → Mitigation: the spec's single-source-of-truth requirement enumerates every file; the tasks list checks each one.

## Migration Plan

1. Edit `build.gradle` toolchain → 25.
2. Edit `Dockerfile` (builder + runtime) and `.gitlab-ci.yml` build image.
3. Update docs/metadata: `AGENTS.md`, `openspec/config.yaml`, `.cursorrules`, `README.md`, `docs/dev-setup/jdtls-setup.md`.
4. Run `./gradlew --warning-mode all clean build` on JDK 25; fix any `-Werror`/deprecation fallout minimally.
5. Run `./gradlew generateJooq` and confirm `git diff --exit-code src/main/java-generated/` is clean.
6. Build the Docker image (`docker build`) to validate both stages resolve and the jar runs.
7. Update the `build-tooling` spec delta.

**Rollback:** revert the change set (single commit/PR). No runtime data migration is involved, so rollback is a pure image/toolchain revert with no state implications.

## Open Questions

- None blocking. Any dependency bump forced by a JDK 25 incompatibility will be decided at build time and recorded in the proposal Impact section.
