## 1. Bump the wrapper

- [x] 1.1 Confirm working tree is clean on branch `feat/gradle-9.x` and JDK 21 is on PATH (`java -version` reports 21).
- [x] 1.2 Run `./gradlew wrapper --gradle-version 9.5.1 --distribution-type bin` — first pass updates `gradle/wrapper/gradle-wrapper.properties` `distributionUrl` only. (Note: Gradle publishes 9.5 as patch 9.5.0/9.5.1; we pin the latest patch `9.5.1`.)
- [x] 1.3 Run `./gradlew wrapper --gradle-version 9.5.1 --distribution-type bin` a **second time** — Gradle 9.5.1 now drives, refreshing `gradle-wrapper.jar`, `gradlew`, and `gradlew.bat` to the 9.5.1 templates.
- [x] 1.4 Verify with `git status` that exactly four wrapper files changed: `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`.

## 2. Resolve Gradle 9 deprecations and breakages

- [x] 2.1 Run `./gradlew --warning-mode all clean build` and capture all output to a scratch file. (Captured at `/tmp/gradle9-build.log`.)
- [x] 2.2 For every `Deprecated Gradle features were used in this build` warning, locate the offending line in `build.gradle` and fix using the recipe in the official Gradle 9.x upgrade guide. **No-op: zero deprecation warnings emitted under Gradle 9.5.1.** The only Java-level `Note: Some input files use or override a deprecated API` lines refer to Java API deprecations in our source code (pre-existing, unrelated to Gradle).
- [x] 2.3 If the `buildscript` classpath block fails to resolve under Gradle 9's stricter resolution (Zonky embedded-postgres + Flyway + jOOQ + postgresql JDBC), add targeted `resolutionStrategy.force` pins inside `buildscript { configurations.classpath { resolutionStrategy { ... } } }`. Do NOT bypass with `--rerun-tasks`. **No-op: buildscript classpath resolved cleanly without force pins.**
- [x] 2.4 Re-run `./gradlew --warning-mode all clean build` until exit code 0 with **zero deprecation warnings**. `BUILD SUCCESSFUL in 3m 3s`, exit 0, `grep -c "Deprecated Gradle features were used in this build" /tmp/gradle9-build.log` == 0.

## 3. Verify codegen and tests

- [x] 3.1 Run `./gradlew generateJooq`. `BUILD SUCCESSFUL in 4s`.
- [x] 3.2 Run `git diff --exit-code src/main/java-generated/` — exit code MUST be 0 (zero byte difference). Confirmed: exit 0, zero diff.
- [x] 3.3 Run `./gradlew checkstyleMain checkstyleTest` and confirm green. (Covered by the `clean build` in 2.4 — both `:checkstyleMain` and `:checkstyleTest` reported success.)
- [x] 3.4 Run `./gradlew test` and confirm all tests pass — including `JooqSchemaDriftTest`, `JdbcTemplateFenceTest`, `LayeredArchitectureTest`, `LoggingConventionTest`, and every functional test suite under `com.epam.aidial.evaluation.functional.PostgresFunctionalTests`. (Covered by the `clean build` in 2.4 — `:test` reported success; if `JooqSchemaDriftTest` had failed, `:test` would not have succeeded.)
- [x] 3.5 Run `./gradlew clean build` one final time to confirm the full build pipeline (compile → checkstyle → test → jar) is green end-to-end. (Same `clean build` from 2.4 — full pipeline `clean → processResources → compileJava → bootJar → jar → assemble → checkstyleMain → compileTestJava → checkstyleTest → test → check → build`, all green.)

## 4. Verify plugin compatibility

- [x] 4.1 Confirm `io.freefair.lombok 9.2.0` applies cleanly under Gradle 9.5.1 — check for any "plugin requires Gradle X.Y" warnings in the build log. **Verified**: build log scan via `grep -iE "plugin requires|incompat|unsupported"` returned zero hits; `:generateEffectiveLombokConfig` and Lombok-driven test compile succeeded.
- [x] 4.2 Confirm `org.springframework.boot 3.5.14` applies cleanly. **Verified**: `:bootJar` produced the boot jar successfully; no plugin compat warnings.
- [x] 4.3 Confirm Checkstyle plugin works with `toolVersion = "10.26.1"`. **Verified**: `:checkstyleMain` and `:checkstyleTest` both succeeded under Gradle 9.5.1.

## 5. Update CI and Docker

- [x] 5.1 Inspect `.gitlab-ci.yml` for any pinned Gradle image tag. **Found** `image: gradle:8.5-jdk21` (line 46) — bumped to `image: gradle:9.5.1-jdk21`. The build job invokes `./gradlew build`, so the wrapper still drives the actual build; the image bump keeps the bundled toolchain aligned for the rare case that scripts in `before_script` invoke `gradle` directly.
- [x] 5.2 Inspect `Dockerfile` for any system-installed Gradle. **Found** `FROM gradle:8.14-jdk21-alpine AS builder` (line 2) — bumped to `FROM gradle:9.5.1-jdk21-alpine`. The builder stage uses `RUN gradle --no-daemon clean bootJar` (NOT the wrapper), so this bump is mandatory to keep the Docker build aligned with the wrapper version. Runtime stage (`amazoncorretto:21-alpine`) does not include Gradle and was not touched.
- [x] 5.3 Confirm CI runner JDK is 17+ (Gradle 9.5 minimum). **Verified**: both updated images (`gradle:9.5.1-jdk21` and `gradle:9.5.1-jdk21-alpine`) ship JDK 21; the GitLab CI `build` job already used a `-jdk21` variant pre-bump.

## 6. Update docs and config metadata

- [x] 6.1 Edit `AGENTS.md` — Quick Reference table row: changed `| Build | Gradle 8.14.4 |` to `| Build | Gradle 9.5.1 |`.
- [x] 6.2 Edit `openspec/config.yaml` under the `# Tech Stack` block: changed `- Build: Gradle 8.14.4` to `- Build: Gradle 9.5.1`.
- [x] 6.3 Grep the repo for any other occurrence of `8.14` / `gradle:8` (excluding `openspec/changes/archive/` and the in-flight `upgrade-gradle-to-9-5` change). **Final sweep returned zero hits** across `.md`, `.yml`, `.yaml`, `Dockerfile`, `.gradle`, `.properties`, `config.yaml`.

## 7. Commit and open PR

- [x] 7.1 Stage all changes; verify the diff: wrapper files + `AGENTS.md` + `openspec/config.yaml` + `.gitlab-ci.yml` + `Dockerfile`. (No `build.gradle` edits were needed.)
- [x] 7.2 Commit with message and standard `Co-Authored-By` trailer. Commit `3165480 Upgrade gradle version to 9.5.1`.
- [x] 7.3 Push branch `feat/gradle-9.x` and open PR against `development`. CI green on remote.

## 8. Archive the change

- [x] 8.1 Run `/opsx:archive upgrade-gradle-to-9-5` after the PR merges. Archived to `openspec/changes/archive/2026-05-29-upgrade-gradle-to-9-5/`. Delta spec synced via `/opsx:sync` → new main spec at `openspec/specs/build-tooling/spec.md`; `openspec/specs/README.md` updated under Infrastructure section.
