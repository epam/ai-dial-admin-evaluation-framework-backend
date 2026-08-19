# eval-cli

## Purpose

This spec defines the `eval-cli` standalone CLI: a picocli-based Spring Boot application that consumes `evaluation-runner-core` and the EF backend's public REST API to enable cross-environment evaluation (clone → fetch → run → import) of test suites between a source EF instance and a target deployment environment.

Status: **Implemented**

## Requirements

### Requirement: Suite Selection by Explicit ID
The CLI SHALL operate only on source EF test suites whose UUIDs are explicitly given via the required `--suites` command-line option (no configuration or environment-variable fallback); it SHALL NOT discover suites via a dynamic filter/search query.

#### Scenario: Given suite ID is processed
- **WHEN** a command is invoked with `--suites <uuid>`
- **THEN** the `clone`, `fetch`, `run`, `import`, and `evaluate` commands process that suite

#### Scenario: Unlisted suite is ignored
- **WHEN** a suite exists on the source EF but its UUID is not passed to `--suites`
- **THEN** the CLI does not clone, fetch, run, or import anything for that suite

### Requirement: Idempotent Suite Cloning
For each selected source suite, the CLI SHALL ensure a clone named `<sourceSuiteName>_<suffix>` exists on the source EF, where `<suffix>` is the value of the required `--clone-suffix` command-line option (no configuration or environment-variable fallback), creating the clone via the suite clone endpoint only if no suite with that exact name already exists; if one already exists, the CLI SHALL reuse it as the destination without creating a new clone.

#### Scenario: Clone does not yet exist
- **WHEN** the `clone` command runs for a selected source suite and no suite named `<sourceSuiteName>_<suffix>` exists on the source EF
- **THEN** the CLI calls the source EF's suite-clone endpoint to create it and uses the newly created suite's ID as the destination for later steps

#### Scenario: Clone already exists
- **WHEN** the `clone` command runs for a selected source suite and a suite named `<sourceSuiteName>_<suffix>` already exists on the source EF
- **THEN** the CLI does not call the suite-clone endpoint again and uses the existing suite's ID as the destination for later steps

### Requirement: Suite Configuration and Test Case Fetching
The CLI SHALL retrieve a suite's execution-relevant configuration (deployment/endpoint reference, request template, input bindings, response column definitions, additional requests in the request chain, MCP tool reference where applicable), the test-case schema of the suite's bound dataset (including each field's per-turn scope declaration), and its full set of test cases from the source EF, so the retrieved data is sufficient to build execution inputs — including per-turn execution decisions — without further source EF calls. The fetched data SHALL be persisted in a form that remains loadable when produced by an earlier CLI version that did not yet record the dataset schema.

#### Scenario: Fetch retrieves suite configuration and all test cases
- **WHEN** the `fetch` command runs for a suite with N test cases in its bound dataset
- **THEN** the CLI retrieves that suite's configuration and all N test cases (paginating through the source EF's test-case listing endpoint as needed) and makes them available to the `run` step

#### Scenario: Fetch also retrieves the bound dataset's test-case schema
- **WHEN** the `fetch` command runs for a suite bound to a dataset
- **THEN** the CLI retrieves that dataset's test-case schema and makes it available to the `run` step alongside the suite configuration and test cases

#### Scenario: Fetched schema preserves per-turn field scope
- **WHEN** the bound dataset declares one or more fields as per-turn
- **THEN** the fetched schema records that scope for each such field, so the `run` step can distinguish per-turn fields from shared fields

#### Scenario: A bundle produced without a dataset schema still loads
- **WHEN** the `run` command is given previously persisted fetch output that predates the dataset-schema field
- **THEN** the CLI loads it without error and treats the schema as absent, deferring to the multi-turn guard rather than failing to parse

### Requirement: Test Case Execution Against a Configured Target Deployment
The CLI SHALL execute each fetched test case against a deployment reference for the target environment, using the suite's request template and input bindings unchanged. The `--deployment-id` command-line option is optional: when supplied, it overrides the suite's own recorded deployment reference so the same request/input template is sent to the target instead of wherever the source suite originally pointed; when omitted, the CLI SHALL fall back to the suite's own recorded `deploymentRef` fetched from the source EF. If `--deployment-id` is omitted and the fetched suite has no recorded `deploymentRef` either, the CLI SHALL fail fast with a clear error before executing any test case.

#### Scenario: Target deployment override is applied
- **WHEN** the `run` command executes a test case with `--deployment-id` supplied, and the source suite's recorded deployment reference points at an environment other than the configured target
- **THEN** the request is sent to the CLI-configured target deployment reference, using the suite's request template and input bindings unchanged

#### Scenario: Falls back to the suite's own recorded deployment reference
- **WHEN** the `run` command executes without `--deployment-id`, and the fetched suite has a recorded `deploymentRef`
- **THEN** the request is sent to the suite's own recorded deployment reference, using the suite's request template and input bindings unchanged

#### Scenario: Fails fast when no deployment reference is available
- **WHEN** the `run` command executes without `--deployment-id`, and the fetched suite has no recorded `deploymentRef`
- **THEN** the CLI SHALL raise an error identifying the suite before any test case is executed, rather than failing per test case

#### Scenario: Execution result captured for every test case
- **WHEN** the `run` command completes for a suite with N fetched test cases
- **THEN** exactly one execution result is produced for each `(request, turn)` position actually executed; the chain and each request's turn loop are fail-fast, so a failed or timed-out call produces one error result for its own position and no results for later turns or later requests — every test case yields at least one result

### Requirement: Multi-Turn Execution Activated From the Fetched Dataset Schema
The CLI SHALL use the fetched dataset's test-case schema — specifically each field's per-turn scope declaration — to decide, per request in the suite's request chain, whether that request is executed once per turn or once per test case. A test case carrying per-turn data SHALL therefore be executed turn-by-turn by the CLI exactly as the EF backend executes it, with the same accumulated-frame and fail-fast semantics.

#### Scenario: Multi-turn case is executed once per turn
- **WHEN** the `run` command executes a test case carrying N turns of per-turn data for a request whose input bindings reference at least one per-turn field
- **THEN** the CLI sends N requests for that test case, in turn order, each carrying that turn's data merged over the case's shared data

#### Scenario: Multi-turn case with no per-turn binding is executed once
- **WHEN** the `run` command executes a test case carrying per-turn data for a request whose input bindings reference no per-turn field
- **THEN** the CLI sends exactly one request for that test case using the case's shared data only

#### Scenario: Turn count is decided per request in the chain
- **WHEN** a suite's request chain mixes requests that bind per-turn fields with requests that do not
- **THEN** each request's turn count is decided from its own input bindings, so some chain positions may execute per turn while others execute once

#### Scenario: Missing schema does not silently downgrade multi-turn execution
- **WHEN** the `run` command is invoked with fetched data that carries no dataset schema (for example a bundle produced before the schema was fetched) while some test case carries per-turn data
- **THEN** the CLI fails with a clear error instructing the user to re-run `fetch`, rather than silently executing every case as a single turn

### Requirement: MCP Suites Reject Multi-Turn Test Cases Pre-Flight
The CLI SHALL fail fast, before invoking the target deployment, when a suite to be run is an MCP tool suite and any of its fetched test cases carries per-turn data. This mirrors the EF backend's own run-creation guard, so the CLI rejects the same suite/dataset combination the backend rejects instead of producing results the backend would never have produced.

#### Scenario: MCP suite with a multi-turn case is rejected before execution
- **WHEN** the `run` (or `evaluate`) command targets an MCP tool suite and at least one fetched test case carries per-turn data
- **THEN** the CLI aborts with a non-zero exit code and an error naming the unsupported combination, and invokes no target deployment and writes no results CSV

#### Scenario: MCP suite without multi-turn cases runs normally
- **WHEN** the `run` command targets an MCP tool suite whose fetched test cases all carry only shared data
- **THEN** the CLI executes the suite normally, with no guard rejection

### Requirement: CSV Result Export Contract
The CLI SHALL write test case execution results to a CSV file whose header and column order match a column set the source EF's run-results import contract accepts: `testCaseName, runIndex, testCaseData, requestBody, responseBody, responseStatusCode, executionStatus, startedAt, completedAt, traceId, retryCount, logDetails, extractedColumns, extractionWarnings, requestIndex, totalRequests, turnIndex, totalTurns`. `testCaseId` is deliberately omitted (source-side test case IDs may not correspond to anything in the destination clone's dataset; the import contract derives one stable identifier per distinct `testCaseName` in the file, so all rows of a test case stay grouped); `testCaseData`/`extractedColumns`/`extractionWarnings` are included because the import contract requires the first and never re-derives the latter two server-side for an imported run; `requestIndex`/`totalRequests`/`turnIndex`/`totalTurns` are included because they are what distinguishes the several rows a single test-case repetition produces for a multi-request or multi-turn suite — without them every imported row is persisted as a single-request, single-turn row.

#### Scenario: CSV header matches the import contract
- **WHEN** the `run` command finishes writing results
- **THEN** the produced CSV file's header row is exactly `testCaseName,runIndex,testCaseData,requestBody,responseBody,responseStatusCode,executionStatus,startedAt,completedAt,traceId,retryCount,logDetails,extractedColumns,extractionWarnings,requestIndex,totalRequests,turnIndex,totalTurns` in that order

#### Scenario: Row identity is written for every result
- **WHEN** the `run` command writes a result produced for a given chain position and turn
- **THEN** that row's `requestIndex`/`totalRequests`/`turnIndex`/`totalTurns` cells carry that result's own position values, and a single-request single-turn result is written as `0,1,0,1`

#### Scenario: A multi-request multi-turn run imports with its identity intact
- **WHEN** a produced CSV containing several rows per test case — differing in chain position or turn — is imported into the destination suite
- **THEN** the import succeeds and each persisted row carries its own chain and turn position under an identifier shared with the other rows of the same test case

#### Scenario: Concurrent result writes do not corrupt the file
- **WHEN** results for multiple test cases are produced concurrently during a `run` invocation
- **THEN** the resulting CSV file contains one well-formed row per produced result, with no interleaved or truncated rows

### Requirement: Result Import into the Cloned Suite
The CLI SHALL import a produced results CSV into the destination suite (the clone resolved during the clone step) on the source EF via the source EF's existing run-results import endpoint, and SHALL NOT separately trigger metric computation, since that endpoint dispatches it automatically.

#### Scenario: Import targets the cloned suite, not the original
- **WHEN** the `import` command runs after `clone` resolved a destination clone ID different from the original source suite ID
- **THEN** the CSV is imported against the destination clone's ID

#### Scenario: No separate metric-computation trigger is issued
- **WHEN** the `import` command completes successfully
- **THEN** the CLI issues no additional request to trigger metric or score computation beyond the single import call

### Requirement: Independent and Chained CLI Commands
The CLI SHALL expose `clone`, `fetch`, `run`, and `import` as independently invocable commands, and SHALL also expose an `evaluate` command that performs all four in sequence for each configured suite.

#### Scenario: Standalone command re-run
- **WHEN** a user invokes only the `import` command against a previously produced results CSV, without first invoking `clone`/`fetch`/`run` in the same invocation
- **THEN** the CLI imports that CSV into the configured destination suite without requiring the other commands to have just run

#### Scenario: Evaluate runs all four steps per suite
- **WHEN** a user invokes the `evaluate` command with one or more configured suites
- **THEN** the CLI performs clone, fetch, run, and import for each configured suite in that order

### Requirement: Source and Target API-Key Authentication
The CLI SHALL authenticate its calls to the source EF and to the target environment's deployment invocation endpoint using API keys supplied via configuration — `eval.source.api-key` (env var `EVAL_SOURCE_API_KEY`) for the source EF and `dial.components.core.api-key` (env var `DIAL_CORE_API_KEY`) for the target — without performing any token acquisition or refresh flow itself. Both credentials SHALL be sourced from environment variables only — there SHALL be no CLI-flag or file-based delivery for either.

#### Scenario: Configured API key is sent to the source EF
- **WHEN** the CLI calls any source EF endpoint
- **THEN** the request includes an `Api-Key: <eval.source.api-key>` header

#### Scenario: Configured API key is sent to the target deployment
- **WHEN** the CLI invokes the configured target deployment during the `run` command
- **THEN** the invocation includes an `Api-Key: <dial.components.core.api-key>` header

## Implementation notes

The `eval-cli` Gradle subproject consumes `evaluation-runner-core`'s `TestCaseRunnerFactory`/`TestCaseRunner` batch execution path via `RunOrchestrationService`, with `CloneService`/`FetchService`/`ImportService` implementing the clone/fetch/import steps and `EvaluateCommand` chaining clone → fetch → run → import for each configured suite. `CsvResultBatchWriter` implements `evaluation-runner-core`'s `ResultBatchWriter`, writing the CSV export contract described above. Shared `evaluation-runner-core` DTOs (plus one deliberate local subset, `TestSuiteUpdateResultDto`, under `client/source/dto`) are used for the source EF's existing `test-suite-clone`, `test-cases`, and `test-suite-runs` (`runs/import`) REST endpoints. See `design.md` for the full technical approach.
