## Why

`eval-cli` is a standalone Spring Boot picocli CLI (subproject `eval-cli/`) that runs the clone → fetch → run → import cross-environment evaluation flow. It already builds an executable `bootJar`, and its own README states its "primary deployment context" is a CI job — but today there is no Docker image and no CI workflow publishing one. Consumers must have a JDK 25 on the runner and manually assemble/run the jar. Packaging it as a container image, published automatically to GHCR, makes it a drop-in step (`docker run ghcr.io/.../eval-cli:<tag> evaluate ...`) for any GitHub Actions pipeline, matching how the main app is already distributed.

## What Changes

- Add `eval-cli/Dockerfile`: multi-stage build (Gradle build stage targeting `:eval-cli:bootJar`, amazoncorretto:25-alpine runtime stage), producing a runnable `eval-cli.jar` image. Build context is the repo root (needs `evaluation-runner-core/` alongside `eval-cli/`), mirroring the root `Dockerfile`'s pattern.
- Add `eval-cli/docker-entrypoint.sh`: forwards CLI args (`"$@"`) to `java -jar eval-cli.jar`, unlike the main app's entrypoint (which has no CLI args to forward).
- Add `.github/workflows/eval-cli-release.yml`: a new, standalone workflow (independent of the shared `epam/ai-dial-ci` reusable pipeline used by `release.yml`) that builds and pushes the `eval-cli` image to GHCR using `docker/build-push-action`, triggered on push to `development`/`release-*` (path-filtered to `eval-cli/**`, `evaluation-runner-core/**`, and root Gradle files) plus `workflow_dispatch`.
- Update `eval-cli/README.md` with a `docker run` usage example alongside the existing `java -jar` quick start.
- **Isolation constraint (critical)**: `deploy-development.yml`'s `registry_package` trigger originally checked only `container_metadata.tag.name == 'development'`, with no check on which package published — a bare `development` tag on the eval-cli image would have spuriously triggered a main-app deploy. Rather than work around this with a tag-naming convention on the publishing side (fragile — the guarantee would live entirely in a different workflow file than the one it protects), `deploy-development.yml`'s condition was updated to also require `github.event.registry_package.package.name == 'ai-dial-admin-evaluation-framework-backend'`. This makes the isolation self-contained in the workflow that actually deploys, and lets the eval-cli image use plain, main-app-consistent tags (`development`, `sha-<short>`) with no special-casing. This is a small, additive, backward-compatible change to `deploy-development.yml`'s trigger condition only — no other change to `release.yml`, the root `Dockerfile`, or `docker-entrypoint.sh`.

## Capabilities

### New Capabilities
- `eval-cli-distribution`: packaging and CI publishing of `eval-cli` as a Docker image (Dockerfile, entrypoint, GHCR publish workflow, tagging scheme, and the isolation guarantee that publishing it cannot trigger the main app's deploy pipeline).

### Modified Capabilities
_None — no existing spec's requirements change. The main app's release/deploy behavior is explicitly unmodified; this only adds a new, independent artifact and pipeline._

## Impact

- **New files**: `eval-cli/Dockerfile`, `eval-cli/docker-entrypoint.sh`, `.github/workflows/eval-cli-release.yml`.
- **Modified files**: `eval-cli/README.md` (add Docker usage section).
- **No DB schema changes.**
- **No application configuration property changes** (existing `eval.*`/`cli.*`/`dial.components.core.*` env vars are read as-is inside the container; no new properties introduced).
- **New external dependency**: GHCR package `ghcr.io/epam/eval-cli`, plus standard `docker/login-action`, `docker/metadata-action`, `docker/build-push-action` GitHub Actions (pinned to commit SHAs per this repo's existing convention).
- **Not touched**: `release.yml`, root `Dockerfile`, root `docker-entrypoint.sh`.
- **Touched, additively only**: `cleanup-untagged-images.yml` (explicit `packages:` list added, since the cleanup action's default scope doesn't cover the new package) and `deploy-development.yml` (a defense-in-depth `package.name` filter added to its `registry_package` trigger condition, so it can never fire off an eval-cli publish regardless of that publish's tag — see design.md).
- **Risk (unverified)**: `ghcr.io/epam/eval-cli` is a new top-level org package (not nested under this repo's namespace); whether the default `GITHUB_TOKEN` can create/push to it on the first real CI run is not verified from this repo — only local `docker build`/`docker run` smoke tests have been performed. See tasks.md's Post-merge verification section and design.md's Open Questions.
