## 1. Toolchain and build images

- [x] 1.1 Bump the Java toolchain in `build.gradle` from `JavaLanguageVersion.of(21)` to `JavaLanguageVersion.of(25)`
- [x] 1.2 Update `Dockerfile` builder stage to `FROM gradle:9.5.1-jdk25-alpine AS builder`
- [x] 1.3 Update `Dockerfile` runtime stage to `FROM amazoncorretto:25-alpine AS runtime`
- [x] 1.4 Update `.gitlab-ci.yml` `build` job image to `gradle:9.5.1-jdk25`

## 2. Documentation and metadata sync

- [x] 2.1 Update `AGENTS.md` Quick Reference table: `| Language | Java 25 |`
- [x] 2.2 Update `openspec/config.yaml` Tech Stack line to `- Java 25`
- [x] 2.3 Update `README.md` Java version reference to Java 25
- [x] 2.4 Update `.cursorrules` Java version line to `**Java**: 25 (LTS)`
- [x] 2.5 Update `docs/dev-setup/jdtls-setup.md` Java 21 references (path/JAVA_HOME) to Java 25

## 3. Build verification on JDK 25

- [x] 3.1 Run `./gradlew --warning-mode all clean build` on JDK 25; confirm exit 0 and no `Deprecated Gradle features were used` line — BUILD SUCCESSFUL in 2m46s, 0 deprecation warnings
- [x] 3.2 Resolve any `-Werror` compiler errors/warnings or annotation-processor (Lombok/MapStruct) failures with the minimal dependency bump needed; record any bump in `proposal.md` Impact — no fallout; clean compile under `-Werror`, no dependency bumps required
- [x] 3.3 Confirm the full test suite passes (unit + Testcontainers functional, including `JooqSchemaDriftTest`) — all tests passed (`check`/`build` green)
- [x] 3.4 Run `./gradlew generateJooq` and confirm `git diff --exit-code src/main/java-generated/` is clean (no codegen diff) — clean, no diff
- [x] 3.5 Run `docker build -t dial-eval-backend .` to validate both image stages resolve and the jar is produced — image built; runtime is Corretto 25.0.3 LTS

## 4. Spec sync

- [x] 4.1 After implementation, ensure the `build-tooling` delta spec (RENAME to JDK 25 + MODIFIED requirements) is accurate; sync to `openspec/specs/build-tooling/spec.md` at archive time — synced via openspec-sync-specs; both requirements renamed+modified, 3 unchanged requirements preserved, no leftover JDK 21 refs
