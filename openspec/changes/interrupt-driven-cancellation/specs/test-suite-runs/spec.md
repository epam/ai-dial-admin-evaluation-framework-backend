## MODIFIED Requirements

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

### Requirement: Configuration properties
The service SHALL expose configurable properties for executor, SSE, execution settings, retry defaults, and concurrent run limits under the `test-suite-run` prefix.
Status: **Implemented**

#### Scenario: Executor properties
- **WHEN** the application starts
- **THEN** it SHALL read `test-suite-run.executor.core-pool-size` (default 5), `test-suite-run.executor.max-pool-size` (default 10), and `test-suite-run.executor.queue-capacity` (default 50)

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

## Implementation notes
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
