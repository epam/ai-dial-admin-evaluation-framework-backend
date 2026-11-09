# Metrics Storage

## Purpose
This spec defines the eval summary storage layer — a denormalized, append-only analytics table for metric-enriched test case results, along with computation snapshot tracking and REST APIs for grid rendering, filtering, aggregation, and recalculation comparison.

Status: **Implemented**

## Key Terms
- **EvalSummary**: One row per (test case, run, run_index, request_index, turn_index, computation) containing execution context and metric outputs. The primary analytical surface for the grid UI.
- **RunMetricSnapshot**: Per-(computation, TSMD) record capturing binding configs and metric declaration versions used during a specific metric computation.
- **computation_id**: UUID identifying a metric computation batch. All eval summaries and snapshots from one computation share the same computation_id.
- **metric_values**: Compact JSONB column (~1-2 KB) containing metric output values only. Used for filtering, sorting, and grid display.
- **metric_infos**: Detailed JSONB column (~5-25 KB) containing metric output info/metadata. Lazy-loaded for drill-down views only.

## Requirements

### Requirement: Database schema for eval summaries
The analytics database SHALL contain a `test_case_eval_summaries` table storing denormalized, append-only rows that combine test case context with metric computation outputs, including per-turn columns, per-request columns, and a summed metric-evaluation latency column.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the installed analytics schema is inspected
- **THEN** the `test_case_eval_summaries` table SHALL have columns: `id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_case_run_result_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `computation_id` (VARCHAR(36), NOT NULL), `test_case_data` (JSONB, NOT NULL), `extracted_columns` (JSONB, NOT NULL, DEFAULT '{}'), `extraction_warnings` (JSONB, NOT NULL, DEFAULT '[]'), `execution_status` (VARCHAR(20), NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `metric_eval_duration_ms` (BIGINT, NOT NULL, DEFAULT 0), `response_status_code` (INTEGER, nullable), `metric_values` (JSONB, NOT NULL, DEFAULT '{}'), `metric_infos` (JSONB, nullable), `turn_index` (INTEGER, NOT NULL, DEFAULT 0), `total_turns` (INTEGER, NOT NULL, DEFAULT 1), `request_index` (INTEGER, NOT NULL, DEFAULT 0), `total_requests` (INTEGER, NOT NULL, DEFAULT 1), `created_at_ms` (BIGINT, NOT NULL), `computed_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the installed database schema is inspected
- **THEN** the unique index `uq_eval_summaries_natural_key` SHALL be `(test_suite_run_id, test_case_id, run_index, request_index, turn_index, computation_id, created_at_ms)`, so each (request, turn) pair is uniquely keyed per computation

#### Scenario: Request-turn identity and metric latency
- **WHEN** eval summaries are persisted and queried
- **THEN** rows SHALL carry the request/turn identity and metric-evaluation latency columns listed above; `metric_eval_duration_ms` SHALL be filterable, sortable, and aggregatable through the `eval_summaries` Query DSL entity's generated-column bindings

#### Scenario: Indexes
- **WHEN** the installed database schema is inspected
- **THEN** indexes SHALL be created on: `(test_suite_run_id, computation_id)` for run-scoped grid queries (the primary query path), `(computation_id)` for computation-scoped queries, `(id)` for direct lookups, and `(test_suite_run_id, computed_at_ms DESC, computation_id)` for latest-computation resolution. There SHALL be no separate request-column index.

#### Scenario: Latest-computation resolution is a top-1 index probe
- **WHEN** the installed eval-summary indexes are inspected
- **THEN** `idx_eval_summaries_run_computed_at` SHALL exist on `(test_suite_run_id, computed_at_ms DESC, computation_id)`, so `WHERE test_suite_run_id = ? ORDER BY computed_at_ms DESC LIMIT 1` is served as a single top-1 index descent with `computation_id` available from the index tuple rather than a scan of the run's rows plus a sort

#### Scenario: No foreign keys
- **WHEN** the installed database schema is inspected
- **THEN** no foreign key constraints SHALL exist (soft references to meta DB and test_case_run_results only)

#### Scenario: No updated_at column
- **WHEN** the installed database schema is inspected
- **THEN** the table SHALL NOT have an `updated_at_ms` column (rows are immutable/append-only)

### Requirement: Eval summaries carry turn columns
`test_case_eval_summaries` SHALL persist one summary per turn result row, with new columns `turn_index INTEGER NOT NULL DEFAULT 0` and `total_turns INTEGER NOT NULL DEFAULT 1`. Response DTOs expose `turnIndex`/`totalTurns`; batch-write item DTOs accept them as optional (defaulting `0/1`). No `multi_turn_id` column is added (grouping is via `test_case_id`).
Status: **Implemented**

#### Scenario: One summary per turn
- **WHEN** a multi-turn case has N turn result rows scored under one computation
- **THEN** N eval-summary rows are written, one per turn, each with its `turn_index` and `total_turns=N`

#### Scenario: Summary key distinguishes turns
- **WHEN** two turns of the same case/run/computation are summarized
- **THEN** both persist because `turn_index` is part of the natural key

### Requirement: Eval summaries carry request columns
`test_case_eval_summaries` SHALL persist one summary per result row for every call of a suite's request chain, with columns `request_index INTEGER NOT NULL DEFAULT 0` and `total_requests INTEGER NOT NULL DEFAULT 1` copied from the source `test_case_run_results` row. The columns SHALL be stamped only when the chain length is greater than 1. Response DTOs SHALL expose `requestIndex`/`totalRequests`; batch-write item DTOs SHALL accept them as optional, defaulting to `0`/`1`. No `chain_id` column SHALL be added — grouping is via `test_case_id` plus the `(request_index, turn_index)` pair. The `ON CONFLICT` target used by the eval-summary batch writer SHALL match the extended unique index exactly.

A metric-less run SHALL write one eval summary per result row (`metric_values = {}`, `metric_infos` SQL NULL, no `run_metric_snapshots`) for every executed chain call, so `test_case_eval_summaries` stays the single read surface for all rows of a chained run.
Status: **Implemented**

#### Scenario: One summary per chain call
- **WHEN** a 2-request chain produces 2 result rows for a repetition and Phase 2 runs
- **THEN** exactly 2 eval-summary rows SHALL exist for that repetition, carrying `request_index` 0 and 1 with `total_requests = 2`

#### Scenario: Request columns mirror the source result row
- **WHEN** an eval summary is written from a result row with `(request_index=1, turn_index=2)`
- **THEN** the summary SHALL carry the same `request_index`, `total_requests`, `turn_index` and `total_turns`

#### Scenario: Metric-less chained run still writes summaries for every call
- **WHEN** a chained run's suite has zero TSMDs
- **THEN** one summary per chain call SHALL be written with `metric_values = {}` and no `run_metric_snapshots`

#### Scenario: Single-request summaries keep the defaults
- **WHEN** an eval summary is written for a single-request suite
- **THEN** `request_index` SHALL be `0` and `total_requests` SHALL be `1` from the defaults

### Requirement: Database schema for run metric snapshots
The **meta** database SHALL contain a `run_metric_snapshots` table storing per-computation binding and version snapshots. The table SHALL hold a foreign key to `test_suite_runs` so a run and its captured metric catalog live in one database and cannot diverge.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the installed meta schema is inspected
- **THEN** the `run_metric_snapshots` table SHALL have columns: `id` (VARCHAR(36), NOT NULL, PK), `computation_id` (VARCHAR(36), NOT NULL), `test_suite_run_id` (VARCHAR(36), NOT NULL), `tsmd_id` (VARCHAR(36), NOT NULL), `tsmd_name` (VARCHAR(255), NOT NULL), `metric_declaration_id` (VARCHAR(36), NOT NULL), `metric_declaration_version_id` (VARCHAR(36), NOT NULL), `config_bindings` (JSONB, NOT NULL, DEFAULT '[]'), `input_bindings` (JSONB, NOT NULL, DEFAULT '[]'), `output_schema` (JSONB, NOT NULL, DEFAULT '{}'), `computed_at_ms` (BIGINT, NOT NULL)

#### Scenario: UNIQUE constraint
- **WHEN** the installed database schema is inspected
- **THEN** a UNIQUE index (`CREATE UNIQUE INDEX`, not a table constraint) SHALL exist on `(computation_id, tsmd_id)`

#### Scenario: Index for run lookup
- **WHEN** the installed meta schema's run-snapshot indexes are inspected
- **THEN** exactly one non-unique index SHALL exist with `test_suite_run_id` as its leading column: `idx_run_metric_snapshots_run_computed_at` on `(test_suite_run_id, computed_at_ms DESC, computation_id DESC)`. It SHALL support run-scoped lookup through its leading column. Latest-computation queries SHALL use `computed_at_ms DESC, computation_id ASC`; the index does not match that full ordering, so this spec SHALL NOT promise a sort-free plan. The single-column index `idx_run_metric_snapshots_run` SHALL NOT exist.

#### Scenario: Foreign key to test suite runs
- **WHEN** the installed database schema is inspected
- **THEN** `test_suite_run_id` SHALL carry a foreign key referencing `test_suite_runs(id)` with `ON DELETE CASCADE`

#### Scenario: Snapshot for an unknown run is rejected
- **WHEN** a snapshot row is written whose `test_suite_run_id` does not exist in `test_suite_runs`
- **THEN** the database SHALL reject the write, so orphaned snapshot rows cannot be created

#### Scenario: Analytics copy is no longer read
- **WHEN** any component reads run metric snapshots
- **THEN** it SHALL read the meta `run_metric_snapshots` table. The same-named table in the analytics database SHALL NOT be read or written by any code path.

### Requirement: Batch write eval summaries
The service SHALL support persisting eval summary rows both via the external REST API (`POST /api/v1/analytics/eval-summaries`) and via internal writes from the in-process metric evaluation engine. Both paths SHALL go through `EvalSummaryService.batchCreate()`, sharing the same validation, mapping, and persistence logic with idempotent `ON CONFLICT DO NOTHING`. Batch items MAY optionally carry a client-generated `id`; when omitted, the service generates one. `test_case_eval_summaries` itself carries no `score`/`passed` columns — those are computed and stored separately (see the `test_case_eval_scores` table requirement).
Status: **Implemented**

#### Scenario: Successful batch write
- **WHEN** client calls `POST /api/v1/analytics/eval-summaries` with a valid envelope containing `testSuiteId`, `testSuiteRunId`, `computationId`, `computedAtMs`, and `items` array
- **THEN** system SHALL insert all items atomically (skipping duplicates) and return HTTP 201 with `{"totalItems": N}` echoing the input count. The envelope's `computedAtMs` SHALL be applied to all inserted rows.

#### Scenario: Empty items array
- **WHEN** client submits an envelope whose `items` array is empty
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Batch item count limit exceeded
- **WHEN** `items` array exceeds `analytics.eval-summaries.batch.max-items` (configurable, default 10000)
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Run existence validation
- **WHEN** a batch write is processed
- **THEN** the service SHALL read the run from meta DB. If not found, return HTTP 404

#### Scenario: Suite ID validation
- **WHEN** the envelope's `testSuiteId` differs from the run's `testSuiteId`
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Timestamp assignment from run
- **WHEN** a batch write is processed for a valid run
- **THEN** all records SHALL receive the run's `created_at_ms` from meta DB

#### Scenario: Idempotent retry
- **WHEN** client retries a previously successful batch
- **THEN** system SHALL return HTTP 201 (duplicates silently skipped via ON CONFLICT DO NOTHING)

#### Scenario: Required fields validation
- **WHEN** required envelope fields are missing (`testSuiteId`, `testSuiteRunId`, `computationId`, `computedAtMs`) or required per-item fields are missing (`testCaseRunResultId`, `testCaseId`, `testCaseName`, `testCaseData`, `runIndex`, `executionStatus`, `execDurationMs`, `metricEvalDurationMs`, `metricValues`)
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: metricValues structural validation
- **WHEN** a batch write item contains `metricValues`
- **THEN** `metricValues` SHALL be a JSON object where each key maps to a nested JSON object whose leaf values are numeric (or null). Items with non-numeric leaf values SHALL be rejected with HTTP 400 and error code `VALIDATION_ERROR`. This ensures downstream filter (`JSONB_NUMERIC`) and aggregation queries can safely cast values to numeric.

> **Implementation note — key naming**: The keys within each nested `metricValues` object are expected to be real metric output field names (not synthetic placeholders like `"error"`). The batch-write API does not enforce field name validity — that is the producer's responsibility. The metric evaluation engine ensures correct key names by extracting them from the TSMD's output schema via `OutputSchemaFieldExtractor`.

#### Scenario: Internal batch write from metric evaluation engine
- **WHEN** the in-process metric evaluation engine produces EvalSummary records
- **THEN** the `EvalSummaryBatchWriteClient` SHALL convert internal models to `EvalSummaryBatchWriteRequestDto` and delegate to `EvalSummaryService.batchCreate()`, reusing the same validation, mapping, and persistence logic as the external API. The existing batch size limit (`analytics.eval-summaries.batch.max-items`) SHALL be respected via chunking. Each item's `id` SHALL be generated by the engine before this call (not left to the service).

#### Scenario: Optional client-generated id accepted and used verbatim
- **WHEN** a batch write item carries an `id`
- **THEN** the service SHALL persist that id verbatim as the row's `id` rather than generating a new one

#### Scenario: Omitted id falls back to server generation
- **WHEN** a batch write item omits `id`
- **THEN** the service SHALL generate one, preserving the existing external-API contract

### Requirement: Database schema for eval summary scores
The analytics database SHALL contain a `test_case_eval_scores` table storing at most one row per `(test_suite_run_id, test_case_id, computation_id)`, computed via SQL and exposed through its own `test_case_eval_scores` Query DSL entity, not joined into the eval-summary REST or Query DSL surface. The table SHALL carry `test_suite_run_id`, `test_case_id`, `test_case_name`, `computation_id`, and aggregated `execution_status`, so it can be read without a context join or deduplication. The primary key SHALL be a surrogate `id`; `uq_test_case_eval_scores_natural_key` SHALL enforce uniqueness on the run/test-case/computation tuple.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the installed analytics score schema is inspected
- **THEN** the `test_case_eval_scores` table SHALL have the columns: `id` (VARCHAR(36), NOT NULL, PK), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `computation_id` (VARCHAR(36), NOT NULL), `execution_status` (VARCHAR(20), NOT NULL), `score` (DOUBLE PRECISION, nullable), `passed` (BOOLEAN, nullable), `computed_at_ms` (BIGINT, NOT NULL), and a unique constraint `uq_test_case_eval_scores_natural_key` on `(test_suite_run_id, test_case_id, computation_id)`

#### Scenario: A row's absence and a present-but-null row are distinguishable
- **WHEN** a test case has no matching `test_case_eval_scores` row (no `overallScore` configured, or a rejected query shape), versus a matching row whose `score` is itself SQL NULL (an `execution_status = FAILED` aggregate, or a `Mean`/`WeightedMean` aggregate that itself evaluates to SQL NULL)
- **THEN** a client querying the `test_case_eval_scores` Query DSL entity directly SHALL be able to distinguish the two cases by row presence — no row versus a row present with `score = null, passed = null`

### Requirement: Batch write eval summary scores (internal only)
The in-process metric evaluation engine SHALL write `test_case_eval_scores` via `TestCaseEvalScoreService.batchInsert()` in chunks of distinct test-case IDs after the final Phase-2 flush attempt, immediately after each chunk's `test_case_metric_scores_aggregated` write attempt. The effective per-test-case score definition SHALL prefer `testCaseOverallScore`, fall back to `overallScore` only when it is not `CustomFunction`, and skip scoring when neither supplies a usable definition. A FAILED aggregate SHALL receive one row with null score/pass; a SUCCESS aggregate SHALL receive a row only when the score query returns that test case. There SHALL be no external REST endpoint for this table, and the `eval_summaries` read surface SHALL NOT join it.
Status: **Implemented**

#### Scenario: Score batch writes once after final flush, chunked by test case
- **WHEN** the final Phase-2 flush has been attempted and an effective per-test-case score definition exists
- **THEN** the executor SHALL process distinct test-case IDs in configured-size chunks, attempt the aggregated-metric write before score computation, and write at most one score item per test case with its run/name/computation/status context
- **AND** FAILED test cases SHALL get null-score rows; SUCCESS test cases without any returned numeric-score group SHALL have no score row

#### Scenario: A repeated write for the same test case is idempotent
- **WHEN** a score batch write is retried (e.g. on a transient error), or a subsequent Phase 2 execution recomputes a score, for a `(test_suite_run_id, test_case_id, computation_id)` already present
- **THEN** the insert SHALL use `ON CONFLICT (test_suite_run_id, test_case_id, computation_id) DO NOTHING`, leaving the existing row unchanged

#### Scenario: A failed score write does not fail the run
- **WHEN** the score computation or batch write throws an unexpected error
- **THEN** the error SHALL be logged and the run SHALL continue — `score`/`passed`/`execution_status` are regenerable derived data, unlike the eval summaries themselves

### Requirement: `test_case_eval_scores` Query DSL entity
The system SHALL expose `test_case_eval_scores` as a Query DSL entity of the same name, presenting exactly one row per test case per computation (guaranteed by the table's unique constraint, so no dedup is needed). The entity SHALL support the `computation_id eq "latest"` sentinel, resolved the same way as `metric_score_results`. This entity SHALL be reachable through the existing generic query endpoint (`POST /api/v1/queries/execute`), and is the sole read surface for a test case's `score`/`passed` — the `eval_summaries` entity and REST endpoints do not expose either field.
Status: **Implemented**

#### Scenario: `computation_id eq "latest"` resolves to the run's latest computation
- **WHEN** a structured query against `test_case_eval_scores` filters by a single `test_suite_run_id` and `computation_id eq "latest"`
- **THEN** the sentinel SHALL resolve to that run's most recently computed computation before translation, the same way it does for `metric_score_results`

### Requirement: Batch write run metric snapshots
The service SHALL support persisting run metric snapshots both via the external REST API (`POST /api/v1/run-metric-snapshots`) and via internal writes from the in-process metric evaluation engine. Both paths SHALL go through `RunMetricSnapshotService.batchCreate()`, sharing the same validation, mapping, and persistence logic with idempotent `ON CONFLICT DO NOTHING`. The write SHALL execute in a meta-database transaction, so the run-existence check and the snapshot insert are atomic.
Status: **Implemented**

#### Scenario: Successful batch write
- **WHEN** client calls `POST /api/v1/run-metric-snapshots` with a valid envelope containing `testSuiteRunId`, `computationId`, `computedAtMs`, and `snapshots` array
- **THEN** system SHALL insert all snapshots atomically and return HTTP 201. The envelope's `computedAtMs` SHALL be applied to all inserted rows.

#### Scenario: Run existence validation
- **WHEN** a batch write is processed
- **THEN** the service SHALL read the run from the meta database within the same transaction as the insert. If not found, return HTTP 404

#### Scenario: Internal write from metric evaluation engine
- **WHEN** the in-process metric evaluation engine captures RunMetricSnapshots before evaluation
- **THEN** the `RunMetricSnapshotBatchWriteClient` SHALL convert internal models to `RunMetricSnapshotBatchWriteRequestDto` and delegate to `RunMetricSnapshotService.batchCreate()`, reusing the same validation, mapping, and persistence logic as the external API.

#### Scenario: Deprecated path accepted
- **WHEN** client calls `POST /api/v1/analytics/run-metric-snapshots`
- **THEN** system SHALL behave identically to the same call against `POST /api/v1/run-metric-snapshots`

### Requirement: List eval summaries with keyset pagination and filters
The service SHALL provide `GET /api/v1/analytics/eval-summaries` with cursor-based pagination, `filter=field:operator:value` syntax, and computation resolution.
Status: **Implemented**

#### Scenario: Computation parameter defaults to latest
- **WHEN** client queries without `computation` query parameter
- **THEN** system SHALL default to the latest computation for the specified run

#### Scenario: Computation parameter with UUID
- **WHEN** client provides `computation=<uuid>`
- **THEN** system SHALL filter by that specific computation_id

#### Scenario: Latest computation resolution
- **WHEN** computation is "latest" or omitted
- **THEN** system SHALL resolve the latest computation_id from `test_case_eval_summaries` WHERE `test_suite_run_id = :runId` ORDER BY `computed_at_ms DESC LIMIT 1`

#### Scenario: Latest computation resolution does not depend on metrics
- **WHEN** computation is "latest" or omitted for a run whose suite had no enabled+valid TSMDs (so the run has eval summaries but no `run_metric_snapshots` rows)
- **THEN** system SHALL resolve that run's computation_id and return its rows

#### Scenario: No computation exists
- **WHEN** computation resolution finds no eval summaries for the run
- **THEN** system SHALL return HTTP 200 with an empty page result

#### Scenario: Required filter — runId always required
- **WHEN** client queries without `runId:eq:...` filter
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: First page (no cursor)
- **WHEN** client queries without cursor
- **THEN** system SHALL return results ordered by `created_at_ms DESC, id DESC`, with `nextCursor` if more exist

#### Scenario: Subsequent pages (with cursor)
- **WHEN** client provides a cursor
- **THEN** system SHALL return results after the cursor position using keyset pagination

#### Scenario: Sort is fixed
- **WHEN** client queries eval summaries
- **THEN** results SHALL always be sorted by `created_at_ms DESC, id DESC`. No user-configurable sort.

#### Scenario: Filter by identity fields
- **WHEN** client includes filters for `suiteId`, `runId`, `testCaseId`, `testCaseName`, `runIndex`
- **THEN** system SHALL filter using the allowlisted operators per field: `suiteId`, `runId`, `testCaseId` accept `eq` and `in`; `testCaseName` accepts `eq`, `ne`, `co`, and `in`; `runIndex` accepts `eq`, `gt`, `ge`, `lt`, and `le`

#### Scenario: Filter by execution fields
- **WHEN** client includes filters for `executionStatus`, `execDurationMs`, `responseStatusCode`
- **THEN** system SHALL filter using standard operators (eq, ge, le, etc.)

#### Scenario: Filter by metric values via JSONB path
- **WHEN** client includes `filter=metricValues.<metricName>.<outputName>:<op>:<value>` (e.g., `metricValues.Accuracy.score:ge:0.8`)
- **THEN** system SHALL filter using JSONB path extraction on the `metric_values` column with parameterized path components

#### Scenario: Filter by testCaseData JSONB path
- **WHEN** client includes `filter=testCaseData.<key>:<op>:<value>`
- **THEN** system SHALL filter using JSONB path extraction on the `test_case_data` column (same pattern as existing analytics results)

#### Scenario: Response excludes metric_infos by default
- **WHEN** client queries the list endpoint
- **THEN** response DTOs SHALL include `metricValues` but SHALL NOT include `metricInfos` (lazy-loaded via detail endpoint only). The SQL SELECT clause SHALL explicitly exclude `metric_infos` to prevent TOAST decompression overhead. Only the `findById` detail query selects `metric_infos`.

### Requirement: Get single eval summary by ID
`GET /api/v1/analytics/eval-summaries/{id}` SHALL return a single readable eval summary including `metricValues`, `metricInfos`, `extractionWarnings`, `requestBody`, and `responseBody`, or HTTP 404 if not found. Nullable fields SHALL be omitted when null. `findById` and export-with-bodies SHALL populate bodies through a LEFT JOIN to `test_case_run_results`; ordinary list SHALL NOT perform this join.
Status: **Implemented**

#### Scenario: Successful retrieval with full detail
- **WHEN** client calls `GET /api/v1/analytics/eval-summaries/{id}` and the eval summary exists
- **THEN** system SHALL return HTTP 200 with the full eval summary including `metricValues`, `metricInfos`, `extractionWarnings`, `requestBody`, and `responseBody`

#### Scenario: Not found
- **WHEN** client calls `GET /api/v1/analytics/eval-summaries/{id}` and no eval summary exists with that ID
- **THEN** system SHALL return HTTP 404

#### Scenario: Missing run result row (LEFT JOIN)
- **WHEN** the referenced `test_case_run_result_id` no longer exists in `test_case_run_results`
- **THEN** the detail response SHALL return the eval summary with absent `requestBody` and `responseBody` fields (LEFT JOIN nulls omitted by non-null serialization)

### Requirement: Metric evaluation latency exposed in eval summary responses
`EvalSummaryResponseDto` and `EvalSummaryDetailResponseDto` SHALL expose the persisted `metric_eval_duration_ms` value as `metricEvalDurationMs` (Long), alongside the existing `execDurationMs`.
Status: **Implemented**

#### Scenario: List response includes metric evaluation latency
- **WHEN** a client calls the eval summaries list endpoint
- **THEN** each returned item SHALL include `metricEvalDurationMs`, reflecting the value persisted at write time

#### Scenario: Detail response includes metric evaluation latency
- **WHEN** a client calls the get-single-eval-summary endpoint
- **THEN** the response SHALL include `metricEvalDurationMs`

### Requirement: Count eval summaries
`GET /api/v1/analytics/eval-summaries/count` SHALL return the count of eval summaries matching filters. Requires `runId` filter. Uses computation resolution (same as list).
Status: **Implemented**

#### Scenario: Count with filters
- **WHEN** client calls `GET /api/v1/analytics/eval-summaries/count` with `runId` filter and optional additional filters
- **THEN** system SHALL return `{"count": N}` matching the filter criteria for the resolved computation

### Requirement: Aggregate eval summaries
`GET /api/v1/analytics/eval-summaries/aggregate` SHALL return metric aggregations for a run's computation. Requires `runId` filter. Uses computation resolution (same as list — defaults to latest). The response is a flat list of per-metric aggregation objects, not grouped by computation (the computation is resolved as a filter, not a grouping dimension). `count` represents the number of non-null values for the metric output.
Status: **Implemented**

#### Scenario: Per-run metric aggregation
- **WHEN** client calls `GET /api/v1/analytics/eval-summaries/aggregate?filter=runId:eq:...&metrics=Accuracy.score,Relevance.score`
- **THEN** system SHALL return HTTP 200 with response:
```json
{
  "computationId": "<resolved-computation-uuid>",
  "metrics": [
    {
      "metric": "Accuracy",
      "output": "score",
      "avg": 0.85,
      "min": 0.32,
      "max": 0.99,
      "count": 950
    },
    {
      "metric": "Relevance",
      "output": "score",
      "avg": 0.88,
      "min": 0.45,
      "max": 0.98,
      "count": 950
    }
  ]
}
```
Where `count` is the number of eval summary rows where the metric output value is non-null (rows with missing metric values are excluded from all aggregations for that metric).

#### Scenario: Metrics parameter required
- **WHEN** client calls the aggregate endpoint without `metrics` query parameter
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Metrics parameter count limit
- **WHEN** client provides more than 50 metric paths in the `metrics` parameter
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`. Each metric path generates 4 SQL aggregate expressions (AVG, MIN, MAX, COUNT); unbounded input would produce excessively large queries.

#### Scenario: Metrics parameter format
- **WHEN** client provides `metrics=MetricName.outputName` (repeatable, comma-separated)
- **THEN** system SHALL extract each metric value using JSONB path `(metric_values->'MetricName'->>'outputName')::numeric` and compute AVG, MIN, MAX, COUNT (excluding nulls) in a single SQL query

#### Scenario: Specific computation
- **WHEN** client provides `computation=<uuid>` query parameter
- **THEN** system SHALL compute aggregations only over eval summaries with that `computation_id`

#### Scenario: No readable computation
- **WHEN** latest-computation resolution finds no readable eval summaries
- **THEN** system SHALL return HTTP 200 with an empty `metrics` array and no non-null `computationId`

#### Scenario: Requested paths have no numeric values
- **WHEN** a computation resolves but filters match no rows or the requested metric outputs are all missing/null
- **THEN** system SHALL return one aggregation item for each requested path with `count = 0`, not an empty `metrics` array; null `avg`/`min`/`max` values SHALL be omitted from the JSON item

### Requirement: List run metric snapshots
`GET /api/v1/run-metric-snapshots` SHALL return a flat, unpaginated JSON array of metric binding snapshots for the first supplied `runId` filter; it SHALL NOT group them into computation envelopes.
Status: **Implemented**

#### Scenario: List snapshots by run
- **WHEN** client calls `GET /api/v1/run-metric-snapshots?filter=runId:eq:...`
- **THEN** system SHALL return all metric snapshots for that run, ordered by `computed_at_ms DESC`

#### Scenario: Required filter — runId
- **WHEN** client queries without `runId:eq:...` filter
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Response includes full binding detail
- **WHEN** snapshots are returned
- **THEN** each snapshot SHALL include: `id`, `computationId`, `testSuiteRunId`, `tsmdId`, `tsmdName`, `metricDeclarationId`, `metricDeclarationVersionId`, `configBindings` (as JSON array), `inputBindings` (as JSON array), `outputSchema` (as JSON object), `computedAtMs`

#### Scenario: Deprecated path returns the same payload
- **WHEN** client calls `GET /api/v1/analytics/run-metric-snapshots?filter=runId:eq:...`
- **THEN** system SHALL return the same response body and status as the equivalent call to `GET /api/v1/run-metric-snapshots`

### Requirement: Deprecated run metric snapshot endpoint aliases
The service SHALL retain `/api/v1/analytics/run-metric-snapshots` as a deprecated alias for both `GET` and `POST`, delegating to the same service methods as `/api/v1/run-metric-snapshots`.
Status: **Implemented**

#### Scenario: Alias is marked deprecated in OpenAPI
- **WHEN** the OpenAPI document is generated
- **THEN** both operations under `/api/v1/analytics/run-metric-snapshots` SHALL be marked `deprecated: true` and their descriptions SHALL name the replacement path

#### Scenario: Canonical operations are not marked deprecated
- **WHEN** the OpenAPI document is generated
- **THEN** the operations under `/api/v1/run-metric-snapshots` SHALL NOT be marked deprecated, and each operation SHALL carry its own operationId distinct from the alias operations

### Requirement: Configuration properties for eval summaries
The system SHALL expose configurable item-count and HTTP request-size limits for eval summary batch writes.
Status: **Implemented**

#### Scenario: Batch max items
- **WHEN** application starts
- **THEN** `analytics.eval-summaries.batch.max-items` SHALL be configurable with a default of 10000

#### Scenario: Batch max request size
- **WHEN** application starts
- **THEN** `analytics.eval-summaries.batch.max-request-size-bytes` SHALL be configurable with a default of 10485760 (10 MB)

### Requirement: Computation versioning model
Metric computations SHALL be versioned via `computation_id` with no mutable `is_latest` flag. "Latest" SHALL be resolved at query time, from the table the caller reads.

Every latest-computation lookup, on either table, SHALL order by `computed_at_ms DESC, computation_id ASC` and take the first row, giving deterministic tie-breaking within its source table. Summary and snapshot lookups SHALL agree when their eligible computation sets agree; no cross-table equality SHALL be promised when only one table has rows for a computation. Summary resolution SHALL also accept a set of runs, returning at most one computation per run in a single statement; a run with no readable eval summaries SHALL be absent rather than mapped to null. An empty set SHALL resolve without issuing a statement.
Status: **Implemented**

#### Scenario: Recalculation creates new computation
- **WHEN** metrics are recalculated for a run
- **THEN** the metric computation pipeline SHALL generate a new `computation_id`, insert new eval summary rows and new run metric snapshots, without modifying existing rows

#### Scenario: Latest resolution
- **WHEN** the API needs to determine the latest computation for a run
- **THEN** it SHALL query `test_case_eval_summaries` for the maximum `computed_at_ms` for that run and use the corresponding `computation_id`, breaking a tie on the smallest `computation_id`

#### Scenario: Latest resolution is independent of a run's row count
- **WHEN** the latest computation of a run is resolved and the run has many eval summary rows spread across more than one computation
- **THEN** resolution SHALL return the `computation_id` with the greatest `computed_at_ms` for that run, whatever the number of rows involved

#### Scenario: Latest resolution requires readable rows
- **WHEN** a computation wrote `run_metric_snapshots` rows for a run but wrote no eval summaries (e.g. its batch write failed)
- **THEN** latest resolution SHALL NOT select that computation, and SHALL select the run's most recent computation that does have eval summaries

#### Scenario: Same-millisecond computations resolve deterministically
- **WHEN** two computations of one run share `computed_at_ms`
- **THEN** each lookup SHALL select the smallest tied computation ID from its own source table; summary-based and snapshot-based lookups SHALL agree when their eligible computation sets agree, but MAY differ when a computation has only snapshots or only summaries

#### Scenario: Latest resolution for a set of runs
- **WHEN** the latest computation is resolved for a set of runs at once
- **THEN** the result maps each run that has eval summaries to exactly one `computation_id`, resolved by the same ordering as the single-run lookup, and omits runs that have none

#### Scenario: Empty run set resolves without a database round trip
- **WHEN** the latest computation is resolved for an empty set of runs
- **THEN** the result is empty and no statement is issued against the database

#### Scenario: Metric-catalog lookups stay on run metric snapshots
- **WHEN** a caller needs the metric column families or metric names of a run's latest computation rather than its readable rows (Query DSL detailed schema discovery, the `test_suite_runs` query entity's `metric_names`)
- **THEN** it SHALL resolve that computation from `run_metric_snapshots` as the row with the greatest `computed_at_ms` for the run, ties broken by the smallest `computation_id`, so that two computations captured in the same millisecond resolve deterministically and agree with the eval-summary-based lookup; and SHALL return no metric families / an empty name list for a run that has none

#### Scenario: Comparison between computations
- **WHEN** client provides two computation UUIDs
- **THEN** the list endpoint SHALL support filtering by specific `computation_id` to enable side-by-side comparison

### Requirement: Filter framework extension for eval summary JSONB filtering
The filter framework SHALL support two-level numeric JSONB paths on `metric_values` and one-level text JSONB paths on `test_case_data`. `WhereBuilder` SHALL use `JSONB_NUMERIC` and `JSONB_STRING` field definitions and bound path components to build typed-jOOQ predicates.
Status: **Implemented**

#### Scenario: New JSONB_NUMERIC FilterFieldType
- **WHEN** a `JSONB_NUMERIC` filter is built
- **THEN** `WhereBuilder` SHALL parse its trimmed value as `BigDecimal` and allow the whitelist's comparison operators (`eq`, `ne`, `gt`, `ge`, `lt`, `le`), preserving decimal precision

#### Scenario: Two-level JSONB path parsing in WhereBuilder
- **WHEN** a filter field uses dot notation and resolves to a `JSONB_NUMERIC` field (e.g., `metricValues.Accuracy.score`)
- **THEN** `WhereBuilder` SHALL split the metric/output path into two bound components (`Accuracy`, `score`); one-level numeric paths and paths deeper than two levels SHALL be rejected

#### Scenario: JSONB_NUMERIC SQL predicate generation
- **WHEN** a `JSONB_NUMERIC` filter condition is built
- **THEN** `WhereBuilder` SHALL extract the two-level JSONB value as numeric through `JsonPathAccessor`, bind both path strings and the numeric comparison value, and apply the requested comparison without interpolating client data into SQL

#### Scenario: Metric value filter usage
- **WHEN** client includes `filter=metricValues.Accuracy.score:ge:0.8`
- **THEN** `WhereBuilder` SHALL compare the numeric metric output at bound path `Accuracy` / `score` against bound `BigDecimal("0.8")` using greater-than-or-equal

#### Scenario: Single-level JSONB_STRING unchanged
- **WHEN** a filter field uses dot notation and resolves to a `JSONB_STRING` field (e.g., `testCaseData.prompt`)
- **THEN** existing single-level behavior SHALL be preserved. The `jsonbKey.contains(".")` rejection SHALL still apply for `JSONB_STRING` fields.

#### Scenario: Filter whitelist
- **WHEN** the eval summaries filter whitelist is configured
- **THEN** it SHALL include fields: `suiteId` (UUID), `runId` (UUID), `testCaseId` (UUID), `testCaseName` (STRING), `executionStatus` (STRING), `runIndex` (LONG), `execDurationMs` (LONG), `responseStatusCode` (LONG), `testCaseData` (JSONB_STRING), `metricValues` (JSONB_NUMERIC)

### Requirement: Extraction warnings in eval summary

The `test_case_eval_summaries` table SHALL have an `extraction_warnings` column (`JSONB NOT NULL DEFAULT '[]'`). `EvalSummary` model, `EvalSummaryRecordMapper`, batch-insert SQL, and `EvalSummaryDetailResponseDto` SHALL include `extractionWarnings`. The value SHALL be copied from `TestCaseRunResult.extractionWarnings` at eval summary write time. If the source value is null, the mapper SHALL default it to `"[]"`.
Status: **Implemented**

#### Scenario: Extraction warnings persisted
- **WHEN** a test case run result has extraction warnings
- **THEN** the corresponding eval summary row has `extraction_warnings` set to the serialized warnings array

#### Scenario: No extraction warnings
- **WHEN** a test case run result has no extraction warnings (null)
- **THEN** the eval summary row has `extraction_warnings = '[]'` (defaulted by `@AfterMapping`)

#### Scenario: Extraction warnings in API response
- **WHEN** `GET /api/v1/analytics/eval-summaries/{id}` is called
- **THEN** the response includes `extractionWarnings` as a JSON value (omitted from list response via `@JsonInclude(NON_NULL)`)

---

### Requirement: Request and response body in eval summary detail

`EvalSummaryDetailResponseDto` SHALL include `requestBody` and `responseBody` fields (nullable `JsonNode`). These SHALL be populated by LEFT-joining `test_case_run_results` on `test_case_run_result_id` in `findById`; the export-with-bodies path SHALL use the same join. Ordinary list, count, and aggregate queries SHALL NOT join the raw-result table. `EvalSummaryRecordMapper.mapExportWithBodies()` SHALL map the body fields from the projected columns.
Status: **Implemented**

#### Scenario: Request and response body in detail view
- **WHEN** `GET /api/v1/analytics/eval-summaries/{id}` is called for an existing summary
- **THEN** the response includes `requestBody` and `responseBody` as JSON values (or omitted if null via `@JsonInclude(NON_NULL)`)

#### Scenario: List query unaffected
- **WHEN** `GET /api/v1/analytics/eval-summaries` (list) is called
- **THEN** the response items do NOT include `requestBody`, `responseBody`, or `extractionWarnings` fields

---

### Requirement: Eval summary and eval score reads exclude deleted runs
Reads of `test_case_eval_summaries` and `test_case_eval_scores` — through their respective Query DSL entities (`eval_summaries`, `test_case_eval_scores`) and through direct repository reads (`PostgresEvalSummaryRepository`'s list/export/aggregate/comparison methods; `findById`) alike — SHALL exclude rows belonging to a test suite run that has an analytics tombstone (see `run-analytics-deletion`). The exclusion SHALL be applied in the read query itself, as an anti-join against `run_deletions`.
Status: **Implemented**

#### Scenario: Eval summaries list excludes a deleted run's rows
- **WHEN** `GET /api/v1/analytics/eval-summaries` is queried with a filter that would otherwise match rows belonging to a deleted run
- **THEN** no row belonging to that run SHALL appear in the result

#### Scenario: Eval score Query DSL entity excludes a deleted run's rows
- **WHEN** a structured query against the `test_case_eval_scores` Query DSL entity would otherwise match rows belonging to a deleted run
- **THEN** no row belonging to that run SHALL appear in the result

#### Scenario: Aggregate and comparison reads exclude a deleted run's rows
- **WHEN** the eval-summary aggregate endpoint or the run-comparison matched-row logic reads rows for a run that has an analytics tombstone
- **THEN** that run's rows SHALL be excluded, consistent with every other read of `test_case_eval_summaries`

## Implementation Notes

- [Summary controller](../../../src/main/java/com/epam/aidial/evaluation/web/controller/EvalSummaryController.java) and [service](../../../src/main/java/com/epam/aidial/evaluation/service/domain/analytics/EvalSummaryService.java) implement batch/list/detail/count/aggregate contracts and structural metric-value validation.
- [Summary repository](../../../src/main/java/com/epam/aidial/evaluation/data/db/analytics/repository/PostgresEvalSummaryRepository.java) uses analyticsDsl, idempotent typed-jOOQ inserts, reduced list projection, body joins for detail/export, aggregate SQL, deterministic latest lookups and tombstone exclusion.
- [Summary mapper](../../../src/main/java/com/epam/aidial/evaluation/service/domain/mapper/EvalSummaryMapper.java) applies defaults; [record mapper](../../../src/main/java/com/epam/aidial/evaluation/data/db/analytics/mapper/EvalSummaryRecordMapper.java) has separate list/export/body projections.
- [Snapshot controller](../../../src/main/java/com/epam/aidial/evaluation/web/controller/RunMetricSnapshotController.java), [deprecated alias](../../../src/main/java/com/epam/aidial/evaluation/web/controller/RunMetricSnapshotDeprecatedController.java), [service](../../../src/main/java/com/epam/aidial/evaluation/service/domain/RunMetricSnapshotService.java), and [repository](../../../src/main/java/com/epam/aidial/evaluation/data/db/repository/PostgresRunMetricSnapshotRepository.java) use one meta-database transaction for batch checks/inserts and return flat snapshot arrays.
- [Metric executor](../../../src/main/java/com/epam/aidial/evaluation/service/domain/job/InProcessMetricEvaluationExecutor.java) writes summaries and derived per-test-case score chunks; [job](../../../src/main/java/com/epam/aidial/evaluation/service/domain/job/TestSuiteEvaluationJob.java) resolves effective per-test-case scoring independently of Phase 3.
- [Score service](../../../src/main/java/com/epam/aidial/evaluation/service/domain/analytics/TestCaseEvalScoreService.java), [score repository](../../../src/main/java/com/epam/aidial/evaluation/data/db/analytics/repository/PostgresTestCaseEvalScoreRepository.java), and [score entity](../../../src/main/java/com/epam/aidial/evaluation/query/service/repository/PostgresTestCaseEvalScoreEntityResolver.java) own internal writes and the standalone read surface. The unique key makes deduplication unnecessary; eval summaries do not expose score/pass.
- [Computation resolver](../../../src/main/java/com/epam/aidial/evaluation/service/domain/analytics/ComputationResolver.java), [filter whitelists](../../../src/main/java/com/epam/aidial/evaluation/data/db/repository/sql/FilterWhitelists.java), and [predicate builder](../../../src/main/java/com/epam/aidial/evaluation/data/db/repository/sql/WhereBuilder.java) implement computation selection and REST filters.
- [Application configuration](../../../src/main/resources/application.yml) supplies analytics batch defaults; [body-filter registration](../../../src/main/java/com/epam/aidial/evaluation/web/filter/MaxRequestBodyFilterConfiguration.java) applies the summary HTTP size limit.
- Scoring expression semantics are owned by [eval-summary-scoring](../eval-summary-scoring/spec.md); storage and the internal write path are owned here.
