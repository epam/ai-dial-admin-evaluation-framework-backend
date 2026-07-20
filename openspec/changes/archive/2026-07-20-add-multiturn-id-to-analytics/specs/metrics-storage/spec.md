## MODIFIED Requirements

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
