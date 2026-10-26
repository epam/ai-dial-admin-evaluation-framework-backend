## MODIFIED Requirements

### Requirement: TestSuiteRunResponseDto structure
The response DTO for a test suite run SHALL include all relevant run information, including the extended `runConfig` with execution and retry settings.
Status: **Implemented**

#### Scenario: Response fields
- **WHEN** system returns a `TestSuiteRunResponseDto`
- **THEN** it SHALL include: `id` (UUID), `testSuiteId` (UUID), `testRunName` (String — user-provided or auto-generated), `status` (String — one of PENDING, RUNNING, CANCELLING, COMPLETED, FAILED, CANCELLED), `runConfig` (object with `numberOfRuns`, optional `testRunName`, optional `execution`, optional `retry`), `numberOfTestCases` (int — finalized at snapshot phase to match `test_case_run_inputs` row count), `startedAt` (Long, nullable, epoch ms — null while the run is PENDING; set when status becomes RUNNING; for a run that ends without ever having been RUNNING it equals `completedAt`), `completedAt` (Long, nullable, epoch ms — set when status becomes COMPLETED, FAILED, or CANCELLED), `errorMessage` (String, nullable — user-friendly error message for FAILED runs), `errorDetails` (object, nullable — structured error info for FAILED runs), `suiteSnapshot` (object, nullable — frozen suite configuration at snapshot phase; null in list results, non-null in detail after snapshot committed), `createdAt` (Long, epoch ms), `updatedAt` (Long, epoch ms)

#### Scenario: Error details structure for FAILED runs
- **WHEN** a run has status FAILED and `errorDetails` is non-null
- **THEN** `errorDetails` SHALL contain: `code` (String — machine-readable error code), `category` (String — one of VALIDATION, TIMEOUT, RESOURCE_LIMIT, TEST_SUITE_ERROR, INTERNAL), `message` (String — user-friendly description), `details` (object, nullable — additional context)

#### Scenario: Null fields for non-terminal runs
- **WHEN** a run has status PENDING, RUNNING or CANCELLING
- **THEN** `completedAt`, `errorMessage`, and `errorDetails` SHALL be null

#### Scenario: Start date null only while PENDING
- **WHEN** a run has status PENDING
- **THEN** `startedAt` SHALL be null
- **AND** every run with a status other than PENDING SHALL have a non-null `startedAt`

#### Scenario: Run that ended without executing has startedAt equal to completedAt
- **WHEN** a run ends directly from PENDING — failed by startup reconciliation, failed by a snapshot-phase failure, failed because the DIAL Core route trigger failed, or cancelled while PENDING
- **THEN** its `startedAt` SHALL equal its `completedAt` (zero duration)

### Requirement: Cancel a test suite run
The service SHALL provide `POST /api/v1/test-suite-runs/{id}/cancel` to cancel a run that has not yet reached a terminal status. PENDING, RUNNING and CANCELLING runs MAY be targeted; the request SHALL make the cancellation observable to API clients immediately, before the async job has stopped.
Status: **Implemented**

#### Scenario: Cancel PENDING run
- **WHEN** client calls `POST /api/v1/test-suite-runs/{id}/cancel` for a run with status PENDING
- **THEN** system SHALL set status to CANCELLED, set `completedAt` and set `startedAt` to the same value, notify SSE clients, and return HTTP 200 with the updated `TestSuiteRunResponseDto`

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

### Requirement: Startup reconciliation of orphaned runs
The service SHALL reconcile orphaned runs on application startup. In-memory state (run executors, SSE emitters) is lost on restart, so any runs left in a non-terminal status (PENDING, RUNNING or CANCELLING) are no longer being executed. The reconciliation strategy is a pluggable seam; the active implementation marks orphaned PENDING/RUNNING runs as FAILED and orphaned CANCELLING runs as CANCELLED.
Status: **Implemented**

#### Scenario: Mark orphaned runs as FAILED on startup
- **WHEN** the application starts and there are `test_suite_runs` records with status PENDING or RUNNING
- **THEN** system SHALL update all such records to status FAILED, set `completedAt` to the current timestamp, set `error_message` to a descriptive message (e.g., "Run was orphaned due to application restart"), and set `error_details` with category `INTERNAL` and code `ORPHANED_RUN`
- **AND** a record that was PENDING (no `startedAt`) SHALL get `startedAt` equal to `completedAt`, while a record that was RUNNING SHALL keep its existing `startedAt`

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
