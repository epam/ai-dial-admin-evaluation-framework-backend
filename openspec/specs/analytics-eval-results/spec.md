# Analytics Eval Results

## Purpose
This spec defines test case run result storage and retrieval in the analytics datasource. Batch write API for the evaluation job (all-or-nothing, JDBC batch insert), keyset-paginated read API with generic filter framework (reuses existing `filter=field:operator:value` syntax, extended with JSONB path filtering on `testCaseData`). Flat denormalized data model with `run_index` for multi-run suites. Append-only (immutable results).

Status: **Implemented**

## Key Terms
- **TestCaseRunResult**: A single test case execution outcome within a test suite run. Immutable after creation.
- **Keyset pagination**: Cursor-based pagination using `(created_at_ms, id)` as the position marker. No OFFSET/LIMIT.
- **ExecutionInfo**: Grouped execution context (status, timing, traceId) -- flat in DB, nested in API DTO.
- **Batch write**: The only write pattern -- accepts an array of results per request. All-or-nothing semantics.
- **JSONB path filtering**: Filtering on top-level keys within `testCaseData` JSONB column using dot-notation (e.g., `testCaseData.prompt:contains:hello`).

## Requirements

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

### Requirement: Batch write test case run results
The service SHALL provide `POST /api/v1/analytics/test-case-results` to persist a batch of test case run results. Envelope DTO with `testSuiteId`, `testSuiteRunId`, and `results` array. All-or-nothing semantics. Idempotent via `ON CONFLICT DO NOTHING`, whose conflict target is the natural key **including `turn_index`**: `(test_suite_run_id, test_case_id, run_index, turn_index, created_at_ms)`. Each result item MAY carry `turnIndex` and `totalTurns`; both are **optional** and default to `0` and `1` respectively when omitted, so pre-existing single-turn callers remain byte-compatible. Results include retry tracking fields and actual request body.
Status: **Implemented**

#### Scenario: Successful batch write
- **WHEN** client calls `POST /api/v1/analytics/test-case-results` with a valid envelope
- **THEN** system SHALL insert all results atomically (skipping duplicates) and return HTTP 201 with `{"totalItems": N}` echoing the input count

#### Scenario: Empty results array
- **WHEN** client submits an envelope whose `results` array is empty
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Batch item count limit exceeded
- **WHEN** `results` array exceeds `analytics.results.batch.max-items` (default 10000)
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Request body size limit exceeded
- **WHEN** request body exceeds `analytics.results.batch.max-request-size-bytes` (default 10MB)
- **THEN** system SHALL return HTTP 413 before deserialization, enforced by `MaxRequestBodyFilter`

#### Scenario: Validation of required fields
- **WHEN** required fields are missing (`testSuiteId`, `testSuiteRunId`, `testCaseId`, `testCaseName`, `testCaseData`, `runIndex`, `executionInfo.status`, `executionInfo.startedAt`, `executionInfo.completedAt`)
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Validation that completedAt >= startedAt
- **WHEN** `executionInfo.completedAt` < `executionInfo.startedAt`
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Validation that testCaseData is a JSON object
- **WHEN** `testCaseData` is not a JSON object
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Run existence validation
- **WHEN** a batch write is processed
- **THEN** the service SHALL read the run from meta DB. If not found, return HTTP 404.

#### Scenario: Suite ID mismatch validation
- **WHEN** envelope's `testSuiteId` differs from the run's `testSuiteId`
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Timestamp assignment from run
- **WHEN** a batch write is processed for a valid run
- **THEN** all records receive the run's `created_at_ms` from meta DB. `exec_duration_ms` is computed server-side as `completedAt - startedAt`.

#### Scenario: Idempotent retry
- **WHEN** client retries a previously successful batch
- **THEN** system SHALL return HTTP 201 (duplicates silently skipped)

#### Scenario: Intra-batch duplicates
- **WHEN** a batch contains duplicate `(testCaseId, runIndex, turnIndex)` items
- **THEN** the first is inserted, subsequent silently skipped via `ON CONFLICT DO NOTHING`
- **AND** two items sharing `(testCaseId, runIndex)` but differing in `turnIndex` SHALL both be inserted (distinct turns are not duplicates)

#### Scenario: Turn fields default for single-turn callers
- **WHEN** a batch item omits `turnIndex` and `totalTurns`
- **THEN** the persisted row SHALL carry `turn_index = 0` and `total_turns = 1`, and be otherwise byte-identical to prior single-turn behavior

#### Scenario: Batch write accepts retry tracking fields
- **WHEN** a batch write includes `retryCount` and `logDetails` per result item
- **THEN** the values SHALL be persisted to the `retry_count` and `log_details` columns

#### Scenario: Batch write accepts requestBody
- **WHEN** a batch write includes `requestBody` per result item
- **THEN** the value SHALL be persisted to the `request_body` column as JSONB

### Requirement: Results batch write accepts optional multiTurnId
The results batch-write item (`TestCaseRunResultItemDto`) SHALL accept an optional, nullable `multiTurnId` (`UUID`) that is persisted to the `multi_turn_id` column, defaulting to NULL when omitted so existing single-turn callers stay byte-compatible.
Status: **Implemented**

#### Scenario: Batch item supplies multiTurnId
- **WHEN** a results batch item includes `multiTurnId`
- **THEN** the value SHALL be persisted to the `multi_turn_id` column

#### Scenario: Batch item omits multiTurnId
- **WHEN** a results batch item omits `multiTurnId`
- **THEN** the persisted row SHALL carry `multi_turn_id = NULL` and be otherwise byte-identical to prior single-turn behavior

### Requirement: List test case run results with keyset pagination and generic filters
The service SHALL provide `GET /api/v1/analytics/test-case-results` with cursor-based pagination and `filter=field:operator:value` syntax.
Status: **Implemented**

#### Scenario: First page (no cursor)
- **WHEN** client queries without cursor
- **THEN** system SHALL return results ordered by `created_at_ms DESC, id DESC`, with `nextCursor` if more exist

#### Scenario: Subsequent pages (with cursor)
- **WHEN** client provides a cursor
- **THEN** system SHALL return results after the cursor position

#### Scenario: Cursor encoding
- **WHEN** the system generates a cursor
- **THEN** it SHALL be URL-safe Base64-encoded JSON `{"createdAt":<ms>,"id":"<uuid>"}`

#### Scenario: Invalid cursor
- **WHEN** client provides a malformed cursor
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Sort is fixed
- **WHEN** client queries results
- **THEN** results SHALL always be sorted by `created_at_ms DESC, id DESC`. No user-configurable sort.

#### Scenario: Sort parameter rejected
- **WHEN** client provides a `sort` query parameter
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Required filter -- suiteId always required
- **WHEN** client queries without `suiteId:eq:...` filter
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Run-anchored partition pruning (run exists)
- **WHEN** `runId` filter is present and run exists in meta
- **THEN** service adds `WHERE created_at_ms = :runCreatedAt` for partition pruning

#### Scenario: Run-anchored partition pruning (orphan scenario)
- **WHEN** `runId` filter is present but run not found in meta
- **THEN** service skips partition pruning and queries with `runId` filter only

#### Scenario: Filter by testCaseData JSONB path
- **WHEN** client includes `filter=testCaseData.<key>:<op>:<value>`
- **THEN** system filters using `test_case_data->>:jsonbKeyParam` with parameterized key. Only top-level keys supported.

### Requirement: Get single test case run result by ID
`GET /api/v1/analytics/test-case-results/{id}` -- returns single result or 404.
Status: **Implemented**

### Requirement: Count results
`GET /api/v1/analytics/test-case-results/count` -- returns count matching filters. Requires `suiteId` filter.
Status: **Implemented**

### Requirement: Configuration properties for analytics results
`analytics.results.batch.max-items` (default 10000) and `analytics.results.batch.max-request-size-bytes` (default 10485760).
Status: **Implemented**

### Requirement: ExecutionStatus enum
Values: `SUCCESS`, `FAILED`, `TIMEOUT`, `ERROR`.
Status: **Implemented**

### Requirement: Filter framework extension for JSONB path filtering
`FilterFieldType.JSONB_STRING` type, `WhereBuilder` extended for JSONB path access with parameterized keys, `FilterWhitelists.ANALYTICS_RESULTS` whitelist. TTFT/TTLT filter entries removed; `retryCount` filter entry added.
Status: **Implemented**

### Requirement: Request body size limit via MaxRequestBodyFilter
Two-phase enforcement: eager `Content-Length` check (JSON 413 response) + lazy stream wrapping for chunked encoding. Registered after Spring Security filter chain.
Status: **Implemented**

### Requirement: Cross-reference to eval summaries
The `test_case_run_results` table remains the raw execution log (request/response bodies, retry logs, trace IDs), now at **per-turn** granularity. The `test_case_eval_summaries` table (defined in `metrics-storage` spec) serves as the metric-enriched analytical surface. The two tables SHALL be linked by `test_case_run_result_id` (soft FK); a multi-turn SHALL contribute one result row and one summary row **per turn**.
Status: **Implemented**

#### Scenario: Raw endpoints return per-turn rows
- **WHEN** the raw `test-case-results` list/get/count endpoints are called for a multi-turn run
- **THEN** they SHALL return one row per turn, each carrying its `turnIndex`/`totalTurns`, without metric scores

#### Scenario: Eval summaries reference test case results
- **WHEN** an eval summary row is created
- **THEN** it SHALL contain a `test_case_run_result_id` referencing the original test case run result (soft FK, no DB constraint)

### Requirement: Turn fields exposed on the test case run result API
The test-case-run-results list and detail responses SHALL expose `turnIndex` (0-based) and `totalTurns` (count) so clients can group flat rows into multi-turns and identify a turn's position. Within a multi-turn, clients group rows by `(testCaseId, runIndex)` and sort each group by `turnIndex`; the server does NOT guarantee within-multi-turn ordering (the keyset pagination spine `ORDER BY created_at_ms DESC, id DESC` and the opaque cursor wire format are unchanged). Single-turn results SHALL report `turnIndex = 0, totalTurns = 1`.
Status: **Implemented**

#### Scenario: Multi-turn result rows carry turn fields
- **WHEN** a 3-turn multi-turn's results are listed
- **THEN** three rows SHALL be returned for the same `(testCaseId, runIndex)`, with `turnIndex` `0`, `1`, `2` and `totalTurns` `3` (clients sort each group by `turnIndex`; the server does not impose a turn order)

#### Scenario: Single-turn result row is unchanged in shape
- **WHEN** a non-multi-turn suite's result is listed
- **THEN** the row SHALL carry `turnIndex = 0` and `totalTurns = 1`, and be otherwise byte-identical to prior behavior

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

## Implementation Notes
- Controller: `AnalyticsResultController` -- `@LogExecution`, `@Validated`
- Service: `AnalyticsResultService` -- reads run from meta for validation/timestamp, writes to analytics
- Repository: `PostgresTestCaseRunResultRepository` -- `@Qualifier("analyticsJdbcTemplate")`, `batchUpdate()` with `ON CONFLICT DO NOTHING`, `PostgresJsonbSqlParameter` for JSONB writes
- Model: `TestCaseRunResult` -- JSONB fields as `String` (raw JSON)
- Cursor: `Cursor` record (data layer) + `CursorCodec` (service layer, URL-safe Base64)
- Mapper: `TestCaseRunResultMapper` (MapStruct) -- computes `execDurationMs` server-side
- Filter whitelist: `FilterWhitelists.ANALYTICS_RESULTS` with 11 fields including `testCaseData` (JSONB_STRING), `retryCount` (LONG)
- Error handling: `PAYLOAD_TOO_LARGE` error code, `InvalidFilterException` handler in `DefaultExceptionHandler`
- Migration: `V1.1__CreateTestCaseRunResultsTable.sql` in `db/migration/analytics/POSTGRES/`
- Retry/timing migration: `V1.4__DropTimingAddRetryColumns.sql` in `db/migration/analytics/POSTGRES/`

### Requirement: Retry tracking fields in TestCaseRunResult model
The `TestCaseRunResult` data model and associated DTOs SHALL include retry tracking fields for recording retry attempt counts and structured retry logs.
Status: **Implemented**

#### Scenario: Model fields
- **WHEN** a `TestCaseRunResult` is constructed
- **THEN** it SHALL support `retryCount` (Integer, NOT NULL, default 0) and `logDetails` (String, nullable — raw JSON) fields

#### Scenario: DTO fields in read API response
- **WHEN** a client reads test case run results via `GET /api/v1/analytics/test-case-results`
- **THEN** each result in the response SHALL include `retryCount` (Integer) and `logDetails` (Object, nullable — structured retry log deserialized from JSONB; follows existing pattern for `testCaseData`/`extractedColumns`)

#### Scenario: Filtering by retryCount
- **WHEN** client queries with `filter=retryCount:ge:1`
- **THEN** system SHALL return results where at least one retry was attempted

#### Scenario: Filter whitelist fields
- **WHEN** the analytics results filter whitelist is configured
- **THEN** `FilterWhitelists.ANALYTICS_RESULTS` SHALL include 11 fields: `suiteId` (UUID), `runId` (UUID), `testCaseId` (UUID), `testCaseName` (STRING), `executionStatus` (STRING), `runIndex` (LONG), `createdAt` (LONG), `execDurationMs` (LONG), `responseStatusCode` (LONG), `retryCount` (LONG), `testCaseData` (JSONB_STRING). Note: `retryCount` uses `FilterFieldType.LONG` (no INTEGER type in the filter framework; LONG works for DB INTEGER columns via implicit SQL cast).
