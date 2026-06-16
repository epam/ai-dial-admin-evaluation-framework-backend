## Context

The project's Gradle wrapper is pinned to **8.14.4** (`gradle/wrapper/gradle-wrapper.properties` `distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.4-bin.zip`). The build script is Groovy DSL (`build.gradle`, ~250 lines). Plugin landscape:

- `id 'java'` (built-in)
- `id 'checkstyle'` (built-in; toolVersion `10.26.1`)
- `id 'io.freefair.lombok' version '9.2.0'` — vendor compatibility matrix supports Gradle 8.10 + Gradle 9.x
- `id 'org.springframework.boot' version '3.5.14'` — Spring Boot 3.5.x officially supports Gradle 7.6.4, 8.x, and 9.0+

Notable build features in use:
- Custom `generateJooq` task with `buildscript { dependencies { classpath … } }` (Zonky embedded-postgres, Flyway, jOOQ codegen, postgresql JDBC).
- `configurations.configureEach { resolutionStrategy { force … } }` (force pins on swagger-core, json-schema-validator).
- `tasks.withType(JavaCompile).configureEach { options.compilerArgs += "-Werror" }`.
- `sourceSets.main.java.srcDirs += "src/main/java-generated"` for committed jOOQ codegen output.
- `java.toolchain.languageVersion = JavaLanguageVersion.of(21)`.

Gradle 9.x removed/changed APIs that are most likely to bite a script of this shape:
1. `Project.convention(...)` and `Convention` API — fully removed in Gradle 9 (we don't use these; verify via grep).
2. `JavaPluginConvention` — replaced by `JavaPluginExtension`; we already use `java { toolchain { ... } }`.
3. Stricter `Property` wiring — assignments via `=` to lazy properties must use the configuration DSL form.
4. Deprecation of the legacy `archiveBaseName`/`archiveVersion` strings — we don't set these explicitly.
5. Tightened `buildscript` classpath resolution: in Gradle 9 the dependency resolution within `buildscript {}` is stricter about transitive duplicate classes; the `generateJooq` script-classpath block (Zonky + Flyway + jOOQ + postgresql) is a hot spot to retest.
6. `configurations { compileOnly { extendsFrom annotationProcessor } }` — still supported, but in Gradle 9 the `configurations { }` block is eager-resolution; this is harmless here.
7. Gradle 9 enforces `org.gradle.unsafe.configuration-cache=true` to opt in (not enabled today; we are NOT enabling it in this change).

The build is consumed by:
- Local developers running `./gradlew clean build test generateJooq`.
- GitLab CI (`.gitlab-ci.yml`) — uses an image that almost certainly invokes the wrapper, not a system `gradle`.
- Dockerfile — runtime image; does not build inside Docker. (Confirm during implementation.)

There are no shared `buildSrc/`, no precompiled script plugins, no version catalog (`gradle/libs.versions.toml`). This minimizes the surface area for Gradle 9 incompatibilities.

## Goals / Non-Goals

**Goals:**
- Bump the Gradle wrapper to **9.5** while keeping the build green: `./gradlew clean build` succeeds, all tests pass (including `JooqSchemaDriftTest`, `JdbcTemplateFenceTest`, `LayeredArchitectureTest`, `LoggingConventionTest`), checkstyle is clean, `./gradlew generateJooq` produces a byte-identical output diff against the current `src/main/java-generated/` tree.
- Eliminate every deprecation warning that fires when running `./gradlew --warning-mode all build` so the build is forward-compatible with Gradle 10.
- Update tech-stack metadata in `AGENTS.md` and `openspec/config.yaml` so the documented version matches reality.
- Keep developer ergonomics identical — same `./gradlew` commands, same tasks, same outputs.

**Non-Goals:**
- Migrating the build script from Groovy to Kotlin DSL.
- Introducing a Gradle version catalog (`libs.versions.toml`).
- Enabling Configuration Cache, Build Cache remote, or Isolated Projects.
- Bumping Spring Boot, jOOQ, Flyway, Lombok plugin, or any application dependency (those are separate changes; only fix versions if Gradle 9.5 cannot apply the plugin at all).
- Migrating CI off GitLab or rewriting the pipeline.
- Touching application code, schemas, or runtime configuration.

## Decisions

### Decision 1: Use `./gradlew wrapper --gradle-version 9.5 --distribution-type bin` (two-pass)
Run the wrapper task twice, as recommended in the official Gradle upgrade guide:
1. First pass: `./gradlew wrapper --gradle-version 9.5 --distribution-type bin` — updates `gradle-wrapper.properties` `distributionUrl`. Gradle 8.14.4 still drives this run.
2. Second pass: `./gradlew wrapper --gradle-version 9.5 --distribution-type bin` — now Gradle 9.5 drives, refreshing `gradle-wrapper.jar`, `gradlew`, and `gradlew.bat` to the 9.5 templates. This pass guarantees the wrapper scripts match the new distribution.

**Alternatives considered:** editing `distributionUrl` by hand and committing — rejected because it leaves the `gradle-wrapper.jar` / `gradlew` scripts at the old version, which mismatches Gradle's own contract and produces warnings.

### Decision 2: Keep `bin` distribution, not `all`
We keep the `-bin` distribution to minimize wrapper download size. The `-all` distribution bundles sources + docs which IDEs can auto-fetch on demand.

**Alternatives considered:** switching to `-all` for offline doc lookup — rejected as orthogonal to this change and a separate tradeoff.

### Decision 3: Fix deprecations with `--warning-mode all`; do NOT add `org.gradle.warning.mode=fail` yet
After bumping, run `./gradlew --warning-mode all clean build` and resolve every deprecation locally. We do **not** set `org.gradle.warning.mode=fail` in `gradle.properties` as part of this change — that would turn future transitive plugin deprecations into hard build failures and is a separate policy decision.

**Alternatives considered:** ignoring deprecations and only fixing breakages — rejected; deprecations now become removals in Gradle 10, so silently ignoring them just defers the same work under worse time pressure.

### Decision 4: Plugin versions stay pinned; no preemptive bumps
We do not bump `io.freefair.lombok` (9.2.0), `org.springframework.boot` (3.5.14), or Checkstyle tool version (10.26.1) unless Gradle 9.5 refuses to apply them. Vendor docs confirm each supports Gradle 9.x at our pinned version.

**Alternatives considered:** bump everything at once — rejected as it conflates two changes and makes blame-bisecting failures harder. Plugin bumps deserve their own change.

### Decision 5: Re-generate `src/main/java-generated/` and assert a zero-diff
After the upgrade, run `./gradlew generateJooq` and `git diff src/main/java-generated/`. The diff MUST be empty. This is the strongest signal that nothing in the codegen pipeline (Zonky + Flyway + jOOQ) regressed under Gradle 9.5.

**Alternatives considered:** trust unit tests — rejected; `generateJooq` runs in a `buildscript` classpath that ordinary tests do not exercise, so changes there would slip past `./gradlew test`.

### Decision 6: Single PR, single commit on `feat/gradle-9.x` (already created)
The user has already created branch `feat/gradle-9.x` in a sibling worktree (`ai-dial-admin-evaluation-framework-backend-gradle-9.x`). All work lands on that branch; we open one PR against `development` when complete.

## Risks / Trade-offs

| Risk | Mitigation |
|---|---|
| **Plugin incompatibility surfaces only at task execution, not apply** | Run a full `./gradlew clean build generateJooq` end-to-end before declaring the upgrade green; do not rely on `./gradlew help` or `./gradlew tasks` succeeding. |
| **`buildscript` classpath conflict under Gradle 9's stricter resolution** (Zonky's transitives clash with Flyway/jOOQ) | If a `Duplicate class … on the classpath` failure appears, add `resolutionStrategy.force` inside the `buildscript { configurations.classpath { … } }` block; do NOT loosen by switching to `--rerun-tasks`. |
| **`-Werror` on Java compile masks legitimate Gradle 9 generated-source warnings** | Resolve any new warnings (not just suppress) because they typically indicate a real ABI change in a transitive dependency. |
| **GitLab runner image still ships JDK 17 or older** | The wrapper is JDK-agnostic for download; Gradle 9.5 itself requires JDK 17+ to *run* and supports building toolchain-21 sources. Confirm `.gitlab-ci.yml` uses an image with JDK 17+; bump if needed. Local devs already run JDK 21 per `java.toolchain`. |
| **Docker base image bundles a pinned Gradle distribution** | Verify `Dockerfile` builds from the wrapper (not a system `gradle` install). If the image uses `gradle:8` upstream, swap to `gradle:9.5` or to a `eclipse-temurin:21-jdk` base that invokes `./gradlew`. |
| **Rollback** | One-line revert of `gradle-wrapper.properties` `distributionUrl`. Re-run `./gradlew wrapper --gradle-version 8.14.4` to restore wrapper jar/scripts. |

## Migration Plan

1. Run `./gradlew wrapper --gradle-version 9.5 --distribution-type bin` twice (see Decision 1).
2. Run `./gradlew --warning-mode all clean build` — capture all deprecation warnings.
3. For each warning, locate the source line in `build.gradle` and fix using the migration recipe from the [Gradle 9.x upgrade guide](https://docs.gradle.org/9.5/userguide/upgrading_major_version_9.html). Common fixes:
   - Replace any `project.convention(...)` calls with the equivalent extension API.
   - Replace `JavaPluginConvention` references with `JavaPluginExtension`.
   - Switch any eager `tasks.foo { … }` to `tasks.named('foo') { … }` if a warning flags it.
4. Run `./gradlew generateJooq` and confirm `git diff src/main/java-generated/` is empty.
5. Run `./gradlew checkstyleMain checkstyleTest test` and confirm green.
6. Update `AGENTS.md` Quick Reference table (`Build | Gradle 9.5`).
7. Update `openspec/config.yaml` line 10 (`- Build: Gradle 9.5`).
8. Check `.gitlab-ci.yml` for any explicit Gradle image pin; update if present.
9. Check `Dockerfile` — if it bundles a Gradle distribution rather than using the wrapper, swap accordingly.
10. Commit; open PR against `development`.

**Rollback**: `git revert` the wrapper commit; `./gradlew` will re-download 8.14.4 on next invocation.

## Open Questions

- **Does the GitLab runner image used by `.gitlab-ci.yml` ship JDK 17+?** If it currently runs JDK 11 or 17, Gradle 9.5 needs JDK 17 minimum to bootstrap. Verify before merging.
- **Are there developer machines locked to JDK 11?** The project already requires JDK 21 via toolchain, so this is unlikely, but worth flagging in the PR description.
