# Test Suite Runs

## Purpose
This spec describes asynchronous test suite run execution, real-time status tracking via SSE, and run lifecycle management (create, list, get, cancel, delete).

Status: **Planned**

## Key Terms
- **TestSuiteRun**: A single execution of a test suite, created asynchronously with a run configuration. Progresses through a status lifecycle: PENDING, RUNNING, COMPLETED, FAILED, CANCELLED.
- **RunConfig**: Configuration for a run; initially contains `numberOfRuns` and optional `testRunName` (extensible via JSONB).
- **Mock evaluation job**: A placeholder job that sleeps for a random duration (0-60s) and randomly fails (20% probability). Will be replaced by actual evaluation logic in a future change.
- **SSE status stream**: A Server-Sent Events endpoint that pushes run status updates to connected clients, with optional filtering.
- **Startup reconciliation**: A process that runs on application startup to recover orphaned runs (PENDING/RUNNING) that were lost due to a crash or restart. In the current mock implementation, these are marked as FAILED; future implementations may re-enqueue or reconnect.

## ADDED Requirements

### Requirement: Trigger a test suite run
The service SHALL provide `POST /api/v1/test-suites/{testSuiteId}/runs` to create and trigger a new test suite run. The endpoint SHALL validate the request, persist a run record with status PENDING (including `testRunName` and `numberOfTestCases` snapshot), dispatch an async job, and return the run details immediately (without waiting for job completion).
Status: **Planned**

#### Scenario: Successful run trigger
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` with a valid `RunConfigDto` body and the test suite exists
- **THEN** system SHALL create a `test_suite_runs` record with status `PENDING`, populate `testRunName` (from config or auto-generated), snapshot `numberOfTestCases` from the test suite's current enabled and valid test case count (`is_enabled = true AND is_valid = true`), dispatch an async evaluation job on the dedicated executor, and return HTTP 202 Accepted with the `TestSuiteRunResponseDto`

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
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/runs` for a test suite that has `valid = false` (failed validation)
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message indicating the test suite is not in a valid state

#### Scenario: Executor rejects job submission
- **WHEN** the run is created successfully but the dedicated executor's queue is full and max pool size is reached at async dispatch time
- **THEN** the run SHALL have been persisted with status PENDING and HTTP 202 returned to the client. The service SHALL catch `RejectedExecutionException` in the post-commit callback, mark the run as FAILED with error category `RESOURCE_LIMIT` and code `EXECUTOR_REJECTED`, and log a warning

### Requirement: Run configuration model
The run request body SHALL contain a `runConfig` object. `runConfig` SHALL support: `numberOfRuns` (integer, required, `@Min(1)`, validated against a configurable maximum in the service layer, default 64) and `testRunName` (String, optional — a user-provided name for the run). The `runConfig` SHALL be stored as JSONB to allow future extension without schema migration.
Status: **Planned**

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

### Requirement: Run status lifecycle
Each test suite run SHALL have a status that follows a defined lifecycle. Valid statuses are: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`. Status transitions SHALL be enforced.
Status: **Planned**

#### Scenario: Normal successful lifecycle
- **WHEN** a run is created and the async job completes without error
- **THEN** the status SHALL transition: PENDING -> RUNNING -> COMPLETED

#### Scenario: Failed lifecycle
- **WHEN** a run is created and the async job encounters an error
- **THEN** the status SHALL transition: PENDING -> RUNNING -> FAILED, and `error_message` and `error_details` SHALL be populated

#### Scenario: Cancelled from PENDING
- **WHEN** a run with status PENDING is cancelled before the async job starts
- **THEN** the status SHALL transition: PENDING -> CANCELLED

#### Scenario: Cancelled from RUNNING
- **WHEN** a run with status RUNNING is cancelled via the cancellation API
- **THEN** the status SHALL transition: RUNNING -> CANCELLED (the async job thread SHALL be interrupted)

#### Scenario: Terminal status is immutable
- **WHEN** a run has reached a terminal status (COMPLETED, FAILED, or CANCELLED)
- **THEN** no further status transitions SHALL occur

### Requirement: List test suite runs (paginated)
The service SHALL provide `GET /api/v1/test-suite-runs` to list runs with filtering, sorting, and pagination.
Status: **Planned**

#### Scenario: Default pagination
- **WHEN** client calls `GET /api/v1/test-suite-runs` without pagination params
- **THEN** response SHALL be a `PageResponseDto<TestSuiteRunResponseDto>` with default `page=0` and `size=100` (matching project-wide pagination default)

#### Scenario: Pagination bounds
- **WHEN** client calls `GET /api/v1/test-suite-runs?page=<p>&size=<s>`
- **THEN** `page` SHALL be >= 0 and `size` SHALL be between 1 and the configured maximum page size

#### Scenario: Filter by testSuiteId
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=testSuiteId:eq:<uuid>`
- **THEN** system SHALL return only runs belonging to that test suite

#### Scenario: Filter by status
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=status:eq:RUNNING`
- **THEN** system SHALL return only runs with status RUNNING

#### Scenario: Filter by testRunName
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=testRunName:eq:My Regression Test`
- **THEN** system SHALL return only runs with matching `testRunName`

#### Scenario: Filter by createdAt range
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=createdAt:gte:1735689600000&filter=createdAt:lt:1738368000000`
- **THEN** system SHALL return only runs created within the specified epoch ms range

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
Status: **Planned**

#### Scenario: Existing run
- **WHEN** client calls `GET /api/v1/test-suite-runs/{id}` for an existing run
- **THEN** system SHALL return the `TestSuiteRunResponseDto` with all fields

#### Scenario: Non-existent run
- **WHEN** client calls `GET /api/v1/test-suite-runs/{id}` for a non-existent id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

### Requirement: TestSuiteRunResponseDto structure
The response DTO for a test suite run SHALL include all relevant run information.
Status: **Planned**

#### Scenario: Response fields
- **WHEN** system returns a `TestSuiteRunResponseDto`
- **THEN** it SHALL include: `id` (UUID), `testSuiteId` (UUID), `testRunName` (String — user-provided or auto-generated), `status` (String — one of PENDING, RUNNING, COMPLETED, FAILED, CANCELLED), `runConfig` (object with `numberOfRuns` and optional `testRunName`), `numberOfTestCases` (int — snapshot of enabled and valid test cases at run creation), `startedAt` (Long, nullable, epoch ms — set when status becomes RUNNING), `completedAt` (Long, nullable, epoch ms — set when status becomes COMPLETED, FAILED, or CANCELLED), `errorMessage` (String, nullable — user-friendly error message for FAILED runs), `errorDetails` (object, nullable — structured error info for FAILED runs), `createdAt` (Long, epoch ms), `updatedAt` (Long, epoch ms)

#### Scenario: Error details structure for FAILED runs
- **WHEN** a run has status FAILED and `errorDetails` is non-null
- **THEN** `errorDetails` SHALL contain: `code` (String — machine-readable error code), `category` (String — one of VALIDATION, TIMEOUT, RESOURCE_LIMIT, TEST_SUITE_ERROR, INTERNAL), `message` (String — user-friendly description), `details` (object, nullable — additional context)

#### Scenario: Null fields for non-terminal runs
- **WHEN** a run has status PENDING or RUNNING
- **THEN** `completedAt`, `errorMessage`, and `errorDetails` SHALL be null

### Requirement: Cancel a test suite run
The service SHALL provide `POST /api/v1/test-suite-runs/{id}/cancel` to cancel a run that has not yet reached a terminal status. Both PENDING and RUNNING runs MAY be cancelled.
Status: **Planned**

#### Scenario: Cancel PENDING run
- **WHEN** client calls `POST /api/v1/test-suite-runs/{id}/cancel` for a run with status PENDING
- **THEN** system SHALL set status to CANCELLED, set `completedAt`, and return HTTP 200 with the updated `TestSuiteRunResponseDto`

#### Scenario: Cancel RUNNING run
- **WHEN** client calls `POST /api/v1/test-suite-runs/{id}/cancel` for a run with status RUNNING
- **THEN** system SHALL interrupt the job thread, and the async job SHALL detect the interruption and set status to CANCELLED. System SHALL return HTTP 200 with the current `TestSuiteRunResponseDto` (status may still be RUNNING momentarily until the job detects cancellation)

#### Scenario: Cancel already-terminal run
- **WHEN** client calls `POST /api/v1/test-suite-runs/{id}/cancel` for a run with status COMPLETED, FAILED, or CANCELLED
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message indicating the run cannot be cancelled in its current status

#### Scenario: Cancel PENDING race condition with async job start
- **WHEN** client calls `POST /api/v1/test-suite-runs/{id}/cancel` for a PENDING run at the exact moment the async job transitions it to RUNNING
- **THEN** system SHALL use optimistic SQL update (`WHERE status = 'PENDING'`); if the row was already transitioned to RUNNING, it SHALL fall through to RUNNING cancellation (thread interruption)

#### Scenario: Cancel non-existent run
- **WHEN** client calls `POST /api/v1/test-suite-runs/{id}/cancel` for a non-existent id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

### Requirement: Delete a test suite run
The service SHALL provide `DELETE /api/v1/test-suite-runs/{id}` to delete a run and its related resources. Only runs in a terminal status (COMPLETED, FAILED, CANCELLED) MAY be deleted. PENDING and RUNNING runs MUST be cancelled first.
Status: **Planned**

#### Scenario: Delete terminal run
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a run with status COMPLETED, FAILED, or CANCELLED
- **THEN** system SHALL delete the run record (and any future related resources via CASCADE) and return HTTP 204 No Content

#### Scenario: Delete RUNNING run rejected
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a run with status RUNNING
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message suggesting to cancel the run first

#### Scenario: Delete PENDING run rejected
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a run with status PENDING
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message indicating that PENDING runs must complete or be cancelled before deletion

#### Scenario: Delete non-existent run
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a non-existent id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

#### Scenario: Cascade delete on test suite removal
- **WHEN** a test suite is deleted via `DELETE /api/v1/test-suites/{id}`
- **THEN** all associated test suite runs SHALL be deleted automatically via database CASCADE

### Requirement: SSE status stream
The service SHALL provide `GET /api/v1/test-suite-runs/status-stream` as a Server-Sent Events endpoint for real-time run status updates. Clients MAY filter which updates they receive via query parameters.
Status: **Planned**

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
Status: **Planned**

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
The service SHALL enforce configurable limits on the number of concurrent (PENDING + RUNNING) runs, both globally and per test suite.
Status: **Planned**

#### Scenario: Within global limit
- **WHEN** the total PENDING + RUNNING run count is below the configured global maximum (default 20)
- **THEN** system SHALL allow creating a new run

#### Scenario: Global limit exceeded
- **WHEN** the total PENDING + RUNNING run count equals or exceeds the configured global maximum
- **THEN** system SHALL reject the run creation with HTTP 429 and include `activeRunsGlobal` and `maxRunsGlobal` in error `details`

#### Scenario: Within per-suite limit
- **WHEN** the PENDING + RUNNING run count for a specific test suite is below the configured per-suite maximum (default 5)
- **THEN** system SHALL allow creating a new run for that suite

#### Scenario: Per-suite limit exceeded
- **WHEN** the PENDING + RUNNING run count for a specific test suite equals or exceeds the configured per-suite maximum
- **THEN** system SHALL reject the run creation with HTTP 429 and include `activeRunsForSuite` and `maxRunsPerSuite` in error `details`

### Requirement: Mock evaluation job
The async evaluation job SHALL simulate long-running processing with configurable randomized duration and failure probability. This mock implementation enables end-to-end testing of the run infrastructure before actual evaluation logic is implemented.
Status: **Planned**

#### Scenario: Random duration
- **WHEN** the mock job starts executing
- **THEN** it SHALL sleep for a random duration between the configured minimum (default 0ms) and maximum (default 60000ms), polling for thread interruption in small intervals (500ms chunks)

#### Scenario: Random failure
- **WHEN** the mock job completes its sleep without interruption
- **THEN** it SHALL randomly fail with the configured probability (default 20%), setting status to FAILED with error category INTERNAL and a message indicating simulated failure

#### Scenario: Successful completion
- **WHEN** the mock job completes its sleep and does not randomly fail
- **THEN** it SHALL set status to COMPLETED

#### Scenario: Cancellation during execution
- **WHEN** the mock job's thread is interrupted during sleep
- **THEN** it SHALL catch `InterruptedException`, restore the interrupt flag, set status to CANCELLED, and notify SSE clients

#### Scenario: Status notifications
- **WHEN** the mock job transitions between statuses (PENDING->RUNNING, RUNNING->COMPLETED/FAILED/CANCELLED)
- **THEN** it SHALL notify all matching SSE clients of each transition

### Requirement: Dedicated async executor
The service SHALL use a dedicated `ThreadPoolTaskExecutor` bean (named `testSuiteRunExecutor`) for test suite run jobs, separate from the default async executor.
Status: **Planned**

#### Scenario: Executor isolation
- **WHEN** test suite run jobs are dispatched
- **THEN** they SHALL execute on the dedicated executor, not the default Spring async executor

#### Scenario: Configurable pool settings
- **WHEN** the application starts
- **THEN** the executor SHALL be configured with core pool size, max pool size, and queue capacity from application properties (defaults: core=5, max=10, queue=50)

#### Scenario: Rejected execution handling
- **WHEN** the executor's queue is full and max pool size is reached
- **THEN** the executor SHALL reject the job submission (via `AbortPolicy`), the service SHALL catch `RejectedExecutionException`, mark the run as FAILED with error category `RESOURCE_LIMIT` and code `EXECUTOR_REJECTED`, and notify SSE clients

#### Scenario: Thread naming
- **WHEN** the executor creates threads
- **THEN** thread names SHALL use the prefix `test-suite-run-` for identification in logs and monitoring

### Requirement: Database schema for test suite runs
The service SHALL create a `test_suite_runs` table via Flyway migration to persist run records.
Status: **Planned**

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
The service SHALL expose configurable properties for executor, SSE, mock job, and concurrent run limits under the `test-suite-run` prefix.
Status: **Planned**

#### Scenario: Executor properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.executor.core-pool-size` (default 5), `test-suite-run.executor.max-pool-size` (default 10), and `test-suite-run.executor.queue-capacity` (default 50)

#### Scenario: SSE properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.sse.timeout-minutes` (default 30) and `test-suite-run.sse.cleanup-interval-ms` (default 300000)

#### Scenario: Mock job properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.mock-job.min-duration-ms` (default 0), `test-suite-run.mock-job.max-duration-ms` (default 60000), and `test-suite-run.mock-job.failure-probability` (default 0.20)

#### Scenario: Concurrent run limit properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.limits.max-concurrent-runs-global` (default 20) and `test-suite-run.limits.max-concurrent-runs-per-suite` (default 5)

#### Scenario: Run config validation properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.run-config.max-number-of-runs` (default 64) for the service-level validation ceiling on `numberOfRuns`

### Requirement: Test run name auto-generation
When `testRunName` is not provided in the run configuration, the service SHALL auto-generate a unique, human-readable name using a monotonically increasing sequence. The sequence SHALL never reuse values, even after run deletion. A UNIQUE constraint on `(test_suite_id, test_run_name)` SHALL be enforced at the database level.
Status: **Planned**

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
When a run is created, the service SHALL snapshot the count of enabled and valid test cases (`is_enabled = true AND is_valid = true`) in the test suite at that moment and store it as `numberOfTestCases` on the run. This is a preliminary value set at run creation time; in a future iteration, it will be computed from actual test results after execution completes. The value is immutable after creation.
Status: **Planned**

#### Scenario: Snapshot on run creation
- **WHEN** client triggers a run for a test suite that has 50 enabled and valid test cases (out of e.g. 60 total)
- **THEN** the created run SHALL have `numberOfTestCases: 50`

#### Scenario: Suite changes after run creation
- **WHEN** test cases are added or removed from the test suite after a run is created
- **THEN** the run's `numberOfTestCases` SHALL remain unchanged (reflects the snapshot at creation time)

#### Scenario: No enabled and valid test cases
- **WHEN** client triggers a run for a test suite that has 0 enabled and valid test cases
- **THEN** the created run SHALL have `numberOfTestCases: 0`

#### Scenario: Future: computed from results
- **WHEN** actual evaluation logic is implemented (future change)
- **THEN** `numberOfTestCases` MAY be updated to reflect the actual count derived from test results rather than the startup snapshot

### Requirement: Update test suite run properties
The service SHALL provide `PATCH /api/v1/test-suite-runs/{id}` to update mutable properties of a test suite run. Currently, only `testRunName` is mutable. This endpoint is extensible for future mutable properties.
Status: **Planned**

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
- **WHEN** client calls `PATCH /api/v1/test-suite-runs/{id}` for a run in any status (PENDING, RUNNING, COMPLETED, FAILED, CANCELLED)
- **THEN** system SHALL allow the update (mutable properties like `testRunName` are not status-dependent)

### Requirement: Startup reconciliation of orphaned runs
The service SHALL reconcile orphaned runs on application startup. In-memory state (thread tracking, SSE emitters) is lost on restart, so any runs left in non-terminal status (PENDING or RUNNING) are no longer being executed. The reconciliation strategy is pluggable; the current mock implementation marks all orphaned runs as FAILED. Future implementations MAY re-enqueue runs or reconnect to an external executor.
Status: **Planned**

#### Scenario: Mark orphaned runs as FAILED on startup
- **WHEN** the application starts and there are `test_suite_runs` records with status PENDING or RUNNING
- **THEN** system SHALL update all such records to status FAILED, set `completedAt` to the current timestamp, set `error_message` to a descriptive message (e.g., "Run was orphaned due to application restart"), and set `error_details` with category `INTERNAL` and code `ORPHANED_RUN`

#### Scenario: No orphaned runs
- **WHEN** the application starts and there are no runs with status PENDING or RUNNING
- **THEN** system SHALL complete reconciliation without modifying any records

#### Scenario: Reconciliation logging
- **WHEN** reconciliation completes
- **THEN** system SHALL log the count of orphaned runs that were marked as FAILED (at INFO level if any were found, at DEBUG level if none)

#### Scenario: Reconciliation runs before accepting requests
- **WHEN** the application starts
- **THEN** reconciliation SHALL complete before the service begins accepting new run creation requests (e.g., via `@EventListener(ApplicationReadyEvent.class)` or `SmartLifecycle`)

#### Scenario: Future extensibility
- **WHEN** a real executor replaces the mock implementation
- **THEN** the reconciliation strategy MAY be changed to re-enqueue orphaned runs or query the external executor for their actual status, without modifying the reconciliation trigger mechanism

## Implementation Notes
- Controller: `com.epam.aidial.evaluation.web.controller.TestSuiteRunController`
- SSE Controller: `com.epam.aidial.evaluation.web.controller.TestSuiteRunSseController`
- Service: `com.epam.aidial.evaluation.service.domain.TestSuiteRunService`
- SSE Service: `com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService`
- Reconciliation: `com.epam.aidial.evaluation.service.domain.TestSuiteRunReconciliation`
- Job: `com.epam.aidial.evaluation.service.domain.job.TestSuiteEvaluationJob`
- Repository: `com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository`
- RowMapper: `com.epam.aidial.evaluation.data.db.mapper.TestSuiteRunRowMapper`
- Model: `com.epam.aidial.evaluation.data.db.model.TestSuiteRun`
- Enum: `com.epam.aidial.evaluation.data.db.model.RunStatus`
- DTOs: `TestSuiteRunRequestDto`, `TestSuiteRunResponseDto`, `TestSuiteRunUpdateDto`, `RunConfigDto`, `RunErrorDetailsDto`, `SseStatusEventDto` (all in `service.domain.dto`)
- Mapper: `com.epam.aidial.evaluation.service.domain.mapper.TestSuiteRunMapper`
- Constants: `com.epam.aidial.evaluation.constants.TestSuiteRunConstants`
- Configuration: `com.epam.aidial.evaluation.configuration.properties.TestSuiteRunProperties`
- Async config: `com.epam.aidial.evaluation.configuration.AsyncConfiguration`
- Flyway migration: `V1.6__CreateTestSuiteRunsTable.sql`
