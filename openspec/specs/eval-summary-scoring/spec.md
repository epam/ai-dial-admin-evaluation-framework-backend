# Eval Summary Scoring

## Purpose
This spec defines per-row overall score computation for eval summaries: deriving a `score` (and a threshold-based `passed`) for each `EvalSummary` row from the suite's effective per-row score definition (`testCaseOverallScore` when configured, else `overallScore` unless it is a `CustomFunction` — see `test-suites`; `Mean` or `WeightedMean` only), computed once per test case from the `test_case_metric_scores` entity (see `test-case-metric-score-aggregation`) and broadcast to every row of that test case — but only when that test case's aggregated `execution_status` (computed independently across all of its rows, see below) is `SUCCESS`. Storage and API exposure of the computed values live in `metrics-storage`; the write-path wiring into the Phase-2 flush cycle lives in `metric-evaluation`; the threshold source lives in `suite-run-snapshot`.

Status: **Implemented**

## Requirements

### Requirement: Per-test-case execution status aggregation gates score computation
The system SHALL compute a per-test-case, per-computation `execution_status` (`SUCCESS` or `FAILED`) by
aggregating across **all** of that test case's `test_case_eval_summaries` rows for the computation — every
`run_index`/`request_index`/`turn_index` combination — independently of `test_case_metric_scores_aggregated`
(see `test-case-metric-score-aggregation`, whose own "absent metric = no numeric sample" aggregation
contract this does not change). The aggregate SHALL be `FAILED` if any of the test case's rows has
`execution_status <> SUCCESS`; otherwise `SUCCESS`. This computation SHALL collapse `TIMEOUT`/`ERROR`/
`FAILED` row statuses uniformly to the aggregate value `FAILED` — it does not preserve which specific
non-`SUCCESS` status occurred.

The system SHALL persist this aggregate as `test_case_eval_scores.execution_status`, written for every row
in the same upsert that writes `score`/`passed`/`computed_at_ms` (never a separate, subsequent `UPDATE`) —
subject to the same write gate as `score`/`passed`: if the suite has no effective per-row score definition,
no `test_case_eval_scores` row (and therefore no `execution_status`) is written at all (see the "No
effective score definition configured" scenario below).

When a test case's aggregate `execution_status` is `FAILED`, the system SHALL NOT compute `score` (and
therefore `passed`) for that test case at all — both SHALL be written as `null`, and the per-test-case score
SQL query SHALL NOT be issued for that test case's id.

A row whose metric was skipped by a `condition` evaluating to `false` (see `conditional-metric-execution`)
SHALL NOT be treated as a failure by this aggregation: such a row's own `execution_status` remains
`SUCCESS`, so it does not, by itself, make the test case's aggregate `FAILED`.
Status: **Implemented**

#### Scenario: All rows SUCCESS yields an aggregate SUCCESS and a computed score
- **WHEN** every one of a test case's `test_case_eval_summaries` rows for a computation has `execution_status = SUCCESS`
- **THEN** `test_case_eval_scores.execution_status` SHALL be `SUCCESS` for that test case, and `score`/`passed` SHALL be computed as before (per the "Mean and WeightedMean produce one score" scenario)

#### Scenario: A metric execution failure on one row fails the whole test case's aggregate
- **WHEN** one of a test case's rows has a metric output field with a `null` value (e.g. `{"Exact Match": {"exact_match": null}}`), which already sets that row's own `execution_status` to `FAILED` (see `metric-evaluation`), while its other rows are `SUCCESS`
- **THEN** `test_case_eval_scores.execution_status` SHALL be `FAILED` for that test case, and `score`/`passed` SHALL both be `null` — the per-test-case score query SHALL NOT be issued for this test case

#### Scenario: A condition-skipped metric does not fail the aggregate
- **WHEN** a test case's rows each have one metric omitted from `metric_values` because its `condition` evaluated to `false` (e.g. `{"Ragas: Answer Relevancy": {"score": 0.0}}` with `"Exact Match"` absent), and every row's own `execution_status` remains `SUCCESS`
- **THEN** `test_case_eval_scores.execution_status` SHALL be `SUCCESS` for that test case, and `score`/`passed` SHALL be computed normally from whatever metrics did fire

#### Scenario: An upstream execution failure also fails the aggregate
- **WHEN** one of a test case's rows has `execution_status = TIMEOUT` or `ERROR`, propagated from a failed `TestCaseRunResult` (see `metric-evaluation`), with no metrics evaluated for that row
- **THEN** `test_case_eval_scores.execution_status` SHALL be `FAILED` for that test case (collapsing `TIMEOUT`/`ERROR` to `FAILED`), and `score`/`passed` SHALL both be `null`

#### Scenario: A fully-failed test case with zero numeric metric samples still gets a row
- **WHEN** a test case's rows are all non-`SUCCESS` (so its aggregate `execution_status` is `FAILED`), leaving no numeric samples in `test_case_metric_scores_aggregated` for that test case at all, but the suite has an effective per-row score definition
- **THEN** the system SHALL still write a `test_case_eval_scores` row for that test case with `execution_status = FAILED` and `score = null` — it SHALL NOT be silently absent from the table

#### Scenario: A SUCCESS test case with zero numeric metric samples stays absent, unchanged
- **WHEN** a test case's rows are all `SUCCESS` but every configured metric was condition-skipped on every row, leaving no numeric samples in `test_case_metric_scores_aggregated` for that test case at all
- **THEN** the system SHALL NOT write a `test_case_eval_scores` row for that test case — it stays absent from both `test_case_metric_scores_aggregated` and `test_case_eval_scores`, exactly as before this change; only a `FAILED` aggregate forces a row to exist despite having no numeric samples

### Requirement: Per-row overall score computed from per-test-case aggregated metric scores
The system SHALL compute a per-row `score` (Double) for each `EvalSummary`, sourced from the suite's
effective per-row score definition (`Mean` or `WeightedMean` — `CustomFunction` is not a valid
`testCaseOverallScore`, see the dedicated requirement below). The effective per-row definition is
resolved once per run, before Phase 2 starts, by `TestSuiteEvaluationJob`: the suite's snapshotted
`testCaseOverallScore` when present, otherwise its snapshotted `overallScore` **unless `overallScore` is a
`CustomFunction`**, in which case there is no effective per-row definition (see `test-suites`,
`suite-run-snapshot`).

The system SHALL compute the score **per test case**, not per row: every `EvalSummary` row belonging to
the same test case (in the same computation) SHALL receive one, identical score. For `Mean` or
`WeightedMean`, the system SHALL build the query directly against the `test_case_metric_scores` entity
(see `test-case-metric-score-aggregation`) — `avg(metric_scores::<name>::avg)` per configured metric,
composed into the same formula used for the run-level `overall` (see `metric-score-statistics`) — then
graft `test_case_id IN (:ids)`/`GROUP BY test_case_id` onto it, but **only for the test cases whose
per-test-case `execution_status` aggregate (see the dedicated requirement above) is `SUCCESS`** —
turning it into one value per (non-failed) test case. A metric absent from a test case's aggregated data
entirely is coalesced to zero for its term (see the dedicated scenario below); a metric that fired on only
some of the test case's rows is not affected by this at all, since its aggregated `avg` is always computed
over just the rows it fired on (see `test-case-metric-score-aggregation`).

This SHALL be issued as one SQL query per chunk of test cases (not one query per row or per test case),
scoped only to that chunk's `SUCCESS`-aggregate test cases, after the last Phase-2 flush has written all
`EvalSummary` rows and once the chunk's `execution_status` aggregates are computed.

If there is no effective per-row definition (neither `testCaseOverallScore` nor a non-`CustomFunction`
`overallScore` is configured), `score` SHALL be `null` for every row. If a test case's `execution_status`
aggregate is `FAILED`, `score` SHALL be `null` for every row of that test case regardless of whether an
effective per-row definition is configured.
Status: **Implemented**

#### Scenario: One query per chunk, not per row or per test case
- **WHEN** a run's `EvalSummary` rows span M distinct test cases (M no larger than the batch size), for a suite with an effective per-row `Mean`/`WeightedMean` definition, and all M test cases have a `SUCCESS` execution-status aggregate
- **THEN** exactly one additional SQL query SHALL be issued against `test_case_metric_scores` to compute scores for all M test cases

#### Scenario: Mean and WeightedMean produce one score per test case, shared by every row
- **WHEN** a suite's effective per-row definition is `Mean` or `WeightedMean`, and a test case has multiple `EvalSummary` rows (multiple turns/requests/reruns) in the same computation, and that test case's execution-status aggregate is `SUCCESS`
- **THEN** every one of that test case's rows SHALL receive the identical `score`, computed once from the test case's aggregated per-metric `avg` values in `test_case_metric_scores`

#### Scenario: A metric absent from a test case's aggregated data entirely is coalesced to zero
- **WHEN** a suite's effective per-row definition is `Mean` or `WeightedMean`, and one of the configured metrics never fired for *any* of a given test case's rows in the computation (its `metric_scores` map has no entry for that metric — see `test-case-metric-score-aggregation`), and the test case's execution-status aggregate is `SUCCESS`
- **THEN** that metric's term SHALL be coalesced to `0` in the average, the same formula/semantics used before this change — this does not reintroduce the originally-reported bug, since a metric firing on only *some* of a test case's rows is aggregated over just those rows and is never absent from the map for that reason alone

#### Scenario: No effective score definition configured
- **WHEN** the suite's snapshotted `testCaseOverallScore` and `overallScore` are both absent
- **THEN** every row's `score` and `passed` SHALL be `null`, and no additional SQL query SHALL be issued for that batch

#### Scenario: A CustomFunction overallScore with no testCaseOverallScore yields no per-row score
- **WHEN** the suite's snapshotted `overallScore` is a `CustomFunction` and `testCaseOverallScore` is absent
- **THEN** there is no effective per-row definition — every row's `score` and `passed` SHALL be `null`, and no additional SQL query SHALL be issued for that batch — regardless of whether the `CustomFunction` would have produced a real (row-safe) or degenerate (population-dependent) value if it had been evaluated

#### Scenario: A test case with a FAILED execution-status aggregate is excluded from the score query
- **WHEN** a Phase-2 flush batch contains test cases with a mix of `SUCCESS` and `FAILED` execution-status aggregates, for a suite with an effective per-row `Mean`/`WeightedMean` definition
- **THEN** the per-test-case score SQL query SHALL be scoped only to the `SUCCESS`-aggregate test case ids; the `FAILED`-aggregate test cases SHALL receive `score = null` without their ids appearing in that query

### Requirement: testCaseOverallScore accepts only Mean or WeightedMean
The system SHALL reject a `CustomFunction` `testCaseOverallScore` with a hard 400 `ValidationException` at
suite create, update, and clone revalidation (`TestSuiteRequestValidator.validateTestCaseOverallScore`).
`overallScore` is unaffected by this restriction and MAY still be any `OverallScoreDefinition`, including
`CustomFunction`. This restriction exists because a population-dependent function (e.g. `roc_auc`) is
meaningless for a single test case, and `Mean`/`WeightedMean` are already structurally metric-only (a
`WeightedMetric` names only a `metricName`/`outputField` pair) — there is no valid per-test-case use for an
arbitrary `CustomFunction` expression.
Status: **Implemented**

#### Scenario: Suite create is rejected when testCaseOverallScore is a CustomFunction
- **WHEN** a suite create request sets `testCaseOverallScore` to a `CustomFunction`
- **THEN** the request SHALL be rejected with HTTP 400 and the suite SHALL NOT be created

#### Scenario: Suite update is rejected when testCaseOverallScore is a CustomFunction
- **WHEN** a suite update (or clone revalidation) request sets `testCaseOverallScore` to a `CustomFunction`
- **THEN** the request SHALL be rejected with HTTP 400 and the suite SHALL NOT be updated

#### Scenario: overallScore may still be a CustomFunction
- **WHEN** a suite create or update request sets `overallScore` to a `CustomFunction` and leaves `testCaseOverallScore` unset or sets it to `Mean`/`WeightedMean`
- **THEN** the request SHALL succeed

### Requirement: Pass/fail derived from score and threshold
The system SHALL derive a per-row `passed` (Boolean) as `score >= threshold`, where `threshold` is the suite's `overallScoreThreshold` as captured in the run's snapshot at run-start time (not the suite's current live value). If either `score` or `threshold` is `null`, `passed` SHALL be `null`. This comparison SHALL be performed in application code, not SQL.
Status: **Implemented**

#### Scenario: Score meets the threshold exactly
- **WHEN** a row's computed `score` equals the snapshotted `overallScoreThreshold`
- **THEN** `passed` SHALL be `true`

#### Scenario: Score below the threshold
- **WHEN** a row's computed `score` is less than the snapshotted `overallScoreThreshold`
- **THEN** `passed` SHALL be `false`

#### Scenario: No threshold configured
- **WHEN** the suite's snapshotted `overallScoreThreshold` is `null`, regardless of whether `score` is computed
- **THEN** `passed` SHALL be `null`

#### Scenario: No score computed
- **WHEN** `score` is `null` (no effective per-row definition, the test case has no present metric at all, or the test case's execution-status aggregate is `FAILED`)
- **THEN** `passed` SHALL be `null`

## Implementation Notes
- New component: `EvalSummaryRowScoreComputer` (`com.epam.aidial.evaluation.query.service.metricscore`), a sibling of `OverallScoreDefinitionResolver` and `FilteredMetricScoreAggregator` (not an extension of the latter — that component's contract is scoped to read-only what-if recomputation, not persistence). It has no `CustomFunction` support — `Mean`/`WeightedMean` are the only reachable branches given `testCaseOverallScore`'s validation restriction; the sealed switch's `CustomFunction` case is a defensive, logged no-op.
- `OverallScoreDefinitionResolver` builds the `Mean`/`WeightedMean` query directly against `test_case_metric_scores` (no separate combiner class, no rewrite step — see `test-case-metric-score-aggregation`); `EvalSummaryRowScoreComputer` only grafts `test_case_id`/`GROUP BY` onto the already-correctly-targeted query.
- `TestCaseExecutionStatusAggregator` (`com.epam.aidial.evaluation.query.service.metricscore`, a sibling of `TestCaseMetricScoreAggregator`) computes the per-test-case `execution_status` aggregate by querying `test_case_eval_summaries` directly (`GROUP BY test_case_id`, `bool_or(execution_status <> 'SUCCESS')`) — independent of `test_case_metric_scores_aggregated`, since that table's `jsonb_each`-driven aggregation never sees a row with empty `metric_values`. Invoked from `InProcessMetricEvaluationExecutor.writeRowScores` before `EvalSummaryRowScoreComputer`, so the SUCCESS/FAILED split is known before the score query is built.
- Invoked from `InProcessMetricEvaluationExecutor` once after the last flush, per chunk of test cases, right after `TestCaseMetricScoreAggregator`/`TestCaseMetricScoreAggregatedService` populate `test_case_metric_scores_aggregated` for the chunk (the per-test-case score computation reads that table, so it must run after aggregation, not before); results are written to `test_case_eval_scores` via `TestCaseEvalScoreService.batchInsert(...)`, in the same insert as `execution_status`.
- Persisted on `test_case_eval_scores`, joined into the `EvalSummary` read surface — see `metrics-storage` for the schema and API-exposure changes, and `metric-evaluation` for the write-path wiring.
- Threshold source: `SuiteSnapshotDto.overallScoreThreshold` — see `suite-run-snapshot`.
- Effective-definition source: `SuiteSnapshotDto.testCaseOverallScore` (fallback: `SuiteSnapshotDto.overallScore`, but never when `overallScore` is a `CustomFunction`), resolved by `TestSuiteEvaluationJob.buildMetricEvaluationContext`/`resolveTestCaseOverallScoreDefinition` — see `test-suites` for the suite-API field and `suite-run-snapshot` for its snapshot capture. Phase 3's run-level `overall` aggregate always uses `overallScore` directly (any of the three variants) and is never affected by `testCaseOverallScore`.
- `testCaseOverallScore`'s `CustomFunction` rejection is enforced in `TestSuiteRequestValidator`, wired into `TestSuiteService.create`/`.update` and `TestSuiteCloneService`'s effective-dto revalidation.
