## MODIFIED Requirements

### Requirement: Trigger a test suite run
The service SHALL provide `POST /api/v1/test-suites/{testSuiteId}/runs` to create and trigger a new test suite run. The endpoint SHALL validate the request, persist a run record with status PENDING (including `testRunName` and `numberOfTestCases`), dispatch an async job, and return the run details immediately. The suite snapshot and test case inputs are captured asynchronously at the start of `executeRunAsync()` — not during the synchronous `createRun()` call — keeping the API response fast.
Status: **Planned**

#### Scenario: Successful run trigger
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` with a valid `RunConfigDto` body and the test suite exists
- **THEN** system SHALL create a `test_suite_runs` record with status `PENDING`, populate `testRunName` (from config or auto-generated), snapshot `numberOfTestCases` from the suite's current enabled and valid count, dispatch an async evaluation job, and return HTTP 202 Accepted with `TestSuiteRunResponseDto`. `suite_snapshot` SHALL initially be null and SHALL be populated by the async job before the run transitions to RUNNING.

#### Scenario: Test suite not found
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` with a non-existent `testSuiteId`
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

#### Scenario: Invalid run configuration
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` with an invalid body (e.g., `numberOfRuns` is null, zero, negative, or exceeds maximum)
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Global concurrent run limit exceeded
- **WHEN** client triggers a run but the total count of PENDING + RUNNING runs across all suites has reached the configured global limit
- **THEN** system SHALL respond with HTTP 429 Too Many Requests with error code `TOO_MANY_REQUESTS` and a message indicating the global limit was reached, including current and maximum counts in `details`

#### Scenario: Per-suite concurrent run limit exceeded
- **WHEN** client triggers a run but the count of PENDING + RUNNING runs for the target test suite has reached the configured per-suite limit
- **THEN** system SHALL respond with HTTP 429 Too Many Requests with error code `TOO_MANY_REQUESTS` and a message indicating the per-suite limit was reached, including current and maximum counts in `details`

#### Scenario: Test suite not in valid state
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` for a test suite with `valid = false`
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION`

#### Scenario: Executor rejects job submission
- **WHEN** the run is created successfully but the dedicated executor's queue is full at async dispatch time
- **THEN** the run SHALL have been persisted with status PENDING and HTTP 202 returned to the client. The service SHALL catch `RejectedExecutionException`, mark the run as FAILED with error category `RESOURCE_LIMIT` and code `EXECUTOR_REJECTED`, and log a warning.

### Requirement: TestSuiteRunResponseDto structure
The response DTO for a test suite run SHALL include all relevant run information, including `suiteSnapshot` (nullable `SuiteSnapshotDto`) that exposes the execution-relevant suite configuration. `suiteSnapshot` SHALL be populated in detail responses (`GET /runs/{runId}`) and `null` in list responses (`GET /runs`). The `test_case_run_inputs` data is internal executor state and SHALL NOT be exposed in `TestSuiteRunResponseDto`.
Status: **Planned**

#### Scenario: Response fields
- **WHEN** system returns a `TestSuiteRunResponseDto`
- **THEN** it SHALL include: `id`, `testSuiteId`, `testRunName`, `status`, `runConfig`, `numberOfTestCases`, `suiteSnapshot` (nullable), `startedAt` (nullable), `completedAt` (nullable), `errorMessage` (nullable), `errorDetails` (nullable), `createdAt`, `updatedAt`

#### Scenario: Detail response includes suite snapshot
- **WHEN** client calls `GET /api/v1/test-suites/{id}/runs/{runId}` for a run that has a snapshot
- **THEN** the response SHALL include `suiteSnapshot` with the full `SuiteSnapshotDto` object

#### Scenario: List response excludes snapshot
- **WHEN** client calls `GET /api/v1/test-suites/{id}/runs` (list endpoint)
- **THEN** each run in the response SHALL have `suiteSnapshot` as `null`

#### Scenario: Legacy run without snapshot
- **WHEN** client calls `GET /api/v1/test-suites/{id}/runs/{runId}` for a run created before the migration
- **THEN** the response SHALL have `suiteSnapshot` as `null`

#### Scenario: Run in PENDING status has null snapshot
- **WHEN** a run exists in PENDING status (snapshot phase has not yet committed)
- **THEN** `suite_snapshot` SHALL be null AND `test_case_run_inputs` SHALL contain zero rows for that run
- **AND** a `GET /runs/{runId}` response for such a run SHALL have `suiteSnapshot` as `null`
- **NOTE** Once the snapshot phase commits, `updateToRunning()` is invoked sequentially in the next statement, so the window during which `suite_snapshot` is non-null but the run is still PENDING is an implementation detail not observable in practice via the API.

### Requirement: Number of test cases snapshot
The `numberOfTestCases` field SHALL hold a preview count at run creation time, derived from the test suite's current enabled and valid test case count (`is_enabled = true AND is_valid = true`). This value SHALL be finalized during the snapshot phase based on the exact number of rows inserted into `test_case_run_inputs` for the run, updated atomically within the snapshot transaction. After the snapshot phase commits, the value SHALL be immutable.
Status: **Planned**

#### Scenario: numberOfTestCases reflects snapshot, not creation-time, count
- **WHEN** the snapshot phase commits
- **THEN** `numberOfTestCases` SHALL equal the number of rows inserted into `test_case_run_inputs` for this run
- **AND** SHALL be updated atomically with the snapshot write (same transaction)
- **NOTE** The creation-time count written in `createRun()` is a preview and may differ if test cases were added, disabled, or invalidated between suite creation and snapshot time.

#### Scenario: Suite changes between run creation and snapshot phase
- **WHEN** the suite's enabled+valid test cases change between run creation and snapshot phase
- **THEN** `numberOfTestCases` SHALL reflect the snapshot-phase count, not the creation-time preview

#### Scenario: Suite changes after snapshot phase committed
- **WHEN** the suite changes after the snapshot phase has committed
- **THEN** `numberOfTestCases` SHALL remain unchanged
