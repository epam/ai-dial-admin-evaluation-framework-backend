## Context

`eval-cli` (`eval-cli/`) is a DB-free Spring Boot picocli CLI producing an executable `bootJar` (`mainClass com.epam.aidial.evaluation.cli.EvalCliApplication`). Its README documents `java -jar eval-cli.jar ...` usage and states a CI job is its primary deployment context.

An earlier iteration of this design also built and auto-published a Docker image to GHCR (`ghcr.io/epam/eval-cli`) via a standalone `.github/workflows/eval-cli-release.yml`, with an accompanying `package.name` filter added to `deploy-development.yml` to prevent that publish from cross-triggering the main app's deploy. That entire publishing path has been reverted: this repository's DevOps-owned release/CI tooling assumes one Docker image per repository, and adding a second automatically-published image caused problems with that shared release pipeline. Rather than investigate/fix the shared tooling (out of scope for this change, and not fully understood at the time), the simpler and safer choice was to drop automated publishing entirely and keep only the Docker image *build recipe*.

## Goals / Non-Goals

**Goals:**
- Produce a runnable `eval-cli` Docker image from the existing `bootJar`, buildable locally and by any external CI pipeline that clones this repo.
- Leave the main app's release/CI/deploy pipeline (`release.yml`, `deploy-development.yml`, `cleanup-untagged-images.yml`, root `Dockerfile`/`docker-entrypoint.sh`) completely untouched.
- Document the "clone and build" usage pattern in `eval-cli/README.md`.

**Non-Goals:**
- No automated publishing of the eval-cli image to any container registry. This was attempted in an earlier iteration and reverted — see Context above.
- No deploy target/pipeline for `eval-cli` itself — it has no server to deploy; it's built and invoked per-CI-job by consumers.
- No GraalVM native-image build — staying with the JVM `bootJar` + JRE base image, consistent with the main app.
- No extraction of `eval-cli`/`evaluation-runner-core` into a separate repository (a possible future fix for the "one image per repo" constraint) — considered, but a larger change than this one; noted as an Open Question below.

## Decisions

**1. Standalone `eval-cli/Dockerfile`, multi-stage, mirroring the root `Dockerfile`'s pattern but targeting `:eval-cli:bootJar`.**
Build context is the repo root (not `eval-cli/`), because `settings.gradle` declares `eval-cli` and `evaluation-runner-core` as sibling subprojects of the root build — the build stage needs `build.gradle`, `settings.gradle`, `gradle.properties`, `lombok.config`, `src/`, `evaluation-runner-core/`, and `eval-cli/` all present, exactly as the root `Dockerfile` already does for its own `:bootJar` target.
No `EXPOSE`/`HEALTHCHECK` — unlike the main app, this is a one-shot CLI (`spring.main.web-application-type=none`, actuator disabled), not a long-running server.

**2. Jar renamed to `eval-cli.jar` inside the image (not `app.jar`).**
Clarity when inspecting the running container or its filesystem — distinguishes it from the main app's `app.jar`.

**3. Dedicated `eval-cli/docker-entrypoint.sh` with `"$@"` argument passthrough.**
The main app's `docker-entrypoint.sh` hardcodes `exec java $DEBUG_OPTS $JAVA_OPTS -jar app.jar` with no trailing args — correct for a server with no CLI arguments. `eval-cli` is invoked with picocli subcommands and flags (`evaluate --suites ... --clone-suffix ... --deployment-id ...`), so the entrypoint must forward `docker run`'s trailing arguments: `exec java $DEBUG_OPTS $JAVA_OPTS -jar eval-cli.jar "$@"`.

**4. No CI publish workflow — consumers clone and build. (Reverted from an earlier design.)**
An earlier iteration added a standalone `.github/workflows/eval-cli-release.yml` (using `docker/login-action`/`docker/metadata-action`/`docker/build-push-action` against GHCR), plus a `package.name` filter on `deploy-development.yml`'s `registry_package` condition to prevent that publish from cross-triggering the main app's deploy, plus a `packages:` addition to `cleanup-untagged-images.yml`. All three were reverted:
- The publish workflow itself is deleted — this repo's shared release/CI tooling assumes one Docker image per repository, and running a second, independent GHCR publish caused issues with that tooling (reported by DevOps; exact mechanism not fully diagnosed at reversion time — see Open Questions).
- The `package.name` filter on `deploy-development.yml` existed solely to protect against that publish workflow's `registry_package` events; with no publish workflow, there's nothing to protect against, so it was reverted to its original condition rather than left as unexercised dead configuration.
- The `packages:` list on `cleanup-untagged-images.yml` existed to sweep the `eval-cli` GHCR package's untagged layers; with no package ever published, leaving that entry in place risked the nightly cleanup job failing on a non-existent package, so it was reverted too.

Consumers instead clone this repository at a pinned git ref (tag or commit — not floating branch HEAD, to keep builds reproducible) and run `docker build -f eval-cli/Dockerfile -t eval-cli:<ref> .` themselves as part of their own CI pipeline.

## Risks / Trade-offs

- **[Risk]** Every consuming CI pipeline repeats the full Gradle build (~90s observed locally, uncached) on every invocation, rather than pulling a pre-built layer-cached image → **Mitigation:** none applied in this change; if this becomes a bottleneck, a shared Gradle/Docker layer cache in the consuming pipeline, or revisiting the repo-extraction option below, would be the next step.
- **[Risk]** Consumers need `git` clone access to this repository (credentials/deploy keys if private) and a Docker daemon in their own CI environment — a heavier dependency than "pull an image with a registry token" → **Mitigation:** none needed beyond documenting the requirement in `eval-cli/README.md`.
- **[Trade-off]** No registry-hosted "latest known-good" image means consumers must actively track/pin a specific ref themselves; there's no equivalent of `:development` always pointing at the newest build → accepted, since automated publishing was the source of the underlying problem being avoided.

## Migration Plan

No migration in the data/schema sense. Rollout: merge `eval-cli/Dockerfile`, `eval-cli/docker-entrypoint.sh`, and the README update to `development`. No CI/CD changes ship with this — `release.yml`, `deploy-development.yml`, and `cleanup-untagged-images.yml` are confirmed back to their original, pre-eval-cli-distribution content. Rollback is trivial: remove the two new files; nothing else in the repo depends on them.

## Open Questions

- What exactly did the shared release/CI tooling do when a second Docker image was published from this monorepo? The reported symptom ("something wrong with releases") was not fully diagnosed before reverting — worth following up with DevOps if automated publishing is revisited later, since the fix may be narrower than "never publish a second image" (e.g. a semantic-release/changelog tool's assumptions, vs. a GHCR-linkage issue, vs. a security-scanning policy).
- Should `eval-cli` (and `evaluation-runner-core`) be extracted into their own repository in the future? That would give eval-cli its own single-image-per-repo release pipeline, matching what the shared tooling expects, at the cost of needing `evaluation-runner-core` to become a versioned, published artifact instead of a sibling Gradle subproject. Deferred — not attempted in this change.
