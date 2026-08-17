## ADDED Requirements

### Requirement: Per-TSMD metric evaluation latency measurement

`InProcessMetricEvaluationExecutor` SHALL measure the elapsed wall-clock time of each dispatched TSMD's provider evaluation call (the `MetricEvaluationWorker.evaluate(...)` invocation, covering semaphore acquisition, request binding, and any retries/backoff), including calls that fail with a transport/interrupt error or are still in flight when the per-result timeout is reached. For each `TestCaseRunResult`, the executor SHALL compute the average of these durations across all TSMDs actually dispatched for that result (excluding TSMDs whose `condition` produced a `ConditionError`, since no provider call was made for those), and pass the result as `avgMetricEvalDurationMs` into the `EvalSummaryBatchWriteItemDto` it builds for that result.

Status: **Implemented**

#### Scenario: Successful TSMD calls contribute real latency

- **WHEN** a `TestCaseRunResult` dispatches two TSMDs and both complete successfully with elapsed times of 100ms and 300ms
- **THEN** the executor SHALL compute `avgMetricEvalDurationMs = 200` for that result's `EvalSummaryBatchWriteItemDto`

#### Scenario: Failed TSMD call still contributes its elapsed time

- **WHEN** a dispatched TSMD's provider call fails with a transport error after 500ms of retries
- **THEN** that TSMD's 500ms elapsed time SHALL be included in the average for that result, the same as a successful call would be

#### Scenario: Timed-out TSMD contributes elapsed time up to the timeout

- **WHEN** a dispatched TSMD's `CompletableFuture` has not completed by the time the per-result `allOf(...).get(timeoutMs)` join times out
- **THEN** the executor SHALL record that TSMD's elapsed time as the time from its dispatch to the timeout detection, and include it in the average

#### Scenario: Condition-error TSMDs are excluded from the average

- **WHEN** a TSMD's `condition` evaluates to a `ConditionError` (no provider call is made) while other TSMDs for the same result are dispatched normally
- **THEN** the `ConditionError` TSMD SHALL NOT contribute to the `avgMetricEvalDurationMs` average; only dispatched (`Success`/`Failure`) TSMDs are averaged

#### Scenario: No TSMDs dispatched for a result

- **WHEN** a `TestCaseRunResult` has zero TSMDs dispatched (metric-less run, or a non-SUCCESS result row propagated without evaluation)
- **THEN** `avgMetricEvalDurationMs` SHALL be `0`
