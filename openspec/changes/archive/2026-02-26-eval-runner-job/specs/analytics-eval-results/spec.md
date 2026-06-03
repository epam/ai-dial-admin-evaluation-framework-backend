## MODIFIED Requirements

### Requirement: Database schema for test case run results
The analytics database SHALL contain a `test_case_run_results` table storing flat, denormalized, append-only execution results, including streaming timing metrics.
Status: **Implemented** (extended with new columns)

#### Scenario: Table structure
- **WHEN** the analytics Flyway migration is applied
- **THEN** the `test_case_run_results` table SHALL have columns: `id` (VARCHAR(36)), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `test_case_data` (JSONB, NOT NULL), `request_body` (JSONB, nullable), `response_body` (JSONB, nullable), `response_status_code` (INTEGER, nullable), `execution_status` (VARCHAR(20), NOT NULL), `exec_started_at_ms` (BIGINT, NOT NULL), `exec_completed_at_ms` (BIGINT, NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `trace_id` (VARCHAR(128), nullable), `extracted_columns` (JSONB, default `{}`), `extraction_warnings` (JSONB, default `[]`), `time_to_first_token_ms` (BIGINT, nullable), `time_to_last_token_ms` (BIGINT, nullable), `created_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)`.

#### Scenario: New streaming timing columns
- **WHEN** the Flyway migration `V1.3__AddStreamingTimingToTestCaseRunResults.sql` is applied
- **THEN** `time_to_first_token_ms` (BIGINT, nullable) and `time_to_last_token_ms` (BIGINT, nullable) columns SHALL be added to `test_case_run_results`. Columns are nullable in the DB schema for backward compatibility with existing rows, but new results SHALL always populate both fields.

#### Scenario: Timing for streaming responses
- **WHEN** a `TestCaseRunResult` is from a streaming SSE endpoint call
- **THEN** `time_to_first_token_ms` SHALL contain the elapsed time from request start to first content delta, and `time_to_last_token_ms` SHALL contain the elapsed time from request start to last content delta

#### Scenario: Timing for non-streaming responses
- **WHEN** a `TestCaseRunResult` is from a non-streaming endpoint call
- **THEN** `time_to_first_token_ms` and `time_to_last_token_ms` SHALL both be set to `exec_duration_ms` (the entire response arrives at once — first token and last token are the same event). This eliminates null checks in analytics queries and simplifies aggregation across streaming/non-streaming results.

#### Scenario: Indexes
- **WHEN** the migration is applied
- **THEN** existing indexes SHALL be preserved: `(test_suite_id, test_suite_run_id, test_case_name)`, `(id)`. No index on timing columns (per-result metadata, not query filters).

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** the existing UNIQUE constraint on `(test_suite_run_id, test_case_id, run_index, created_at_ms)` SHALL be preserved

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL exist (soft references to meta DB only)

#### Scenario: No updated_at column
- **WHEN** the migration is applied
- **THEN** the table SHALL NOT have an `updated_at_ms` column (results are immutable/append-only)

## ADDED Requirements

### Requirement: Streaming timing fields in TestCaseRunResult model
The `TestCaseRunResult` data model and associated DTO SHALL include nullable streaming timing fields for time-to-first-token and time-to-last-token measurements.

#### Scenario: Model fields
- **WHEN** a `TestCaseRunResult` is constructed
- **THEN** it SHALL support `timeToFirstTokenMs` (Long) and `timeToLastTokenMs` (Long) fields. These are always populated for new results (non-streaming: TTFT = TTLT = duration; streaming: measured from first/last content delta). The DB column remains nullable for backward compatibility with pre-existing rows.

#### Scenario: DTO fields in read API response
- **WHEN** a client reads test case run results via `GET /api/v1/analytics/test-case-results`
- **THEN** each result in the response SHALL include `executionInfo.timeToFirstTokenMs` (Long, nullable — null only for pre-existing rows) and `executionInfo.timeToLastTokenMs` (Long, nullable — null only for pre-existing rows)

#### Scenario: Batch write accepts timing fields
- **WHEN** a batch write includes `executionInfo.timeToFirstTokenMs` and `executionInfo.timeToLastTokenMs`
- **THEN** the values SHALL be persisted to the `time_to_first_token_ms` and `time_to_last_token_ms` columns

#### Scenario: Null timing fields accepted (backward compatibility)
- **WHEN** a batch write omits `executionInfo.timeToFirstTokenMs` and `executionInfo.timeToLastTokenMs` (or sets them to null)
- **THEN** the values SHALL be stored as NULL (not rejected). Note: the real executor always populates these fields; null acceptance is for backward compatibility with external batch writes only.

### Requirement: Filtering on streaming timing fields
The analytics results list API SHALL support filtering on the new streaming timing fields.

#### Scenario: Filter by timeToFirstTokenMs range
- **WHEN** client queries with `filter=executionInfo.timeToFirstTokenMs:gte:100&filter=executionInfo.timeToFirstTokenMs:lte:5000`
- **THEN** system SHALL return results where TTFT is between 100ms and 5000ms

#### Scenario: Filter by timeToLastTokenMs range
- **WHEN** client queries with `filter=executionInfo.timeToLastTokenMs:lte:30000`
- **THEN** system SHALL return results where TTLT is at most 30 seconds
