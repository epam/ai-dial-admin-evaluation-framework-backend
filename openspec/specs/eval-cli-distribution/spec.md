# eval-cli-distribution

## Purpose

This spec defines the distribution of the `eval-cli` standalone CLI as a locally buildable Docker image: how the image is built, and how arguments are passed through to the underlying jar. The image is **not** published by CI — this monorepo's release tooling assumes one Docker image per repository, so consumers clone this repo at a pinned ref and build the image themselves rather than pulling from a registry.

Status: **Implemented**

## Requirements

### Requirement: Eval CLI Docker Image
The system SHALL provide a Docker image, built from `eval-cli/Dockerfile`, that packages the `eval-cli` executable jar (`eval-cli.jar`) and runs it without requiring a JDK on the host.

#### Scenario: Building the image locally
- **WHEN** `docker build -f eval-cli/Dockerfile -t eval-cli:local .` is run from the repository root
- **THEN** the build succeeds and produces an image containing a runnable `eval-cli.jar`

#### Scenario: Running a command without a JDK on the host
- **WHEN** `docker run --rm eval-cli:local --help` is run on a host with no JDK installed
- **THEN** the container starts, executes the CLI's `--help` output, and exits with code 0

### Requirement: CLI Argument Passthrough
The eval-cli Docker image's entrypoint SHALL forward all arguments passed to `docker run` to the underlying `java -jar eval-cli.jar` invocation, so picocli subcommands and flags work unmodified inside the container.

#### Scenario: Running a subcommand with flags
- **WHEN** the image is run as `docker run --rm -e EVAL_TOKEN=... -e DIAL_CORE_URL=... -e DIAL_CORE_API_KEY=... eval-cli:local evaluate --suites <uuid> --clone-suffix eval --deployment-id <id>`
- **THEN** the container invokes `java -jar eval-cli.jar evaluate --suites <uuid> --clone-suffix eval --deployment-id <id>` and the CLI's picocli exit code is propagated as the container's exit code

### Requirement: No Automated GHCR Publishing
The system SHALL NOT automatically publish the eval-cli Docker image to any container registry. Consumers (e.g. other CI pipelines) SHALL obtain the image by cloning this repository at a pinned ref and building it locally via `eval-cli/Dockerfile`.

#### Scenario: No publish workflow exists
- **WHEN** a commit touching `eval-cli/**` is pushed to any branch
- **THEN** no GitHub Actions workflow builds or pushes an eval-cli image to a container registry as a result

#### Scenario: Consumer builds from a pinned ref
- **WHEN** a CI pipeline outside this repo needs to run eval-cli
- **THEN** it clones this repository at a specific tag/commit and runs `docker build -f eval-cli/Dockerfile -t eval-cli:<ref> .` itself, rather than referencing a pre-published image

## Implementation notes

- `eval-cli/Dockerfile` — multi-stage build producing the `eval-cli.jar` runtime image.
- `eval-cli/docker-entrypoint.sh` — forwards `"$@"` to `java -jar eval-cli.jar`.
- No publish workflow, and no changes to `release.yml`, `deploy-development.yml`, or `cleanup-untagged-images.yml` — all reverted to their pre-eval-cli-distribution state.
