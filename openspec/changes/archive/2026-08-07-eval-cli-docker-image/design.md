## Context

`eval-cli` (`eval-cli/`) is a DB-free Spring Boot picocli CLI producing an executable `bootJar` (`mainClass com.epam.aidial.evaluation.cli.EvalCliApplication`). Its README already documents `java -jar eval-cli.jar ...` usage and states a CI job is its primary deployment context, but no Dockerfile or publish workflow exists.

The main app already has a working Docker + CI publish pattern to reuse as a template:
- Root `Dockerfile`: multi-stage (`gradle:9.6.0-jdk25-alpine` build → `amazoncorretto:25-alpine` runtime), non-root `appuser`, `docker-entrypoint.sh` wrapping `java -jar app.jar` (no arg passthrough — the main app takes none).
- `.github/workflows/release.yml`: on push to `development`/`release-*`, delegates entirely to the external reusable workflow `epam/ai-dial-ci/.github/workflows/java_release.yml@4.8.0` (not vendored in this repo — its support for building a second Dockerfile/image for a subproject can't be verified).
- `.github/workflows/deploy-development.yml`: triggers on the `registry_package` GitHub event when `github.event.registry_package.package_version.container_metadata.tag.name == 'development'` — **critically, this condition does not check which package/image was published**, only the tag string.
- `.github/workflows/cleanup-untagged-images.yml`: nightly `dataaxiom/ghcr-cleanup-action` sweep of untagged GHCR images, scoped by `packages: write` permission on the whole repo namespace (not per-package as far as can be confirmed without testing).

Because `java_release.yml`'s multi-module support is unverified and reusing it risks coupling eval-cli's publish cadence to internals we can't inspect, this design uses a **standalone workflow** with standard `docker/*` GitHub Actions, independent of `ai-dial-ci`.

## Goals / Non-Goals

**Goals:**
- Produce a runnable `eval-cli` Docker image from the existing `bootJar`, buildable locally and in CI.
- Publish that image automatically to GHCR on the same push triggers as the main app (`development`/`release-*`) plus manual dispatch.
- Guarantee the new publish workflow cannot cross-trigger `deploy-development.yml`'s main-app deploy.
- Document `docker run` usage in `eval-cli/README.md` alongside the existing `java -jar` instructions.

**Non-Goals:**
- No changes to `release.yml`, `deploy-development.yml`, the root `Dockerfile`, or `docker-entrypoint.sh` — the main app's build/publish/deploy pipeline is untouched.
- No deploy target/pipeline for `eval-cli` itself — only image publishing. It has no server to deploy; it's invoked per-CI-job by consumers.
- No GraalVM native-image build — staying with the JVM `bootJar` + JRE base image, consistent with the main app; native-image's build complexity and Spring AOT verification burden aren't justified here.
- No reuse of the `epam/ai-dial-ci` reusable workflow, since its multi-Dockerfile/subproject support is unverified from this repo.

## Decisions

**1. Standalone `eval-cli/Dockerfile`, multi-stage, mirroring the root `Dockerfile`'s pattern but targeting `:eval-cli:bootJar`.**
Build context is the repo root (not `eval-cli/`), because `settings.gradle` declares `eval-cli` and `evaluation-runner-core` as sibling subprojects of the root build — the build stage needs `build.gradle`, `settings.gradle`, `gradle.properties`, `lombok.config`, `src/`, `evaluation-runner-core/`, and `eval-cli/` all present, exactly as the root `Dockerfile` already does for its own `:bootJar` target. Alternative considered: a `eval-cli`-scoped build context with its own copied Gradle wrapper files — rejected, since it would require duplicating/faking the multi-module `settings.gradle` structure just to get Gradle to resolve, adding maintenance burden with no real benefit.
No `EXPOSE`/`HEALTHCHECK` — unlike the main app, this is a one-shot CLI (`spring.main.web-application-type=none`, actuator disabled), not a long-running server.

**2. Jar renamed to `eval-cli.jar` inside the image (not `app.jar`).**
User-requested naming for clarity when inspecting the running container or its filesystem — distinguishes it from the main app's `app.jar` if both images are ever compared/debugged side by side.

**3. Dedicated `eval-cli/docker-entrypoint.sh` with `"$@"` argument passthrough.**
The main app's `docker-entrypoint.sh` hardcodes `exec java $DEBUG_OPTS $JAVA_OPTS -jar app.jar` with no trailing args — correct for a server with no CLI arguments. `eval-cli` is invoked with picocli subcommands and flags (`evaluate --suites ... --clone-suffix ... --deployment-id ...`), so the entrypoint must forward `docker run`'s trailing arguments: `exec java $DEBUG_OPTS $JAVA_OPTS -jar eval-cli.jar "$@"`. This is the one behavioral difference from the main app's entrypoint; everything else (cert import hook, `DEBUG_OPTS`/`JAVA_OPTS` passthrough) is kept identical for consistency.

**4. New standalone `.github/workflows/eval-cli-release.yml` using `docker/login-action` + `docker/metadata-action` + `docker/build-push-action` directly against GHCR, rather than extending `release.yml` or reusing `ai-dial-ci`.**
Rationale: `java_release.yml`'s support for a second Dockerfile/subproject image can't be verified from this repo (external, unvendored workflow) — extending `release.yml` on an unverified assumption risks silently breaking the main app's release path if the reusable workflow doesn't support it the way expected. A standalone workflow is fully self-contained, uses no unverified external capability, and is trivially reviewable in this repo alone. Trade-off: some duplication of trigger/branch logic between `release.yml` and `eval-cli-release.yml` — accepted as the safer default.
`permissions: packages: write` + `secrets.GITHUB_TOKEN` is sufficient for GHCR push (no new secret needed), matching `cleanup-untagged-images.yml`'s existing use of the same permission.

**5. Triggers: push to `development`/`release-*` (path-filtered to `eval-cli/**`, `evaluation-runner-core/**`, and root Gradle files) + `workflow_dispatch`.**
Mirrors `release.yml`'s trigger branches so the CLI's release cadence stays in lockstep with the main app, while the `paths:` filter avoids rebuilding/republishing on changes that can't affect `eval-cli` (e.g. `src/` web-layer-only changes, docs).

**6. Isolation lives in `deploy-development.yml` itself, via a `package.name` filter — not in a tag-naming convention on the publishing side.**
`deploy-development.yml`'s original `registry_package` trigger condition was:
```yaml
github.event.registry_package.package_version.container_metadata.tag.name == 'development'
```
This checks only the tag string, not which package/image published it. An earlier iteration of this design worked around that by prefixing every eval-cli tag with `eval-cli-` (`eval-cli-development`, `eval-cli-sha-<short>`), making the tag string structurally unable to collide with `development`. That was rejected as the final approach: it puts the isolation guarantee in a *different* workflow file (`eval-cli-release.yml`) than the one it protects (`deploy-development.yml`) — a later edit to the tag scheme there could silently reopen the cross-trigger risk with nothing catching it until it actually fired, and it forced eval-cli's tags to diverge from the main app's plain branch-name convention for no benefit to the CLI itself.

Instead, `deploy-development.yml`'s own condition was updated to also require:
```yaml
github.event.registry_package.package.name == 'ai-dial-admin-evaluation-framework-backend'
```
This makes the guarantee self-contained in the workflow that actually deploys: it can never fire off *any* other package's publish, regardless of that package's tag. This is the one deliberate, additive edit to `deploy-development.yml` in this change — narrowly scoped to the `registry_package` branch of its `if:`, with no change to its `workflow_dispatch`/`workflow_run` branches or to what it deploys. With this in place, eval-cli's image uses plain, main-app-consistent tags: `development`, `release-<x>`, `sha-<short>` — no special prefix needed.

## Risks / Trade-offs

- **[Risk]** `eval-cli-release.yml` duplicates trigger/branch logic already present in `release.yml`, so the two can drift (e.g. someone updates `release.yml`'s branch list without updating this one) → **Mitigation:** keep the trigger block minimal and call out the parallel in both files' comments; acceptable given the low frequency of branch-strategy changes.
- **[Risk]** `cleanup-untagged-images.yml`'s scope (whether `dataaxiom/ghcr-cleanup-action` sweeps all packages under the repo owner or must be told about each package explicitly) is unverified → **Mitigation:** verify during implementation by checking the action's behavior/docs; if per-package scoping is required, add `eval-cli` explicitly rather than assuming.
- **[Risk]** Build context is the repo root, so any accidental `COPY` of unrelated files (or Docker layer cache invalidation from unrelated root-level changes) could slow down or destabilize the `eval-cli` image build → **Mitigation:** the `paths:` filter on the workflow limits *when* the job runs, but the Dockerfile's `COPY` list itself stays scoped to exactly the directories Gradle needs (`src/`, `evaluation-runner-core/`, `eval-cli/`, root Gradle files) — same discipline the root `Dockerfile` already follows.
- **[Trade-off]** Standalone workflow instead of reusing `ai-dial-ci` means eval-cli's pipeline won't automatically inherit future security/reliability improvements made centrally to `java_release.yml` → accepted, since correctness (not touching the unverified reusable workflow) is prioritized over pipeline consistency for this first iteration; revisit once `ai-dial-ci`'s multi-module support is confirmed.

## Migration Plan

No migration in the data/schema sense — this is additive infrastructure (new files, plus the small additive filter in `deploy-development.yml`). Rollout is: merge the new Dockerfile/entrypoint/workflow/README update and the `deploy-development.yml` filter to `development`, let `eval-cli-release.yml` run once, and verify the image appears in GHCR tagged `development`/`sha-<short>` with no corresponding `deploy-development.yml` run. Rollback is trivial: revert the added files and the one-line filter change; nothing else in the repo depends on the new image existing.

## Open Questions

- ~~Does `dataaxiom/ghcr-cleanup-action` need an explicit per-package configuration to also clean up `eval-cli`'s untagged layers?~~ **Resolved**: confirmed it defaults to only the package matching the repo name; `cleanup-untagged-images.yml` now lists both packages explicitly via `packages:`.
- Should a future iteration reconsider reusing `epam/ai-dial-ci`'s reusable workflow for `eval-cli` once its multi-Dockerfile/subproject capabilities are confirmed, to reduce trigger-logic duplication with `release.yml`? Deferred, not blocking this change.
- The image is published as `ghcr.io/epam/eval-cli` — a top-level org package, not nested under this repo's package namespace (a deliberate choice, since eval-cli is a candidate for future extraction into its own repo). Unlike a repo-matching package name, GHCR won't auto-link it to this repository, and pushing it depends on the `epam` org's package-creation policy permitting a new, unlinked package via the default `GITHUB_TOKEN`. Not verified from this repo; if the publish step fails with a permissions error, an org admin will need to confirm/adjust that policy, or a PAT with broader `packages:write` scope may be needed instead of `secrets.GITHUB_TOKEN`.
