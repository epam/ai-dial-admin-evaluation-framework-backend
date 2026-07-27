## ADDED Requirements

### Requirement: Eval summaries carry turn columns
`test_case_eval_summaries` SHALL persist one summary per turn result row, with new columns `turn_index INTEGER NOT NULL DEFAULT 0` and `total_turns INTEGER NOT NULL DEFAULT 1`. Response DTOs expose `turnIndex`/`totalTurns`; batch-write item DTOs accept them as optional (defaulting `0/1`). No `multi_turn_id` column is added (grouping is via `test_case_id`).

#### Scenario: One summary per turn
- **WHEN** a multi-turn case has N turn result rows scored under one computation
- **THEN** N eval-summary rows are written, one per turn, each with its `turn_index` and `total_turns=N`

#### Scenario: Summary key distinguishes turns
- **WHEN** two turns of the same case/run/computation are summarized
- **THEN** both persist because `turn_index` is part of the natural key

## MODIFIED Requirements

### Requirement: Database schema for eval summaries
The analytics database SHALL contain a `test_case_eval_summaries` table storing denormalized, append-only rows that combine test case context with metric computation outputs, including per-turn columns.

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
- **WHEN** the migration is applied
- **THEN** indexes SHALL be created on: `(test_suite_run_id, computation_id)` for run-scoped grid queries (the primary query path), `(computation_id)` for computation-scoped queries, `(id)` for direct lookups

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist (soft references to meta DB and test_case_run_results only)

#### Scenario: No updated_at column
- **WHEN** the migration is applied
- **THEN** the table SHALL NOT have an `updated_at_ms` column (rows are immutable/append-only)

## Implementation notes

Planned. Migration `V1.14__AddTurnColumnsToEvalSummaries.sql` (columns + extended unique key + backfill); `data.db.analytics.model.EvalSummary` (+ RecordMapper + mapper), `PostgresEvalSummaryRepository` insert/conflict/read projections, `EvalSummaryResponseDto`/`EvalSummaryBatchWriteItemDto`.
