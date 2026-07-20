## MODIFIED Requirements

### Requirement: Database schema for test case run results
The analytics database SHALL contain a `test_case_run_results` table storing flat, denormalized, append-only execution results, including retry tracking fields, **per-turn identity** (`turn_index`, `total_turns`) so that each turn of a multi-turn is its own row, and a nullable **`multi_turn_id`** grouping key so that turn rows can be regrouped into their originating multi-turn.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migrations through the multi-turn-id change are applied
- **THEN** the `test_case_run_results` table SHALL have columns: `id` (VARCHAR(36)), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `turn_index` (INTEGER, NOT NULL, DEFAULT 0), `total_turns` (INTEGER, NOT NULL, DEFAULT 1), `last_turn_index` (INTEGER, NOT NULL, DEFAULT 0), `multi_turn_id` (VARCHAR(36), nullable), `test_case_data` (JSONB, NOT NULL), `request_body` (JSONB, nullable), `response_body` (JSONB, nullable), `response_status_code` (INTEGER, nullable), `execution_status` (VARCHAR(20), NOT NULL), `exec_started_at_ms` (BIGINT, NOT NULL), `exec_completed_at_ms` (BIGINT, NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `trace_id` (VARCHAR(128), nullable), `extracted_columns` (JSONB, default `{}`), `extraction_warnings` (JSONB, default `[]`), `retry_count` (INTEGER, NOT NULL, DEFAULT 0), `log_details` (JSONB, nullable), `created_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: Turn columns backfill existing rows
- **WHEN** the `ADD COLUMN turn_index INTEGER NOT NULL DEFAULT 0` / `total_turns INTEGER NOT NULL DEFAULT 1` migration is applied to a table with pre-existing single-turn rows
- **THEN** every pre-existing row SHALL carry `turn_index = 0` and `total_turns = 1` with no separate backfill pass and no table rewrite

#### Scenario: multi_turn_id backfills existing rows as NULL
- **WHEN** the `ADD COLUMN IF NOT EXISTS multi_turn_id VARCHAR(36)` (nullable, no default) migration is applied to a table with pre-existing rows
- **THEN** every pre-existing row SHALL carry `multi_turn_id = NULL` (single-turn semantics) as a metadata-only change with no table rewrite
- **AND** `multi_turn_id` SHALL NOT be part of any UNIQUE or primary key

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** the UNIQUE constraint SHALL be `(test_suite_run_id, test_case_id, run_index, turn_index, created_at_ms)`, so each turn of a multi-turn is uniquely keyed and idempotent `ON CONFLICT DO NOTHING` writes hold per turn
- **AND** because `turn_index` is `NOT NULL`, the index SHALL be a plain unique index (no `NULLS NOT DISTINCT`)

#### Scenario: Retry columns
- **WHEN** the retry-tracking migration is applied
- **THEN** `retry_count` (INTEGER, NOT NULL, DEFAULT 0) and `log_details` (JSONB, nullable) columns SHALL exist on `test_case_run_results`

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist (soft references only)

#### Scenario: Indexes
- **WHEN** the migration is applied
- **THEN** existing indexes SHALL be preserved: `(test_suite_id, test_suite_run_id, test_case_name)`, `(id)`. No index on retry columns (per-result metadata, not query filters).
- **AND** a non-unique grouping index `(test_suite_run_id, multi_turn_id, created_at_ms)` SHALL exist — equality/grouping columns leading, `created_at_ms` trailing to align with the `(created_at_ms, id)` keyset spine and remain time-partition-ready.

#### Scenario: No updated_at column
- **WHEN** the migration is applied
- **THEN** the table SHALL NOT have an `updated_at_ms` column (results are immutable/append-only)

## ADDED Requirements

### Requirement: multi_turn_id is populated on result rows and exposed on the read surface
Each persisted `test_case_run_results` row SHALL carry the `multi_turn_id` of its originating multi-turn (NULL for single-turn rows), and the result read API SHALL expose it as `multiTurnId` (nullable `UUID`) so a client can group turn rows without relying on `trace_id`.
Status: **Implemented**

#### Scenario: Multi-turn result rows carry the multi-turn id
- **WHEN** a multi-turn is executed and each surviving turn is persisted as its own result row
- **THEN** every one of those rows SHALL carry the same non-null `multi_turn_id` equal to the source multi-turn's id
- **AND** the broken `0/0` sentinel row and the degenerate "no readable turns" ERROR row for that multi-turn SHALL also carry that `multi_turn_id`

#### Scenario: Single-turn result rows carry NULL
- **WHEN** a single-turn test case is executed
- **THEN** the persisted result row SHALL carry `multi_turn_id = NULL`
- **AND** `TestCaseRunResultResponseDto` SHALL omit the `multiTurnId` field (via `@JsonInclude(NON_NULL)`), leaving the single-turn payload byte-identical to prior behavior

#### Scenario: GET single result exposes multiTurnId
- **WHEN** `GET /api/v1/analytics/test-case-results/{id}` returns a multi-turn result row
- **THEN** the response SHALL include `multiTurnId` as a `UUID` string equal to the row's `multi_turn_id`

### Requirement: Results batch write accepts optional multiTurnId
The results batch-write item (`TestCaseRunResultItemDto`) SHALL accept an optional, nullable `multiTurnId` (`UUID`) that is persisted to the `multi_turn_id` column, defaulting to NULL when omitted so existing single-turn callers stay byte-compatible.
Status: **Implemented**

#### Scenario: Batch item supplies multiTurnId
- **WHEN** a results batch item includes `multiTurnId`
- **THEN** the value SHALL be persisted to the `multi_turn_id` column

#### Scenario: Batch item omits multiTurnId
- **WHEN** a results batch item omits `multiTurnId`
- **THEN** the persisted row SHALL carry `multi_turn_id = NULL` and be otherwise byte-identical to prior single-turn behavior
