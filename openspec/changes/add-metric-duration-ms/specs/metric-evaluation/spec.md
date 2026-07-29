## ADDED Requirements

### Requirement: Per-row metric evaluation duration measurement
The metric evaluation executor SHALL measure the wall-clock duration of each result row's metric evaluation and carry it on that row's eval summary as `metricDurationMs`. The value SHALL be obtained from the injected `java.time.Clock`.
Status: **Planned**

#### Scenario: Measured window
- **WHEN** the executor evaluates metrics for a SUCCESS result row
- **THEN** `metricDurationMs` SHALL be the elapsed milliseconds from before the first metric's `condition` evaluation through completion of the per-row join and the timeout/missing-TSMD reconciliation — thereby including condition evaluation, async dispatch, and provider-concurrency (semaphore) waiting

#### Scenario: Not a sum of provider call durations
- **WHEN** a row dispatches multiple TSMDs that execute concurrently
- **THEN** `metricDurationMs` SHALL be the elapsed wall-clock time for the row, which MAY be less than the sum of the individual provider call durations

#### Scenario: Row where metric evaluation never ran
- **WHEN** a result row has `executionStatus != SUCCESS` and its eval summary is produced by propagation without evaluating metrics
- **THEN** `metricDurationMs` SHALL be `null`

#### Scenario: Row where every metric was condition-skipped
- **WHEN** every TSMD's `condition` evaluates to `false` for a SUCCESS row, so no provider call is dispatched
- **THEN** `metricDurationMs` SHALL be a non-null measured value (typically `0`), because evaluation did run

#### Scenario: Row that timed out
- **WHEN** the per-row join exceeds `perResultTimeoutMs` and the executor records timeout failures
- **THEN** `metricDurationMs` SHALL be at least `perResultTimeoutMs`

#### Scenario: Cancelled row writes nothing
- **WHEN** the run's cancellation signal is set before a row's evaluation begins
- **THEN** no eval summary item SHALL be produced for that row, and therefore no `metricDurationMs` SHALL be recorded

#### Scenario: Measurement never fails a row
- **WHEN** metric evaluation for a row ends by timeout, execution exception, or thread interruption
- **THEN** `metricDurationMs` SHALL still be recorded, and the measurement SHALL NOT alter the row's `executionStatus`, `metricValues`, or `metricInfos`

#### Scenario: Time source
- **WHEN** the executor measures the duration
- **THEN** it SHALL read time from the injected `Clock` bean, and SHALL NOT call `System.currentTimeMillis()` or `Instant.now()`

## MODIFIED Requirements

### Requirement: EvalSummary assembly from TestCaseRunResult
The system SHALL build one EvalSummary per TestCaseRunResult, copying context fields from the result and adding computed metric values plus the row's measured metric evaluation duration.
Status: **Planned**

#### Scenario: Field mapping from result to summary
- **WHEN** an EvalSummary is built for a TestCaseRunResult
- **THEN** the batch write envelope SHALL carry `testSuiteId`, `testSuiteRunId`, `computationId`, and `computedAtMs` from the MetricEvaluationContext. Each item SHALL carry: `testCaseRunResultId` = result.id, `testCaseId`, `testCaseName`, `runIndex`, `testCaseData`, `extractedColumns`, `execDurationMs`, `responseStatusCode` from result, plus `metricDurationMs` measured by the executor for that row (not copied from the result). The `createdAtMs` is derived by the service from the run's creation timestamp (not set per-item).

#### Scenario: Non-SUCCESS result propagation
- **WHEN** a TestCaseRunResult has `executionStatus != SUCCESS`
- **THEN** the EvalSummary SHALL have `executionStatus` propagated from the result, `metricValues = {}`, `metricInfos = null`, `metricDurationMs = null` — no metric evaluation SHALL be attempted

#### Scenario: Metric error determines executionStatus
- **WHEN** all metrics evaluate successfully (no `type: "error"` outputs)
- **THEN** the EvalSummary SHALL have `executionStatus = SUCCESS`

#### Scenario: Any metric error or transport failure fails the summary
- **WHEN** at least one metric output field has `type: "error"` OR at least one TSMD evaluation fails with a transport error (worker exception)
- **THEN** the EvalSummary SHALL have `executionStatus = FAILED`

## Implementation notes

- Measurement lives in `service/domain/job/InProcessMetricEvaluationExecutor.java`: a new `java.time.Clock` constructor dependency (bean from `ClockConfiguration`), two `clock.millis()` reads inside `evaluateAndBuild`, and a `Long metricDurationMs` parameter on `buildItem`; `buildPropagatedItem` passes `null`.
- No new component is introduced — there is no conversion or validation logic to isolate, and `TsmdEvaluationResult` / `MetricOutputMapper` are unchanged because the measurement is per row, not per metric.
- `Clock.fixed` cannot express elapsed time, so unit tests require a ticking clock stub.
