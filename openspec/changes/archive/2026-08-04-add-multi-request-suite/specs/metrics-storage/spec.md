## MODIFIED Requirements

### Requirement: Database schema for eval summaries
The analytics database SHALL contain a `test_case_eval_summaries` table storing denormalized, append-only rows that combine test case context with metric computation outputs, including per-turn columns and per-request columns.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migrations V1.5 and V1.7 are applied
- **THEN** the `test_case_eval_summaries` table SHALL have columns: `id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_case_run_result_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `computation_id` (VARCHAR(36), NOT NULL), `test_case_data` (JSONB, NOT NULL), `extracted_columns` (JSONB, NOT NULL, DEFAULT '{}'), `extraction_warnings` (JSONB, NOT NULL, DEFAULT '[]'), `execution_status` (VARCHAR(20), NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `response_status_code` (INTEGER, nullable), `metric_values` (JSONB, NOT NULL, DEFAULT '{}'), `metric_infos` (JSONB, nullable), `turn_index` (INTEGER, NOT NULL, DEFAULT 0), `total_turns` (INTEGER, NOT NULL, DEFAULT 1), `request_index` (INTEGER, NOT NULL, DEFAULT 0), `total_requests` (INTEGER, NOT NULL, DEFAULT 1), `created_at_ms` (BIGINT, NOT NULL), `computed_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** the unique index `uq_eval_summaries_natural_key` SHALL be `(test_suite_run_id, test_case_id, run_index, request_index, turn_index, computation_id, created_at_ms)` — extended with `request_index` positioned immediately after `run_index` (dropping and recreating the prior `(test_suite_run_id, test_case_id, run_index, turn_index, computation_id, created_at_ms)` index) so each (request, turn) pair is uniquely keyed per computation

#### Scenario: Turn columns added
- **WHEN** the analytics Flyway migration `V1.14__AddTurnColumnsToEvalSummaries.sql` is applied
- **THEN** `turn_index` (INTEGER, NOT NULL, DEFAULT 0) and `total_turns` (INTEGER, NOT NULL, DEFAULT 1) columns SHALL be added to `test_case_eval_summaries`, and existing rows backfill to those defaults

#### Scenario: Request columns added
- **WHEN** the analytics Flyway migration `V1.17__AddRequestColumnsToEvalSummaries.sql` is applied
- **THEN** `request_index` (INTEGER, NOT NULL, DEFAULT 0) and `total_requests` (INTEGER, NOT NULL, DEFAULT 1) columns SHALL be added to `test_case_eval_summaries`, and existing rows backfill to those defaults

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

## ADDED Requirements

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
