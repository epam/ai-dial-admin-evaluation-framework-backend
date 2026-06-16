# Test Suite Runs — Delta Spec

## MODIFIED Requirements

### Requirement: Evaluation job orchestration
The `TestSuiteEvaluationJob` SHALL delegate to `EvaluationExecutor.execute()` for deployment evaluation (Phase 1) and `MetricEvaluationExecutor.execute()` for metric evaluation (Phase 2), following a consistent pattern: build context, then call execute. Both executors are interfaces with in-process implementations (`InProcessEvaluationExecutor`, `InProcessMetricEvaluationExecutor`). The cancellation signal SHALL be registered before async dispatch to prevent race conditions. Configuration value resolution SHALL use `ObjectUtils.defaultIfNull` from Apache Commons Lang.
Status: **Implemented** (deployment evaluation) / **Planned** (metric evaluation chaining)

#### Scenario: Job delegates to executor
- **WHEN** `TestSuiteEvaluationJob.executeRunAsync(runId)` is called
- **THEN** it SHALL construct an `EvaluationContext` from the run's `RunConfigDto` (with system defaults for omitted fields) and call `evaluationExecutor.execute(context)`

#### Scenario: Cancellation signal registered before dispatch
- **WHEN** `TestSuiteRunService` triggers an evaluation run
- **THEN** `TestSuiteEvaluationJob.registerCancellationSignal(runId)` SHALL be called synchronously BEFORE calling the `@Async executeRunAsync(runId, token)`. The `@Async` annotation means the entire `executeRunAsync()` body runs in the executor thread, so signal registration must happen in the caller's thread to prevent `interruptRun(runId)` from silently losing the cancellation signal.

#### Scenario: Status notifications
- **WHEN** the evaluation job transitions between statuses (PENDING->RUNNING, RUNNING->COMPLETED/FAILED/CANCELLED)
- **THEN** it SHALL notify all matching SSE clients of each transition

#### Scenario: Cancellation during execution
- **WHEN** the run is cancelled while the evaluation job is running
- **THEN** the cancellation signal SHALL be propagated to the executor, which handles graceful shutdown (see eval-execution-engine spec)

#### Scenario: Configuration resolution uses ObjectUtils
- **WHEN** the job resolves run configuration values with system defaults
- **THEN** it SHALL use `ObjectUtils.defaultIfNull(value, default)` from Apache Commons Lang instead of custom `resolveInt`/`resolveLong`/`resolveDouble` static methods

#### Scenario: Metric evaluation chained after deployment evaluation
- **WHEN** the deployment evaluation phase completes (all test cases executed) and the run is not cancelled
- **THEN** the job SHALL build a `MetricEvaluationContext` (loading aggregated TSMDs, generating computationId/computedAtMs, building provider semaphores) and call `MetricEvaluationExecutor.execute(context)`. The executor handles the "no TSMDs" case by returning early without writing any records.

#### Scenario: Cancellation between phases
- **WHEN** the deployment evaluation phase completes and the cancellation signal is set before metric evaluation starts
- **THEN** the job SHALL skip metric evaluation and transition to CANCELLED status

#### Scenario: Metric evaluation failure does not fail the run
- **WHEN** the metric evaluation phase encounters errors (provider unavailable, individual metric errors)
- **THEN** the run SHALL still transition to COMPLETED. Individual metric errors are captured per-EvalSummary row (`executionStatus = FAILED` with error details in `metricInfos`).

### Requirement: Run status lifecycle
Each test suite run SHALL have a status that follows a defined lifecycle. Valid statuses are: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`. Status transitions SHALL be enforced.
Status: **Implemented** (base lifecycle) / **Planned** (extended for metric evaluation)

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
- **THEN** the status SHALL transition: RUNNING -> CANCELLED (the async job thread SHALL be interrupted). Cancellation may occur during either the deployment evaluation or metric evaluation phase.

#### Scenario: Terminal status is immutable
- **WHEN** a run has reached a terminal status (COMPLETED, FAILED, or CANCELLED)
- **THEN** no further status transitions SHALL occur
