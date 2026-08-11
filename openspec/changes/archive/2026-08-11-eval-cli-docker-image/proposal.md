## Why

`eval-cli` is a standalone Spring Boot picocli CLI (subproject `eval-cli/`) that runs the clone → fetch → run → import cross-environment evaluation flow. It already builds an executable `bootJar`, and its own README states its "primary deployment context" is a CI job — but consumers need a JDK 25 on the runner and must manually assemble/run the jar. A Docker image removes that JDK dependency.

An earlier iteration of this change also auto-published the image to GHCR via a dedicated workflow. That was reverted: this repository's shared release/CI tooling assumes one Docker image per repository, and adding a second automatically-published image alongside the main app's caused release-pipeline problems. Rather than work around that, this change keeps only the Docker image build recipe itself — consumers clone this repo at a pinned ref and build the image themselves.

## What Changes

- Add `eval-cli/Dockerfile`: multi-stage build (Gradle build stage targeting `:eval-cli:bootJar`, amazoncorretto:25-alpine runtime stage), producing a runnable `eval-cli.jar` image. Build context is the repo root (needs `evaluation-runner-core/` alongside `eval-cli/`), mirroring the root `Dockerfile`'s pattern.
- Add `eval-cli/docker-entrypoint.sh`: forwards CLI args (`"$@"`) to `java -jar eval-cli.jar`, unlike the main app's entrypoint (which has no CLI args to forward).
- Update `eval-cli/README.md` with a `docker build`/`docker run` usage example (build-it-yourself, not pull-from-registry) alongside the existing `java -jar` quick start.
- **Removed** (from the earlier iteration, now reverted): `.github/workflows/eval-cli-release.yml` (the GHCR publish workflow), the `packages:` addition to `cleanup-untagged-images.yml`, and the `package.name` filter added to `deploy-development.yml`. All three files are back to their pre-eval-cli-distribution state; the main app's release/deploy pipeline is entirely unaffected by this change.

## Capabilities

### New Capabilities
- `eval-cli-distribution`: packaging `eval-cli` as a locally buildable Docker image (Dockerfile, entrypoint) with no automated registry publishing.

### Modified Capabilities
_None — no existing spec's requirements change. The main app's release/deploy behavior is untouched._

## Impact

- **New files**: `eval-cli/Dockerfile`, `eval-cli/docker-entrypoint.sh`.
- **Modified files**: `eval-cli/README.md` (Docker build/run usage section).
- **No DB schema changes. No application configuration property changes** (existing `eval.*`/`cli.*`/`dial.components.core.*` env vars are read as-is inside the container).
- **No new external dependency**: no registry package, no new GitHub Actions.
- **Not touched**: `release.yml`, `deploy-development.yml`, `cleanup-untagged-images.yml`, root `Dockerfile`, root `docker-entrypoint.sh` — all confirmed unmodified (or reverted back to their original state).
