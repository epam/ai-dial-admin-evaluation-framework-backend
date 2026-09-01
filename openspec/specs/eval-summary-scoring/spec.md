# Eval Summary Scoring

## Purpose
This spec defines per-row overall score computation for eval summaries: deriving a `score` (and a threshold-based `passed`) for each `EvalSummary` row by reusing the exact `StructuredQuery` a suite's `overallScore` definition (`Mean`, `WeightedMean`, or `CustomFunction`) already resolves for the run-level aggregate, grafted with a per-row `GROUP BY id`. Storage and API exposure of the computed values live in `metrics-storage`; the write-path wiring into the Phase-2 flush cycle lives in `metric-evaluation`; the threshold source lives in `suite-run-snapshot`.

Status: **Implemented**

## Requirements

### Requirement: Per-row overall score computed by reusing the resolved OverallScoreDefinition query
The system SHALL compute a per-row `score` (Double) for each `EvalSummary` by reusing the exact `StructuredQuery` that `OverallScoreDefinitionResolver` already produces for a suite's `overallScore` definition (`Mean`, `WeightedMean`, or `CustomFunction`) — grafting an `id IN (:rowIds)` filter and a `GROUP BY id` onto that query for a batch of row ids, turning the run-level aggregate into one value per row. This SHALL be issued as one SQL query per Phase-2 flush batch (not one query per row), immediately after that batch's `EvalSummary` rows are written — a second write, not a follow-up `UPDATE` on the same row.

If the suite has no `overallScore` definition configured, `score` SHALL be `null` for every row.
Status: **Implemented**

#### Scenario: One query per flush batch, not per row
- **WHEN** a Phase-2 flush writes a batch of N `EvalSummary` rows for a suite with an `overallScore` definition configured
- **THEN** exactly one additional SQL query SHALL be issued to compute scores for all N rows in that batch, scoped by `id IN (:rowIds)` and grouped by `id`

#### Scenario: Mean and WeightedMean produce a real per-row value
- **WHEN** a suite's `overallScore` is `Mean` or `WeightedMean`
- **THEN** each row's `score` SHALL be computed by grouping the same SQL expression `OverallScoreDefinitionResolver` builds for the run-level `overall`, scoped to that row's own id

#### Scenario: A row-safe CustomFunction produces a real per-row value
- **WHEN** a suite's `overallScore` is a `CustomFunction` whose resolved query is a single-column aggregate over `eval_summaries` with no pre-existing `groupBy` (e.g. a simple `avg` over one metric field)
- **THEN** the system SHALL graft the per-row `id`/`GROUP BY id` onto it exactly as for `Mean`/`WeightedMean`, and each row's `score` SHALL reflect that row's own value

#### Scenario: No overallScore definition configured
- **WHEN** the suite's snapshotted `overallScore` is absent
- **THEN** every row's `score` and `passed` SHALL be `null`, and no additional SQL query SHALL be issued for that batch

### Requirement: Grafting the per-row group is safe for any resolved query shape, with a shape guard
The system SHALL add `id` as a select column and set `groupBy = ["id"]` on the resolved query, relying on the existing Structured Query DSL builder behavior that a select column whose field name is also a `groupBy` key is resolved via that column's own output alias rather than re-translated. This SHALL require no changes to the Structured Query DSL builder itself.

Before grafting, the system SHALL verify the resolved query's shape: `entity == "eval_summaries"`, `mode == AGGREGATE`, exactly one select column with a non-blank alias, and **no pre-existing `groupBy`**. A query failing any of these checks SHALL be rejected — `score`/`passed` stay `null` for that batch, a warning SHALL be logged, and no SQL query SHALL be executed for it. A `CustomFunction` that already specifies its own `groupBy` SHALL be rejected rather than having it silently overwritten.
Status: **Implemented**

#### Scenario: Well-formed query grafts successfully
- **WHEN** the resolved query targets `eval_summaries`, is in `AGGREGATE` mode, selects exactly one aliased column, and has no `groupBy`
- **THEN** the system SHALL graft `id`/`GROUP BY id` and execute the query

#### Scenario: Wrong entity is rejected
- **WHEN** the resolved query's `entity` is not `eval_summaries`
- **THEN** `score`/`passed` SHALL be `null` for the batch, a warning SHALL be logged, and no query SHALL execute

#### Scenario: Non-aggregate mode is rejected
- **WHEN** the resolved query's `mode` is not `AGGREGATE`
- **THEN** `score`/`passed` SHALL be `null` for the batch, a warning SHALL be logged, and no query SHALL execute

#### Scenario: Multiple or unaliased select columns are rejected
- **WHEN** the resolved query selects zero or more than one column, or its single select column has no alias
- **THEN** `score`/`passed` SHALL be `null` for the batch, a warning SHALL be logged, and no query SHALL execute

#### Scenario: A CustomFunction with a pre-existing groupBy is rejected, not overwritten
- **WHEN** a `CustomFunction`'s resolved query already specifies a non-empty `groupBy`
- **THEN** the system SHALL reject it (log a warning) rather than overwrite its `groupBy` with `["id"]`

### Requirement: Population-dependent CustomFunctions degrade to a null per-row score, not an error
When a suite's `overallScore` is a `CustomFunction` that is inherently population-dependent (e.g. `roc_auc`, which needs many rows' labels/probabilities ranked against each other), grouping its resolved query by `id` SHALL still execute successfully, but its aggregate SHALL evaluate to SQL `NULL` for every row — a single-row `GROUP BY id` group cannot satisfy a function that requires a population. This is a mathematical property of the function itself (e.g. `roc_auc_score`'s own `NULLIF(n_pos * n_neg, 0)` degenerates on a single-class group), not special-cased application logic.
Status: **Implemented**

#### Scenario: roc_auc CustomFunction yields null score per row without failing the run
- **WHEN** a suite's `overallScore` is a `CustomFunction` computing `roc_auc(label, probability)`
- **THEN** every row's `score` and `passed` SHALL be `null`, and the run SHALL complete successfully with no exception

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
- **WHEN** `score` is `null` (no `overallScore` definition, or the definition's aggregate is itself degenerate for a single row) and `overallScoreThreshold` is non-null
- **THEN** `passed` SHALL be `null`

## Implementation Notes
- New component: `EvalSummaryRowScoreComputer` (`com.epam.aidial.evaluation.query.service.metricscore`), a sibling of `OverallScoreDefinitionResolver` and `FilteredMetricScoreAggregator` (not an extension of the latter — that component's contract is scoped to read-only what-if recomputation, not persistence).
- Reuses `OverallScoreDefinitionResolver.resolve(...)` unchanged — no separate implementation for `Mean`/`WeightedMean`/`CustomFunction` resolution.
- Invoked from `InProcessMetricEvaluationExecutor`'s flush cycle, right after each batch's `EvalSummaryBatchWriteClient.batchWrite(...)` call; results are written to `test_case_eval_scores` via `EvalSummaryScoreService.batchCreate(...)`.
- Persisted on `test_case_eval_scores`, joined into the `EvalSummary` read surface — see `metrics-storage` for the schema and API-exposure changes, and `metric-evaluation` for the write-path wiring.
- Threshold source: `SuiteSnapshotDto.overallScoreThreshold` — see `suite-run-snapshot`.
