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
The analytics database SHALL contain a `test_case_eval_summaries` table storing denormalized, append-only rows that combine test case context with metric computation outputs, at **per-turn** granularity (`turn_index`, `total_turns`) so that each turn of a multi-turn produces its own summary row, and a nullable **`multi_turn_id`** grouping key so that summary turn rows can be regrouped into their originating multi-turn (the summary table carries no `trace_id`, so `multi_turn_id` is its only multi-turn grouping key).
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migrations through the multi-turn-id change are applied
- **THEN** the `test_case_eval_summaries` table SHALL have columns: `id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_case_run_result_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `turn_index` (INTEGER, NOT NULL, DEFAULT 0), `total_turns` (INTEGER, NOT NULL, DEFAULT 1), `multi_turn_id` (VARCHAR(36), nullable), `computation_id` (VARCHAR(36), NOT NULL), `test_case_data` (JSONB, NOT NULL), `extracted_columns` (JSONB, NOT NULL, DEFAULT '{}'), `extraction_warnings` (JSONB, NOT NULL, DEFAULT '[]'), `execution_status` (VARCHAR(20), NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `response_status_code` (INTEGER, nullable), `metric_values` (JSONB, NOT NULL, DEFAULT '{}'), `metric_infos` (JSONB, nullable), `created_at_ms` (BIGINT, NOT NULL), `computed_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: Turn columns backfill existing rows
- **WHEN** the `ADD COLUMN turn_index INTEGER NOT NULL DEFAULT 0` / `total_turns INTEGER NOT NULL DEFAULT 1` migration is applied to a table with pre-existing single-turn summary rows
- **THEN** every pre-existing row SHALL carry `turn_index = 0` and `total_turns = 1`, in one metadata-only statement (no rewrite)

#### Scenario: multi_turn_id backfills existing rows as NULL
- **WHEN** the `ADD COLUMN IF NOT EXISTS multi_turn_id VARCHAR(36)` (nullable, no default) migration is applied to a table with pre-existing summary rows
- **THEN** every pre-existing row SHALL carry `multi_turn_id = NULL` (single-turn semantics) as a metadata-only change with no table rewrite
- **AND** `multi_turn_id` SHALL NOT be part of the UNIQUE natural key or primary key

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** the UNIQUE constraint SHALL be `(test_suite_run_id, test_case_id, run_index, turn_index, computation_id, created_at_ms)`, so each turn's summary is uniquely keyed per computation
- **AND** because `turn_index` is `NOT NULL`, the index SHALL be a plain unique index (no `NULLS NOT DISTINCT`)

#### Scenario: Indexes
- **WHEN** the migration is applied
- **THEN** indexes SHALL be created on: `(test_suite_run_id, computation_id)` for run-scoped grid queries (the primary query path), `(computation_id)` for computation-scoped queries, `(id)` for direct lookups
- **AND** a non-unique grouping index `(test_suite_run_id, multi_turn_id, created_at_ms)` SHALL exist — equality/grouping columns leading, `created_at_ms` trailing to align with the `(created_at_ms, id)` keyset spine and remain time-partition-ready.

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist (soft references to meta DB and test_case_run_results only)

#### Scenario: No updated_at column
- **WHEN** the migration is applied
- **THEN** the table SHALL NOT have an `updated_at_ms` column (rows are immutable/append-only)

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
The service SHALL support persisting eval summary rows both via the external REST API (`POST /api/v1/analytics/eval-summaries`) and via internal writes from the in-process metric evaluation engine. Both paths SHALL go through `EvalSummaryService.batchCreate()`, sharing the same validation, mapping, and persistence logic with idempotent `ON CONFLICT DO NOTHING`, whose conflict target is the natural key **including `turn_index`**: `(test_suite_run_id, test_case_id, run_index, turn_index, computation_id, created_at_ms)`. Each item MAY carry `turnIndex`, `totalTurns`, and `multiTurnId`; `turnIndex`/`totalTurns` are **optional** and default to `0` and `1` respectively when omitted, and `multiTurnId` is **optional** and defaults to NULL when omitted, so pre-existing single-turn callers remain byte-compatible.
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

#### Scenario: Turn fields default for single-turn callers
- **WHEN** a batch item omits `turnIndex` and `totalTurns`
- **THEN** the persisted row SHALL carry `turn_index = 0` and `total_turns = 1`, and be otherwise byte-identical to prior single-turn behavior

#### Scenario: multiTurnId defaults to NULL for single-turn callers
- **WHEN** a batch item omits `multiTurnId`
- **THEN** the persisted row SHALL carry `multi_turn_id = NULL`, and be otherwise byte-identical to prior single-turn behavior

#### Scenario: multiTurnId persisted when supplied
- **WHEN** a batch item includes `multiTurnId`
- **THEN** the value SHALL be persisted to the `multi_turn_id` column; it SHALL NOT affect the conflict target (distinctness is still decided by the natural key)

#### Scenario: Distinct turns are not duplicates
- **WHEN** a batch contains two items sharing `(testCaseId, runIndex, computationId)` but differing in `turnIndex`
- **THEN** both SHALL be inserted (distinct turns of one multi-turn are not duplicates); items identical on the full natural key SHALL be silently skipped via `ON CONFLICT DO NOTHING`

#### Scenario: Required fields validation
- **WHEN** required envelope fields are missing (`testSuiteId`, `testSuiteRunId`, `computationId`, `computedAtMs`) or required per-item fields are missing (`testCaseRunResultId`, `testCaseId`, `testCaseName`, `testCaseData`, `runIndex`, `executionStatus`, `execDurationMs`, `metricValues`)
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: metricValues structural validation
- **WHEN** a batch write item contains `metricValues`
- **THEN** `metricValues` SHALL be a JSON object where each key maps to a nested JSON object whose leaf values are numeric (or null). Items with non-numeric leaf values SHALL be rejected with HTTP 400 and error code `VALIDATION_ERROR`. This ensures downstream filter (`JSONB_NUMERIC`) and aggregation queries can safely cast values to numeric.

> **Implementation note — key naming**: The keys within each nested `metricValues` object are expected to be real metric output field names (not synthetic placeholders like `"error"`). The batch-write API does not enforce field name validity — that is the producer's responsibility. The metric evaluation engine ensures correct key names by extracting them from the TSMD's output schema via `OutputSchemaFieldExtractor`.

#### Scenario: Internal batch write from metric evaluation engine
- **WHEN** the in-process metric evaluation engine produces EvalSummary records
- **THEN** the `EvalSummaryBatchWriteClient` SHALL convert internal models to `EvalSummaryBatchWriteRequestDto` and delegate to `EvalSummaryService.batchCreate()`, reusing the same validation, mapping, and persistence logic as the external API. The existing batch size limit (`analytics.eval-summaries.batch.max-items`) SHALL be respected via chunking.

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
- **THEN** system SHALL resolve the latest computation_id from `run_metric_snapshots` WHERE `test_suite_run_id = :runId` ORDER BY `computed_at_ms DESC LIMIT 1`

#### Scenario: No computation exists
- **WHEN** computation resolution finds no snapshots for the run
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

### Requirement: Count eval summaries
`GET /api/v1/analytics/eval-summaries/count` SHALL return the count of eval summaries matching filters. Requires `runId` filter. Uses computation resolution (same as list).
Status: **Implemented**

#### Scenario: Count with filters
- **WHEN** client calls `GET /api/v1/analytics/eval-summaries/count` with `runId` filter and optional additional filters
- **THEN** system SHALL return `{"count": N}` matching the filter criteria for the resolved computation

### Requirement: Turn fields exposed on the eval summary API
Eval summary list and detail responses SHALL expose `turnIndex` (0-based), `totalTurns` (count), and `multiTurnId` (nullable `UUID`, omitted when null). Single-turn summaries SHALL report `turnIndex = 0, totalTurns = 1` and omit `multiTurnId`. The batch-write envelope from the metric evaluation engine SHALL carry `turnIndex`/`totalTurns`/`multiTurnId` copied verbatim from each source `TestCaseRunResult`.
Status: **Implemented**

#### Scenario: Multi-turn summary rows carry turn fields
- **WHEN** a 3-turn multi-turn is evaluated under one computation
- **THEN** three summary rows SHALL exist for the same `(testCaseId, runIndex, computationId)`, with `turnIndex` `0`, `1`, `2` and `totalTurns` `3`

#### Scenario: Multi-turn summary rows carry the shared multiTurnId
- **WHEN** the same 3-turn multi-turn is evaluated
- **THEN** all three summary rows SHALL expose the same non-null `multiTurnId` equal to the source multi-turn's id, so a client can group them without a `trace_id`

#### Scenario: Single-turn summary is unchanged in shape
- **WHEN** a non-multi-turn suite is evaluated
- **THEN** the summary row SHALL carry `turnIndex = 0` and `totalTurns = 1`, omit `multiTurnId`, and otherwise be byte-identical to prior behavior

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
Metric computations SHALL be versioned via `computation_id` with no mutable `is_latest` flag. "Latest" SHALL be resolved at query time.
Status: **Implemented**

#### Scenario: Recalculation creates new computation
- **WHEN** metrics are recalculated for a run
- **THEN** the metric computation pipeline SHALL generate a new `computation_id`, insert new eval summary rows and new run metric snapshots, without modifying existing rows

#### Scenario: Latest resolution
- **WHEN** the API needs to determine the latest computation for a run
- **THEN** it SHALL query `run_metric_snapshots` for the maximum `computed_at_ms` for that run and use the corresponding `computation_id`

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

The `test_case_eval_summaries` table SHALL have an `extraction_warnings` column (`JSONB NOT NULL DEFAULT '[]'`), added via Flyway migration V1.7. `EvalSummary` model, `EvalSummaryRowMapper`, batch-insert SQL, and `EvalSummaryDetailResponseDto` SHALL include `extractionWarnings`. The value SHALL be copied from `TestCaseRunResult.extractionWarnings` at eval summary write time. If the source value is null, the mapper SHALL default it to `"[]"`.
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

`EvalSummaryDetailResponseDto` SHALL include `requestBody` and `responseBody` fields (nullable `JsonNode`). These SHALL be populated by LEFT-joining `test_case_run_results` on `test_case_run_result_id` in the `findById` query only (`SELECT_BY_ID_DETAIL_SQL`). The list query (`findAll`, count, aggregate) SHALL NOT include this join. `EvalSummaryRowMapper` SHALL use `hasColumn()` to conditionally map these fields.
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
- Service: `EvalSummaryService` in `service.domain.analytics` — reads run from meta for validation, writes to analytics; `RunMetricSnapshotService` in `service.domain.analytics`
- Repository: `PostgresEvalSummaryRepository` — `@Qualifier("analyticsJdbcTemplate")`, batch insert with ON CONFLICT DO NOTHING; `findAll()`/`count()`/`aggregate()` use `SELECT_LIST_COLUMNS` (excludes `metric_infos`, `extraction_warnings`, `request_body`, `response_body`); `findById()` uses `SELECT_BY_ID_DETAIL_SQL` with LEFT JOIN on `test_case_run_results` to include `request_body`, `response_body`, and all columns including `metric_infos` and `extraction_warnings`; `PostgresRunMetricSnapshotRepository` — same qualifier
- Model: `EvalSummary` — JSONB fields as `String` (raw JSON) in data model; includes `extractionWarnings`, `requestBody`, `responseBody` (nullable); `RunMetricSnapshot` — bindings as `String` (raw JSON)
- Cursor: Reuse existing `Cursor` record and `CursorCodec` from analytics layer
- Mapper: `EvalSummaryMapper` (MapStruct) — maps between model and DTOs; `@AfterMapping` defaults `extractionWarnings` to `"[]"` if null; `RunMetricSnapshotMapper`
- DTOs: `EvalSummaryResponseDto` (excludes `metricInfos`, `extractionWarnings`, `requestBody`, `responseBody` for list), `EvalSummaryDetailResponseDto` (includes all fields for get-by-id, nullable fields use `@JsonInclude(NON_NULL)`), `EvalSummaryBatchWriteRequestDto`, `RunMetricSnapshotResponseDto`, `RunMetricSnapshotBatchWriteRequestDto`, `MetricAggregationResponseDto`
- Filter whitelist: New `FilterWhitelists.EVAL_SUMMARIES` with JSONB_NUMERIC type for metric value filtering
- Migrations: `V1.5__CreateTestCaseEvalSummariesTable.sql`, `V1.6__CreateRunMetricSnapshotsTable.sql`, `V1.7__AddExtractionWarningsToEvalSummaries.sql`, `V1.8__NormalizeErrorShapedMetricValues.sql` in `db/migration/analytics/POSTGRES/`
