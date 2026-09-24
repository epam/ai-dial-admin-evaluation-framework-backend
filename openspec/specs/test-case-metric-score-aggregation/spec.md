# Test Case Metric Score Aggregation

## Purpose
This spec defines the per-test-case, per-computation aggregation of raw eval-summary metric values —
avg/min/max/count per metric, collapsed across every `run_index`/`request_index`/`turn_index` combination
— into a new `test_case_metric_scores_aggregated` table, computed during Phase 2's flush cycle. This table
is the data source both `eval-summary-scoring`'s per-test-case score and `metric-score-statistics`'s
run-level `overall` are rebuilt on top of, so a metric's partial presence no longer skews a score and every
test case counts equally regardless of row count.

Status: **Implemented**

## Requirements

### Requirement: Per-test-case metric aggregation across turns/requests/reruns
The system SHALL compute, per test case per computation, per-metric `avg`/`min`/`max`/`count` aggregated
across **all** of that test case's `test_case_eval_summaries` rows — every `run_index`/`request_index`/
`turn_index` combination collapsed together — and persist the result as one row in
`test_case_metric_scores_aggregated`, keyed by `(test_suite_run_id, test_case_id, computation_id)`, with
the per-metric statistics stored as a JSONB map `{"<metricName>": {"avg":.., "min":.., "max":.., "count":..}}`.
Status: **Implemented**

#### Scenario: Multiple rows for one test case collapse into one aggregated row
- **WHEN** a test case has 8 `test_case_eval_summaries` rows for one computation (2 turns × 2 requests × 2 reruns)
- **THEN** exactly one `test_case_metric_scores_aggregated` row exists for that `(test_suite_run_id, test_case_id, computation_id)`, with each metric's `avg`/`min`/`max`/`count` computed across all 8 rows

#### Scenario: A metric that never fired for a test case is omitted from the map
- **WHEN** a metric has a `condition` that never evaluates to `true` for any of a test case's rows in a computation
- **THEN** that metric's key is absent from the test case's `metric_scores` JSONB map — never a zero or null entry

#### Scenario: A metric that fired on only some of a test case's rows is aggregated over only those rows
- **WHEN** a metric's `condition` scopes it to a subset of a test case's rows (e.g. `request.index == 0`), so 4 of the test case's 8 rows have a value and 4 do not
- **THEN** the metric's `avg`/`min`/`max` are computed over exactly the 4 rows that have a value, and `count` equals 4

### Requirement: Aggregation runs during Phase 2's flush cycle, fail-soft
The system SHALL compute and persist the aggregation for a flush batch's affected test cases immediately
before that batch's per-row score is written (see `eval-summary-scoring`), re-aggregating each affected
test case's **entire** row set for the computation (not just the current batch's rows), and upserting the
result (`INSERT ... ON CONFLICT (test_suite_run_id, test_case_id, computation_id) DO UPDATE`) so that a
test case whose rows straddle multiple flush batches converges to a correct, idempotent result. A failure
computing or writing the aggregation SHALL be logged and SHALL NOT fail the flush or the run.
Status: **Implemented**

#### Scenario: A test case's rows spanning two flush batches still aggregate correctly
- **WHEN** a test case's rows are split across two separate Phase-2 flush batches
- **THEN** after both flushes complete, exactly one `test_case_metric_scores_aggregated` row exists for that test case and computation, reflecting all of its rows

#### Scenario: Aggregation failure does not fail the flush
- **WHEN** the aggregation computation throws for a flush batch
- **THEN** the flush's eval-summary and per-row-score writes still succeed, the error is logged, and the run continues

### Requirement: `test_case_metric_scores` Query DSL entity
The system SHALL expose `test_case_metric_scores_aggregated` as a Query DSL entity named
`test_case_metric_scores`, flattening the `metric_scores` JSONB column into addressable fields
`metric_scores::<metricName>::avg|min|max|count`, using the same JSONB-flattening mechanism already used
for `eval_summaries.metric_values`. This entity SHALL be reachable through the existing generic query
endpoint (`POST /api/v1/queries/execute`), consistent with how `metric_score_results` is exposed.
Status: **Implemented**

#### Scenario: A flattened metric field is queryable
- **WHEN** a structured query selects `metric_scores::MetricA::avg` from `test_case_metric_scores`, filtered by `test_case_id`
- **THEN** the query returns that test case's aggregated average for `MetricA`

## Implementation Notes
- New table: `test_case_metric_scores_aggregated` (analytics DB), added by
  `V1.20__CreateTestCaseMetricScoresAggregatedTable.sql` — `id`, `test_suite_run_id`, `test_case_id`,
  `computation_id`, `metric_scores` JSONB, `created_at_ms` (set once, never updated by later upserts —
  reserved for future partitioning), `computed_at_ms`; a unique index on
  `(test_suite_run_id, test_case_id, computation_id)` and a lookup index on `computation_id`.
- New components: `TestCaseMetricScoreAggregator` (hand-written jOOQ — a single scan of
  `test_case_eval_summaries` that walks `metric_values` generically via two chained `jsonb_each` calls
  filtered to numeric leaves (`jsonb_typeof(...) = 'number'`), combined via `jsonb_object_agg`; needs no
  `MetricFieldDiscoverer`/`MetricField` input, since it self-discovers metric keys from the data; this is
  a data-normalization step, not a scoring computation, so it bypasses the generic Query DSL translator),
  `TestCaseMetricScoreAggregated`
  (model)/`RecordMapper`/repository (interface + Postgres impl)/service/batch-write DTO (mirroring
  `TestCaseEvalScore`'s equivalents), `PostgresTestCaseMetricScoreAggregatedEntityResolver`
  (`StructuredQueryEntityResolver` for `test_case_metric_scores`), `TestCaseMetricScoresSchemaProvider`
  (static base schema only — no per-run detailed-schema flattening, since the spec's own scenario only
  requires the field to be queryable via `JsonbFieldResolver`, independent of what the schema-discovery
  endpoint advertises).
- Hooked into `InProcessMetricEvaluationExecutor.doFlush`, running **before** the per-row score write
  (`eval-summary-scoring`), since that computation reads `test_case_metric_scores_aggregated`.
- The `metric_scores` JSONB key is `<metricName>` in the metric-field-discovery sense, i.e.
  `<tsmdName>.<outputField>` (`MetricField.metricName()`'s own format) — not the bare TSMD name, which
  would collide across a TSMD's distinct output fields (e.g. a classifier's `label` and `probability`).
