## MODIFIED Requirements

### Requirement: Database schema for eval summaries
The analytics database SHALL contain a `test_case_eval_summaries` table storing denormalized, append-only rows that combine test case context with metric computation outputs, including per-turn columns.
Status: **Planned**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migrations V1.5 and V1.7 are applied
- **THEN** the `test_case_eval_summaries` table SHALL have columns: `id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_case_run_result_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `computation_id` (VARCHAR(36), NOT NULL), `test_case_data` (JSONB, NOT NULL), `extracted_columns` (JSONB, NOT NULL, DEFAULT '{}'), `extraction_warnings` (JSONB, NOT NULL, DEFAULT '[]'), `execution_status` (VARCHAR(20), NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `response_status_code` (INTEGER, nullable), `metric_values` (JSONB, NOT NULL, DEFAULT '{}'), `metric_infos` (JSONB, nullable), `turn_index` (INTEGER, NOT NULL, DEFAULT 0), `total_turns` (INTEGER, NOT NULL, DEFAULT 1), `created_at_ms` (BIGINT, NOT NULL), `computed_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** the UNIQUE constraint SHALL be `(test_suite_run_id, test_case_id, run_index, turn_index, computation_id, created_at_ms)` — extended with `turn_index` (dropping and recreating the prior `(test_suite_run_id, test_case_id, run_index, computation_id, created_at_ms)` constraint) so each turn is uniquely keyed per computation

#### Scenario: Turn columns added
- **WHEN** the analytics Flyway migration `V1.14__AddTurnColumnsToEvalSummaries.sql` is applied
- **THEN** `turn_index` (INTEGER, NOT NULL, DEFAULT 0) and `total_turns` (INTEGER, NOT NULL, DEFAULT 1) columns SHALL be added to `test_case_eval_summaries`, and existing rows backfill to those defaults

#### Scenario: Indexes
- **WHEN** the migrations are applied
- **THEN** indexes SHALL be created on: `(test_suite_run_id, computation_id)` for run-scoped grid queries (the primary query path), `(computation_id)` for computation-scoped queries, `(id)` for direct lookups, and `(test_suite_run_id, computed_at_ms DESC, computation_id)` for latest-computation resolution

#### Scenario: Latest-computation resolution is index-only
- **WHEN** the latest computation of a run is resolved
- **THEN** the query SHALL be satisfiable from the `(test_suite_run_id, computed_at_ms DESC, computation_id)` index alone, so its cost SHALL NOT grow with the number of summary rows the run has

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist (soft references to meta DB and test_case_run_results only)

#### Scenario: No updated_at column
- **WHEN** the migration is applied
- **THEN** the table SHALL NOT have an `updated_at_ms` column (rows are immutable/append-only)

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

### Requirement: Computation versioning model
Metric computations SHALL be versioned via `computation_id` with no mutable `is_latest` flag. "Latest" SHALL be resolved at query time, from the table the caller reads.
Status: **Planned**

#### Scenario: Recalculation creates new computation
- **WHEN** metrics are recalculated for a run
- **THEN** the metric computation pipeline SHALL generate a new `computation_id`, insert new eval summary rows and new run metric snapshots, without modifying existing rows

#### Scenario: Latest resolution
- **WHEN** the API needs to determine the latest computation for a run
- **THEN** it SHALL query `test_case_eval_summaries` for the maximum `computed_at_ms` for that run and use the corresponding `computation_id`

#### Scenario: Latest resolution requires readable rows
- **WHEN** a computation wrote `run_metric_snapshots` rows for a run but wrote no eval summaries (e.g. its batch write failed)
- **THEN** latest resolution SHALL NOT select that computation, and SHALL select the run's most recent computation that does have eval summaries

#### Scenario: Metric-catalog lookups stay on run metric snapshots
- **WHEN** a caller needs the metric column families of a run's latest computation rather than its readable rows (Query DSL detailed schema discovery)
- **THEN** it SHALL resolve that computation from `run_metric_snapshots` and SHALL return no metric families for a run that has none

#### Scenario: Comparison between computations
- **WHEN** client provides two computation UUIDs
- **THEN** the list endpoint SHALL support filtering by specific `computation_id` to enable side-by-side comparison

## Implementation notes

- `com.epam.aidial.evaluation.service.domain.analytics.ComputationResolver` — `latest` resolves via `EvalSummaryRepository.findLatestComputationId(runId)`; the `RunMetricSnapshotRepository` dependency is removed from this class. Shared by the list, count, aggregate, export, preview, and `metric_score_results` `"latest"`-sentinel paths.
- `com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository` / `PostgresEvalSummaryRepository` — new `findLatestComputationId(UUID runId)` via the typed jOOQ DSL, mirroring `PostgresRunMetricSnapshotRepository.findLatestComputationId`.
- `com.epam.aidial.evaluation.experimental.query.service.EvalSummariesSchemaProvider` — unchanged; keeps using `RunMetricSnapshotRepository.findLatestComputationId` for metric-family discovery.
- Analytics Flyway migration `V1.15__AddEvalSummariesRunComputedAtIndex.sql` adds the resolution index; requires `./gradlew generateJooq` and a `docs/database-schema.md` update.
