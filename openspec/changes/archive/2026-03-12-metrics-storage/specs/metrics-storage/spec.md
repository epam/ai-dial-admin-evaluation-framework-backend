# Metrics Storage

## Purpose
This spec defines the eval summary storage layer — a denormalized, append-only analytics table for metric-enriched test case results, along with computation snapshot tracking and REST APIs for grid rendering, filtering, aggregation, and recalculation comparison.

Status: **Planned**

## Key Terms
- **EvalSummary**: One row per (test case, run, run_index, computation) containing test case context + all metric scores. The primary analytical surface for the grid UI.
- **RunMetricSnapshot**: Per-(computation, TSMD) record capturing binding configs and metric declaration versions used during a specific metric computation.
- **computation_id**: UUID identifying a metric computation batch. All eval summaries and snapshots from one computation share the same computation_id.
- **metric_values**: Compact JSONB column (~1-2 KB) containing metric output values only. Used for filtering, sorting, and grid display.
- **metric_infos**: Detailed JSONB column (~5-25 KB) containing metric output info/metadata. Lazy-loaded for drill-down views only.

## Requirements

### Requirement: Database schema for eval summaries
The analytics database SHALL contain a `test_case_eval_summaries` table storing denormalized, append-only rows that combine test case context with metric computation outputs.
Status: **Planned**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migration V1.5 is applied
- **THEN** the `test_case_eval_summaries` table SHALL have columns: `id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_case_run_result_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `computation_id` (VARCHAR(36), NOT NULL), `test_case_data` (JSONB, NOT NULL), `extracted_columns` (JSONB, NOT NULL, DEFAULT '{}'), `execution_status` (VARCHAR(20), NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `response_status_code` (INTEGER, nullable), `metric_values` (JSONB, NOT NULL, DEFAULT '{}'), `metric_infos` (JSONB, nullable), `created_at_ms` (BIGINT, NOT NULL), `computed_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** a UNIQUE constraint SHALL exist on `(test_suite_run_id, test_case_id, run_index, computation_id, created_at_ms)`

#### Scenario: Indexes
- **WHEN** the migration is applied
- **THEN** indexes SHALL be created on: `(test_suite_run_id, computation_id)` for run-scoped grid queries (the primary query path), `(computation_id)` for computation-scoped queries, `(id)` for direct lookups

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist (soft references to meta DB and test_case_run_results only)

#### Scenario: No updated_at column
- **WHEN** the migration is applied
- **THEN** the table SHALL NOT have an `updated_at_ms` column (rows are immutable/append-only)

### Requirement: Database schema for run metric snapshots
The analytics database SHALL contain a `run_metric_snapshots` table storing per-computation binding and version snapshots.
Status: **Planned**

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
The service SHALL provide `POST /api/v1/analytics/eval-summaries` to persist a batch of eval summary rows for a given run and computation. Idempotent via ON CONFLICT DO NOTHING.
Status: **Planned**

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
- **WHEN** required envelope fields are missing (`testSuiteId`, `testSuiteRunId`, `computationId`, `computedAtMs`) or required per-item fields are missing (`testCaseRunResultId`, `testCaseId`, `testCaseName`, `testCaseData`, `runIndex`, `executionStatus`, `execDurationMs`, `metricValues`)
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: metricValues structural validation
- **WHEN** a batch write item contains `metricValues`
- **THEN** `metricValues` SHALL be a JSON object where each key maps to a nested JSON object whose leaf values are numeric (or null). Items with non-numeric leaf values SHALL be rejected with HTTP 400 and error code `VALIDATION_ERROR`. This ensures downstream filter (`JSONB_NUMERIC`) and aggregation queries can safely cast values to numeric.

### Requirement: Batch write run metric snapshots
The service SHALL provide `POST /api/v1/analytics/run-metric-snapshots` to persist binding snapshots for a computation. Idempotent via ON CONFLICT DO NOTHING.
Status: **Planned**

#### Scenario: Successful batch write
- **WHEN** client calls `POST /api/v1/analytics/run-metric-snapshots` with a valid envelope containing `testSuiteRunId`, `computationId`, `computedAtMs`, and `snapshots` array
- **THEN** system SHALL insert all snapshots atomically and return HTTP 201. The envelope's `computedAtMs` SHALL be applied to all inserted rows.

#### Scenario: Run existence validation
- **WHEN** a batch write is processed
- **THEN** the service SHALL read the run from meta DB. If not found, return HTTP 404

### Requirement: List eval summaries with keyset pagination and filters
The service SHALL provide `GET /api/v1/analytics/eval-summaries` with cursor-based pagination, `filter=field:operator:value` syntax, and computation resolution.
Status: **Planned**

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
- **THEN** system SHALL filter using standard operators (eq, gte, lte, etc.)

#### Scenario: Filter by metric values via JSONB path
- **WHEN** client includes `filter=metricValues.<metricName>.<outputName>:<op>:<value>` (e.g., `metricValues.Accuracy.score:gte:0.8`)
- **THEN** system SHALL filter using JSONB path extraction on the `metric_values` column with parameterized path components

#### Scenario: Filter by testCaseData JSONB path
- **WHEN** client includes `filter=testCaseData.<key>:<op>:<value>`
- **THEN** system SHALL filter using JSONB path extraction on the `test_case_data` column (same pattern as existing analytics results)

#### Scenario: Response excludes metric_infos by default
- **WHEN** client queries the list endpoint
- **THEN** response DTOs SHALL include `metricValues` but SHALL NOT include `metricInfos` (lazy-loaded via detail endpoint only). The SQL SELECT clause SHALL explicitly exclude `metric_infos` to prevent TOAST decompression overhead. Only the `findById` detail query selects `metric_infos`.

### Requirement: Get single eval summary by ID
`GET /api/v1/analytics/eval-summaries/{id}` SHALL return a single eval summary including both `metricValues` and `metricInfos`, or HTTP 404 if not found.
Status: **Planned**

#### Scenario: Successful retrieval with full detail
- **WHEN** client calls `GET /api/v1/analytics/eval-summaries/{id}` and the eval summary exists
- **THEN** system SHALL return HTTP 200 with the full eval summary including `metricValues` AND `metricInfos`

#### Scenario: Not found
- **WHEN** client calls `GET /api/v1/analytics/eval-summaries/{id}` and no eval summary exists with that ID
- **THEN** system SHALL return HTTP 404

### Requirement: Count eval summaries
`GET /api/v1/analytics/eval-summaries/count` SHALL return the count of eval summaries matching filters. Requires `runId` filter. Uses computation resolution (same as list).
Status: **Planned**

#### Scenario: Count with filters
- **WHEN** client calls `GET /api/v1/analytics/eval-summaries/count` with `runId` filter and optional additional filters
- **THEN** system SHALL return `{"count": N}` matching the filter criteria for the resolved computation

### Requirement: Aggregate eval summaries
`GET /api/v1/analytics/eval-summaries/aggregate` SHALL return metric aggregations for a run's computation. Requires `runId` filter. Uses computation resolution (same as list — defaults to latest). The response is a flat list of per-metric aggregation objects, not grouped by computation (the computation is resolved as a filter, not a grouping dimension). `count` represents the number of non-null values for the metric output.
Status: **Planned**

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
Status: **Planned**

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
Status: **Planned**

#### Scenario: Batch max items
- **WHEN** application starts
- **THEN** `analytics.eval-summaries.batch.max-items` SHALL be configurable with a default of 10000

#### Scenario: Batch max request size
- **WHEN** application starts
- **THEN** `analytics.eval-summaries.batch.max-request-size-bytes` SHALL be configurable with a default of 10485760 (10 MB)

### Requirement: Computation versioning model
Metric computations SHALL be versioned via `computation_id` with no mutable `is_latest` flag. "Latest" SHALL be resolved at query time.
Status: **Planned**

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
Status: **Planned**

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
- **WHEN** client includes `filter=metricValues.Accuracy.score:gte:0.8`
- **THEN** WhereBuilder SHALL produce SQL: `(metric_values->:jsonbKey1_N->>:jsonbKey2_N)::numeric >= :filter_N` with params `jsonbKey1_N = "Accuracy"`, `jsonbKey2_N = "score"`, `filter_N = 0.8`

#### Scenario: Single-level JSONB_STRING unchanged
- **WHEN** a filter field uses dot notation and resolves to a `JSONB_STRING` field (e.g., `testCaseData.prompt`)
- **THEN** existing single-level behavior SHALL be preserved. The `jsonbKey.contains(".")` rejection SHALL still apply for `JSONB_STRING` fields.

#### Scenario: Filter whitelist
- **WHEN** the eval summaries filter whitelist is configured
- **THEN** it SHALL include fields: `suiteId` (UUID), `runId` (UUID), `testCaseId` (UUID), `testCaseName` (STRING), `executionStatus` (STRING), `runIndex` (LONG), `execDurationMs` (LONG), `responseStatusCode` (LONG), `testCaseData` (JSONB_STRING), `metricValues` (JSONB_NUMERIC)

## Implementation Notes
- Controller: `EvalSummaryController` — `@LogExecution`, `@Validated`; `RunMetricSnapshotController` — `@LogExecution`, `@Validated`
- Service: `EvalSummaryService` in `service.domain.analytics` — reads run from meta for validation, writes to analytics; `RunMetricSnapshotService` in `service.domain.analytics`
- Repository: `PostgresEvalSummaryRepository` — `@Qualifier("analyticsJdbcTemplate")`, batch insert with ON CONFLICT DO NOTHING; `findAll()`/`count()`/`aggregate()` use explicit column list excluding `metric_infos`; `findById()` selects all columns; `PostgresRunMetricSnapshotRepository` — same qualifier
- Model: `EvalSummary` — JSONB fields as `String` (raw JSON) in data model; `RunMetricSnapshot` — bindings as `String` (raw JSON)
- Cursor: Reuse existing `Cursor` record and `CursorCodec` from analytics layer
- Mapper: `EvalSummaryMapper` (MapStruct) — maps between model and DTOs; `RunMetricSnapshotMapper`
- DTOs: `EvalSummaryResponseDto` (excludes `metricInfos` for list), `EvalSummaryDetailResponseDto` (includes `metricInfos` for get-by-id), `EvalSummaryBatchWriteRequestDto`, `RunMetricSnapshotResponseDto`, `RunMetricSnapshotBatchWriteRequestDto`, `MetricAggregationResponseDto`
- Filter whitelist: New `FilterWhitelists.EVAL_SUMMARIES` with JSONB_NUMERIC type for metric value filtering
- Migrations: `V1.5__CreateTestCaseEvalSummariesTable.sql` (index on `(test_suite_run_id, computation_id)` for the primary query path), `V1.6__CreateRunMetricSnapshotsTable.sql` in `db/migration/analytics/POSTGRES/`
