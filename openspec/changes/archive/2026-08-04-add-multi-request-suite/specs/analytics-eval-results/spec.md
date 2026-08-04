## MODIFIED Requirements

### Requirement: Database schema for test case run results
The analytics database SHALL contain a `test_case_run_results` table storing flat, denormalized, append-only execution results, including retry tracking fields, per-turn columns, and per-request columns.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migration is applied
- **THEN** the `test_case_run_results` table SHALL have columns: `id` (VARCHAR(36)), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `test_case_data` (JSONB, NOT NULL), `request_body` (JSONB, nullable), `response_body` (JSONB, nullable), `response_status_code` (INTEGER, nullable), `execution_status` (VARCHAR(20), NOT NULL), `exec_started_at_ms` (BIGINT, NOT NULL), `exec_completed_at_ms` (BIGINT, NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `trace_id` (VARCHAR(128), nullable), `extracted_columns` (JSONB, default `{}`), `extraction_warnings` (JSONB, default `[]`), `retry_count` (INTEGER, NOT NULL, DEFAULT 0), `log_details` (JSONB, nullable), `turn_index` (INTEGER, NOT NULL, DEFAULT 0), `total_turns` (INTEGER, NOT NULL, DEFAULT 1), `request_index` (INTEGER, NOT NULL, DEFAULT 0), `total_requests` (INTEGER, NOT NULL, DEFAULT 1), `created_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: Retry tracking columns added
- **WHEN** the Flyway migration `V1.4__DropTimingAddRetryColumns.sql` is applied
- **THEN** `retry_count` (INTEGER, NOT NULL, DEFAULT 0) and `log_details` (JSONB, nullable) columns SHALL be added to `test_case_run_results`

#### Scenario: Streaming timing columns removed
- **WHEN** the Flyway migration `V1.4__DropTimingAddRetryColumns.sql` is applied
- **THEN** `time_to_first_token_ms` and `time_to_last_token_ms` columns SHALL be dropped from `test_case_run_results`

#### Scenario: Turn columns added
- **WHEN** the Flyway migration `V1.13__AddTurnColumnsToTestCaseRunResults.sql` is applied
- **THEN** `turn_index` (INTEGER, NOT NULL, DEFAULT 0) and `total_turns` (INTEGER, NOT NULL, DEFAULT 1) columns SHALL be added to `test_case_run_results`, and existing rows backfill to those defaults

#### Scenario: Request columns added
- **WHEN** the Flyway migration `V1.16__AddRequestColumnsToTestCaseRunResults.sql` is applied
- **THEN** `request_index` (INTEGER, NOT NULL, DEFAULT 0) and `total_requests` (INTEGER, NOT NULL, DEFAULT 1) columns SHALL be added to `test_case_run_results`, and existing rows backfill to those defaults

#### Scenario: Indexes
- **WHEN** the migration is applied
- **THEN** existing indexes SHALL be preserved: `(test_suite_id, test_suite_run_id, test_case_name)`, `(id)`. No index on retry columns (per-result metadata, not query filters). No new index is added for the request columns (they are row-identity components, not a query-filter path).

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** the UNIQUE constraint `uq_results_run_case_index` SHALL be `(test_suite_run_id, test_case_id, run_index, request_index, turn_index, created_at_ms)` — extended with `request_index` positioned immediately after `run_index` (dropping and recreating the prior `(test_suite_run_id, test_case_id, run_index, turn_index, created_at_ms)` constraint) so each (request, turn) pair is uniquely keyed and `ON CONFLICT` holds per chain call

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist (soft references to meta DB only)

#### Scenario: No updated_at column
- **WHEN** the migration is applied
- **THEN** the table SHALL NOT have an `updated_at_ms` column (results are immutable/append-only)

## ADDED Requirements

### Requirement: Per-request result rows with request columns

`test_case_run_results` SHALL persist one row per HTTP call of a suite's request chain. The columns `request_index INTEGER NOT NULL DEFAULT 0` and `total_requests INTEGER NOT NULL DEFAULT 1` SHALL identify the row's position in the chain and the chain's length. Rows of one test-case repetition share the same `test_case_id`, `run_index` and `trace_id`; the request dimension is orthogonal to the turn dimension, so a row's identity within a repetition is the pair `(request_index, turn_index)`, with `request_index` contiguous `0..total_requests-1` and `turn_index` contiguous within each request. The values SHALL be stamped only when the chain length is greater than 1, so rows written for a single-request suite are byte-identical to rows written before these columns existed. No `chain_id` or `last_request_index` column SHALL be added. Response DTOs SHALL expose `requestIndex` and `totalRequests`; batch-write item DTOs SHALL accept them as optional, defaulting to `0`/`1`, so existing external callers stay compatible.

The `ON CONFLICT` target used by the batch writer SHALL match the extended unique constraint exactly, so re-writing a batch remains a no-op.

Status: **Implemented**

#### Scenario: Chain rows share test_case_id and trace_id
- **WHEN** a 3-request chain produces 3 rows for one repetition
- **THEN** all rows share the same `test_case_id`, `run_index` and `trace_id`, with `request_index` `0..2` and `total_requests = 3`

#### Scenario: Two-dimensional identity for a chained multi-turn run
- **WHEN** a chain's request #1 runs 2 turns
- **THEN** rows SHALL exist with `(request_index=1, turn_index=0, total_turns=2)` and `(request_index=1, turn_index=1, total_turns=2)`

#### Scenario: Single-request suite writes defaults
- **WHEN** a suite with no additional requests runs
- **THEN** every row SHALL carry `request_index = 0` and `total_requests = 1` without explicit stamping

#### Scenario: Re-writing a chain batch is idempotent
- **WHEN** the same batch of chain rows is written twice
- **THEN** the second write SHALL insert no rows and SHALL NOT raise a constraint violation

#### Scenario: Optional batch-write fields default
- **WHEN** an external batch-write item omits `requestIndex` and `totalRequests`
- **THEN** the persisted row SHALL carry `0` and `1`
