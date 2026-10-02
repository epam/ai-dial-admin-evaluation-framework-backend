# Test Case Metric Score Aggregation

## Purpose
This spec defines the per-test-case, per-computation aggregation of raw eval-summary metric values —
avg/min/max/count per metric, collapsed across every `run_index`/`request_index`/`turn_index` combination
— into a new `test_case_metric_scores_aggregated` table, computed once after Phase 2's final flush, in chunks of the batch size. This table
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

A metric output field whose `metric_values` leaf is an explicit JSON `null` (e.g. `{"Exact Match":
{"exact_match": null}}` — a provider-reported evaluation error surfaced as a null value, see
`metric-evaluation`) SHALL be treated as a real sample of `0`: it SHALL count toward that field's `count`
and contribute `0` to its `avg`/`min`/`max`, the same as if the metric had genuinely scored zero. This is
distinct from the field being **absent** from `metric_values` altogether (never dispatched, or
`condition`-skipped), which SHALL remain excluded from that field's stats entirely, per the scenario below.
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

#### Scenario: An explicit null output value counts as a failing zero, not an absent sample
- **WHEN** a test case has one row where a metric's output value is `null` (e.g. `{"Exact Match": {"exact_match": null}}`, a provider error) and one row where the same metric fires normally with value `1.0`
- **THEN** that metric's `count` is 2 (not 1), its `avg` is 0.5 (not 1.0), and its `min` is 0

#### Scenario: A row where the metric never fired does not contribute to another row's null sample
- **WHEN** one row of a test case has a metric's output value as `null` and a second row of the same test case has no key for that metric in `metric_values` at all
- **THEN** the metric's `count` is 1, reflecting only the null row — the row where the metric is absent contributes nothing

### Requirement: Aggregation runs once after Phase 2's final flush, fail-soft
The system SHALL compute and persist the aggregation for every test case of the computation exactly once,
after the last `test_case_eval_summaries` flush (and also when a flush failure or cancellation ends the
loop early, for the test cases already seen), in chunks of the context batch size, immediately before
each chunk's test case score computation (see `eval-summary-scoring`). Because every row of a test case exists by
then, each test case is aggregated over its **entire** row set and inserted once
(`INSERT ... ON CONFLICT (test_suite_run_id, test_case_id, computation_id) DO NOTHING`) — the table is
insert-only. A failure computing or writing the aggregation SHALL be logged and SHALL NOT fail the run.
Status: **Implemented**

#### Scenario: A test case's rows spanning two flush batches are aggregated once
- **WHEN** a test case's rows are split across two separate Phase-2 flush batches
- **THEN** after both flushes complete, exactly one aggregation is computed and inserted for that test case and computation, reflecting all of its rows

#### Scenario: Aggregation failure does not fail the run
- **WHEN** the aggregation computation throws for a chunk
- **THEN** the eval-summary writes already flushed remain, the error is logged, and the run continues

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
  `computation_id`, `metric_scores` JSONB, `created_at_ms` (reserved for future partitioning), `computed_at_ms`; a unique index on
  `(test_suite_run_id, test_case_id, computation_id)` and a lookup index on `computation_id`.
- New components: `TestCaseMetricScoreAggregator` (hand-written jOOQ — a single scan of
  `test_case_eval_summaries` that walks `metric_values` generically via two chained `jsonb_each` calls,
  filtered to leaves whose `jsonb_typeof(...)` is `number` or `null` (a `null` leaf's value is coalesced to
  `0` via a `CASE WHEN` rather than cast, since `null::text::double precision` would error) — any other
  leaf type (string, object, array, boolean) stays excluded — combined via `jsonb_object_agg`; needs no
  `MetricFieldDiscoverer`/`MetricField` input, since it self-discovers metric keys from the data; this is
  a data-normalization step, not a scoring computation, so it bypasses the generic Query DSL translator),
  `TestCaseMetricScoreAggregated`
  (model)/`RecordMapper`/repository (interface + Postgres impl)/service/batch-write DTO (mirroring
  `TestCaseEvalScore`'s equivalents), `PostgresTestCaseMetricScoreAggregatedEntityResolver`
  (`StructuredQueryEntityResolver` for `test_case_metric_scores`), `TestCaseMetricScoresSchemaProvider`
  (static base schema only — no per-run detailed-schema flattening, since the spec's own scenario only
  requires the field to be queryable via `JsonbFieldResolver`, independent of what the schema-discovery
  endpoint advertises).
- Hooked into `InProcessMetricEvaluationExecutor.writeTestCaseMetricScores`, running once after the final flush (in chunks), **before** the per-test-case score write
  (see `eval-summary-scoring`), since that computation reads `test_case_metric_scores_aggregated`.
- The `metric_scores` JSONB key is `<metricName>` in the metric-field-discovery sense, i.e.
  `<tsmdName>.<outputField>` (`MetricField.metricName()`'s own format) — not the bare TSMD name, which
  would collide across a TSMD's distinct output fields (e.g. a classifier's `label` and `probability`).
- `test_case_eval_scores.execution_status` (see `eval-summary-scoring`) is a **separate** per-test-case
  aggregate, computed by a sibling component `TestCaseExecutionStatusAggregator` that queries
  `test_case_eval_summaries` directly rather than through this table — it is not part of
  `test_case_metric_scores_aggregated`'s contract and does not change the "absent metric key = no sample,
  explicit null = a failing zero sample" behavior described above. It exists because this table's
  `jsonb_each`-driven aggregation never sees a row whose `metric_values = '{}'` (e.g. every metric
  condition-skipped, or the row failed before any metric ran), which is exactly the case the
  execution-status aggregate needs to see.
