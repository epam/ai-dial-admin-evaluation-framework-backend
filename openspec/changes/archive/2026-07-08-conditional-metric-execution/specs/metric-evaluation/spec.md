## ADDED Requirements

### Requirement: Metric evaluation honors the metric's execution condition
The system SHALL, before dispatching a metric for a test-case result, evaluate that metric's
`condition` (when non-blank) against the namespaced dictionary and gate evaluation on the result: run
on clean boolean `true`, omit on clean boolean `false`, and on any other outcome
skip-with-surfaced-error. A blank/null condition SHALL always run. Condition evaluation SHALL run
synchronously on the orchestrating thread before any async dispatch (the `ConditionContext` is
read-only and never accessed from worker threads).
Status: **Planned**

#### Scenario: Metric runs when condition is true
- **WHEN** a metric's condition evaluates to `true` for a test-case result
- **THEN** the metric SHALL be dispatched and its output SHALL appear in that result's `metricValues`

#### Scenario: Skipped metric is omitted from the eval summary
- **WHEN** a metric's condition evaluates to `false` for a test-case result
- **THEN** that metric SHALL have no entry in the result's `metricValues` or `metricInfos`; because the
  metric key is absent, JSONB numeric extraction yields NULL and the metric is naturally excluded from
  the NULL-skipping Phase-3 SQL aggregates — no Phase-3 code change is made

#### Scenario: Condition error does not fail the test-case result
- **WHEN** a metric's condition throws, returns a non-boolean, or returns null at runtime
- **THEN** the metric SHALL NOT be evaluated, a metric-level `{error}` SHALL be recorded under
  `metricInfos` (rendered as `metricError::<name>`), no `metricValues` entry SHALL be written for it,
  and the summary's `executionStatus` SHALL remain `SUCCESS`

#### Scenario: Blank condition preserves prior behavior
- **WHEN** a metric has a null or blank condition
- **THEN** it SHALL be evaluated for every test-case result as before

## MODIFIED Requirements

### Requirement: Typed TSMD evaluation result carrier
The system SHALL represent a per-TSMD evaluation outcome with a sealed interface `TsmdEvaluationResult`
in `service.domain.job`. It SHALL permit three variants — `Success`, `Failure`, and `ConditionError` —
each carrying `outputFieldNames` (`List<String>`) extracted from the TSMD's output schema.
Status: **Planned**

#### Scenario: Sealed interface with three variants
- **WHEN** a TSMD evaluation completes
- **THEN** the result SHALL be represented as `TsmdEvaluationResult.Success(EvaluationResponseDto response, List<String> outputFieldNames)`, `TsmdEvaluationResult.Failure(Exception error, List<String> outputFieldNames)`, or `TsmdEvaluationResult.ConditionError(String message, List<String> outputFieldNames)`

#### Scenario: ConditionError produced when a condition fails
- **WHEN** a metric's `condition` throws, returns a non-boolean, or returns null
- **THEN** the executor SHALL record a `ConditionError(message, outputFieldNames)` for that TSMD and SHALL NOT dispatch the `/evaluate` call

#### Scenario: Output field names extracted before evaluation dispatch
- **WHEN** the metric evaluation executor starts execution
- **THEN** it SHALL extract output field names for each TSMD using `OutputSchemaFieldExtractor` before dispatching async evaluations, and include them in every `TsmdEvaluationResult`

#### Scenario: MetricOutputMapper consumes typed results
- **WHEN** `MetricOutputMapper.buildMetricValues()` and `buildMetricInfos()` are called
- **THEN** they SHALL accept `Map<String, TsmdEvaluationResult>` and use pattern matching on the sealed type (no `instanceof Object` checks)

#### Scenario: checkForErrors uses typed results and ignores ConditionError
- **WHEN** `checkForErrors()` determines whether any TSMD evaluation failed
- **THEN** it SHALL accept `Map<String, TsmdEvaluationResult>` and count `Failure` instances or `Success` instances containing error-type metric outputs, and it SHALL NOT treat a `ConditionError` as a failure

#### Scenario: TSMD with empty field names (defense-in-depth)
- **WHEN** a TSMD's output schema yields an empty field name list (should not happen after validation)
- **THEN** the output mapper SHALL produce an empty object `{}` in `metricValues` for that TSMD and record the error only in `metricInfos`

#### Scenario: Timeout fallback produces Failure with field names
- **WHEN** a TSMD evaluation times out and no result was recorded
- **THEN** the executor SHALL record a `Failure` with a `RuntimeException` and the pre-extracted output field names for that TSMD

### Requirement: Output mapping to metricValues and metricInfos
The system SHALL map `EvaluationResponse` output fields to EvalSummary's `metricValues` and `metricInfos` JSONB columns, keyed by TSMD name. Output field names SHALL always come from the metric's actual output schema, never from synthetic placeholder keys. A metric skipped by a false condition SHALL have no entry in either column; a metric with a `ConditionError` SHALL have no `metricValues` entry and a wholesale metric-level `metricInfos` error entry.
Status: **Planned**

#### Scenario: Value output without details
- **WHEN** an output field has `{type: "value", value: 1}` with no details
- **THEN** `metricValues[tsmdName][fieldName]` SHALL be `1` and no entry SHALL be added to metricInfos for that field

#### Scenario: Value output with details
- **WHEN** an output field has `{type: "value", value: 0.8, details: {"reason": "..."}}`
- **THEN** `metricValues[tsmdName][fieldName]` SHALL be `0.8` and `metricInfos[tsmdName][fieldName]` SHALL be `{"reason": "..."}`

#### Scenario: Error output
- **WHEN** an output field has `{type: "error", message: "Invalid pattern"}`
- **THEN** `metricValues[tsmdName][fieldName]` SHALL be `null` (explicit JSON null, preserved via ObjectNode.putNull) and `metricInfos[tsmdName][fieldName]` SHALL be `{"error": "Invalid pattern"}`

#### Scenario: Transport failure for a TSMD
- **WHEN** the `/evaluate` call for a TSMD fails with a transport error (HTTP 500, timeout, all retries exhausted) and the TSMD's output schema has field names `["recall", "precision", "f1", "mrr"]`
- **THEN** `metricValues[tsmdName]` SHALL contain `{"recall": null, "precision": null, "f1": null, "mrr": null}` (all output fields set to null) and `metricInfos[tsmdName]` SHALL contain `{"recall": {"error": "<exception message>"}, "precision": {"error": "<exception message>"}, "f1": {"error": "<exception message>"}, "mrr": {"error": "<exception message>"}}` (error entry per output field)

#### Scenario: Transport failure with empty output schema (fallback)
- **WHEN** the `/evaluate` call for a TSMD fails with a transport error AND the TSMD's output schema has no extractable field names (null, malformed, or empty properties)
- **THEN** `metricValues[tsmdName]` SHALL be an empty object `{}` and `metricInfos[tsmdName]` SHALL contain `{"error": "<exception message>"}`. The system SHALL log a WARN indicating the TSMD has an invalid output schema.

> **Clarifying note**: This fallback is the sole remaining exception to the per-field error format in `metricInfos`. It uses `{"error": "message"}` directly under the TSMD name (without a field-name wrapper) because no output field names are available. This case is defense-in-depth only — output schema validation (see tsmd-validation spec) prevents TSMDs with invalid schemas from entering evaluation under normal operation.

#### Scenario: Multiple TSMDs merged into single EvalSummary
- **WHEN** a test case has TSMDs "Accuracy" and "RAG Quality" both evaluated
- **THEN** `metricValues` SHALL contain keys for both TSMD names: `{"Accuracy": {...}, "RAG Quality": {...}}`

#### Scenario: Metric skipped by false condition has no entry
- **WHEN** a metric's condition evaluates to `false` for a test-case result
- **THEN** neither `metricValues` nor `metricInfos` SHALL contain a key for that TSMD

#### Scenario: ConditionError renders as a wholesale metricError
- **WHEN** a metric's evaluation result is a `ConditionError` with message `M`
- **THEN** `metricValues` SHALL contain no key for that TSMD, and `metricInfos[tsmdName]` SHALL be the wholesale node `{"error": M}` (no per-field wrapper), which the export renders as the `metricError::<tsmdName>` column. This reuses the existing wholesale-error shape and therefore relies on `error` not being one of the metric's output-schema field names.

### Requirement: EvalSummary assembly from TestCaseRunResult
The system SHALL build one EvalSummary per TestCaseRunResult, copying context fields from the result and adding computed metric values. The `extractedColumns` value SHALL be copied from the result in its stored shape without normalization (a column-major object of per-column arrays for a multi-step result; an object of scalars for a single-step result).
Status: **Planned**

#### Scenario: Field mapping from result to summary
- **WHEN** an EvalSummary is built for a TestCaseRunResult
- **THEN** the batch write envelope SHALL carry `testSuiteId`, `testSuiteRunId`, `computationId`, and `computedAtMs` from the MetricEvaluationContext. Each item SHALL carry: `testCaseRunResultId` = result.id, `testCaseId`, `testCaseName`, `runIndex`, `testCaseData`, `extractedColumns`, `execDurationMs`, `responseStatusCode` from result. The `createdAtMs` is derived by the service from the run's creation timestamp (not set per-item).

#### Scenario: Multi-step extractedColumns stored verbatim
- **WHEN** an EvalSummary is built for a multi-step result whose `extractedColumns` is `{"answer": ["Paris","Tokio"]}`
- **THEN** `EvalSummary.extractedColumns` SHALL store `{"answer": ["Paris","Tokio"]}` unchanged (no collapse to a single step)

#### Scenario: Non-SUCCESS result propagation
- **WHEN** a TestCaseRunResult has `executionStatus != SUCCESS`
- **THEN** the EvalSummary SHALL have `executionStatus` propagated from the result, `metricValues = {}`, `metricInfos = null` — no metric evaluation SHALL be attempted

#### Scenario: Metric error determines executionStatus
- **WHEN** all metrics evaluate successfully (no `type: "error"` outputs)
- **THEN** the EvalSummary SHALL have `executionStatus = SUCCESS`

#### Scenario: Any metric error or transport failure fails the summary
- **WHEN** at least one metric output field has `type: "error"` OR at least one TSMD evaluation fails with a transport error (worker exception)
- **THEN** the EvalSummary SHALL have `executionStatus = FAILED`

#### Scenario: Condition skip or ConditionError does not fail the summary
- **WHEN** the only non-successful outcomes for a result are metrics skipped by a false condition (absent) and/or `ConditionError` entries
- **THEN** the EvalSummary `executionStatus` SHALL remain `SUCCESS`
