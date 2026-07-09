## MODIFIED Requirements

### Requirement: Database schema for eval summaries
The analytics database SHALL contain a `test_case_eval_summaries` table storing denormalized, append-only rows that combine test case context with metric computation outputs, at **per-turn** granularity (`turn_index`, `total_turns`) so that each turn of a multi-turn conversation produces its own summary row.
Status: **Planned**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migrations through the per-turn change are applied
- **THEN** the `test_case_eval_summaries` table SHALL have columns: `id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_case_run_result_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `turn_index` (INTEGER, NOT NULL, DEFAULT 0), `total_turns` (INTEGER, NOT NULL, DEFAULT 1), `computation_id` (VARCHAR(36), NOT NULL), `test_case_data` (JSONB, NOT NULL), `extracted_columns` (JSONB, NOT NULL, DEFAULT '{}'), `extraction_warnings` (JSONB, NOT NULL, DEFAULT '[]'), `execution_status` (VARCHAR(20), NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `response_status_code` (INTEGER, nullable), `metric_values` (JSONB, NOT NULL, DEFAULT '{}'), `metric_infos` (JSONB, nullable), `created_at_ms` (BIGINT, NOT NULL), `computed_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: Turn columns backfill existing rows
- **WHEN** the `ADD COLUMN turn_index INTEGER NOT NULL DEFAULT 0` / `total_turns INTEGER NOT NULL DEFAULT 1` migration is applied to a table with pre-existing single-turn summary rows
- **THEN** every pre-existing row SHALL carry `turn_index = 0` and `total_turns = 1`, in one metadata-only statement (no rewrite)

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** the UNIQUE constraint SHALL be `(test_suite_run_id, test_case_id, run_index, turn_index, computation_id, created_at_ms)`, so each turn's summary is uniquely keyed per computation
- **AND** because `turn_index` is `NOT NULL`, the index SHALL be a plain unique index (no `NULLS NOT DISTINCT`)

#### Scenario: Indexes
- **WHEN** the migration is applied
- **THEN** indexes SHALL be created on: `(test_suite_run_id, computation_id)` for run-scoped grid queries, `(computation_id)` for computation-scoped queries, `(id)` for direct lookups

#### Scenario: No updated_at column
- **WHEN** the migration is applied
- **THEN** the table SHALL NOT have an `updated_at_ms` column (rows are immutable/append-only)

### Requirement: Batch write eval summaries
The service SHALL support persisting eval summary rows both via the external REST API (`POST /api/v1/analytics/eval-summaries`) and via internal writes from the in-process metric evaluation engine. Both paths SHALL go through `EvalSummaryService.batchCreate()`, sharing the same validation, mapping, and persistence logic with idempotent `ON CONFLICT DO NOTHING`, whose conflict target is the natural key **including `turn_index`**: `(test_suite_run_id, test_case_id, run_index, turn_index, computation_id, created_at_ms)`. Each item MAY carry `turnIndex` and `totalTurns`; both are **optional** and default to `0` and `1` respectively when omitted, so pre-existing single-turn callers remain byte-compatible.
Status: **Planned**

#### Scenario: Turn fields default for single-turn callers
- **WHEN** a batch item omits `turnIndex` and `totalTurns`
- **THEN** the persisted row SHALL carry `turn_index = 0` and `total_turns = 1`, and be otherwise byte-identical to prior single-turn behavior

#### Scenario: Distinct turns are not duplicates
- **WHEN** a batch contains two items sharing `(testCaseId, runIndex, computationId)` but differing in `turnIndex`
- **THEN** both SHALL be inserted (distinct turns of one conversation are not duplicates); items identical on the full natural key SHALL be silently skipped via `ON CONFLICT DO NOTHING`

## ADDED Requirements

### Requirement: Turn fields exposed on the eval summary API
Eval summary list and detail responses SHALL expose `turnIndex` (0-based) and `totalTurns` (count). Single-turn summaries SHALL report `turnIndex = 0, totalTurns = 1`. The batch-write envelope from the metric evaluation engine SHALL carry `turnIndex`/`totalTurns` copied verbatim from each source `TestCaseRunResult`.
Status: **Planned**

#### Scenario: Multi-turn summary rows carry turn fields
- **WHEN** a 3-turn conversation is evaluated under one computation
- **THEN** three summary rows SHALL exist for the same `(testCaseId, runIndex, computationId)`, with `turnIndex` `0`, `1`, `2` and `totalTurns` `3`

#### Scenario: Single-turn summary is unchanged in shape
- **WHEN** a non-multi-turn suite is evaluated
- **THEN** the summary row SHALL carry `turnIndex = 0` and `totalTurns = 1`, otherwise byte-identical to prior behavior
