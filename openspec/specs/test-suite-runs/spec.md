# Test Suite Runs

## Purpose
This spec describes asynchronous test suite run execution, real-time status tracking via SSE, and run lifecycle management (create, list, get, cancel, delete).

Status: **Implemented**

## Key Terms
- **TestSuiteRun**: A single execution of a test suite, created asynchronously with a run configuration. Progresses through a status lifecycle: PENDING, RUNNING, CANCELLING, COMPLETED, FAILED, CANCELLED.
- **RunConfig**: Configuration for a run; contains `numberOfRuns`, optional `testRunName`, optional `execution` (ExecutionSettingsDto), and optional `retry` (RetryPolicyDto). Stored as JSONB for extensibility.
- **Evaluation execution engine**: The real evaluation engine that calls target deployment endpoints per test case, captures responses (including streaming SSE), and writes results to analytics DB. Replaced the former mock evaluation job. See `eval-execution-engine` spec.
- **SSE status stream**: A Server-Sent Events endpoint that pushes run status updates to connected clients, with optional filtering.
- **Cancellation**: Interrupt-driven, via a per-run worker `ExecutorService` (a `RunHandle` tracked in the per-JVM `ActiveRunRegistry`). Cancelling a RUNNING run shuts that executor down immediately — interrupting in-flight workers and rejecting new dispatch — rather than interrupting the job/orchestration thread itself. The run is observable as CANCELLING from the moment the cancel request commits until the async job finalizes it as CANCELLED.
- **Startup reconciliation**: A process that runs on application startup to recover orphaned runs (PENDING/RUNNING/CANCELLING) that were lost due to a crash or restart, since the in-memory `ActiveRunRegistry` and SSE emitters do not survive a restart. Orphaned PENDING/RUNNING runs are marked FAILED; orphaned CANCELLING runs are finalized as CANCELLED.

## Requirements

### Requirement: Trigger a test suite run
The service SHALL provide `POST /api/v1/test-suites/{testSuiteId}/runs` to create and trigger a new test suite run. The endpoint SHALL validate the request, verify the suite is bound to a dataset, persist a run record with status PENDING (including `testRunName` and `numberOfTestCases` snapshot), dispatch an async job, and return the run details immediately (without waiting for job completion). The unbound-suite guard (`datasetId IS NULL`) SHALL run before the `valid = false` check, so unbound suites SHALL surface as HTTP 409 with error code `SUITE_HAS_NO_DATASET` regardless of their validation state. An additional **run-time presence check** SHALL be performed after the `valid = false` guard: the service SHALL count the runnable test cases for the bound dataset — those that are valid and (when the suite has a `testCaseFilter`) match that filter — and if the count is zero, SHALL respond with HTTP 409 `INVALID_OPERATION` with message "Suite has no valid and enabled test cases". No run record SHALL be persisted and no async job SHALL be dispatched when this check fails. The count SHALL NOT consider any other exclusion source; `test_suites.disabled_test_case_ids` is not read.

Guard order:
1. Suite not found → 404 `NOT_FOUND`
2. Unbound (`datasetId == null`) → 409 `SUITE_HAS_NO_DATASET`
3. Config-invalid (`isValid == false`) → 409 `INVALID_OPERATION`
4. Zero runnable test cases → 409 `INVALID_OPERATION`
5. Concurrent run limits → 429 `TOO_MANY_REQUESTS`

Status: **Implemented**

#### Scenario: Successful run trigger
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` with a valid `RunConfigDto` body and the test suite exists and is bound to a dataset
- **THEN** system SHALL create a `test_suite_runs` record with status `PENDING`, populate `testRunName` (from config or auto-generated), snapshot `numberOfTestCases` from the bound dataset's runnable test case count (valid, and matching `testCaseFilter` when set), dispatch an async evaluation job on the dedicated executor, and return HTTP 202 Accepted with the `TestSuiteRunResponseDto`

#### Scenario: Test suite not found
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` with a non-existent `testSuiteId`
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

#### Scenario: Unbound suite (datasetId is null) rejected
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` for an existing suite whose `datasetId IS NULL`
- **THEN** system SHALL respond with HTTP 409 and error code `SUITE_HAS_NO_DATASET`; no run record SHALL be persisted and no async job SHALL be dispatched; this check SHALL run before the `valid = false` guard so the dataset-binding failure mode is reported even when the suite would also fail validation

#### Scenario: Invalid run configuration
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` with an invalid body (e.g., `numberOfRuns` is null, zero, negative, or exceeds maximum)
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Global concurrent run limit exceeded
- **WHEN** client triggers a run but the total count of active (PENDING + RUNNING + CANCELLING) runs across all suites has reached the configured global limit
- **THEN** system SHALL respond with HTTP 429 Too Many Requests with error code `TOO_MANY_REQUESTS` and a message indicating the global limit was reached, including current and maximum counts in `details`

#### Scenario: Per-suite concurrent run limit exceeded
- **WHEN** client triggers a run but the count of active (PENDING + RUNNING + CANCELLING) runs for the target test suite has reached the configured per-suite limit
- **THEN** system SHALL respond with HTTP 429 Too Many Requests with error code `TOO_MANY_REQUESTS` and a message indicating the per-suite limit was reached, including current and maximum counts in `details`

#### Scenario: Test suite not in valid state
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` for a bound test suite that has `valid = false` (failed validation)
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message indicating the test suite is not in a valid state (this check applies only to suites that pass the `SUITE_HAS_NO_DATASET` guard)

#### Scenario: Bound suite with no runnable test cases rejected
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` for a config-valid, bound suite whose dataset has zero runnable test cases — because none are valid, or none match the suite's `testCaseFilter`
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with message "Suite has no valid and enabled test cases"; no run record SHALL be persisted and no async job SHALL be dispatched

#### Scenario: Runnable count ignores legacy stored exclusions
- **WHEN** a config-valid, bound suite carries a non-empty `test_suites.disabled_test_case_ids` value stored by an earlier version of the product
- **THEN** the runnable count and the persisted `numberOfTestCases` SHALL equal the number of valid, `testCaseFilter`-matching test cases in the dataset, as if the stored value were empty
- **AND** a `testCaseFilter` matching only test cases named in that stored value SHALL still produce a successful run (no 409)

#### Scenario: Runnable count honors testCaseFilter
- **WHEN** a config-valid, bound suite has valid test cases but its `testCaseFilter` matches a non-empty subset of them
- **THEN** the zero-runnable guard SHALL pass and the persisted `numberOfTestCases` SHALL equal the count of the filter-matching subset

#### Scenario: Executor rejects job submission
- **WHEN** the run is created successfully but the dedicated executor rejects the submission at async dispatch time (e.g. once the executor has been closed because the application context is shutting down)
- **THEN** the run SHALL have been persisted with status PENDING and HTTP 202 returned to the client. The service SHALL catch `RejectedExecutionException` in the post-commit callback, mark the run as FAILED with error category `RESOURCE_LIMIT` and code `EXECUTOR_REJECTED`, and log a warning

### Requirement: Run configuration model
The run request body SHALL contain a `runConfig` object. `runConfig` SHALL support: `numberOfRuns` (integer, required, `@Min(1)`, validated against a configurable maximum in the service layer, default 64), `testRunName` (String, optional — a user-provided name for the run), `execution` (ExecutionSettingsDto, optional — concurrency, timeout, and rate limiting settings), and `retry` (RetryPolicyDto, optional — retry behavior for failed calls). The `runConfig` SHALL be stored as JSONB to allow future extension without schema migration.
Status: **Implemented**

#### Scenario: Valid numberOfRuns
- **WHEN** client sends `runConfig: { "numberOfRuns": 5 }`
- **THEN** system SHALL accept the configuration and persist it as JSONB

#### Scenario: numberOfRuns below minimum
- **WHEN** client sends `runConfig: { "numberOfRuns": 0 }` or negative value
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: numberOfRuns above maximum
- **WHEN** client sends `runConfig: { "numberOfRuns": 65 }` (exceeds configured max, default 64)
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: numberOfRuns is null
- **WHEN** client sends `runConfig: { "numberOfRuns": null }` or omits `numberOfRuns`
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: testRunName provided in config
- **WHEN** client sends `runConfig: { "numberOfRuns": 5, "testRunName": "Regression Run #3" }`
- **THEN** system SHALL use the provided `testRunName` as the run's `testRunName`

#### Scenario: testRunName omitted in config
- **WHEN** client sends `runConfig` without `testRunName` or with `testRunName: null`
- **THEN** system SHALL auto-generate a unique human-readable name for the run (see testRunName auto-generation requirement)

#### Scenario: Minimal runConfig (90% of users)
- **WHEN** client sends `runConfig: { "numberOfRuns": 3 }` without `execution` or `retry`
- **THEN** system SHALL accept the configuration, apply system default execution settings (sequential, 30s timeout, no retry, no rate limit), and persist as JSONB

#### Scenario: Full runConfig with execution settings
- **WHEN** client sends:
  ```json
  {
    "runConfig": {
      "numberOfRuns": 5,
      "execution": {
        "concurrencyLevel": 10,
        "requestTimeoutMs": 120000,
        "rateLimitRps": 5.0
      },
      "retry": {
        "maxRetries": 2,
        "retryDelayMs": 2000,
        "retryBackoffMultiplier": 2.0
      }
    }
  }
  ```
- **THEN** system SHALL validate all fields against system maximums and persist the full configuration as JSONB

#### Scenario: Execution settings validation — concurrencyLevel
- **WHEN** `execution.concurrencyLevel` is provided
- **THEN** it SHALL be >= 1 and <= system max (configurable, default 50). Values outside range SHALL result in HTTP 400

#### Scenario: Execution settings validation — requestTimeoutMs
- **WHEN** `execution.requestTimeoutMs` is provided
- **THEN** it SHALL be >= 1000 and <= system max (configurable, default 600000). Values outside range SHALL result in HTTP 400

#### Scenario: Execution settings validation — rateLimitRps
- **WHEN** `execution.rateLimitRps` is provided
- **THEN** it SHALL be >= 0.1. Values below SHALL result in HTTP 400. Null means no rate limit.

#### Scenario: Retry settings validation — maxRetries
- **WHEN** `retry.maxRetries` is provided
- **THEN** it SHALL be >= 0 and <= system max (configurable, default 10). Values outside range SHALL result in HTTP 400

#### Scenario: Retry settings validation — retryDelayMs
- **WHEN** `retry.retryDelayMs` is provided
- **THEN** it SHALL be >= 100 and <= system max (configurable, default 60000). Values outside range SHALL result in HTTP 400

#### Scenario: Retry settings validation — retryBackoffMultiplier
- **WHEN** `retry.retryBackoffMultiplier` is provided
- **THEN** it SHALL be >= 1.0 and <= 10.0. Values outside range SHALL result in HTTP 400

#### Scenario: Partial execution settings
- **WHEN** client provides `execution: { "concurrencyLevel": 5 }` without `requestTimeoutMs` or `rateLimitRps`
- **THEN** system SHALL accept the partial settings; omitted fields SHALL use system defaults

### Requirement: Run status lifecycle
Each test suite run SHALL have a status that follows a defined lifecycle. Valid statuses are: `PENDING`, `RUNNING`, `CANCELLING`, `COMPLETED`, `FAILED`, `CANCELLED`. `CANCELLING` is a non-terminal status meaning "cancellation was requested while the run was RUNNING and the async job has not yet finalized it". Status transitions SHALL be enforced.
Status: **Implemented**

#### Scenario: Normal successful lifecycle
- **WHEN** a run is created and both the deployment evaluation and metric evaluation phases complete without fatal error
- **THEN** the status SHALL transition: PENDING -> RUNNING -> COMPLETED

#### Scenario: Failed lifecycle
- **WHEN** a run is created and the async job encounters a fatal error (unhandled exception, analytics write failure)
- **THEN** the status SHALL transition: PENDING -> RUNNING -> FAILED, and `error_message` and `error_details` SHALL be populated

#### Scenario: Cancelled from PENDING
- **WHEN** a run with status PENDING is cancelled before the async job starts
- **THEN** the status SHALL transition: PENDING -> CANCELLED

#### Scenario: Cancelled from RUNNING
- **WHEN** a run with status RUNNING is cancelled via the cancellation API
- **THEN** the status SHALL transition: RUNNING -> CANCELLING (written synchronously by the cancel request) -> CANCELLED (written by the async job once its work has stopped). Cancellation may occur during any phase.

#### Scenario: Late cancel still ends CANCELLED
- **WHEN** a run is CANCELLING and the async job finishes all remaining work before it observes the cancellation
- **THEN** the run SHALL end CANCELLED, not COMPLETED — a run whose cancellation was requested is never reported as COMPLETED

#### Scenario: Terminal status is immutable
- **WHEN** a run has reached a terminal status (COMPLETED, FAILED, or CANCELLED)
- **THEN** no further status transitions SHALL occur

### Requirement: List test suite runs (paginated)
The service SHALL provide `GET /api/v1/test-suite-runs` to list runs with filtering, sorting, and pagination. Filterable fields SHALL include: `testSuiteId` (UUID, `eq`/`in`), `id` (UUID, `eq`/`in`), `status` (STRING, `eq`/`ne`/`in`), `testRunName` (STRING, `eq`/`ne`/`co`/`in`), `createdAt` (LONG epoch ms, `gt`/`gte`/`lt`/`lte`), `startedAt` (LONG epoch ms, `gt`/`gte`/`lt`/`lte`), `completedAt` (LONG epoch ms, `gt`/`gte`/`lt`/`lte`).
Status: **Implemented**

#### Scenario: Default pagination
- **WHEN** client calls `GET /api/v1/test-suite-runs` without pagination params
- **THEN** response SHALL be a `PageResponseDto<TestSuiteRunResponseDto>` with default `page=0` and `size=100` (matching project-wide pagination default)

#### Scenario: Pagination bounds
- **WHEN** client calls `GET /api/v1/test-suite-runs?page=<p>&size=<s>`
- **THEN** `page` SHALL be >= 0 and `size` SHALL be between 1 and the configured maximum page size

#### Scenario: Filter by testSuiteId
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=testSuiteId:eq:<uuid>`
- **THEN** system SHALL return only runs belonging to that test suite

#### Scenario: Filter by id (equality)
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=id:eq:<uuid>`
- **THEN** system SHALL return only the run with that exact id

#### Scenario: Filter by id (set membership)
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=id:in:<uuid1>,<uuid2>`
- **THEN** system SHALL return only runs whose id appears in the provided set

#### Scenario: Filter by status
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=status:eq:RUNNING`
- **THEN** system SHALL return only runs with status RUNNING

#### Scenario: Filter by testRunName
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=testRunName:eq:My Regression Test`
- **THEN** system SHALL return only runs with matching `testRunName`

#### Scenario: Filter by createdAt range
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=createdAt:ge:1735689600000&filter=createdAt:lt:1738368000000`
- **THEN** system SHALL return only runs created within the specified epoch ms range

#### Scenario: Filter by startedAt range
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=startedAt:ge:<epochMs>`
- **THEN** system SHALL return only runs where `startedAt` is greater than or equal to the given epoch ms value; runs with null `startedAt` SHALL be excluded

#### Scenario: Filter by startedAt upper bound
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=startedAt:lt:<epochMs>`
- **THEN** system SHALL return only runs where `startedAt` is strictly less than the given epoch ms value; runs with null `startedAt` SHALL be excluded

#### Scenario: Filter by completedAt range
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=completedAt:ge:<epochMs>`
- **THEN** system SHALL return only runs where `completedAt` is greater than or equal to the given epoch ms value; runs with null `completedAt` (PENDING, RUNNING or CANCELLING runs) SHALL be excluded

#### Scenario: Filter by completedAt upper bound
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=completedAt:lt:<epochMs>`
- **THEN** system SHALL return only runs where `completedAt` is strictly less than the given epoch ms value; runs with null `completedAt` SHALL be excluded

#### Scenario: Multiple filters combined with AND
- **WHEN** client provides multiple `filter` parameters
- **THEN** system SHALL apply all filters using AND combination

#### Scenario: Sorting
- **WHEN** client calls `GET /api/v1/test-suite-runs?sort=createdAt,desc`
- **THEN** system SHALL sort results by `createdAt` descending

#### Scenario: Default sort order
- **WHEN** client calls `GET /api/v1/test-suite-runs` without `sort` parameter
- **THEN** system SHALL sort by `createdAt` descending (most recent first)

#### Scenario: Sortable fields
- **WHEN** client calls `GET /api/v1/test-suite-runs?sort=<field>,<dir>`
- **THEN** system SHALL support sorting by: `createdAt`, `startedAt`, `completedAt`, `status`, `testRunName`

#### Scenario: Include total count
- **WHEN** client calls `GET /api/v1/test-suite-runs?includeTotalCount=true`
- **THEN** response SHALL include `totalElements` and `totalPages`

### Requirement: Get test suite run by id
The service SHALL provide `GET /api/v1/test-suite-runs/{id}` to retrieve a single run's details.
Status: **Implemented**

#### Scenario: Existing run
- **WHEN** client calls `GET /api/v1/test-suite-runs/{id}` for an existing run
- **THEN** system SHALL return the `TestSuiteRunResponseDto` with all fields

#### Scenario: Non-existent run
- **WHEN** client calls `GET /api/v1/test-suite-runs/{id}` for a non-existent id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

### Requirement: TestSuiteRunResponseDto structure
The response DTO for a test suite run SHALL include all relevant run information, including the extended `runConfig` with execution and retry settings.
Status: **Implemented**

#### Scenario: Response fields
- **WHEN** system returns a `TestSuiteRunResponseDto`
- **THEN** it SHALL include: `id` (UUID), `testSuiteId` (UUID), `testRunName` (String — user-provided or auto-generated), `status` (String — one of PENDING, RUNNING, CANCELLING, COMPLETED, FAILED, CANCELLED), `runConfig` (object with `numberOfRuns`, optional `testRunName`, optional `execution`, optional `retry`), `numberOfTestCases` (int — finalized at snapshot phase to match `test_case_run_inputs` row count), `startedAt` (Long, nullable, epoch ms — set when status becomes RUNNING), `completedAt` (Long, nullable, epoch ms — set when status becomes COMPLETED, FAILED, or CANCELLED), `errorMessage` (String, nullable — user-friendly error message for FAILED runs), `errorDetails` (object, nullable — structured error info for FAILED runs), `suiteSnapshot` (object, nullable — frozen suite configuration at snapshot phase; null in list results, non-null in detail after snapshot committed), `createdAt` (Long, epoch ms), `updatedAt` (Long, epoch ms)

#### Scenario: Error details structure for FAILED runs
- **WHEN** a run has status FAILED and `errorDetails` is non-null
- **THEN** `errorDetails` SHALL contain: `code` (String — machine-readable error code), `category` (String — one of VALIDATION, TIMEOUT, RESOURCE_LIMIT, TEST_SUITE_ERROR, INTERNAL), `message` (String — user-friendly description), `details` (object, nullable — additional context)

#### Scenario: Null fields for non-terminal runs
- **WHEN** a run has status PENDING, RUNNING or CANCELLING
- **THEN** `completedAt`, `errorMessage`, and `errorDetails` SHALL be null

### Requirement: Cancel a test suite run
The service SHALL provide `POST /api/v1/test-suite-runs/{id}/cancel` to cancel a run that has not yet reached a terminal status. PENDING, RUNNING and CANCELLING runs MAY be targeted; the request SHALL make the cancellation observable to API clients immediately, before the async job has stopped.
Status: **Implemented**

#### Scenario: Cancel PENDING run
- **WHEN** client calls `POST /api/v1/test-suite-runs/{id}/cancel` for a run with status PENDING
- **THEN** system SHALL set status to CANCELLED, set `completedAt`, notify SSE clients, and return HTTP 200 with the updated `TestSuiteRunResponseDto`

#### Scenario: Cancel RUNNING run
- **WHEN** client calls `POST /api/v1/test-suite-runs/{id}/cancel` for a run with status RUNNING
- **THEN** system SHALL set status to CANCELLING (optimistic `WHERE status = 'RUNNING'`, `completedAt` stays null), interrupt the run's in-flight work, notify SSE clients of the CANCELLING transition, and return HTTP 200 with the `TestSuiteRunResponseDto` showing `status = CANCELLING`. The async job later transitions the run to CANCELLED and notifies SSE clients again.

#### Scenario: Cancel is idempotent while CANCELLING
- **WHEN** client calls `POST /api/v1/test-suite-runs/{id}/cancel` for a run with status CANCELLING
- **THEN** system SHALL make no change and respond with HTTP 200 and the current `TestSuiteRunResponseDto` (`status = CANCELLING`)

#### Scenario: Cancel already-terminal run
- **WHEN** client calls `POST /api/v1/test-suite-runs/{id}/cancel` for a run with status COMPLETED, FAILED, or CANCELLED
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message indicating the run cannot be cancelled in its current status

#### Scenario: Cancel PENDING race condition with async job start
- **WHEN** client calls `POST /api/v1/test-suite-runs/{id}/cancel` for a PENDING run at the exact moment the async job transitions it to RUNNING
- **THEN** system SHALL use optimistic SQL update (`WHERE status = 'PENDING'`); if the row was already transitioned to RUNNING, it SHALL fall through to RUNNING cancellation (`RUNNING -> CANCELLING` plus interruption). Conversely, when the cancel wins, the async job's own `PENDING -> RUNNING` optimistic update affects zero rows and the job SHALL exit without executing anything.

#### Scenario: Cancel observable after reload
- **WHEN** a client re-fetches a run (GET by id, list, or SSE stream) after a cancel request was accepted for a RUNNING run and before the job has finalized it
- **THEN** the run SHALL be reported with `status = CANCELLING`, distinguishable from a run that is merely RUNNING

#### Scenario: Cancel non-existent run
- **WHEN** client calls `POST /api/v1/test-suite-runs/{id}/cancel` for a non-existent id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

### Requirement: Delete a test suite run
The service SHALL provide `DELETE /api/v1/test-suite-runs/{id}` to delete a run and its related resources. Only runs in a terminal status (COMPLETED, FAILED, CANCELLED) MAY be deleted. PENDING and RUNNING runs MUST be cancelled first; a CANCELLING run MUST finish cancelling first.
Status: **Implemented**

#### Scenario: Delete terminal run
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a run with status COMPLETED, FAILED, or CANCELLED
- **THEN** system SHALL delete the run record (and any future related resources via CASCADE) and return HTTP 204 No Content

#### Scenario: Delete RUNNING run rejected
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a run with status RUNNING
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message suggesting to cancel the run first

#### Scenario: Delete PENDING run rejected
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a run with status PENDING
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message indicating that PENDING runs must complete or be cancelled before deletion

#### Scenario: Delete CANCELLING run rejected
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a run with status CANCELLING
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message indicating the run is still being cancelled

#### Scenario: Delete non-existent run
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a non-existent id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

#### Scenario: Cascade delete on test suite removal
- **WHEN** a test suite is deleted via `DELETE /api/v1/test-suites/{id}`
- **THEN** all associated test suite runs SHALL be deleted automatically via database CASCADE

### Requirement: SSE status stream
The service SHALL provide `GET /api/v1/test-suite-runs/status-stream` as a Server-Sent Events endpoint for real-time run status updates. Clients MAY filter which updates they receive via query parameters.
Status: **Implemented**

#### Scenario: Connect without filters (all updates)
- **WHEN** client connects to `GET /api/v1/test-suite-runs/status-stream` without query parameters
- **THEN** system SHALL stream status update events for ALL runs

#### Scenario: Filter by runIds
- **WHEN** client connects with `?runIds=uuid1,uuid2`
- **THEN** system SHALL stream updates only for the specified run ids

#### Scenario: Filter by testSuiteIds
- **WHEN** client connects with `?testSuiteIds=uuid1,uuid2`
- **THEN** system SHALL stream updates only for runs belonging to the specified test suites

#### Scenario: Filter by statuses
- **WHEN** client connects with `?statuses=RUNNING,COMPLETED`
- **THEN** system SHALL stream updates only for runs transitioning to the specified statuses

#### Scenario: Combined filters
- **WHEN** client provides multiple filter parameters (e.g., `?testSuiteIds=uuid1&statuses=FAILED`)
- **THEN** system SHALL apply all filters with AND combination

#### Scenario: Cancellation emits two transitions
- **WHEN** a RUNNING run is cancelled via the cancel endpoint
- **THEN** connected clients SHALL receive a `status-update` event with `status = CANCELLING` when the cancel request commits, followed later by a `status-update` event with `status = CANCELLED` when the async job finalizes the run

#### Scenario: SSE event format
- **WHEN** a run status changes
- **THEN** the SSE event SHALL have event name `status-update` and JSON data containing: `runId` (UUID), `testSuiteId` (UUID), `status` (String), `message` (String, nullable — human-readable status description), `timestamp` (Long, epoch ms)

#### Scenario: Connection timeout
- **WHEN** an SSE connection has been open for longer than the configured timeout (default 30 minutes)
- **THEN** system SHALL close the connection gracefully

#### Scenario: Heartbeat events
- **WHEN** an SSE connection is active and no status updates have been sent recently
- **THEN** system SHALL periodically send heartbeat events to keep the connection alive

### Requirement: SSE emitter cleanup
The service SHALL implement a scheduled cleanup mechanism to remove stale or broken SSE connections and prevent memory leaks.
Status: **Implemented**

#### Scenario: Scheduled cleanup runs periodically
- **WHEN** the configured cleanup interval elapses (default 5 minutes)
- **THEN** system SHALL iterate active SSE emitters, attempt a heartbeat ping, and remove any that fail

#### Scenario: Broken connection detected
- **WHEN** a heartbeat ping to an SSE emitter throws an IOException
- **THEN** system SHALL remove the emitter from the active connection pool

#### Scenario: Cleanup logging
- **WHEN** the cleanup task completes
- **THEN** system SHALL log the number of removed stale emitters and the count of remaining active emitters

### Requirement: Concurrent run limits
The service SHALL enforce configurable limits on the number of concurrent active runs (PENDING + RUNNING + CANCELLING), both globally and per test suite. A CANCELLING run still holds execution resources until the job finalizes it and therefore counts as active.
Status: **Implemented**

#### Scenario: Within global limit
- **WHEN** the total active run count is below the configured global maximum (default 20)
- **THEN** system SHALL allow creating a new run

#### Scenario: Global limit exceeded
- **WHEN** the total active run count equals or exceeds the configured global maximum
- **THEN** system SHALL reject the run creation with HTTP 429 and include `activeRunsGlobal` and `maxRunsGlobal` in error `details`

#### Scenario: Within per-suite limit
- **WHEN** the active run count for a specific test suite is below the configured per-suite maximum (default 5)
- **THEN** system SHALL allow creating a new run for that suite

#### Scenario: Per-suite limit exceeded
- **WHEN** the active run count for a specific test suite equals or exceeds the configured per-suite maximum
- **THEN** system SHALL reject the run creation with HTTP 429 and include `activeRunsForSuite` and `maxRunsPerSuite` in error `details`

### Requirement: Evaluation job orchestration
The `TestSuiteEvaluationJob` SHALL delegate to `EvaluationExecutor.execute()` for deployment evaluation (Phase 1) and `MetricEvaluationExecutor.execute()` for metric evaluation (Phase 2), following a consistent pattern: build context, then call execute. Both executors are interfaces with in-process implementations (`InProcessEvaluationExecutor`, `InProcessMetricEvaluationExecutor`). Every run SHALL own exactly one worker executor for its whole lifetime, registered in a per-JVM registry before the job is submitted and removed when the job finishes; cancellation SHALL be delivered by shutting that executor down, not by a flag that phases poll. The job's own thread SHALL never be interrupted. Configuration value resolution SHALL use `ObjectUtils.defaultIfNull` from Apache Commons Lang.
Status: **Implemented**

#### Scenario: Job delegates to executor
- **WHEN** the job runs for a run id
- **THEN** it SHALL construct an `EvaluationContext` from the run's `RunConfigDto` (with system defaults for omitted fields), carrying the run's worker executor, and call `evaluationExecutor.execute(context)`

#### Scenario: Cancellation signal registered before dispatch
- **WHEN** `TestSuiteRunService` triggers an evaluation run
- **THEN** the run's worker executor SHALL be created and registered synchronously in the caller's thread BEFORE the job is submitted to the async executor, so that a cancel request arriving before the job thread starts still finds and shuts down the run's executor. If submission fails (executor rejection or any other exception), the registration SHALL be removed to prevent leaks.

#### Scenario: Job start is guarded against a prior PENDING cancel
- **WHEN** the job starts and attempts `PENDING -> RUNNING` with an optimistic update
- **THEN** if zero rows are affected (the run was cancelled while PENDING) the job SHALL exit without executing any phase and without writing any status

#### Scenario: Status notifications
- **WHEN** the evaluation job transitions between statuses (PENDING->RUNNING, RUNNING->COMPLETED/FAILED, CANCELLING->CANCELLED)
- **THEN** it SHALL notify all matching SSE clients of each transition

#### Scenario: Cancellation during execution
- **WHEN** the run is cancelled while the evaluation job is running
- **THEN** the run's worker executor SHALL be shut down immediately (in-flight workers interrupted, new dispatches rejected); the current phase SHALL end promptly, the job SHALL NOT start any further phase, and the job SHALL write CANCELLED (see eval-execution-engine and metric-evaluation specs for per-phase behavior)

#### Scenario: Terminal write is guarded
- **WHEN** all phases finish and the job writes the terminal status
- **THEN** `COMPLETED` SHALL be written only with an optimistic `WHERE status = 'RUNNING'`; if zero rows are affected the status is CANCELLING and the job SHALL write CANCELLED instead. When a phase fails with an exception while the run is CANCELLING (or its executor has been shut down), the job SHALL write CANCELLED rather than FAILED.

#### Scenario: Configuration resolution uses ObjectUtils
- **WHEN** the job resolves run configuration values with system defaults
- **THEN** it SHALL use `ObjectUtils.defaultIfNull(value, default)` from Apache Commons Lang instead of custom `resolveInt`/`resolveLong`/`resolveDouble` static methods

#### Scenario: Metric evaluation chained after deployment evaluation
- **WHEN** the deployment evaluation phase completes (all test cases executed) and the run is not cancelled
- **THEN** the job SHALL build a `MetricEvaluationContext` (loading aggregated TSMDs, generating computationId/computedAtMs, building provider semaphores, carrying the same worker executor) and call `MetricEvaluationExecutor.execute(context)`. The executor SHALL run for any TSMD count, including zero — with an empty TSMD list it writes eval summaries with empty `metric_values` and no run metric snapshots (see metric-evaluation spec).

#### Scenario: Run without metrics still yields results
- **WHEN** a run's suite has no enabled+valid TSMDs
- **AND** the eval-summary batch write succeeds (as for a metric-bearing run, a failed analytics write ends the run FAILED with code `ANALYTICS_WRITE_FAILED`)
- **THEN** the run SHALL still reach COMPLETED and its eval summaries SHALL be readable through the eval-summary list, count, aggregate, and export endpoints

#### Scenario: Cancellation between phases
- **WHEN** a phase completes and the run's executor has been shut down before the next phase starts
- **THEN** the job SHALL skip all remaining phases and transition to CANCELLED status

#### Scenario: Metric evaluation failure does not fail the run
- **WHEN** the metric evaluation phase encounters errors (provider unavailable, individual metric errors)
- **THEN** the run SHALL still transition to COMPLETED. Individual metric errors are captured per-EvalSummary row (`executionStatus = FAILED` with error details in `metricInfos`).

### Requirement: Dedicated run job executor
The service SHALL execute each test suite run job on a dedicated executor (bean `testSuiteRunExecutor`), separate from the default Spring async executor. The executor SHALL start one new thread per run for the run's whole lifetime — no thread pool, no queue, no executor-level concurrency limit — so that the number of concurrently executing runs is governed solely by the concurrent run limits (`test-suite-run.limits.*`). The threads SHALL be virtual when `spring.threads.virtual.enabled` is `true` (default) and platform threads when it is `false`, so that a single switch moves the whole JVM to platform threads for sampling/profiling.
Status: **Implemented**

#### Scenario: Executor isolation
- **WHEN** test suite run jobs are dispatched
- **THEN** they SHALL execute on the dedicated executor, not the default Spring async executor

#### Scenario: One thread per run
- **WHEN** N runs are admitted by the concurrent run limits and dispatched
- **THEN** all N jobs SHALL start executing immediately on N distinct threads; none SHALL wait in a queue behind a pool size

#### Scenario: Virtual threads by default
- **WHEN** `spring.threads.virtual.enabled` is `true` (the default)
- **THEN** run job threads and each run's worker threads SHALL be virtual threads

#### Scenario: Platform threads on opt-out
- **WHEN** `spring.threads.virtual.enabled` is `false`
- **THEN** run job threads and each run's worker threads SHALL be platform daemon threads, visible to sampling profilers and thread dumps; cancellation and interrupt behaviour SHALL be identical to the virtual-thread case

#### Scenario: Thread naming
- **WHEN** the executor creates threads
- **THEN** run job thread names SHALL use the prefix `test-suite-run-` and run worker thread names the prefix `run-worker-` for identification in logs, thread dumps and profilers

#### Scenario: Rejected execution after shutdown
- **WHEN** the executor rejects a run job at dispatch time (e.g. it has been closed because the application context is shutting down)
- **THEN** the service SHALL treat the `RejectedExecutionException` by marking the run as FAILED with error category `RESOURCE_LIMIT` and code `EXECUTOR_REJECTED` and notify SSE clients

#### Scenario: Context close interrupts in-flight jobs
- **WHEN** the application context is closed while run jobs are executing
- **THEN** the executor SHALL interrupt the in-flight job threads immediately rather than waiting for the runs to finish; any run left in a non-terminal status SHALL be reconciled at the next startup

### Requirement: Database schema for test suite runs
The service SHALL create a `test_suite_runs` table via Flyway migration to persist run records.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the Flyway migration is applied
- **THEN** the `test_suite_runs` table SHALL be created with columns: `id` (VARCHAR(36), PK), `test_suite_id` (VARCHAR(36), NOT NULL, FK to test_suites.id ON DELETE CASCADE), `test_run_name` (VARCHAR(255), NOT NULL), `status` (VARCHAR(20), NOT NULL), `run_config` (JSONB, NOT NULL), `number_of_test_cases` (INTEGER, NOT NULL), `started_at_ms` (BIGINT, nullable — epoch ms), `completed_at_ms` (BIGINT, nullable — epoch ms), `error_message` (TEXT, nullable), `error_details` (JSONB, nullable), `created_at_ms` (BIGINT, NOT NULL — epoch ms), `updated_at_ms` (BIGINT, NOT NULL — epoch ms). Column names follow the project `_ms` suffix convention for epoch millisecond columns.

#### Scenario: Indexes and constraints
- **WHEN** the migration is applied
- **THEN** indexes SHALL be created on: `test_suite_id`, `status`, and `created_at_ms DESC`. A UNIQUE constraint SHALL be created on `(test_suite_id, test_run_name)`.

#### Scenario: Sequence for auto-generated names
- **WHEN** the migration is applied
- **THEN** a PostgreSQL sequence `test_suite_run_name_seq` SHALL be created for generating monotonically increasing run numbers used in auto-generated names

#### Scenario: Foreign key cascade
- **WHEN** a test suite is deleted
- **THEN** all related test suite runs SHALL be automatically deleted via ON DELETE CASCADE

### Requirement: Configuration properties
The service SHALL expose configurable properties for SSE, execution settings, retry defaults, and concurrent run limits under the `test-suite-run` prefix, and SHALL honour Spring Boot's `spring.threads.virtual.enabled` as the thread mode of the run job executor and of every run's worker executor.
Status: **Implemented**

#### Scenario: Executor properties
- **WHEN** a deployment YAML still contains `test-suite-run.executor.core-pool-size`, `test-suite-run.executor.max-pool-size` or `test-suite-run.executor.queue-capacity`
- **THEN** the application SHALL start normally and the properties SHALL have no effect (the run job executor is thread-per-run with no pool or queue)

#### Scenario: Thread mode property
- **WHEN** the application starts
- **THEN** it SHALL read `spring.threads.virtual.enabled` (environment variable `VIRTUAL_THREADS_ENABLED`, default `true`) and use it to decide whether run job threads and run worker threads are virtual (`true`) or platform (`false`) threads

#### Scenario: SSE properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.sse.timeout-minutes` (default 30) and `test-suite-run.sse.cleanup-interval-ms` (default 300000)

#### Scenario: Execution defaults and limits
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.execution.default-concurrency-level` (default 1), `test-suite-run.execution.max-concurrency-level` (default 50), `test-suite-run.execution.default-request-timeout-ms` (default 30000), `test-suite-run.execution.max-request-timeout-ms` (default 600000), `test-suite-run.execution.result-batch-size` (default 100), and `test-suite-run.execution.max-response-size-bytes` (default 5242880)

#### Scenario: Cancellation grace period property is removed
- **WHEN** a deployment YAML still contains `test-suite-run.execution.cancellation-grace-period-ms`
- **THEN** the application SHALL start normally and the property SHALL have no effect (cancellation interrupts in-flight work immediately; there is no drain period to configure)

#### Scenario: Retry defaults and limits
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.retry.default-max-retries` (default 0), `test-suite-run.retry.max-max-retries` (default 10), `test-suite-run.retry.default-retry-delay-ms` (default 1000), `test-suite-run.retry.max-retry-delay-ms` (default 60000 — serves dual role: validation ceiling for user-provided `retryDelayMs` AND cap on computed exponential backoff delay), `test-suite-run.retry.default-retry-backoff-multiplier` (default 2.0), and `test-suite-run.retry.max-retry-backoff-multiplier` (default 10.0)

#### Scenario: Concurrent run limit properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.limits.max-concurrent-runs-global` (default 20) and `test-suite-run.limits.max-concurrent-runs-per-suite` (default 5)

#### Scenario: Run config validation properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.run-config.max-number-of-runs` (default 64) for the service-level validation ceiling on `numberOfRuns`

#### Scenario: Execution header blacklist property
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.execution.header-blacklist` — a list of header names that are system-managed and cannot be set by users via `requestTemplate.headers`. Default: `[Authorization, Host, Content-Length, Transfer-Encoding, Connection, X-Correlation-Id]`.

### Requirement: Test run name auto-generation
When `testRunName` is not provided in the run configuration, the service SHALL auto-generate a unique, human-readable name using a monotonically increasing sequence. The sequence SHALL never reuse values, even after run deletion. A UNIQUE constraint on `(test_suite_id, test_run_name)` SHALL be enforced at the database level.
Status: **Implemented**

#### Scenario: Auto-generated name when not provided
- **WHEN** client triggers a run without `testRunName` in `runConfig`
- **THEN** system SHALL auto-generate a unique name using a PostgreSQL sequence (e.g., `"Run #42"` where 42 is the next value from the sequence)

#### Scenario: User-provided name used as-is
- **WHEN** client triggers a run with `testRunName: "My Regression Test"` in `runConfig`
- **THEN** system SHALL use `"My Regression Test"` as the run's `testRunName`

#### Scenario: Sequence survives deletion
- **WHEN** "Run #5" is deleted and a new run is created without specifying a name
- **THEN** system SHALL generate "Run #6" (or the next sequence value), never reusing "Run #5"

#### Scenario: Unique constraint on test suite + name
- **WHEN** client triggers a run with a `testRunName` that already exists for another run in the same test suite
- **THEN** system SHALL respond with HTTP 409 and error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: Same name allowed across different test suites
- **WHEN** two different test suites each have a run named "Run #1"
- **THEN** system SHALL allow both (uniqueness is per test suite, not global)

### Requirement: Number of test cases snapshot
When a run is created, the service sets a preliminary `numberOfTestCases` from the suite's runnable count (valid, and matching `testCaseFilter` when set). This value is **finalized at the snapshot phase** (not immutable at creation): the snapshot phase counts inserted `test_case_run_inputs` rows and updates `number_of_test_cases` to that exact count before the run transitions to RUNNING.
Status: **Implemented**

#### Scenario: Snapshot on run creation
- **WHEN** client triggers a run for a test suite whose bound dataset has 50 runnable test cases (out of e.g. 60 total)
- **THEN** the created run SHALL have `numberOfTestCases: 50` (preliminary; finalized at snapshot phase to match exactly inserted input rows)

#### Scenario: Suite changes after run creation
- **WHEN** test cases are added or removed from the test suite after a run is created
- **THEN** the run's `numberOfTestCases` SHALL reflect the count captured at snapshot phase (which runs before RUNNING state)

#### Scenario: No runnable test cases
- **WHEN** client triggers a run for a test suite that has 0 runnable test cases
- **THEN** the created run SHALL have `numberOfTestCases: 0`

### Requirement: Suite snapshot field
The `TestSuiteRunResponseDto` SHALL include a `suiteSnapshot` field containing the frozen suite configuration at the time the run's snapshot phase executed.
Status: **Implemented**

#### Scenario: Suite snapshot in detail endpoint
- **WHEN** client calls `GET /api/v1/test-suite-runs/{id}`
- **THEN** response SHALL include `suiteSnapshot` (object or null); if the snapshot phase has committed, it SHALL be non-null and contain `snapshotVersion`, `suiteType`, and all execution-relevant fields for that suite type

#### Scenario: Suite snapshot excluded from list endpoint
- **WHEN** client calls `GET /api/v1/test-suite-runs` (list)
- **THEN** response SHALL NOT include `suiteSnapshot` for each item (the field SHALL be null/absent); this is intentional — the list tier uses a reduced SELECT that excludes the JSONB column to avoid TOAST overhead

### Requirement: Update test suite run properties
The service SHALL provide `PATCH /api/v1/test-suite-runs/{id}` to update mutable properties of a test suite run. Currently, only `testRunName` is mutable. This endpoint is extensible for future mutable properties.
Status: **Implemented**

#### Scenario: Update testRunName
- **WHEN** client calls `PATCH /api/v1/test-suite-runs/{id}` with body `{ "testRunName": "New Name" }`
- **THEN** system SHALL update the run's `testRunName`, update `updatedAt`, and return HTTP 200 with the updated `TestSuiteRunResponseDto`

#### Scenario: Update with empty or null testRunName rejected
- **WHEN** client calls `PATCH /api/v1/test-suite-runs/{id}` with `testRunName` as null or blank
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Update with duplicate testRunName
- **WHEN** client calls `PATCH /api/v1/test-suite-runs/{id}` with a `testRunName` that already exists for another run in the same test suite
- **THEN** system SHALL respond with HTTP 409 and error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: Update non-existent run
- **WHEN** client calls `PATCH /api/v1/test-suite-runs/{id}` for a non-existent id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

#### Scenario: Update any-status run
- **WHEN** client calls `PATCH /api/v1/test-suite-runs/{id}` for a run in any status (PENDING, RUNNING, CANCELLING, COMPLETED, FAILED, CANCELLED)
- **THEN** system SHALL allow the update (mutable properties like `testRunName` are not status-dependent)

### Requirement: Startup reconciliation of orphaned runs
The service SHALL reconcile orphaned runs on application startup. In-memory state (run executors, SSE emitters) is lost on restart, so any runs left in a non-terminal status (PENDING, RUNNING or CANCELLING) are no longer being executed. The reconciliation strategy is a pluggable seam; the active implementation marks orphaned PENDING/RUNNING runs as FAILED and orphaned CANCELLING runs as CANCELLED.
Status: **Implemented**

#### Scenario: Mark orphaned runs as FAILED on startup
- **WHEN** the application starts and there are `test_suite_runs` records with status PENDING or RUNNING
- **THEN** system SHALL update all such records to status FAILED, set `completedAt` to the current timestamp, set `error_message` to a descriptive message (e.g., "Run was orphaned due to application restart"), and set `error_details` with category `INTERNAL` and code `ORPHANED_RUN`

#### Scenario: Finalize orphaned CANCELLING runs as CANCELLED on startup
- **WHEN** the application starts and there are `test_suite_runs` records with status CANCELLING
- **THEN** system SHALL update all such records to status CANCELLED and set `completedAt` to the current timestamp, with no error message or error details — the user asked for the cancellation and the restart merely completed it

#### Scenario: No orphaned runs
- **WHEN** the application starts and there are no runs with status PENDING, RUNNING or CANCELLING
- **THEN** system SHALL complete reconciliation without modifying any records

#### Scenario: Reconciliation logging
- **WHEN** reconciliation completes
- **THEN** system SHALL log the count of orphaned runs that were marked as FAILED and the count finalized as CANCELLED (at INFO level if any were found, at DEBUG level if none)

#### Scenario: Reconciliation runs before accepting requests
- **WHEN** the application starts
- **THEN** reconciliation SHALL complete before the service begins accepting new run creation requests (e.g., via `@EventListener(ApplicationReadyEvent.class)` or `SmartLifecycle`)

### Requirement: Header blacklist validation at suite save time
The system SHALL validate `requestTemplate.headers` against the configured header blacklist when a test suite is created or updated. Suites with blacklisted headers SHALL be marked as invalid with a validation warning.
Status: **Implemented**

#### Scenario: Blacklisted header detected during save
- **WHEN** a test suite is created or updated with `requestTemplate.headers` that include a header on the system blacklist (e.g., `Authorization`)
- **THEN** the suite SHALL be marked `isValid = false` with a `validationWarning` describing which header(s) are blacklisted (e.g., "Header 'Authorization' is system-managed and cannot be set in request template"). Blacklist comparison SHALL be **case-insensitive** (HTTP headers are case-insensitive per RFC 7230).

#### Scenario: No blacklisted headers
- **WHEN** a test suite is created or updated with `requestTemplate.headers` that contain no blacklisted headers
- **THEN** this validation check SHALL pass (other validation rules still apply)

#### Scenario: Multiple blacklisted headers
- **WHEN** a test suite's `requestTemplate.headers` includes multiple blacklisted headers (e.g., `Authorization` and `Host`)
- **THEN** ALL blacklisted headers SHALL be reported in the `validationWarning` (not just the first one)

#### Scenario: Validation integrated with existing pipeline
- **WHEN** header blacklist validation is performed
- **THEN** it SHALL be integrated into the existing `SuiteValidationService` pipeline alongside other validation checks (e.g., JSONata expression validation, deployment validation)

## Implementation Notes
- Controller: `com.epam.aidial.evaluation.web.controller.TestSuiteRunController`
- SSE Controller: `com.epam.aidial.evaluation.web.controller.TestSuiteRunSseController`
- Service: `com.epam.aidial.evaluation.service.domain.TestSuiteRunService`
- SSE Service: `com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService`
- Reconciliation: `com.epam.aidial.evaluation.service.domain.TestSuiteRunReconciliation`
- Job: `com.epam.aidial.evaluation.service.domain.job.TestSuiteEvaluationJob`
- Repository: `com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository`
- RecordMapper: `com.epam.aidial.evaluation.data.db.mapper.TestSuiteRunRecordMapper`
- Model: `com.epam.aidial.evaluation.data.db.model.TestSuiteRun`
- Enum: `com.epam.aidial.evaluation.data.db.model.RunStatus`
- DTOs: `TestSuiteRunRequestDto`, `TestSuiteRunResponseDto`, `TestSuiteRunUpdateDto`, `RunConfigDto`, `ExecutionSettingsDto`, `RetryPolicyDto`, `RunErrorDetailsDto`, `SseStatusEventDto` (all in `service.domain.dto`)
- Mapper: `com.epam.aidial.evaluation.service.domain.mapper.TestSuiteRunMapper`
- Constants: `com.epam.aidial.evaluation.constants.TestSuiteRunConstants`
- Configuration: `com.epam.aidial.evaluation.configuration.properties.TestSuiteRunProperties`, `com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties`
- Async config: `com.epam.aidial.evaluation.configuration.AsyncConfiguration` — `testSuiteRunExecutor` is a
  `SimpleAsyncTaskExecutor` (thread-per-run, `test-suite-run-` prefix, `ContextPropagatingTaskDecorator`,
  `cancelRemainingTasksOnClose`, no concurrency limit); its thread mode and every run's worker executor come
  from runner-core's `RunExecutorFactory` bean (`Threading.VIRTUAL.isActive(environment)`). The former
  `test-suite-run.executor.*` pool properties are gone; run concurrency is governed only by `test-suite-run.limits.*`.
- Flyway migration: `V1.6__CreateTestSuiteRunsTable.sql`
- Execution engine: See `eval-execution-engine` spec for executor, worker, and related components
- `TestSuiteRunService.createRun` calls `RunnableTestCaseSelector.countRunnable(datasetId, filterJson)`
  directly, passing the suite's `testCaseFilter` (see `suite-test-case-filter`). The former
  `RunnableTestCaseCounter` wrapper is deleted — its only remaining job was coalescing a null exclusion list.
- The message text "Suite has no valid and enabled test cases" is retained verbatim for client
  compatibility, even though "enabled" no longer refers to any stored per-suite exclusion list.
- `RunStatus` gains `CANCELLING`; `TERMINAL_STATUSES` is unchanged; a new `ACTIVE_STATUSES` set feeds
  `TestSuiteRunService.enforceConcurrencyLimits`.
- `TestSuiteRunRepository`: `updateToRunning` / `updateToCompleted` / `updateToCancelled` become status-guarded and
  return the affected-row count; new `markCancelling(id)` (RUNNING → CANCELLING, no `completed_at`) and
  `cancelOrphanedCancellingRuns()`.
- `service.domain.job.ActiveRunRegistry` (per-JVM `ConcurrentHashMap<UUID, RunHandle>`) replaces the
  `AtomicBoolean` map; `TestSuiteEvaluationJob.dispatch(...)` registers the handle then submits to the
  `testSuiteRunExecutor` bean directly (no `@Async`).
- Multi-instance deployments: the registry is per-JVM, so a cancel served by another instance only writes
  `CANCELLING`; the owning job finalizes `CANCELLED` when it finishes (guarded terminal write). Same single-instance
  assumption as startup reconciliation.
