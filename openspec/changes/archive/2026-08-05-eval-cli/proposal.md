## Why

Today the EF backend can only run a suite's test cases against a deployment reachable from wherever that EF instance itself is deployed. Teams that want to evaluate an application deployed to a **different** environment (no EF instance running alongside it) currently have no way to reuse the standard test suites and Phase 1 execution engine — they would have to stand up a second EF instance next to the target application just to run an evaluation. A standalone CLI tool that reuses the existing `evaluation-runner-core` execution engine closes this gap without requiring a second full EF deployment.

## What Changes

- Add a new Gradle subproject, `eval-cli`, a standalone Spring Boot CLI (picocli-based, root package `com.epam.aidial.evaluation.cli`) that:
  - **Clones** configured "standard" test suites (referenced by UUID) from a **source** EF instance into `<prefix>_<name>` copies, reusing an existing clone as the destination if one is already present (no re-clone).
  - **Fetches** each suite's configuration and test cases from the source EF over its existing REST API.
  - **Runs** each test case against a CLI-configured deployment on a **target** environment, using `evaluation-runner-core`'s existing concurrent batch execution path (`TestCaseRunnerFactory` / `TestCaseRunner.submit`/`awaitCompletion`) — the same dispatch logic the EF backend itself uses for DB-backed runs — writing results to CSV via a new `ResultBatchWriter` implementation.
  - **Imports** the resulting CSV into the cloned suite on the source EF via the existing `POST /api/v1/test-suites/{id}/runs/import` endpoint, which already triggers metric computation automatically after import.
- Supplies its own target-environment DIAL Core `RestClient` bean (`"dialCoreTryOutRestClient"`) and a thin source-EF HTTP client, both scoped to the CLI's own Spring context — no changes to the existing EF backend or `evaluation-runner-core` production code are required; the CLI is purely a new consumer of the already-exported `evaluation-runner-core` module and the EF backend's already-existing public REST API.
- Authentication for both the source EF and the target environment is via **static, pre-obtained bearer tokens** supplied through CLI configuration — explicitly a temporary simplification; OIDC client-credentials support is a known follow-up, deliberately out of scope for this change.

## Capabilities

### New Capabilities
- `eval-cli`: standalone CLI tool (new `eval-cli` Gradle subproject) that clones standard test suites from a source EF instance, fetches their config/test cases, executes them against a target deployment via `evaluation-runner-core`, and imports the results back into the source EF via its runs-import API — enabling cross-environment evaluation without a second EF deployment.

### Modified Capabilities
(none — this change is purely additive; it consumes `evaluation-runner-core-module`, `test-suite-clone`, `test-cases`, and `eval-results-import` capabilities as-is, without changing their requirements)

## Impact

- **New code**: entirely new `eval-cli` Gradle subproject — no modifications to the existing root application or to `evaluation-runner-core`'s production code.
- **Dependencies added** (to the new module only): `info.picocli:picocli` + `picocli-spring-boot-starter`, `org.apache.commons:commons-csv`.
- **Build**: `settings.gradle` gains `include 'eval-cli'`; new module produces its own executable `bootJar`.
- **Config**: new `eval-cli`-scoped `@ConfigurationProperties` (`cli.source.*`, `cli.target.*`, `cli.*`, reusing `dial.components.core.*` from `evaluation-runner-core` for the target DIAL Core host) — documented in a new `eval-cli/README.md` config table, not `docs/configuration.md` (that doc is scoped to the EF backend proper).
- **No DB schema changes** — the module is DB-free by design, consistent with `evaluation-runner-core`.
- **No changes to existing REST API contracts** — the CLI is purely a client of the already-existing `test-suite-clone`, `test-cases`, and `test-suite-runs` (`runs/import`) endpoints.
