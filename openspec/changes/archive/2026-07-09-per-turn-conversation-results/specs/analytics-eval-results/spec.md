## MODIFIED Requirements

### Requirement: Database schema for test case run results
The analytics database SHALL contain a `test_case_run_results` table storing flat, denormalized, append-only execution results, including retry tracking fields and **per-turn identity** (`turn_index`, `total_turns`) so that each turn of a multi-turn conversation is its own row.
Status: **Planned**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migrations through the per-turn change are applied
- **THEN** the `test_case_run_results` table SHALL have columns: `id` (VARCHAR(36)), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `turn_index` (INTEGER, NOT NULL, DEFAULT 0), `total_turns` (INTEGER, NOT NULL, DEFAULT 1), `test_case_data` (JSONB, NOT NULL), `request_body` (JSONB, nullable), `response_body` (JSONB, nullable), `response_status_code` (INTEGER, nullable), `execution_status` (VARCHAR(20), NOT NULL), `exec_started_at_ms` (BIGINT, NOT NULL), `exec_completed_at_ms` (BIGINT, NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `trace_id` (VARCHAR(128), nullable), `extracted_columns` (JSONB, default `{}`), `extraction_warnings` (JSONB, default `[]`), `retry_count` (INTEGER, NOT NULL, DEFAULT 0), `log_details` (JSONB, nullable), `created_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: Turn columns backfill existing rows
- **WHEN** the `ADD COLUMN turn_index INTEGER NOT NULL DEFAULT 0` / `total_turns INTEGER NOT NULL DEFAULT 1` migration is applied to a table with pre-existing single-turn rows
- **THEN** every pre-existing row SHALL carry `turn_index = 0` and `total_turns = 1` with no separate backfill pass and no table rewrite

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** the UNIQUE constraint SHALL be `(test_suite_run_id, test_case_id, run_index, turn_index, created_at_ms)`, so each turn of a conversation is uniquely keyed and idempotent `ON CONFLICT DO NOTHING` writes hold per turn
- **AND** because `turn_index` is `NOT NULL`, the index SHALL be a plain unique index (no `NULLS NOT DISTINCT`)

#### Scenario: Retry columns
- **WHEN** the retry-tracking migration is applied
- **THEN** `retry_count` (INTEGER, NOT NULL, DEFAULT 0) and `log_details` (JSONB, nullable) columns SHALL exist on `test_case_run_results`

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist (soft references only)

### Requirement: Cross-reference to eval summaries
The `test_case_run_results` table remains the raw execution log (request/response bodies, retry logs, trace IDs), now at **per-turn** granularity. The `test_case_eval_summaries` table (defined in `metrics-storage` spec) serves as the metric-enriched analytical surface. The two tables SHALL be linked by `test_case_run_result_id` (soft FK); a multi-turn conversation SHALL contribute one result row and one summary row **per turn**.
Status: **Planned**

#### Scenario: Raw endpoints return per-turn rows
- **WHEN** the raw `test-case-results` list/get/count endpoints are called for a multi-turn run
- **THEN** they SHALL return one row per turn, each carrying its `turnIndex`/`totalTurns`, without metric scores

### Requirement: Batch write test case run results
The service SHALL provide `POST /api/v1/analytics/test-case-results` to persist a batch of test case run results. Envelope DTO with `testSuiteId`, `testSuiteRunId`, and `results` array. All-or-nothing semantics. Idempotent via `ON CONFLICT DO NOTHING`, whose conflict target is the natural key **including `turn_index`**: `(test_suite_run_id, test_case_id, run_index, turn_index, created_at_ms)`. Each result item MAY carry `turnIndex` and `totalTurns`; both are **optional** and default to `0` and `1` respectively when omitted, so pre-existing single-turn callers remain byte-compatible. Results include retry tracking fields and actual request body.
Status: **Planned**

#### Scenario: Intra-batch duplicates
- **WHEN** a batch contains duplicate `(testCaseId, runIndex, turnIndex)` items
- **THEN** the first is inserted, subsequent silently skipped via `ON CONFLICT DO NOTHING`
- **AND** two items sharing `(testCaseId, runIndex)` but differing in `turnIndex` SHALL both be inserted (distinct turns are not duplicates)

#### Scenario: Turn fields default for single-turn callers
- **WHEN** a batch item omits `turnIndex` and `totalTurns`
- **THEN** the persisted row SHALL carry `turn_index = 0` and `total_turns = 1`, and be otherwise byte-identical to prior single-turn behavior

## ADDED Requirements

### Requirement: Turn fields exposed on the test case run result API
The test-case-run-results list and detail responses SHALL expose `turnIndex` (0-based) and `totalTurns` (count) so clients can group flat rows into conversations and identify a turn's position. Within a conversation, clients group rows by `(testCaseId, runIndex)` and sort each group by `turnIndex`; the server does NOT guarantee within-conversation ordering (the keyset pagination spine `ORDER BY created_at_ms DESC, id DESC` and the opaque cursor wire format are unchanged). Single-turn results SHALL report `turnIndex = 0, totalTurns = 1`.
Status: **Planned**

#### Scenario: Multi-turn result rows carry turn fields
- **WHEN** a 3-turn conversation's results are listed
- **THEN** three rows SHALL be returned for the same `(testCaseId, runIndex)`, with `turnIndex` `0`, `1`, `2` and `totalTurns` `3` (clients sort each group by `turnIndex`; the server does not impose a turn order)

#### Scenario: Single-turn result row is unchanged in shape
- **WHEN** a non-multi-turn suite's result is listed
- **THEN** the row SHALL carry `turnIndex = 0` and `totalTurns = 1`, and be otherwise byte-identical to prior behavior
