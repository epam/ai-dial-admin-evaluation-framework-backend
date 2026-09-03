# Metrics Storage

## Purpose
This spec defines the eval summary storage layer — a denormalized, append-only analytics table for metric-enriched test case results, along with computation snapshot tracking and REST APIs for grid rendering, filtering, aggregation, and recalculation comparison.

Status: **Implemented**

## Key Terms
- **EvalSummary**: One row per (test case, run, run_index, computation) containing test case context + all metric scores. The primary analytical surface for the grid UI.
- **RunMetricSnapshot**: Per-(computation, TSMD) record capturing binding configs and metric declaration versions used during a specific metric computation.
- **computation_id**: UUID identifying a metric computation batch. All eval summaries and snapshots from one computation share the same computation_id.
- **metric_values**: Compact JSONB column (~1-2 KB) containing metric output values only. Used for filtering, sorting, and grid display.
- **metric_infos**: Detailed JSONB column (~5-25 KB) containing metric output info/metadata. Lazy-loaded for drill-down views only.

## Requirements

### Requirement: Database schema for eval summaries
The analytics database SHALL contain a `test_case_eval_summaries` table storing denormalized, append-only rows that combine test case context with metric computation outputs, including per-turn columns, per-request columns, and a summed metric-evaluation latency column.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migrations V1.5, V1.7, V1.14, V1.16, and V1.18 are applied
- **THEN** the `test_case_eval_summaries` table SHALL have columns: `id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_case_run_result_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `computation_id` (VARCHAR(36), NOT NULL), `test_case_data` (JSONB, NOT NULL), `extracted_columns` (JSONB, NOT NULL, DEFAULT '{}'), `extraction_warnings` (JSONB, NOT NULL, DEFAULT '[]'), `execution_status` (VARCHAR(20), NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `metric_eval_duration_ms` (BIGINT, NOT NULL, DEFAULT 0), `response_status_code` (INTEGER, nullable), `metric_values` (JSONB, NOT NULL, DEFAULT '{}'), `metric_infos` (JSONB, nullable), `turn_index` (INTEGER, NOT NULL, DEFAULT 0), `total_turns` (INTEGER, NOT NULL, DEFAULT 1), `request_index` (INTEGER, NOT NULL, DEFAULT 0), `total_requests` (INTEGER, NOT NULL, DEFAULT 1), `created_at_ms` (BIGINT, NOT NULL), `computed_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** the unique index `uq_eval_summaries_natural_key` SHALL be `(test_suite_run_id, test_case_id, run_index, request_index, turn_index, computation_id, created_at_ms)` — extended with `turn_index` and then `request_index` (positioned immediately after `run_index`, dropping and recreating the prior index each time) so each (request, turn) pair is uniquely keyed per computation

#### Scenario: Turn columns added
- **WHEN** the analytics Flyway migration `V1.14__AddTurnColumnsToEvalSummaries.sql` is applied
- **THEN** `turn_index` (INTEGER, NOT NULL, DEFAULT 0) and `total_turns` (INTEGER, NOT NULL, DEFAULT 1) columns SHALL be added to `test_case_eval_summaries`, and existing rows backfill to those defaults

#### Scenario: Request columns added
- **WHEN** the analytics Flyway migration `V1.18__AddRequestColumnsToEvalSummaries.sql` is applied
- **THEN** `request_index` (INTEGER, NOT NULL, DEFAULT 0) and `total_requests` (INTEGER, NOT NULL, DEFAULT 1) columns SHALL be added to `test_case_eval_summaries`, and existing rows backfill to those defaults

#### Scenario: Metric evaluation latency column added
- **WHEN** the analytics Flyway migration `V1.16__AddMetricEvalDurationToEvalSummaries.sql` is applied
- **THEN** a `metric_eval_duration_ms` (BIGINT, NOT NULL, DEFAULT 0) column SHALL be added to `test_case_eval_summaries`, and existing rows backfill to `0`. The column SHALL become filterable, sortable, and aggregatable (e.g. `avg`, `sum`) through the structured Query DSL on the `eval_summaries` entity automatically, with no changes to the entity resolver or function registry, because DSL field bindings are derived at runtime from the generated jOOQ table's columns

#### Scenario: Indexes
- **WHEN** the migration is applied
- **THEN** indexes SHALL be created on: `(test_suite_run_id, computation_id)` for run-scoped grid queries (the primary query path), `(computation_id)` for computation-scoped queries, `(id)` for direct lookups, and `(test_suite_run_id, computed_at_ms DESC, computation_id)` for latest-computation resolution. No new index is added for the request columns.

#### Scenario: Latest-computation resolution is a top-1 index probe
- **WHEN** the analytics Flyway migration `V1.15__AddEvalSummariesRunComputedAtIndex.sql` is applied
- **THEN** `idx_eval_summaries_run_computed_at` SHALL exist on `(test_suite_run_id, computed_at_ms DESC, computation_id)`, so `WHERE test_suite_run_id = ? ORDER BY computed_at_ms DESC LIMIT 1` is served as a single top-1 index descent with `computation_id` available from the index tuple rather than a scan of the run's rows plus a sort

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist (soft references to meta DB and test_case_run_results only)

#### Scenario: No updated_at column
- **WHEN** the migration is applied
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

A metric-less run SHALL continue to write one eval summary per result row (`metric_values = {}`, `metric_infos` JSON null, no `run_metric_snapshots`) for every chain call, so `test_case_eval_summaries` stays the single read surface for all rows of a chained run.
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

#### Scenario: Legacy summaries keep the defaults
- **WHEN** an eval summary is written for a single-request suite
- **THEN** `request_index` SHALL be `0` and `total_requests` SHALL be `1` from the defaults

### Requirement: Database schema for run metric snapshots
The analytics database SHALL contain a `run_metric_snapshots` table storing per-computation binding and version snapshots.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migration V1.6 is applied
- **THEN** the `run_metric_snapshots` table SHALL have columns: `id` (VARCHAR(36), NOT NULL, PK), `computation_id` (VARCHAR(36), NOT NULL), `test_suite_run_id` (VARCHAR(36), NOT NULL), `tsmd_id` (VARCHAR(36), NOT NULL), `tsmd_name` (VARCHAR(255), NOT NULL), `metric_declaration_id` (VARCHAR(36), NOT NULL), `metric_declaration_version_id` (VARCHAR(36), NOT NULL), `config_bindings` (JSONB, NOT NULL, DEFAULT '[]'), `input_bindings` (JSONB, NOT NULL, DEFAULT '[]'), `output_schema` (JSONB, NOT NULL, DEFAULT '{}'), `computed_at_ms` (BIGINT, NOT NULL)

#### Scenario: UNIQUE constraint
- **WHEN** the migration is applied
- **THEN** a UNIQUE constraint SHALL exist on `(computation_id, tsmd_id)`

#### Scenario: Index for run lookup
- **WHEN** the migration is applied
- **THEN** an index SHALL exist on `(test_suite_run_id)` for listing snapshots by run

### Requirement: Batch write eval summaries
The service SHALL support persisting eval summary rows both via the external REST API (`POST /api/v1/analytics/eval-summaries`) and via internal writes from the in-process metric evaluation engine. Both paths SHALL go through `EvalSummaryService.batchCreate()`, sharing the same validation, mapping, and persistence logic with idempotent `ON CONFLICT DO NOTHING`. Batch items MAY optionally carry a client-generated `id`; when omitted, the service generates one. `test_case_eval_summaries` itself carries no `score`/`passed` columns — those are computed and stored separately (see the new `test_case_eval_scores` table requirement).
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
- **THEN** the `EvalSummaryBatchWriteClient` SHALL convert internal models to `EvalSummaryBatchWriteRequestDto` and delegate to `EvalSummaryService.batchCreate()`, reusing the same validation, mapping, and persistence logic as the external API. The existing batch size limit (`analytics.eval-summaries.batch.max-items`) SHALL be respected via chunking. Each item's `id` SHALL be generated by the engine before this call (not left to the service), so that a subsequent per-row score computation can reference it without a re-query.

#### Scenario: Optional client-generated id accepted and used verbatim
- **WHEN** a batch write item carries an `id`
- **THEN** the service SHALL persist that id verbatim as the row's `id` rather than generating a new one

#### Scenario: Omitted id falls back to server generation
- **WHEN** a batch write item omits `id`
- **THEN** the service SHALL generate one, preserving the existing external-API contract

### Requirement: Database schema for eval summary scores
The analytics database SHALL contain a `test_case_eval_scores` table storing per-row overall score/pass-fail, computed via SQL and joined into the eval-summary read surface (not native columns on `test_case_eval_summaries`). The table SHALL carry no denormalized run/computation/test-case context — every read reaches it via a join to `test_case_eval_summaries`, which already carries that context — so `eval_summary_id` is the only key needed.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migration V1.19 is applied
- **THEN** the `test_case_eval_scores` table SHALL have exactly the columns: `eval_summary_id` (VARCHAR(36), NOT NULL, PK), `score` (DOUBLE PRECISION, nullable), `passed` (BOOLEAN, nullable), `computed_at_ms` (BIGINT, NOT NULL) — no other columns and no secondary indexes

#### Scenario: Primary key is the scored row's own id
- **WHEN** the migration is applied
- **THEN** `eval_summary_id` SHALL be the primary key — a 1:1 (or 0:1, since a row without a computable score is simply never inserted) relationship with `test_case_eval_summaries.id`, needing no surrogate PK

#### Scenario: A row's absence and a present-but-null row read identically
- **WHEN** a `test_case_eval_summaries` row has no matching `test_case_eval_scores` row (no `overallScore` configured, or a rejected query shape), versus a matching row whose `score` is itself SQL NULL (e.g. a population-dependent CustomFunction)
- **THEN** both SHALL read back as `score = null, passed = null` via the LEFT JOIN — a client cannot and need not distinguish the two cases

### Requirement: Batch write eval summary scores (internal only)
The in-process metric evaluation engine SHALL write `test_case_eval_scores` rows via `TestCaseEvalScoreService.batchCreate()`, one batch per Phase-2 flush, immediately after that flush's `test_case_eval_summaries` batch write succeeds. There SHALL be no external REST endpoint for this table — it is populated only by the internal engine and read only via the LEFT JOIN into the existing eval-summary endpoints.
Status: **Implemented**

#### Scenario: One score batch write per flush
- **WHEN** a Phase-2 flush writes N `test_case_eval_summaries` rows and the suite has an `overallScore` definition configured
- **THEN** at most one `test_case_eval_scores` batch write SHALL follow, containing an entry for every row id the score computation returned a result for

#### Scenario: Idempotent retry
- **WHEN** a score batch write is retried for ids already present
- **THEN** the insert SHALL use `ON CONFLICT (eval_summary_id) DO NOTHING`, so duplicates are silently skipped

#### Scenario: A failed score write does not fail the run
- **WHEN** the score computation or batch write throws an unexpected error
- **THEN** the error SHALL be logged and the run SHALL continue — `score`/`passed` are regenerable derived data, unlike the eval summaries themselves

### Requirement: Overall score and pass/fail exposed in eval summary responses
`EvalSummaryResponseDto` and `EvalSummaryDetailResponseDto` SHALL expose `score` (Double, nullable) and `passed` (Boolean, nullable), populated via a LEFT JOIN to `test_case_eval_scores` in all four `PostgresEvalSummaryRepository` query builders (list, export, export-with-bodies, detail) — not a native column on `test_case_eval_summaries`. Both fields SHALL be omitted from the JSON payload when `null` (`@JsonInclude(NON_NULL)`), consistent with other optional eval-summary fields.
Status: **Implemented**

#### Scenario: List response includes score and passed
- **WHEN** a client calls the eval summaries list endpoint for a run whose rows have a matching `test_case_eval_scores` row
- **THEN** each returned item SHALL include `score` and `passed`, reflecting the joined values

#### Scenario: Detail response includes score and passed
- **WHEN** a client calls the get-single-eval-summary endpoint
- **THEN** the response SHALL include `score` and `passed`, populated via the same join

#### Scenario: Null score/passed omitted from the response
- **WHEN** a row has no matching `test_case_eval_scores` row, or one with `score = NULL`
- **THEN** the response SHALL omit `score`/`passed` from the JSON payload rather than emitting explicit `null` values

### Requirement: Batch write run metric snapshots
The service SHALL support persisting run metric snapshots both via the external REST API (`POST /api/v1/analytics/run-metric-snapshots`) and via internal writes from the in-process metric evaluation engine. Both paths SHALL go through `RunMetricSnapshotService.batchCreate()`, sharing the same validation, mapping, and persistence logic with idempotent `ON CONFLICT DO NOTHING`.
Status: **Implemented**

#### Scenario: Successful batch write
- **WHEN** client calls `POST /api/v1/analytics/run-metric-snapshots` with a valid envelope containing `testSuiteRunId`, `computationId`, `computedAtMs`, and `snapshots` array
- **THEN** system SHALL insert all snapshots atomically and return HTTP 201. The envelope's `computedAtMs` SHALL be applied to all inserted rows.

#### Scenario: Run existence validation
- **WHEN** a batch write is processed
- **THEN** the service SHALL read the run from meta DB. If not found, return HTTP 404

#### Scenario: Internal write from metric evaluation engine
- **WHEN** the in-process metric evaluation engine captures RunMetricSnapshots before evaluation
- **THEN** the `RunMetricSnapshotBatchWriteClient` SHALL convert internal models to `RunMetricSnapshotBatchWriteRequestDto` and delegate to `RunMetricSnapshotService.batchCreate()`, reusing the same validation, mapping, and persistence logic as the external API.

### Requirement: List eval summaries with keyset pagination and filters
The service SHALL provide `GET /api/v1/analytics/eval-summaries` with cursor-based pagination, `filter=field:operator:value` syntax, and computation resolution.
Status: **Implemented**

#### Scenario: Computation parameter required
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
- **THEN** system SHALL filter using the existing filter framework (eq, ne, contains operators as applicable)

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
`GET /api/v1/analytics/eval-summaries/{id}` SHALL return a single eval summary including `metricValues`, `metricInfos`, `extractionWarnings`, `requestBody`, and `responseBody`, or HTTP 404 if not found. The `requestBody` and `responseBody` fields are populated by LEFT-joining `test_case_run_results` on `test_case_run_result_id` in the `findById` query only. The list query SHALL NOT perform this join.
Status: **Implemented**

#### Scenario: Successful retrieval with full detail
- **WHEN** client calls `GET /api/v1/analytics/eval-summaries/{id}` and the eval summary exists
- **THEN** system SHALL return HTTP 200 with the full eval summary including `metricValues`, `metricInfos`, `extractionWarnings`, `requestBody`, and `responseBody`

#### Scenario: Not found
- **WHEN** client calls `GET /api/v1/analytics/eval-summaries/{id}` and no eval summary exists with that ID
- **THEN** system SHALL return HTTP 404

#### Scenario: Missing run result row (LEFT JOIN)
- **WHEN** the referenced `test_case_run_result_id` no longer exists in `test_case_run_results`
- **THEN** the detail response returns the eval summary with `requestBody: null` and `responseBody: null` (LEFT JOIN behavior)

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

#### Scenario: Empty result
- **WHEN** no eval summaries match the filters or all metric values are null
- **THEN** system SHALL return HTTP 200 with `computationId` and empty `metrics` array

### Requirement: List run metric snapshots
`GET /api/v1/analytics/run-metric-snapshots` SHALL return metric binding snapshots for a run, grouped by computation.
Status: **Implemented**

#### Scenario: List snapshots by run
- **WHEN** client calls `GET /api/v1/analytics/run-metric-snapshots?filter=runId:eq:...`
- **THEN** system SHALL return all metric snapshots for that run, ordered by `computed_at_ms DESC`

#### Scenario: Required filter — runId
- **WHEN** client queries without `runId:eq:...` filter
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Response includes full binding detail
- **WHEN** snapshots are returned
- **THEN** each snapshot SHALL include: `id`, `computationId`, `testSuiteRunId`, `tsmdId`, `tsmdName`, `metricDeclarationId`, `metricDeclarationVersionId`, `configBindings` (as JSON array), `inputBindings` (as JSON array), `outputSchema` (as JSON object), `computedAtMs`

### Requirement: Configuration properties for eval summaries
Configurable limits for eval summary batch writes.
Status: **Implemented**

#### Scenario: Batch max items
- **WHEN** application starts
- **THEN** `analytics.eval-summaries.batch.max-items` SHALL be configurable with a default of 10000

#### Scenario: Batch max request size
- **WHEN** application starts
- **THEN** `analytics.eval-summaries.batch.max-request-size-bytes` SHALL be configurable with a default of 10485760 (10 MB)

### Requirement: Computation versioning model
Metric computations SHALL be versioned via `computation_id` with no mutable `is_latest` flag. "Latest" SHALL be resolved at query time, from the table the caller reads.
Status: **Implemented**

#### Scenario: Recalculation creates new computation
- **WHEN** metrics are recalculated for a run
- **THEN** the metric computation pipeline SHALL generate a new `computation_id`, insert new eval summary rows and new run metric snapshots, without modifying existing rows

#### Scenario: Latest resolution
- **WHEN** the API needs to determine the latest computation for a run
- **THEN** it SHALL query `test_case_eval_summaries` for the maximum `computed_at_ms` for that run and use the corresponding `computation_id`

#### Scenario: Latest resolution is independent of a run's row count
- **WHEN** the latest computation of a run is resolved and the run has many eval summary rows spread across more than one computation
- **THEN** resolution SHALL return the `computation_id` with the greatest `computed_at_ms` for that run, whatever the number of rows involved

#### Scenario: Latest resolution requires readable rows
- **WHEN** a computation wrote `run_metric_snapshots` rows for a run but wrote no eval summaries (e.g. its batch write failed)
- **THEN** latest resolution SHALL NOT select that computation, and SHALL select the run's most recent computation that does have eval summaries

#### Scenario: Metric-catalog lookups stay on run metric snapshots
- **WHEN** a caller needs the metric column families of a run's latest computation rather than its readable rows (Query DSL detailed schema discovery)
- **THEN** it SHALL resolve that computation from `run_metric_snapshots` and SHALL return no metric families for a run that has none

#### Scenario: Comparison between computations
- **WHEN** client provides two computation UUIDs
- **THEN** the list endpoint SHALL support filtering by specific `computation_id` to enable side-by-side comparison

### Requirement: Filter framework extension for eval summary JSONB filtering
The filter framework SHALL support JSONB path filtering on `metric_values` and `test_case_data` columns for eval summaries. A new `JSONB_NUMERIC` field type SHALL be added to `FilterFieldType` to handle two-level JSONB paths with numeric casting. The existing `WhereBuilder` currently rejects nested JSONB paths (dot in jsonbKey); this restriction SHALL be relaxed for `JSONB_NUMERIC` fields.
Status: **Implemented**

#### Scenario: New JSONB_NUMERIC FilterFieldType
- **WHEN** the filter framework is extended
- **THEN** `FilterFieldType` enum SHALL include a new `JSONB_NUMERIC` value. `WhereBuilder.parseValue()` SHALL parse raw values as `BigDecimal` (via `new BigDecimal(rawValue)`) for this type, ensuring exact decimal representation when bound as a JDBC `numeric` parameter — avoiding IEEE 754 floating-point precision loss that `Double` would introduce. `WhereBuilder.validateOperator()` SHALL allow comparison operators (EQ, NE, GT, GTE, LT, LTE) for `JSONB_NUMERIC`.

#### Scenario: Two-level JSONB path parsing in WhereBuilder
- **WHEN** a filter field uses dot notation and resolves to a `JSONB_NUMERIC` field (e.g., `metricValues.Accuracy.score`)
- **THEN** `WhereBuilder` SHALL split the jsonbKey on the FIRST dot to produce two parameterized path components (`jsonbKey1 = "Accuracy"`, `jsonbKey2 = "score"`). The current `jsonbKey.contains(".")` rejection in WhereBuilder (lines 61-63) SHALL be bypassed for `JSONB_NUMERIC` fields. Only two-level paths SHALL be supported; deeper nesting SHALL be rejected.

#### Scenario: JSONB_NUMERIC SQL predicate generation
- **WHEN** a `JSONB_NUMERIC` filter condition is built
- **THEN** `WhereBuilder` SHALL generate a new predicate using the arrow operator with numeric cast: `(column->:jsonbKey1Param->>:jsonbKey2Param)::numeric <op> :valueParam` where `:jsonbKey1Param` and `:jsonbKey2Param` are named parameters bound as strings (NOT quoted literals) to prevent SQL injection, and `<op>` is the comparison operator.

#### Scenario: Metric value filter usage
- **WHEN** client includes `filter=metricValues.Accuracy.score:ge:0.8`
- **THEN** WhereBuilder SHALL produce SQL: `(metric_values->:jsonbKey1_N->>:jsonbKey2_N)::numeric >= :filter_N` with params `jsonbKey1_N = "Accuracy"`, `jsonbKey2_N = "score"`, `filter_N = 0.8`

#### Scenario: Single-level JSONB_STRING unchanged
- **WHEN** a filter field uses dot notation and resolves to a `JSONB_STRING` field (e.g., `testCaseData.prompt`)
- **THEN** existing single-level behavior SHALL be preserved. The `jsonbKey.contains(".")` rejection SHALL still apply for `JSONB_STRING` fields.

#### Scenario: Filter whitelist
- **WHEN** the eval summaries filter whitelist is configured
- **THEN** it SHALL include fields: `suiteId` (UUID), `runId` (UUID), `testCaseId` (UUID), `testCaseName` (STRING), `executionStatus` (STRING), `runIndex` (LONG), `execDurationMs` (LONG), `responseStatusCode` (LONG), `testCaseData` (JSONB_STRING), `metricValues` (JSONB_NUMERIC)

### Requirement: Extraction warnings in eval summary

The `test_case_eval_summaries` table SHALL have an `extraction_warnings` column (`JSONB NOT NULL DEFAULT '[]'`), added via Flyway migration V1.7. `EvalSummary` model, `EvalSummaryRecordMapper`, batch-insert SQL, and `EvalSummaryDetailResponseDto` SHALL include `extractionWarnings`. The value SHALL be copied from `TestCaseRunResult.extractionWarnings` at eval summary write time. If the source value is null, the mapper SHALL default it to `"[]"`.
Status: **Implemented**

#### Scenario: Extraction warnings persisted
- **WHEN** a test case run result has extraction warnings
- **THEN** the corresponding eval summary row has `extraction_warnings` set to the serialized warnings array

#### Scenario: No extraction warnings
- **WHEN** a test case run result has no extraction warnings (null)
- **THEN** the eval summary row has `extraction_warnings = '[]'` (defaulted by `@AfterMapping`)

#### Scenario: Extraction warnings in API response
- **WHEN** `GET /api/v1/eval-summaries/{id}` is called
- **THEN** the response includes `extractionWarnings` as a JSON value (omitted from list response via `@JsonInclude(NON_NULL)`)

---

### Requirement: Request and response body in eval summary detail

`EvalSummaryDetailResponseDto` SHALL include `requestBody` and `responseBody` fields (nullable `JsonNode`). These SHALL be populated by LEFT-joining `test_case_run_results` on `test_case_run_result_id` in the `findById` query only (`SELECT_BY_ID_DETAIL_SQL`). The list query (`findAll`, count, aggregate) SHALL NOT include this join. `EvalSummaryRecordMapper` SHALL use `hasColumn()` to conditionally map these fields.
Status: **Implemented**

#### Scenario: Request and response body in detail view
- **WHEN** `GET /api/v1/eval-summaries/{id}` is called for an existing summary
- **THEN** the response includes `requestBody` and `responseBody` as JSON values (or omitted if null via `@JsonInclude(NON_NULL)`)

#### Scenario: List query unaffected
- **WHEN** `GET /api/v1/eval-summaries` (list) is called
- **THEN** the response items do NOT include `requestBody`, `responseBody`, or `extractionWarnings` fields

---

## Implementation Notes
- Controller: `EvalSummaryController` — `@LogExecution`, `@Validated`; `RunMetricSnapshotController` — `@LogExecution`, `@Validated`
- Service: `EvalSummaryService` in `service.domain.analytics` — reads run from meta for validation, writes to analytics; `RunMetricSnapshotService` in `service.domain.analytics`; `TestCaseEvalScoreService.batchCreate(computedAtMs, items)` in `service.domain.analytics` — `@Transactional("analyticsTransactionManager")`, internal-only (no controller, no external REST endpoint)
- Repository: `PostgresEvalSummaryRepository` — typed jOOQ `DSLContext` with `@Qualifier("analyticsDsl")`, batch insert with ON CONFLICT DO NOTHING; `findAll()`/`count()`/`aggregate()` project a list-tier column set (excludes `metric_infos`, `extraction_warnings`, `request_body`, `response_body`); `findById()` (and the export-with-bodies list query) LEFT JOINs `test_case_run_results` to include `request_body`, `response_body`, and all columns including `metric_infos` and `extraction_warnings`; also LEFT JOINs `test_case_eval_scores` (all four query builders) to expose `score`/`passed`; `findLatestComputationId(runId)` resolves "latest" for the read path; `existsByRunIdAndComputationId(runId, computationId)` answers export's explicit-computation existence check via `fetchExists`; `PostgresRunMetricSnapshotRepository` — same qualifier, and its own `findLatestComputationId` is retained for Query DSL metric-family discovery only; `TestCaseEvalScoreRepository`/`PostgresTestCaseEvalScoreRepository` — `saveAll`, jOOQ batch insert, `onConflict(EVAL_SUMMARY_ID).doNothing()`
- Computation resolution: `ComputationResolver` in `service.domain.analytics` — maps `computation` (explicit UUID | `latest` | `null`) to a `computationId`, resolving `latest` through `EvalSummaryRepository.findLatestComputationId`; shared by list, count, aggregate, export, preview, and the `metric_score_results` `"latest"`-sentinel path
- Model: `EvalSummary` — JSONB fields as `String` (raw JSON) in data model; includes `extractionWarnings`, `requestBody`, `responseBody` (nullable); `RunMetricSnapshot` — bindings as `String` (raw JSON); `TestCaseEvalScore` (`data.db.analytics.model`) — `evalSummaryId`, `score` (nullable), `passed` (nullable), `computedAtMs`
- Cursor: Reuse existing `Cursor` record and `CursorCodec` from analytics layer
- Mapper: `EvalSummaryMapper` (MapStruct) — maps between model and DTOs; `@AfterMapping` defaults `extractionWarnings` to `"[]"` if null; `RunMetricSnapshotMapper`
- DTOs: `EvalSummaryResponseDto` (excludes `metricInfos`, `extractionWarnings`, `requestBody`, `responseBody` for list; includes `score`/`passed`, `@JsonInclude(NON_NULL)`), `EvalSummaryDetailResponseDto` (includes all fields for get-by-id, nullable fields use `@JsonInclude(NON_NULL)`, including `score`/`passed`), `EvalSummaryBatchWriteRequestDto` (items MAY carry `id`), `RunMetricSnapshotResponseDto`, `RunMetricSnapshotBatchWriteRequestDto`, `MetricAggregationResponseDto`, `TestCaseEvalScoreBatchWriteItemDto` (`service.domain.dto.analytics`: `evalSummaryId`, `score`, `passed`)
- Filter whitelist: New `FilterWhitelists.EVAL_SUMMARIES` with JSONB_NUMERIC type for metric value filtering
- Migrations: `V1.5__CreateTestCaseEvalSummariesTable.sql`, `V1.6__CreateRunMetricSnapshotsTable.sql`, `V1.7__AddExtractionWarningsToEvalSummaries.sql`, `V1.8__NormalizeErrorShapedMetricValues.sql`, `V1.15__AddEvalSummariesRunComputedAtIndex.sql`, `V1.19__CreateTestCaseEvalScoresTable.sql` in `db/migration/analytics/POSTGRES/`
- Computation semantics (per-row `GROUP BY id` graft, `CustomFunction` handling, threshold comparison) are owned by the `eval-summary-scoring` capability; this capability owns storage (`test_case_eval_scores`), the internal batch-write path, and API exposure via the join.
- Filtering/sorting the list endpoint by `score`/`passed` is explicitly deferred to a follow-up change.
