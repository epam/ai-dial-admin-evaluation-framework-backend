## MODIFIED Requirements

### Requirement: Database schema for eval summaries
The analytics database SHALL contain a `test_case_eval_summaries` table storing denormalized, append-only rows that combine test case context with metric computation outputs, including per-turn columns and per-row metric-evaluation timing.
Status: **Planned**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migrations V1.5, V1.7 and V1.15 are applied
- **THEN** the `test_case_eval_summaries` table SHALL have columns: `id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_case_run_result_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `computation_id` (VARCHAR(36), NOT NULL), `test_case_data` (JSONB, NOT NULL), `extracted_columns` (JSONB, NOT NULL, DEFAULT '{}'), `extraction_warnings` (JSONB, NOT NULL, DEFAULT '[]'), `execution_status` (VARCHAR(20), NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `metric_duration_ms` (BIGINT, nullable), `response_status_code` (INTEGER, nullable), `metric_values` (JSONB, NOT NULL, DEFAULT '{}'), `metric_infos` (JSONB, nullable), `turn_index` (INTEGER, NOT NULL, DEFAULT 0), `total_turns` (INTEGER, NOT NULL, DEFAULT 1), `created_at_ms` (BIGINT, NOT NULL), `computed_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** the UNIQUE constraint SHALL be `(test_suite_run_id, test_case_id, run_index, turn_index, computation_id, created_at_ms)` — extended with `turn_index` (dropping and recreating the prior `(test_suite_run_id, test_case_id, run_index, computation_id, created_at_ms)` constraint) so each turn is uniquely keyed per computation

#### Scenario: Turn columns added
- **WHEN** the analytics Flyway migration `V1.14__AddTurnColumnsToEvalSummaries.sql` is applied
- **THEN** `turn_index` (INTEGER, NOT NULL, DEFAULT 0) and `total_turns` (INTEGER, NOT NULL, DEFAULT 1) columns SHALL be added to `test_case_eval_summaries`, and existing rows backfill to those defaults

#### Scenario: Metric duration column added
- **WHEN** the analytics Flyway migration `V1.15__AddMetricDurationToEvalSummaries.sql` is applied
- **THEN** a `metric_duration_ms` (BIGINT, **nullable**, no DEFAULT) column SHALL be added to `test_case_eval_summaries`, and rows written before the migration SHALL read as `NULL`

#### Scenario: Metric duration is nullable by design
- **WHEN** a row's `metric_duration_ms` is `NULL`
- **THEN** it SHALL mean metric evaluation never ran for that row, and SHALL be distinguishable from the value `0`, which means evaluation ran and completed within one millisecond

#### Scenario: Indexes
- **WHEN** the migration is applied
- **THEN** indexes SHALL be created on: `(test_suite_run_id, computation_id)` for run-scoped grid queries (the primary query path), `(computation_id)` for computation-scoped queries, `(id)` for direct lookups

#### Scenario: No index on metric duration
- **WHEN** the `V1.15` migration is applied
- **THEN** no index SHALL be created on `metric_duration_ms` — every read path is already constrained by `computation_id` and `created_at_ms`

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist (soft references to meta DB and test_case_run_results only)

### Requirement: Batch write eval summaries
The service SHALL support persisting eval summary rows both via the external REST API (`POST /api/v1/analytics/eval-summaries`) and via internal writes from the in-process metric evaluation engine. Both paths SHALL go through `EvalSummaryService.batchCreate()`, sharing the same validation, mapping, and persistence logic with idempotent `ON CONFLICT DO NOTHING`.
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

#### Scenario: metricDurationMs is optional
- **WHEN** a batch write item omits `metricDurationMs`
- **THEN** system SHALL accept the item and persist `metric_duration_ms` as `NULL` — unlike `execDurationMs`, the field SHALL NOT be a required per-item field, so existing external callers remain valid

#### Scenario: metricDurationMs is persisted verbatim
- **WHEN** a batch write item carries `metricDurationMs`
- **THEN** system SHALL persist the value unchanged (no recomputation, no derivation from timestamps) and echo it on subsequent reads of that row

#### Scenario: metricValues structural validation
- **WHEN** a batch write item contains `metricValues`
- **THEN** `metricValues` SHALL be a JSON object where each key maps to a nested JSON object whose leaf values are numeric (or null). Items with non-numeric leaf values SHALL be rejected with HTTP 400 and error code `VALIDATION_ERROR`. This ensures downstream filter (`JSONB_NUMERIC`) and aggregation queries can safely cast values to numeric.

> **Implementation note — key naming**: The keys within each nested `metricValues` object are expected to be real metric output field names (not synthetic placeholders like `"error"`). The batch-write API does not enforce field name validity — that is the producer's responsibility. The metric evaluation engine ensures correct key names by extracting them from the TSMD's output schema via `OutputSchemaFieldExtractor`.

#### Scenario: Internal batch write from metric evaluation engine
- **WHEN** the in-process metric evaluation engine produces EvalSummary records
- **THEN** the `EvalSummaryBatchWriteClient` SHALL convert internal models to `EvalSummaryBatchWriteRequestDto` and delegate to `EvalSummaryService.batchCreate()`, reusing the same validation, mapping, and persistence logic as the external API. The existing batch size limit (`analytics.eval-summaries.batch.max-items`) SHALL be respected via chunking.

## ADDED Requirements

### Requirement: Metric duration read surfaces
`metricDurationMs` SHALL be readable everywhere a row's execution timing is already readable, and SHALL be excluded from the legacy list-endpoint filter vocabulary.
Status: **Planned**

#### Scenario: Present in list response
- **WHEN** a client calls `GET /api/v1/analytics/eval-summaries` and a returned row has a non-null `metric_duration_ms`
- **THEN** each response item SHALL include `metricDurationMs` with that value

#### Scenario: Present in detail response
- **WHEN** a client calls `GET /api/v1/analytics/eval-summaries/{id}`
- **THEN** the response SHALL include `metricDurationMs`

#### Scenario: Null value is rendered as absent or null, never zero
- **WHEN** a returned row has `metric_duration_ms = NULL`
- **THEN** the response SHALL NOT report `metricDurationMs` as `0`

#### Scenario: Not a legacy filter field
- **WHEN** a client calls the list endpoint with `filter=metricDurationMs:<op>:<value>`
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR` — `metricDurationMs` SHALL NOT be added to `FilterWhitelists.EVAL_SUMMARIES`

#### Scenario: Queryable through the structured query DSL
- **WHEN** a client posts a structured query against the `eval_summaries` entity selecting, filtering or aggregating `metric_duration_ms` (e.g. `avg(metric_duration_ms)`)
- **THEN** the query SHALL succeed, because the entity's schema and field bindings are derived from the generated jOOQ table rather than a hand-maintained list

## Implementation notes

- Migration: `src/main/resources/db/migration/analytics/POSTGRES/V1.15__AddMetricDurationToEvalSummaries.sql`; regenerate with `./gradlew generateJooq` (`JooqSchemaDriftTest` guards the committed sources).
- Model / mapping: `data/db/analytics/model/EvalSummary.java`, `data/db/analytics/mapper/EvalSummaryRecordMapper.java` (all three map methods), `service/domain/mapper/EvalSummaryMapper.java` (`toEntity` is multi-source and needs an explicit `@Mapping`).
- Persistence: `data/db/analytics/repository/PostgresEvalSummaryRepository.java` — the insert plus all four select projections (list, `findById`, export, export-with-bodies).
- DTOs: `service/domain/dto/analytics/EvalSummaryBatchWriteItemDto.java` (optional), `EvalSummaryResponseDto.java`, `EvalSummaryDetailResponseDto.java`.
- Query DSL access derives from `JooqTableSchemaResolver` via `EvalSummariesSchemaProvider` and `PostgresEvalSummaryEntityResolver` — no DSL-side code.
