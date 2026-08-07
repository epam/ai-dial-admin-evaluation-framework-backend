## ADDED Requirements

### Requirement: Eval CLI Docker Image
The system SHALL provide a Docker image, built from `eval-cli/Dockerfile`, that packages the `eval-cli` executable jar (`eval-cli.jar`) and runs it without requiring a JDK on the host.

**Status**: Implemented

#### Scenario: Building the image locally
- **WHEN** `docker build -f eval-cli/Dockerfile -t eval-cli:local .` is run from the repository root
- **THEN** the build succeeds and produces an image containing a runnable `eval-cli.jar`

#### Scenario: Running a command without a JDK on the host
- **WHEN** `docker run --rm eval-cli:local --help` is run on a host with no JDK installed
- **THEN** the container starts, executes the CLI's `--help` output, and exits with code 0

### Requirement: CLI Argument Passthrough
The eval-cli Docker image's entrypoint SHALL forward all arguments passed to `docker run` to the underlying `java -jar eval-cli.jar` invocation, so picocli subcommands and flags work unmodified inside the container.

**Status**: Implemented

#### Scenario: Running a subcommand with flags
- **WHEN** the image is run as `docker run --rm -e EVAL_TOKEN=... -e DIAL_CORE_URL=... -e DIAL_CORE_API_KEY=... eval-cli:local evaluate --suites <uuid> --clone-suffix eval --deployment-id <id>`
- **THEN** the container invokes `java -jar eval-cli.jar evaluate --suites <uuid> --clone-suffix eval --deployment-id <id>` and the CLI's picocli exit code is propagated as the container's exit code

### Requirement: Automated GHCR Publishing
The system SHALL automatically build and publish the eval-cli Docker image to GHCR (`ghcr.io/epam/eval-cli`) via a dedicated GitHub Actions workflow, triggered on push to the `development`/`release-*` branches (when `eval-cli/**`, `evaluation-runner-core/**`, or root Gradle files change) and on manual `workflow_dispatch`.

**Status**: Implemented

#### Scenario: Push to development triggers a publish
- **WHEN** a commit touching `eval-cli/**` is pushed to the `development` branch
- **THEN** the eval-cli publish workflow runs, builds the image, and pushes it to the GHCR package `ghcr.io/epam/eval-cli`

#### Scenario: Unrelated push does not trigger a publish
- **WHEN** a commit touching only root application source (`src/**`) with no changes under `eval-cli/**`, `evaluation-runner-core/**`, or root Gradle files is pushed to `development`
- **THEN** the eval-cli publish workflow does not run

#### Scenario: Manual publish via workflow_dispatch
- **WHEN** the eval-cli publish workflow is triggered manually via `workflow_dispatch`
- **THEN** the workflow builds and pushes the current branch's eval-cli image to GHCR regardless of which paths most recently changed

### Requirement: Isolation from Main App Deploy
Publishing the eval-cli image SHALL NOT trigger `deploy-development.yml`'s main-app deploy. This SHALL be enforced by `deploy-development.yml`'s `registry_package` trigger condition checking `github.event.registry_package.package.name == 'ai-dial-admin-evaluation-framework-backend'` in addition to the tag name — not by any special-casing of the eval-cli image's own tags, which SHALL use the same plain branch-name/short-SHA scheme as the main app (`development`, `release-<x>`, `sha-<short>`).

**Status**: Implemented

#### Scenario: Development branch publish uses plain tags
- **WHEN** the eval-cli image is published from a push to `development`
- **THEN** the resulting image is tagged `development` (plus a `sha-<short>` tag), with no special prefix

#### Scenario: Publishing eval-cli does not trigger the main app's deploy workflow
- **WHEN** the eval-cli image is published to GHCR, including under a tag literally named `development`
- **THEN** `deploy-development.yml` does not run as a result of that publish, because its `registry_package` condition's `package.name` check does not match `ghcr.io/epam/eval-cli`

## Implementation notes

- `eval-cli/Dockerfile` — multi-stage build producing the `eval-cli.jar` runtime image.
- `eval-cli/docker-entrypoint.sh` — forwards `"$@"` to `java -jar eval-cli.jar`.
- `.github/workflows/eval-cli-release.yml` — standalone GHCR publish workflow (`docker/login-action` + `docker/metadata-action` + `docker/build-push-action`).
- `.github/workflows/deploy-development.yml` — `registry_package` trigger condition now also checks `github.event.registry_package.package.name == 'ai-dial-admin-evaluation-framework-backend'`, the isolation mechanism.
- `.github/workflows/cleanup-untagged-images.yml` — explicit `packages:` list covering both the main app and `eval-cli` GHCR packages.
