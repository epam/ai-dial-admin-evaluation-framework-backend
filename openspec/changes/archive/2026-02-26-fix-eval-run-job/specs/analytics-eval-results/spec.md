## MODIFIED Requirements

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

### Requirement: Filter framework extension for JSONB path filtering
`FilterFieldType.JSONB_STRING` type, `WhereBuilder` extended for JSONB path access with parameterized keys, `FilterWhitelists.ANALYTICS_RESULTS` whitelist. TTFT/TTLT filter entries removed; `retryCount` filter entry added.
Status: **Implemented**

#### Scenario: Filter whitelist fields
- **WHEN** the analytics results filter whitelist is configured
- **THEN** `FilterWhitelists.ANALYTICS_RESULTS` SHALL include 11 fields: `suiteId` (UUID), `runId` (UUID), `testCaseId` (UUID), `testCaseName` (STRING), `executionStatus` (STRING), `runIndex` (LONG), `createdAt` (LONG), `execDurationMs` (LONG), `responseStatusCode` (LONG), `retryCount` (LONG), `testCaseData` (JSONB_STRING). The `executionInfo.timeToFirstTokenMs` and `executionInfo.timeToLastTokenMs` entries SHALL be removed (reducing from 12 to 11 fields, since 2 are dropped and 1 `retryCount` is added). Note: `retryCount` uses `FilterFieldType.LONG` (no INTEGER type in the filter framework; LONG works for DB INTEGER columns via implicit SQL cast).

## ADDED Requirements

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
- **WHEN** client queries with `filter=retryCount:gte:1`
- **THEN** system SHALL return results where at least one retry was attempted

## REMOVED Requirements

### Requirement: Streaming timing fields in TestCaseRunResult model
**Reason**: TTFT/TTLT fields add significant complexity (~25 files) with limited user value. Removed globally.
**Migration**: Remove `executionInfo.timeToFirstTokenMs` and `executionInfo.timeToLastTokenMs` from all API responses. DB columns dropped via migration `V1.4__DropTimingAddRetryColumns.sql`.

### Requirement: Filtering on streaming timing fields
**Reason**: Underlying TTFT/TTLT data fields removed. Filters no longer applicable.
**Migration**: Remove `executionInfo.timeToFirstTokenMs` and `executionInfo.timeToLastTokenMs` filter entries from `FilterWhitelists.ANALYTICS_RESULTS`.
