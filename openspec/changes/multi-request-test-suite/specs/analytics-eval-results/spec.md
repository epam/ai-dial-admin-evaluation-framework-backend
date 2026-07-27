## MODIFIED Requirements

### Requirement: Database schema for test case run results
The analytics database SHALL contain a `test_case_run_results` table storing flat, denormalized, append-only execution results, including retry tracking fields, per-turn columns, and per-request columns.
Status: **Planned**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migration is applied
- **THEN** the `test_case_run_results` table SHALL have columns: `id` (VARCHAR(36)), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `test_case_data` (JSONB, NOT NULL), `request_body` (JSONB, nullable), `response_body` (JSONB, nullable), `response_status_code` (INTEGER, nullable), `execution_status` (VARCHAR(20), NOT NULL), `exec_started_at_ms` (BIGINT, NOT NULL), `exec_completed_at_ms` (BIGINT, NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `trace_id` (VARCHAR(128), nullable), `extracted_columns` (JSONB, default `{}`), `extraction_warnings` (JSONB, default `[]`), `retry_count` (INTEGER, NOT NULL, DEFAULT 0), `log_details` (JSONB, nullable), `turn_index` (INTEGER, NOT NULL, DEFAULT 0), `total_turns` (INTEGER, NOT NULL, DEFAULT 1), `request_index` (INTEGER, NOT NULL, DEFAULT 0), `request_label` (VARCHAR(255), nullable), `created_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

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
- **WHEN** the analytics Flyway migration adding request columns is applied
- **THEN** `request_index` (INTEGER, NOT NULL, DEFAULT 0) and `request_label` (VARCHAR(255), nullable) columns SHALL be added to `test_case_run_results`, and existing rows backfill to `request_index = 0`

#### Scenario: No total_requests column
- **WHEN** the request columns are added
- **THEN** no `total_requests` column SHALL be created — the chain length is run-level configuration derivable from the run's frozen snapshot, identical on every row of a run, and an aborted chain is identified by its final row's ERROR status

#### Scenario: Indexes
- **WHEN** the migration is applied
- **THEN** existing indexes SHALL be preserved: `(test_suite_id, test_suite_run_id, test_case_name)`, `(id)`. No index on retry columns (per-result metadata, not query filters). No index on `request_index` or `request_label` (per-result metadata, not a primary query filter).

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** the UNIQUE constraint SHALL be `(test_suite_run_id, test_case_id, run_index, turn_index, request_index, created_at_ms)` — extended with `request_index` (dropping and recreating the prior `(test_suite_run_id, test_case_id, run_index, turn_index, created_at_ms)` constraint) so each chain request is uniquely keyed and `ON CONFLICT` holds per request. `request_label` SHALL NOT participate in the key: it is a mutable display value carrying no additional uniqueness, following the existing convention where `test_case_name` sits beside the keyed `test_case_id`.

#### Scenario: Upsert conflict target matches the constraint
- **WHEN** results are batch-written
- **THEN** the `ON CONFLICT` target SHALL list exactly the columns of the widened UNIQUE constraint, including `request_index`

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist (soft references to meta DB only)

#### Scenario: No updated_at column
- **WHEN** the migration is applied
- **THEN** the table SHALL NOT have an `updated_at_ms` column (results are immutable/append-only)

## ADDED Requirements

### Requirement: Per-request result rows with request columns
`test_case_run_results` SHALL persist one row per executed chain request. `request_index INTEGER NOT NULL DEFAULT 0` identifies the request's 0-based chain position and `request_label VARCHAR(255)` carries that request's resolved human-readable label. Rows of one test-case run SHALL share `test_case_id`, `test_case_name`, and `run_index`; chain indices are contiguous `0..k` for the requests actually executed. Each row SHALL carry only its own request's `extracted_columns` and `extraction_warnings`, its own `request_body`, `response_body`, `response_status_code`, timing, and `retry_count`. Response DTOs SHALL expose `requestIndex` and `requestLabel`. Because `request_label` is denormalized onto the row, analytics consumers SHALL NOT require a cross-datasource lookup into the meta DB to render it.
Status: **Planned**

#### Scenario: Chain rows share test case identity
- **WHEN** a three-request chain produces three rows for one test case
- **THEN** all rows share `test_case_id`, `test_case_name`, and `run_index`, with `request_index` `0`, `1`, `2`

#### Scenario: Single-request defaults preserved
- **WHEN** a single-request result is written
- **THEN** it has `request_index = 0`, and pre-existing rows backfill to that default

#### Scenario: Two chain requests do not collide on the natural key
- **WHEN** two requests of the same case/run are written
- **THEN** both persist because `request_index` distinguishes them

#### Scenario: Extracted columns are request-local
- **WHEN** chain request 0 extracts `session_id` and request 1 extracts `answer`
- **THEN** request 0's row `extracted_columns` contains only `session_id` and request 1's contains only `answer`

#### Scenario: Aborted chain writes only executed requests
- **WHEN** a four-request chain fails at request 2
- **THEN** three rows persist — `request_index` `0` and `1` as SUCCESS and `2` as ERROR — and no row exists for `request_index` 3

#### Scenario: Multi-request rows carry inert turn columns
- **WHEN** a multi-request row is written
- **THEN** it carries `turn_index = 0` and `total_turns = 1`, since multi-request and multi-turn are mutually exclusive

#### Scenario: Response DTO exposes request fields
- **WHEN** a client fetches result rows
- **THEN** each item SHALL include `requestIndex` and `requestLabel`

### Requirement: Result listing does not guarantee intra-run ordering
Result listing SHALL NOT guarantee ordering of rows within a single run. Keyset pagination orders by `(created_at_ms, id)`, and because `created_at_ms` is constant for all rows of a run and `id` is a random UUID, the effective order within a run is arbitrary. The API documentation SHALL state that clients needing chain or turn order MUST sort by `(runIndex, requestIndex, turnIndex)`.
Status: **Planned**

#### Scenario: Arbitrary order within a run
- **WHEN** a client lists results for a run containing multi-request test cases
- **THEN** rows MAY be returned in any order relative to their chain position

#### Scenario: Cursor shape is unchanged
- **WHEN** keyset pagination is used
- **THEN** the cursor SHALL continue to encode `(createdAt, id)` only, unaffected by the widened natural key

## Implementation notes

Analytics migration adding `request_index` / `request_label` and swapping the unique index; `TestCaseRunResult` model, `TestCaseRunResultRecordMapper`, `PostgresTestCaseRunResultRepository` (insert column list and `ON CONFLICT` target), `TestCaseRunResultResponseDto`. Requires `./gradlew generateJooq` and a `docs/database-schema.md` update.
