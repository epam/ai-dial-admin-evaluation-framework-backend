## ADDED Requirements

### Requirement: Per-turn result rows with turn columns
`test_case_run_results` SHALL persist one row per executed turn. New columns `turn_index INTEGER NOT NULL DEFAULT 0` and `total_turns INTEGER NOT NULL DEFAULT 1` identify the turn and its conversation length. Turns of one conversation share the same `test_case_id` (no separate grouping key); indices are contiguous `0..N-1`. Each turn row carries that turn's own `test_case_data`, the full accumulated `request_body`, that turn's raw `response_body`, its scalar `extracted_columns`, and the shared `trace_id`. Response DTOs expose `turnIndex` and `totalTurns`; batch-write item DTOs accept them as optional (defaulting `0/1`) so single-turn external callers stay compatible. No `multi_turn_id` or `last_turn_index` column is added.

#### Scenario: Turn rows share test_case_id and trace_id
- **WHEN** a multi-turn case produces N turn rows
- **THEN** all rows share the same `test_case_id` and `trace_id`, with `turn_index` `0..N-1` and `total_turns=N`

#### Scenario: Single-turn defaults preserved
- **WHEN** a single-turn result is written
- **THEN** it has `turn_index=0` and `total_turns=1`, and existing rows backfill to those defaults

#### Scenario: Two turns do not collide on the natural key
- **WHEN** two turns of the same case/run are written
- **THEN** both persist because `turn_index` distinguishes them

#### Scenario: Single-turn batch-write remains compatible
- **WHEN** an external caller writes a result omitting turn fields
- **THEN** it defaults to `turn_index=0, total_turns=1`

## MODIFIED Requirements

### Requirement: Database schema for test case run results
The analytics database SHALL contain a `test_case_run_results` table storing flat, denormalized, append-only execution results, including retry tracking fields and per-turn columns.

#### Scenario: Table structure
- **WHEN** the analytics Flyway migration is applied
- **THEN** the `test_case_run_results` table SHALL have columns: `id` (VARCHAR(36)), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `test_case_data` (JSONB, NOT NULL), `request_body` (JSONB, nullable), `response_body` (JSONB, nullable), `response_status_code` (INTEGER, nullable), `execution_status` (VARCHAR(20), NOT NULL), `exec_started_at_ms` (BIGINT, NOT NULL), `exec_completed_at_ms` (BIGINT, NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `trace_id` (VARCHAR(128), nullable), `extracted_columns` (JSONB, default `{}`), `extraction_warnings` (JSONB, default `[]`), `retry_count` (INTEGER, NOT NULL, DEFAULT 0), `log_details` (JSONB, nullable), `turn_index` (INTEGER, NOT NULL, DEFAULT 0), `total_turns` (INTEGER, NOT NULL, DEFAULT 1), `created_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: Retry tracking columns added
- **WHEN** the Flyway migration `V1.4__DropTimingAddRetryColumns.sql` is applied
- **THEN** `retry_count` (INTEGER, NOT NULL, DEFAULT 0) and `log_details` (JSONB, nullable) columns SHALL be added to `test_case_run_results`

#### Scenario: Streaming timing columns removed
- **WHEN** the Flyway migration `V1.4__DropTimingAddRetryColumns.sql` is applied
- **THEN** `time_to_first_token_ms` and `time_to_last_token_ms` columns SHALL be dropped from `test_case_run_results`

#### Scenario: Turn columns added
- **WHEN** the Flyway migration `V1.13__AddTurnColumnsToTestCaseRunResults.sql` is applied
- **THEN** `turn_index` (INTEGER, NOT NULL, DEFAULT 0) and `total_turns` (INTEGER, NOT NULL, DEFAULT 1) columns SHALL be added to `test_case_run_results`, and existing rows backfill to those defaults

#### Scenario: Indexes
- **WHEN** the migration is applied
- **THEN** existing indexes SHALL be preserved: `(test_suite_id, test_suite_run_id, test_case_name)`, `(id)`. No index on retry columns (per-result metadata, not query filters).

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** the UNIQUE constraint SHALL be `(test_suite_run_id, test_case_id, run_index, turn_index, created_at_ms)` — extended with `turn_index` (dropping and recreating the prior `(test_suite_run_id, test_case_id, run_index, created_at_ms)` constraint) so each turn is uniquely keyed and `ON CONFLICT` holds per turn

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist (soft references to meta DB only)

#### Scenario: No updated_at column
- **WHEN** the migration is applied
- **THEN** the table SHALL NOT have an `updated_at_ms` column (results are immutable/append-only)

## Implementation notes

Planned. Migration `V1.13__AddTurnColumnsToTestCaseRunResults.sql` (columns + extended unique key + `NOT NULL DEFAULT` backfill); `data.db.analytics.model.TestCaseRunResult` (+ RecordMapper + MapStruct mapper), `PostgresTestCaseRunResultRepository` batch insert & conflict target, `TestCaseRunResultItemDto`/`ResponseDto`.
