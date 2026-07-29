## MODIFIED Requirements

### Requirement: Evaluation job orchestration
The `TestSuiteEvaluationJob` SHALL delegate to `EvaluationExecutor.execute()` for deployment evaluation (Phase 1) and `MetricEvaluationExecutor.execute()` for metric evaluation (Phase 2), following a consistent pattern: build context, then call execute. Both executors are interfaces with in-process implementations (`InProcessEvaluationExecutor`, `InProcessMetricEvaluationExecutor`). The cancellation signal SHALL be registered before async dispatch to prevent race conditions. Configuration value resolution SHALL use `ObjectUtils.defaultIfNull` from Apache Commons Lang.
Status: **Planned**

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
- **THEN** the job SHALL build a `MetricEvaluationContext` (loading aggregated TSMDs, generating computationId/computedAtMs, building provider semaphores) and call `MetricEvaluationExecutor.execute(context)`. The executor SHALL run for any TSMD count, including zero — with an empty TSMD list it writes eval summaries with empty `metric_values` and no run metric snapshots (see metric-evaluation spec).

#### Scenario: Run without metrics still yields results
- **WHEN** a run's suite has no enabled+valid TSMDs
- **AND** the eval-summary batch write succeeds (as for a metric-bearing run, a failed analytics write sets the cancellation signal and the run ends CANCELLED)
- **THEN** the run SHALL still reach COMPLETED and its eval summaries SHALL be readable through the eval-summary list, count, aggregate, and export endpoints

#### Scenario: Cancellation between phases
- **WHEN** the deployment evaluation phase completes and the cancellation signal is set before metric evaluation starts
- **THEN** the job SHALL skip metric evaluation and transition to CANCELLED status

#### Scenario: Metric evaluation failure does not fail the run
- **WHEN** the metric evaluation phase encounters errors (provider unavailable, individual metric errors)
- **THEN** the run SHALL still transition to COMPLETED. Individual metric errors are captured per-EvalSummary row (`executionStatus = FAILED` with error details in `metricInfos`).

## Implementation notes

- `com.epam.aidial.evaluation.service.domain.job.TestSuiteEvaluationJob` — unchanged. Phase 2 is already invoked unconditionally for a non-cancelled run, and Phase 3 (`MetricScoreComputation`) already skips cleanly when no numeric metric fields are discovered. The behavior change lives entirely in `InProcessMetricEvaluationExecutor`.
