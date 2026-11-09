# Analytics Eval Results

## Purpose
This spec defines append-only test case run result storage and retrieval in the analytics datasource: transactional typed-jOOQ batch writes, cursor-based reads, and allowlisted filters including JSONB paths. Results identify each repetition, request, and turn.

Status: **Implemented**

## Key Terms
- **TestCaseRunResult**: A single test case execution outcome within a test suite run. Immutable after creation.
- **Keyset pagination**: Cursor-based pagination using `(created_at_ms, id)` as the position marker, with `LIMIT size + 1` and no OFFSET.
- **ExecutionInfo**: Grouped execution context (status, timing, traceId) -- flat in DB, nested in API DTO.
- **Batch write**: The only write pattern -- accepts an array of results per request. All-or-nothing semantics.
- **JSONB path filtering**: Filtering on top-level keys within `testCaseData` JSONB column using dot-notation (e.g., `testCaseData.prompt:co:hello`).

## Requirements

### Requirement: Database schema for test case run results
The analytics database SHALL contain a `test_case_run_results` table storing flat, denormalized, append-only execution results, including retry tracking fields, per-turn columns, and per-request columns.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the installed analytics schema is inspected
- **THEN** the `test_case_run_results` table SHALL have columns: `id` (VARCHAR(36)), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `test_case_data` (JSONB, NOT NULL), `request_body` (JSONB, nullable), `response_body` (JSONB, nullable), `response_status_code` (INTEGER, nullable), `execution_status` (VARCHAR(20), NOT NULL), `exec_started_at_ms` (BIGINT, NOT NULL), `exec_completed_at_ms` (BIGINT, NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `trace_id` (VARCHAR(128), nullable), `extracted_columns` (JSONB, NOT NULL, DEFAULT `{}`), `extraction_warnings` (JSONB, NOT NULL, DEFAULT `[]`), `retry_count` (INTEGER, NOT NULL, DEFAULT 0), `log_details` (JSONB, nullable), `turn_index` (INTEGER, NOT NULL, DEFAULT 0), `total_turns` (INTEGER, NOT NULL, DEFAULT 1), `request_index` (INTEGER, NOT NULL, DEFAULT 0), `total_requests` (INTEGER, NOT NULL, DEFAULT 1), `created_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: Retry metadata and request-turn identity
- **WHEN** execution results are persisted
- **THEN** rows SHALL support retry metadata and request/turn identity with the types and defaults listed above; there SHALL be no `time_to_first_token_ms` or `time_to_last_token_ms` columns

#### Scenario: Indexes
- **WHEN** the installed database schema is inspected
- **THEN** indexes SHALL exist on `(test_suite_id, test_suite_run_id, test_case_name)` and `(id)`; there SHALL be no separate retry/request-column index

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the installed database schema is inspected
- **THEN** the UNIQUE constraint `uq_results_run_case_index` SHALL be `(test_suite_run_id, test_case_id, run_index, request_index, turn_index, created_at_ms)`, so each (request, turn) pair is uniquely keyed and `ON CONFLICT` holds per chain call

#### Scenario: No foreign keys
- **WHEN** the installed database schema is inspected
- **THEN** no foreign key constraints SHALL exist (soft references to meta DB only)

#### Scenario: No updated_at column
- **WHEN** the installed database schema is inspected
- **THEN** the table SHALL NOT have an `updated_at_ms` column (results are immutable/append-only)

### Requirement: Per-turn result rows with turn columns
`test_case_run_results` SHALL persist one row per executed turn. `turn_index INTEGER NOT NULL DEFAULT 0` and `total_turns INTEGER NOT NULL DEFAULT 1` SHALL identify the turn and planned turn count. Turns of one test-case repetition SHALL share `test_case_id` and `trace_id`; executed turn indices SHALL be contiguous from 0, while early termination MAY leave planned later turns absent. Each row SHALL carry its own `test_case_data`, accumulated `request_body`, raw `response_body`, and scalar `extracted_columns`. Response DTOs SHALL expose `turnIndex` and `totalTurns`; batch-write items SHALL accept them optionally with `0`/`1` defaults.
Status: **Implemented**

#### Scenario: Turn rows share test_case_id and trace_id
- **WHEN** a multi-turn case produces N turn rows
- **THEN** all rows share the same `test_case_id` and `trace_id`, with `turn_index` `0..N-1` and `total_turns=N`

#### Scenario: Single-turn defaults preserved
- **WHEN** a single-turn result is written
- **THEN** it SHALL have `turn_index=0` and `total_turns=1`

#### Scenario: Two turns do not collide on the natural key
- **WHEN** two turns of the same case/run are written
- **THEN** both persist because `turn_index` distinguishes them

#### Scenario: Single-turn batch-write remains compatible
- **WHEN** an external caller writes a result omitting turn fields
- **THEN** it defaults to `turn_index=0, total_turns=1`

### Requirement: Per-request result rows with request columns
`test_case_run_results` SHALL persist one row per executed HTTP call of a suite's request chain. The columns `request_index INTEGER NOT NULL DEFAULT 0` and `total_requests INTEGER NOT NULL DEFAULT 1` SHALL identify the row's position and planned chain length. Rows of one test-case repetition SHALL share `test_case_id`, `run_index`, and `trace_id`; the request and turn dimensions SHALL identify each executed call by `(request_index, turn_index)`. Executed indices SHALL be contiguous within each dimension; early termination MAY leave later planned calls absent. Single-request suites SHALL carry `0`/`1`. Response DTOs SHALL expose `requestIndex` and `totalRequests`; batch-write DTOs SHALL accept them optionally with `0`/`1` defaults.

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

### Requirement: Batch write test case run results
The service SHALL provide `POST /api/v1/analytics/test-case-results` to persist a batch of test case run results. Envelope DTO with `testSuiteId`, `testSuiteRunId`, and `results` array. All-or-nothing semantics. Idempotent via `ON CONFLICT DO NOTHING`. Results include retry tracking fields and actual request body.
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
- **THEN** a declared oversized `Content-Length` SHALL return HTTP 413 `PAYLOAD_TOO_LARGE` before deserialization; a request without content length SHALL be limited while its body is read

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
- **WHEN** a batch contains duplicate `(testCaseId, runIndex, requestIndex, turnIndex)` items for the same run
- **THEN** the first is inserted, subsequent silently skipped via `ON CONFLICT DO NOTHING`

#### Scenario: Batch write accepts retry tracking fields
- **WHEN** a batch write includes `executionInfo.retryCount` and `executionInfo.logDetails` per result item
- **THEN** the values SHALL be persisted to the `retry_count` and `log_details` columns

#### Scenario: Batch write accepts requestBody
- **WHEN** a batch write includes `requestBody` per result item
- **THEN** the value SHALL be persisted to the `request_body` column as JSONB

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
`GET /api/v1/analytics/test-case-results/{id}` SHALL return a single readable result or HTTP 404 `NOT_FOUND`.
Status: **Implemented**

#### Scenario: Lookup by result ID
- **WHEN** a client requests a result ID
- **THEN** the system SHALL return HTTP 200 for a readable row and HTTP 404 for an unknown or tombstoned row

### Requirement: Count results
`GET /api/v1/analytics/test-case-results/count` SHALL return `{"count": N}` for readable rows matching the allowlisted filters and SHALL require a `suiteId` filter.
Status: **Implemented**

#### Scenario: Count requires suite scope
- **WHEN** a client requests the count with a valid `suiteId` filter
- **THEN** the system SHALL return the matching readable-row count; omitting that filter SHALL return HTTP 400 `VALIDATION_ERROR`

### Requirement: Configuration properties for analytics results
The system SHALL bind `analytics.results.batch.max-items` (default 10000) and `analytics.results.batch.max-request-size-bytes` (default 10485760) from configuration.
Status: **Implemented**

#### Scenario: Configured limits
- **WHEN** a deployment configures the analytics-result batch limits
- **THEN** batch item validation and HTTP body limiting SHALL use those configured values

### Requirement: ExecutionStatus enum
The execution status model SHALL support `SUCCESS`, `FAILED`, `TIMEOUT`, and `ERROR`.
Status: **Implemented**

#### Scenario: Status accepted by batch write
- **WHEN** a batch item supplies a valid execution status
- **THEN** it SHALL persist that status; an unknown enum value SHALL fail HTTP request binding with HTTP 400

### Requirement: Filter framework extension for JSONB path filtering
`WhereBuilder` SHALL build typed-jOOQ predicates for `FilterWhitelists.ANALYTICS_RESULTS`, supporting one-level `JSONB_STRING` paths with bound keys and the scalar `retryCount` filter. Streaming timing fields SHALL NOT be filterable.
Status: **Implemented**

#### Scenario: Allowlisted JSONB path
- **WHEN** a result query filters `testCaseData.prompt:co:hello`
- **THEN** the JSONB key SHALL be bound as a value and the text comparison SHALL be case-insensitive; a deeper path SHALL be rejected

### Requirement: Request body size limit via MaxRequestBodyFilter
The HTTP body limiter SHALL reject an excessive `Content-Length` eagerly with HTTP 413 `PAYLOAD_TOO_LARGE`, and SHALL wrap requests without a content length to detect excess bytes while reading. The filters SHALL be registered after the Spring Security filter chain.
Status: **Implemented**

#### Scenario: Body limit enforcement timing
- **WHEN** a request declares an excessive content length
- **THEN** it SHALL be rejected before deserialization; without a content length, enforcement SHALL occur while the body stream is consumed

### Requirement: Cross-reference to eval summaries
The `test_case_run_results` table SHALL remain the raw execution log (request/response bodies, retry logs, trace IDs). The `test_case_eval_summaries` table (defined in `metrics-storage`) SHALL serve as the metric-enriched analytical surface, linked by `test_case_run_result_id` without a database foreign key.
Status: **Implemented**

#### Scenario: Existing endpoints unchanged
- **WHEN** clients call existing `GET /api/v1/analytics/test-case-results` endpoints
- **THEN** behavior SHALL remain unchanged — these endpoints return raw execution data without metric scores

#### Scenario: Eval summaries reference test case results
- **WHEN** an eval summary row is created
- **THEN** it SHALL contain a `test_case_run_result_id` referencing the original test case run result (soft FK, no DB constraint)

### Requirement: Test case run result reads exclude deleted runs
Reads of `test_case_run_results` — there is no Query DSL entity for this table, so this applies to its direct repository reads (`PostgresTestCaseRunResultRepository.findAll`/`findById`/`count`, including export and listing paths) — SHALL exclude rows belonging to a test suite run that has an analytics tombstone (see `run-analytics-deletion`). The exclusion SHALL be applied in the read query itself, as an anti-join against `run_deletions`.
Status: **Implemented**

#### Scenario: Result listing excludes a deleted run's rows
- **WHEN** a repository read of `test_case_run_results` would otherwise match rows belonging to a deleted run
- **THEN** no row belonging to that run SHALL appear in the result

#### Scenario: An active run's result rows are unaffected
- **WHEN** a repository read targets a run with no analytics tombstone
- **THEN** its matching rows SHALL remain readable

### Requirement: Retry tracking fields in TestCaseRunResult model
The `TestCaseRunResult` data model and associated DTOs SHALL include retry tracking fields for recording retry attempt counts and structured retry logs.
Status: **Implemented**

#### Scenario: Model fields
- **WHEN** a `TestCaseRunResult` is constructed
- **THEN** it SHALL support `retryCount` (Integer) and `logDetails` (String, nullable — raw JSON) fields; the batch DTO mapper SHALL default an omitted retry count to 0

#### Scenario: DTO fields in read API response
- **WHEN** a client reads test case run results via `GET /api/v1/analytics/test-case-results`
- **THEN** each result SHALL expose `executionInfo.retryCount` and optional `executionInfo.logDetails` (structured retry log), rather than top-level retry fields

#### Scenario: Filtering by retryCount
- **WHEN** client queries with `filter=retryCount:ge:1`
- **THEN** system SHALL return results where at least one retry was attempted

#### Scenario: Filter whitelist fields
- **WHEN** the analytics results filter whitelist is configured
- **THEN** `FilterWhitelists.ANALYTICS_RESULTS` SHALL include 11 fields: `suiteId` (UUID), `runId` (UUID), `testCaseId` (UUID), `testCaseName` (STRING), `executionStatus` (STRING), `runIndex` (LONG), `createdAt` (LONG), `execDurationMs` (LONG), `responseStatusCode` (LONG), `retryCount` (LONG), `testCaseData` (JSONB_STRING). Note: `retryCount` uses `FilterFieldType.LONG` (no INTEGER type in the filter framework; LONG works for DB INTEGER columns via implicit SQL cast).

## Implementation Notes
- [Controller](../../../src/main/java/com/epam/aidial/evaluation/web/controller/AnalyticsResultController.java): batch/list/detail/count routes, sort rejection, and pagination resolution.
- [Service](../../../src/main/java/com/epam/aidial/evaluation/service/domain/analytics/AnalyticsResultService.java): meta-run validation/timestamp lookup, batch checks, required suite-filter presence and orphan-safe timestamp pruning.
- [Repository](../../../src/main/java/com/epam/aidial/evaluation/data/db/analytics/repository/PostgresTestCaseRunResultRepository.java): `analyticsDsl`, typed-jOOQ transactional batch inserts with conflict skipping, keyset reads, tombstone exclusion.
- [Mapper](../../../src/main/java/com/epam/aidial/evaluation/service/domain/mapper/TestCaseRunResultMapper.java): nested execution information, duration computation, and field defaults.
- [Body limiter](../../../src/main/java/com/epam/aidial/evaluation/web/filter/MaxRequestBodyFilter.java) and [registration](../../../src/main/java/com/epam/aidial/evaluation/web/filter/MaxRequestBodyFilterConfiguration.java): eager and streaming size checks.
- [Filter whitelist](../../../src/main/java/com/epam/aidial/evaluation/data/db/repository/sql/FilterWhitelists.java) and [predicate builder](../../../src/main/java/com/epam/aidial/evaluation/data/db/repository/sql/WhereBuilder.java) define allowed result fields/operators and JSONB paths.
