# Eval Summary Scoring

## Purpose
This spec defines per-row overall score computation for eval summaries: deriving a `score` (and a threshold-based `passed`) for each `EvalSummary` row from the suite's effective per-row score definition (`testCaseOverallScore` when configured, else `overallScore` unless it is a `CustomFunction` — see `test-suites`; `Mean` or `WeightedMean` only), computed once per test case from the `test_case_metric_scores` entity (see `test-case-metric-score-aggregation`) and broadcast to every row of that test case. Storage and API exposure of the computed values live in `metrics-storage`; the write-path wiring into the Phase-2 flush cycle lives in `metric-evaluation`; the threshold source lives in `suite-run-snapshot`.

Status: **Implemented**

## Requirements

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
graft `test_case_id IN (:ids)`/`GROUP BY test_case_id` onto it for the batch's distinct affected test
cases, turning it into one value per test case. A metric absent from a test case's aggregated data
entirely is coalesced to zero for its term (see the dedicated scenario below); a metric that fired on only
some of the test case's rows is not affected by this at all, since its aggregated `avg` is always computed
over just the rows it fired on (see `test-case-metric-score-aggregation`).

This SHALL be issued as one SQL query per Phase-2 flush batch (not one query per row or per test case),
immediately after that batch's `EvalSummary` rows are written.

If there is no effective per-row definition (neither `testCaseOverallScore` nor a non-`CustomFunction`
`overallScore` is configured), `score` SHALL be `null` for every row.
Status: **Implemented**

#### Scenario: One query per flush batch, not per row or per test case
- **WHEN** a Phase-2 flush writes a batch of N `EvalSummary` rows, spanning M distinct test cases, for a suite with an effective per-row `Mean`/`WeightedMean` definition
- **THEN** exactly one additional SQL query SHALL be issued against `test_case_metric_scores` to compute scores for all M test cases in that batch

#### Scenario: Mean and WeightedMean produce one score per test case, shared by every row
- **WHEN** a suite's effective per-row definition is `Mean` or `WeightedMean`, and a test case has multiple `EvalSummary` rows (multiple turns/requests/reruns) in the same computation
- **THEN** every one of that test case's rows SHALL receive the identical `score`, computed once from the test case's aggregated per-metric `avg` values in `test_case_metric_scores`

#### Scenario: A metric absent from a test case's aggregated data entirely is coalesced to zero
- **WHEN** a suite's effective per-row definition is `Mean` or `WeightedMean`, and one of the configured metrics never fired for *any* of a given test case's rows in the computation (its `metric_scores` map has no entry for that metric — see `test-case-metric-score-aggregation`)
- **THEN** that metric's term SHALL be coalesced to `0` in the average, the same formula/semantics used before this change — this does not reintroduce the originally-reported bug, since a metric firing on only *some* of a test case's rows is aggregated over just those rows and is never absent from the map for that reason alone

#### Scenario: No effective score definition configured
- **WHEN** the suite's snapshotted `testCaseOverallScore` and `overallScore` are both absent
- **THEN** every row's `score` and `passed` SHALL be `null`, and no additional SQL query SHALL be issued for that batch

#### Scenario: A CustomFunction overallScore with no testCaseOverallScore yields no per-row score
- **WHEN** the suite's snapshotted `overallScore` is a `CustomFunction` and `testCaseOverallScore` is absent
- **THEN** there is no effective per-row definition — every row's `score` and `passed` SHALL be `null`, and no additional SQL query SHALL be issued for that batch — regardless of whether the `CustomFunction` would have produced a real (row-safe) or degenerate (population-dependent) value if it had been evaluated

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
- **WHEN** `score` is `null` (no effective per-row definition, or the test case has no present metric at all)
- **THEN** `passed` SHALL be `null`

## Implementation Notes
- New component: `EvalSummaryRowScoreComputer` (`com.epam.aidial.evaluation.query.service.metricscore`), a sibling of `OverallScoreDefinitionResolver` and `FilteredMetricScoreAggregator` (not an extension of the latter — that component's contract is scoped to read-only what-if recomputation, not persistence). It has no `CustomFunction` support — `Mean`/`WeightedMean` are the only reachable branches given `testCaseOverallScore`'s validation restriction; the sealed switch's `CustomFunction` case is a defensive, logged no-op.
- `OverallScoreDefinitionResolver` builds the `Mean`/`WeightedMean` query directly against `test_case_metric_scores` (no separate combiner class, no rewrite step — see `test-case-metric-score-aggregation`); `EvalSummaryRowScoreComputer` only grafts `test_case_id`/`GROUP BY` onto the already-correctly-targeted query.
- Invoked from `InProcessMetricEvaluationExecutor`'s flush cycle, right after `TestCaseMetricScoreAggregator`/`TestCaseMetricScoreAggregatedService` populate `test_case_metric_scores_aggregated` for the batch's affected test cases (the per-test-case score computation reads that table, so it must run after aggregation, not before); results are written to `test_case_eval_scores` via `TestCaseEvalScoreService.batchCreate(...)`.
- Persisted on `test_case_eval_scores`, joined into the `EvalSummary` read surface — see `metrics-storage` for the schema and API-exposure changes, and `metric-evaluation` for the write-path wiring.
- Threshold source: `SuiteSnapshotDto.overallScoreThreshold` — see `suite-run-snapshot`.
- Effective-definition source: `SuiteSnapshotDto.testCaseOverallScore` (fallback: `SuiteSnapshotDto.overallScore`, but never when `overallScore` is a `CustomFunction`), resolved by `TestSuiteEvaluationJob.buildMetricEvaluationContext`/`resolveTestCaseOverallScoreDefinition` — see `test-suites` for the suite-API field and `suite-run-snapshot` for its snapshot capture. Phase 3's run-level `overall` aggregate always uses `overallScore` directly (any of the three variants) and is never affected by `testCaseOverallScore`.
- `testCaseOverallScore`'s `CustomFunction` rejection is enforced in `TestSuiteRequestValidator`, wired into `TestSuiteService.create`/`.update` and `TestSuiteCloneService`'s effective-dto revalidation.
