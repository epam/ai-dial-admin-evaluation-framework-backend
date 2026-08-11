## REMOVED Requirements

### Requirement: Automated GHCR Publishing
**Reason**: This monorepo's shared release/CI tooling assumes one Docker image per repository; adding a second automatically-published image (eval-cli) alongside the main app's image caused release-pipeline issues. Auto-publishing was dropped in favor of consumers building the image themselves from a pinned repo ref.
**Migration**: Consumers that were pulling `ghcr.io/epam/eval-cli` must instead clone this repository at a pinned tag/commit and run `docker build -f eval-cli/Dockerfile -t eval-cli:<ref> .` from the repo root as part of their own pipeline.

### Requirement: Isolation from Main App Deploy
**Reason**: This requirement existed solely to prevent an automated eval-cli publish from spuriously triggering `deploy-development.yml`. With automated publishing removed entirely (see "Automated GHCR Publishing" above), there is no publish event to isolate against, so the requirement no longer applies. `deploy-development.yml` has been reverted to its original, pre-eval-cli-distribution trigger condition.
**Migration**: None needed — `deploy-development.yml`'s behavior is simply restored to what it was before this capability existed.

## ADDED Requirements

### Requirement: No Automated GHCR Publishing
The system SHALL NOT automatically publish the eval-cli Docker image to any container registry. Consumers (e.g. other CI pipelines) SHALL obtain the image by cloning this repository at a pinned ref and building it locally via `eval-cli/Dockerfile`.

#### Scenario: No publish workflow exists
- **WHEN** a commit touching `eval-cli/**` is pushed to any branch
- **THEN** no GitHub Actions workflow builds or pushes an eval-cli image to a container registry as a result

#### Scenario: Consumer builds from a pinned ref
- **WHEN** a CI pipeline outside this repo needs to run eval-cli
- **THEN** it clones this repository at a specific tag/commit and runs `docker build -f eval-cli/Dockerfile -t eval-cli:<ref> .` itself, rather than referencing a pre-published image
