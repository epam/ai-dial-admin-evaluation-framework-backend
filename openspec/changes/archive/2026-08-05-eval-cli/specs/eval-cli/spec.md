## ADDED Requirements

### Requirement: Suite Selection by Explicit ID
The CLI SHALL operate only on source EF test suites whose UUIDs are explicitly given via the required `--suites` command-line option (no configuration or environment-variable fallback — consistent with `--deployment-id`); it SHALL NOT discover suites via a dynamic filter/search query. Status: Implemented.

#### Scenario: Given suite ID is processed
- **WHEN** a command is invoked with `--suites <uuid>`
- **THEN** the `clone`, `fetch`, `run`, `import`, and `evaluate` commands process that suite

#### Scenario: Unlisted suite is ignored
- **WHEN** a suite exists on the source EF but its UUID is not passed to `--suites`
- **THEN** the CLI does not clone, fetch, run, or import anything for that suite

### Requirement: Idempotent Suite Cloning
For each selected source suite, the CLI SHALL ensure a clone named `<sourceSuiteName>_<suffix>` exists on the source EF, where `<suffix>` is the value of the required `--clone-suffix` command-line option (no configuration or environment-variable fallback), creating the clone via the suite clone endpoint only if no suite with that exact name already exists; if one already exists, the CLI SHALL reuse it as the destination without creating a new clone. Status: Implemented.

#### Scenario: Clone does not yet exist
- **WHEN** the `clone` command runs for a selected source suite and no suite named `<sourceSuiteName>_<suffix>` exists on the source EF
- **THEN** the CLI calls the source EF's suite-clone endpoint to create it and uses the newly created suite's ID as the destination for later steps

#### Scenario: Clone already exists
- **WHEN** the `clone` command runs for a selected source suite and a suite named `<sourceSuiteName>_<suffix>` already exists on the source EF
- **THEN** the CLI does not call the suite-clone endpoint again and uses the existing suite's ID as the destination for later steps

### Requirement: Suite Configuration and Test Case Fetching
The CLI SHALL retrieve a suite's execution-relevant configuration (deployment/endpoint reference, request template, input bindings, response column definitions, MCP tool reference where applicable) and its full set of test cases from the source EF, so the retrieved data is sufficient to build execution inputs without further source EF calls. Status: Implemented.

#### Scenario: Fetch retrieves suite configuration and all test cases
- **WHEN** the `fetch` command runs for a suite with N test cases in its bound dataset
- **THEN** the CLI retrieves that suite's configuration and all N test cases (paginating through the source EF's test-case listing endpoint as needed) and makes them available to the `run` step

### Requirement: Test Case Execution Against a Configured Target Deployment
The CLI SHALL execute each fetched test case against a deployment reference configured for the target environment, overriding the suite's own recorded deployment/endpoint reference so the same request/input template is sent to the target instead of wherever the source suite originally pointed. Status: Implemented.

#### Scenario: Target deployment override is applied
- **WHEN** the `run` command executes a test case whose source suite recorded deployment reference points at an environment other than the configured target
- **THEN** the request is sent to the CLI-configured target deployment reference, using the suite's request template and input bindings unchanged

#### Scenario: Execution result captured for every test case
- **WHEN** the `run` command completes for a suite with N fetched test cases
- **THEN** exactly one execution result (or, for a multi-turn test case, one result per turn) is produced for each test case, including cases where the target deployment call failed or timed out

### Requirement: CSV Result Export Contract
The CLI SHALL write test case execution results to a CSV file whose header and column order match a column set the source EF's run-results import contract accepts: `testCaseName, runIndex, testCaseData, requestBody, responseBody, responseStatusCode, executionStatus, startedAt, completedAt, traceId, retryCount, logDetails, extractedColumns, extractionWarnings`. `testCaseId` is deliberately omitted (source-side test case IDs may not correspond to anything in the destination clone's dataset); `testCaseData`/`extractedColumns`/`extractionWarnings` are included because the import contract requires the first and never re-derives the latter two server-side for an imported run. Status: Implemented.

#### Scenario: CSV header matches the import contract
- **WHEN** the `run` command finishes writing results
- **THEN** the produced CSV file's header row is exactly `testCaseName,runIndex,testCaseData,requestBody,responseBody,responseStatusCode,executionStatus,startedAt,completedAt,traceId,retryCount,logDetails,extractedColumns,extractionWarnings` in that order

#### Scenario: Concurrent result writes do not corrupt the file
- **WHEN** results for multiple test cases are produced concurrently during a `run` invocation
- **THEN** the resulting CSV file contains one well-formed row per produced result, with no interleaved or truncated rows

### Requirement: Result Import into the Cloned Suite
The CLI SHALL import a produced results CSV into the destination suite (the clone resolved during the clone step) on the source EF via the source EF's existing run-results import endpoint, and SHALL NOT separately trigger metric computation, since that endpoint dispatches it automatically. Status: Implemented.

#### Scenario: Import targets the cloned suite, not the original
- **WHEN** the `import` command runs after `clone` resolved a destination clone ID different from the original source suite ID
- **THEN** the CSV is imported against the destination clone's ID

#### Scenario: No separate metric-computation trigger is issued
- **WHEN** the `import` command completes successfully
- **THEN** the CLI issues no additional request to trigger metric or score computation beyond the single import call

### Requirement: Independent and Chained CLI Commands
The CLI SHALL expose `clone`, `fetch`, `run`, and `import` as independently invocable commands, and SHALL also expose an `evaluate` command that performs all four in sequence for each configured suite. Status: Implemented.

#### Scenario: Standalone command re-run
- **WHEN** a user invokes only the `import` command against a previously produced results CSV, without first invoking `clone`/`fetch`/`run` in the same invocation
- **THEN** the CLI imports that CSV into the configured destination suite without requiring the other commands to have just run

#### Scenario: Evaluate runs all four steps per suite
- **WHEN** a user invokes the `evaluate` command with one or more configured suites
- **THEN** the CLI performs clone, fetch, run, and import for each configured suite in that order

### Requirement: Source Bearer Token / Target API-Key Authentication
The CLI SHALL authenticate its calls to the source EF using a bearer token supplied via configuration (`eval.source.token`, env var `EVAL_TOKEN`), and its calls to the target environment's deployment invocation endpoint using an API key supplied via configuration (`dial.components.core.api-key`, env var `DIAL_CORE_API_KEY`), without performing any token acquisition or refresh flow itself. Both credentials SHALL be sourced from environment variables only — there SHALL be no CLI-flag or file-based delivery for either. Status: Implemented.

#### Scenario: Configured token is sent to the source EF
- **WHEN** the CLI calls any source EF endpoint
- **THEN** the request includes an `Authorization: Bearer <eval.source.token>` header

#### Scenario: Configured API key is sent to the target deployment
- **WHEN** the CLI invokes the configured target deployment during the `run` command
- **THEN** the invocation includes an `Api-Key: <dial.components.core.api-key>` header

## Implementation notes

Status: Implemented. The `eval-cli` Gradle subproject consumes `evaluation-runner-core`'s `TestCaseRunnerFactory`/`TestCaseRunner` batch execution path via `RunOrchestrationService`, with `CloneService`/`FetchService`/`ImportService` implementing the clone/fetch/import steps and `EvaluateCommand` chaining clone → fetch → run → import for each configured suite. `CsvResultBatchWriter` implements `evaluation-runner-core`'s `ResultBatchWriter`, writing the CSV export contract described above. Shared `evaluation-runner-core` DTOs (plus one deliberate local subset, `TestSuiteUpdateResultDto`, under `client/source/dto`) are used for the source EF's existing `test-suite-clone`, `test-cases`, and `test-suite-runs` (`runs/import`) REST endpoints. See `design.md` for the full technical approach.
