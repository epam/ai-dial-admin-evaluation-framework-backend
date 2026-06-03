## MODIFIED Requirements

### Requirement: Snapshot phase before evaluation execution
Before transitioning a run to RUNNING, the system SHALL execute a snapshot phase that reads suite config and all enabled+valid test case data under a single REPEATABLE READ transaction and persists both snapshots to the meta DB. The snapshot phase SHALL complete before any HTTP calls are made.
Status: **Planned**

#### Scenario: Snapshot phase runs at start of executeRunAsync
- **WHEN** `executeRunAsync()` is invoked for a run
- **THEN** before updating the run to RUNNING, it SHALL execute a REPEATABLE READ transaction that: reads the suite, builds and persists `suite_snapshot` to `test_suite_runs`, and inserts all enabled+valid test case rows into `test_case_run_inputs` (ordered by `position`)

#### Scenario: Snapshot transaction is pure DB I/O
- **WHEN** the snapshot phase executes
- **THEN** no HTTP calls to deployment endpoints SHALL occur during the REPEATABLE READ transaction; it SHALL only perform DB reads (meta) and DB writes (meta)

#### Scenario: Snapshot phase duration bounded by DB I/O
- **WHEN** the snapshot phase processes a suite with 50K test cases
- **THEN** the REPEATABLE READ connection SHALL be held only for the duration of DB reads and batch INSERTs (expected ~10–15s), not for the duration of HTTP execution

#### Scenario: Inconsistent snapshot state
- **WHEN** exactly one of `suite_snapshot` or `test_case_run_inputs` rows is present for a run (a state that should not occur since both are written atomically)
- **THEN** the executor SHALL fail the run fast with status FAILED, error category `INTERNAL`, and `errorDetails.code = SNAPSHOT_STATE_INCONSISTENT`

### Requirement: Snapshot phase failure handling
The executor SHALL map every snapshot-phase failure mode to a specific structured error code under category `INTERNAL` and mark the run FAILED. The following codes SHALL be defined in the `RunErrorDetailsDto` / `ErrorCode` catalog:

- `SNAPSHOT_STATE_INCONSISTENT` — exactly one of `suite_snapshot` / `test_case_run_inputs` is present for a run
- `UNSUPPORTED_SNAPSHOT_VERSION` — `snapshotVersion` is unknown or newer than the current supported version
- `SNAPSHOT_FAILED` — generic snapshot-phase failure (DB error, serialization/deserialization failure not otherwise classified)
- `SNAPSHOT_SERIALIZATION_CONFLICT` — PostgreSQL serialization failure (SQLState `40001`) persisted after the REPEATABLE READ snapshot tx has exhausted its retries
- `SNAPSHOT_SUITE_MISSING` — live suite no longer exists when the snapshot phase (or legacy-run fallback `buildContext()`) runs

Status: **Planned**

#### Scenario: Snapshot DB error
- **WHEN** the snapshot phase fails due to a DB-level error (connection, write failure, deserialization failure) that is not otherwise classified
- **THEN** the run SHALL be marked FAILED with category `INTERNAL` and `errorDetails.code = SNAPSHOT_FAILED`

#### Scenario: Suite missing at snapshot time
- **WHEN** the snapshot phase runs and the referenced `test_suite_id` no longer exists in `test_suites`
- **THEN** the run SHALL be marked FAILED with category `INTERNAL` and `errorDetails.code = SNAPSHOT_SUITE_MISSING`

#### Scenario: Legacy run whose live suite was deleted
- **WHEN** a run has `suite_snapshot = null` (legacy) AND the referenced `test_suite_id` no longer exists in `test_suites`
- **THEN** the executor SHALL fail the run with category `INTERNAL` and code `SNAPSHOT_SUITE_MISSING`
- **AND** SHALL NOT crash or enter an inconsistent state

#### Scenario: Serialization conflict after retries exhausted
- **WHEN** the snapshot phase encounters PostgreSQL serialization failures (SQLState `40001`) on every retry attempt
- **THEN** the run SHALL be marked FAILED with category `INTERNAL` and `errorDetails.code = SNAPSHOT_SERIALIZATION_CONFLICT`

#### Scenario: Inconsistent snapshot state
- **WHEN** exactly one of `suite_snapshot` / `test_case_run_inputs` is present for a run
- **THEN** the run SHALL be marked FAILED with category `INTERNAL` and `errorDetails.code = SNAPSHOT_STATE_INCONSISTENT`

#### Scenario: Unknown or newer snapshot version
- **WHEN** the executor deserializes a `suite_snapshot` whose `snapshotVersion` is unknown or newer than the current supported version
- **THEN** the run SHALL be marked FAILED with category `INTERNAL` and `errorDetails.code = UNSUPPORTED_SNAPSHOT_VERSION`

### Requirement: Unified input carrier
The `TestCaseRunInput` row (or an equivalent snapshot-backed carrier) SHALL replace the live `TestCase` entity as the execution input carrier for **both** DEPLOYMENT (HTTP) and MCP_TOOL execution paths. Neither worker path SHALL query the live `test_cases` table during execution.
Status: **Planned**

#### Scenario: DEPLOYMENT path uses snapshot input
- **WHEN** the worker dispatches a DEPLOYMENT test case
- **THEN** it SHALL read `testCaseData` and any `requestTemplateOverride` / `inputBindingsOverride` from the `TestCaseRunInput` row, combined with snapshot-level `requestTemplate` / `inputBindings` from the `EvaluationContext`

#### Scenario: MCP_TOOL path uses snapshot input
- **WHEN** the worker dispatches an MCP_TOOL test case
- **THEN** it SHALL read `testCaseData` and overrides from the `TestCaseRunInput` row, combined with snapshot-level `argumentTemplate`, `mcpDeploymentRef`, `toolRef`, and `inputBindings` from the `EvaluationContext`

### Requirement: In-process evaluation execution
The `InProcessEvaluationExecutor` SHALL read suite configuration from the `EvaluationContext` (populated from `suite_snapshot`) and iterate test cases by paging through `test_case_run_inputs` for the run, not from the live `test_cases` table.
Status: **Planned**

#### Scenario: Sequential execution (default)
- **WHEN** `concurrencyLevel` is 1 (default)
- **THEN** the executor SHALL process test case calls one at a time, iterating through `test_case_run_inputs` rows in `position` order

#### Scenario: Parallel execution
- **WHEN** `concurrencyLevel` is greater than 1
- **THEN** the executor SHALL process up to `concurrencyLevel` test case calls concurrently using a semaphore-bounded virtual thread executor

#### Scenario: All snapshotted test cases are executed
- **WHEN** the executor runs for a run with N rows in `test_case_run_inputs` and `numberOfRuns = M`
- **THEN** the executor SHALL dispatch exactly N * M evaluation tasks

#### Scenario: Test cases that became disabled/invalid after snapshot are still executed
- **WHEN** a test case is in `test_case_run_inputs` but has subsequently been disabled or invalidated in the live `test_cases` table
- **THEN** the executor SHALL still execute it — the snapshot freezes the set and data at run start

#### Scenario: Test case deleted after snapshot
- **WHEN** a test case in `test_case_run_inputs` has been deleted from the live `test_cases` table
- **THEN** the executor SHALL execute it using the snapshotted data in `test_case_run_inputs` — no live DB lookup is required
- **AND** this applies to deletion of individual test cases only; deleting the parent test suite cascades through `test_suite_runs` and removes the run (and its `test_case_run_inputs`) entirely, so no orphaned execution occurs.

#### Scenario: Fallback for legacy runs without snapshot (synthesized)
- **WHEN** `executeRunAsync()` starts for a run with `suite_snapshot = null` and no `test_case_run_inputs` rows
- **THEN** `buildContext()` SHALL synthesize a transient in-memory `SuiteSnapshotDto` from the live suite via `SuiteSnapshotBuilder`, and SHALL pass the live `findEnabledValidByTestSuiteId` results as the test case source. The `InProcessEvaluationExecutor` SHALL execute through the single snapshot-driven code path with no special legacy branching inside the executor.

#### Scenario: Legacy run with zero enabled+valid test cases
- **WHEN** a legacy run (null snapshot) resolves its live suite AND finds zero enabled+valid test cases
- **THEN** the executor SHALL complete the run with status COMPLETED and zero results, consistent with baseline zero-TC behavior.

### Requirement: Single test case evaluation (worker)
The `EvaluationWorker` SHALL resolve the request body, call the target deployment endpoint, capture the response, extract response columns, and build a `TestCaseRunResult`. Suite configuration (template, bindings, deploymentRef, endpointRef, responseColumns) SHALL be read from the `EvaluationContext` (snapshot), not re-fetched from the database. Test case data SHALL come from the `TestCaseRunInput` row (or in-memory fallback for legacy runs), not from a live `test_cases` query.
Status: **Planned**

#### Scenario: Full request resolution from snapshot (HTTP)
- **WHEN** a test case is dispatched for execution in a DEPLOYMENT suite
- **THEN** the worker SHALL resolve the full request using the snapshot's `requestTemplate` and `inputBindings` from the `EvaluationContext`, combined with the test case data from the `TestCaseRunInput` row and any per-case overrides stored in that row. The worker SHALL NOT call `ResolvedRequestService.resolveRequest(suiteId, testCaseId)`.

#### Scenario: Deployment ref from snapshot
- **WHEN** the worker builds the deployment URL
- **THEN** it SHALL read `deploymentRef` and `endpointRef` from the `EvaluationContext` snapshot fields

#### Scenario: MCP execution uses context (unchanged)
- **WHEN** a test case is dispatched for execution in an MCP_TOOL suite
- **THEN** the worker SHALL use pre-deserialized DTOs from `EvaluationContext` (`mcpDeploymentRefDto`, `toolRefDto`, `argumentTemplateDto`, `inputBindings`)

#### Scenario: Per-test-case overrides applied from inputs row
- **WHEN** a `TestCaseRunInput` row has non-null `requestTemplateOverride` or `inputBindingsOverride`
- **THEN** the worker SHALL use the override in preference to the snapshot's suite-level values

#### Scenario: Endpoint invocation (non-streaming)
- **WHEN** the resolved request is sent and the response `Content-Type` is NOT `text/event-stream`
- **THEN** the worker SHALL capture the full response body, HTTP status code, and timing

#### Scenario: Endpoint invocation (streaming SSE)
- **WHEN** the resolved request is sent and the response `Content-Type` is `text/event-stream`
- **THEN** the worker SHALL accumulate SSE chunks via `StreamingResponseAccumulator` and record the assembled response

#### Scenario: Request timeout
- **WHEN** the endpoint does not respond within `requestTimeoutMs`
- **THEN** the worker SHALL set `executionStatus = TIMEOUT`, record null response fields, and record elapsed time

#### Scenario: Network error
- **WHEN** the endpoint call fails with a network-level error
- **THEN** the worker SHALL set `executionStatus = ERROR` and store the error message in `responseBody` as a JSON error envelope

#### Scenario: HTTP error from target (4xx/5xx)
- **WHEN** the endpoint returns an HTTP 4xx or 5xx status
- **THEN** the worker SHALL set `executionStatus = FAILED`, store the response body and status code as-is

#### Scenario: Response column extraction
- **WHEN** a response body is captured
- **THEN** the worker SHALL apply the suite's `responseColumns` definitions (from the snapshot in `EvaluationContext`) via `ResponseColumnExtractor`

#### Scenario: Request body stored in results
- **WHEN** the worker builds a `TestCaseRunResult`
- **THEN** it SHALL serialize and store the resolved request body in `requestBody` (JSONB). `requestBody` SHALL be null only when request resolution fails before the HTTP call.

#### Scenario: Retry count tracked in results
- **WHEN** the worker completes execution (with or without retries)
- **THEN** the result SHALL include `retryCount` set to the number of retry attempts made

#### Scenario: Log details populated on retries
- **WHEN** the worker completes execution with `retryCount > 0`
- **THEN** the result SHALL include `logDetails` with a structured log of retry attempts

#### Scenario: Log details null when no retries
- **WHEN** the worker completes execution with `retryCount = 0`
- **THEN** `logDetails` SHALL be null

### Requirement: Evaluation context carries snapshot
The `EvaluationContext` SHALL carry all execution-relevant suite configuration from the snapshot: `suiteType`, `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema`, and MCP fields. The `TestSuiteEvaluationJob` SHALL populate these fields by deserializing `suite_snapshot` from the run record after the snapshot phase completes.
Status: **Planned**

#### Scenario: Context built from persisted snapshot
- **WHEN** `TestSuiteEvaluationJob.buildContext()` runs after the snapshot phase
- **THEN** it SHALL deserialize `suite_snapshot` into `SuiteSnapshotDto` and populate all `EvaluationContext` snapshot fields. It SHALL NOT read from `testSuiteRepository`.

#### Scenario: Context fallback for legacy runs
- **WHEN** `buildContext()` runs for a legacy run with `suite_snapshot = null`
- **THEN** it SHALL synthesize a transient `SuiteSnapshotDto` from the live suite via `SuiteSnapshotBuilder` and populate the context identically to the persisted-snapshot case
