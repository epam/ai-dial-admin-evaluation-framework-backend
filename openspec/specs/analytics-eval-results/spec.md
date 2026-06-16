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
The analytics database SHALL contain a `test_case_run_results` table storing flat, denormalized, append-only execution results, including retry tracking fields.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the analytics Flyway migration is applied
- **THEN** the `test_case_run_results` table SHALL have columns: `id` (VARCHAR(36)), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `test_case_data` (JSONB, NOT NULL), `request_body` (JSONB, nullable), `response_body` (JSONB, nullable), `response_status_code` (INTEGER, nullable), `execution_status` (VARCHAR(20), NOT NULL), `exec_started_at_ms` (BIGINT, NOT NULL), `exec_completed_at_ms` (BIGINT, NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `trace_id` (VARCHAR(128), nullable), `extracted_columns` (JSONB, default `{}`), `extraction_warnings` (JSONB, default `[]`), `retry_count` (INTEGER, NOT NULL, DEFAULT 0), `log_details` (JSONB, nullable), `created_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: Retry tracking columns added
- **WHEN** the Flyway migration `V1.4__DropTimingAddRetryColumns.sql` is applied
- **THEN** `retry_count` (INTEGER, NOT NULL, DEFAULT 0) and `log_details` (JSONB, nullable) columns SHALL be added to `test_case_run_results`

#### Scenario: Streaming timing columns removed
- **WHEN** the Flyway migration `V1.4__DropTimingAddRetryColumns.sql` is applied
- **THEN** `time_to_first_token_ms` and `time_to_last_token_ms` columns SHALL be dropped from `test_case_run_results`

#### Scenario: Indexes
- **WHEN** the migration is applied
- **THEN** existing indexes SHALL be preserved: `(test_suite_id, test_suite_run_id, test_case_name)`, `(id)`. No index on retry columns (per-result metadata, not query filters).

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** the existing UNIQUE constraint on `(test_suite_run_id, test_case_id, run_index, created_at_ms)` SHALL be preserved

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist (soft references to meta DB only)

#### Scenario: No updated_at column
- **WHEN** the migration is applied
- **THEN** the table SHALL NOT have an `updated_at_ms` column (results are immutable/append-only)

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
- **WHEN** a batch contains duplicate `(testCaseId, runIndex)` items
- **THEN** the first is inserted, subsequent silently skipped via `ON CONFLICT DO NOTHING`

#### Scenario: Batch write accepts retry tracking fields
- **WHEN** a batch write includes `retryCount` and `logDetails` per result item
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
The `test_case_run_results` table remains the raw execution log (request/response bodies, retry logs, trace IDs). The `test_case_eval_summaries` table (defined in `metrics-storage` spec) serves as the metric-enriched analytical surface, containing denormalized test case context plus metric scores. The two tables are linked by `test_case_run_result_id` (soft FK). No changes to existing `test_case_run_results` schema or API endpoints.
Status: **Implemented**

#### Scenario: Existing endpoints unchanged
- **WHEN** clients call existing `GET /api/v1/analytics/test-case-results` endpoints
- **THEN** behavior SHALL remain unchanged — these endpoints return raw execution data without metric scores

#### Scenario: Eval summaries reference test case results
- **WHEN** an eval summary row is created
- **THEN** it SHALL contain a `test_case_run_result_id` referencing the original test case run result (soft FK, no DB constraint)

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
