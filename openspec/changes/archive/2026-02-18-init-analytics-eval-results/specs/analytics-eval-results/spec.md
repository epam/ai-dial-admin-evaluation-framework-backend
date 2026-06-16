# Analytics Eval Results

## Purpose
This spec defines test case run result storage and retrieval in the analytics datasource. Batch write API for the evaluation job (all-or-nothing, JDBC batch insert), keyset-paginated read API with generic filter framework (reuses existing `filter=field:operator:value` syntax, extended with JSONB path filtering on `testCaseData`). Flat denormalized data model with `run_index` for multi-run suites. Append-only (immutable results).

Status: **New**

## Key Terms
- **TestCaseRunResult**: A single test case execution outcome within a test suite run. Immutable after creation.
- **Keyset pagination**: Cursor-based pagination using `(created_at_ms, id)` as the position marker. No OFFSET/LIMIT.
- **ExecutionInfo**: Grouped execution context (status, timing, traceId) — flat in DB, nested in API DTO.
- **Batch write**: The only write pattern — accepts an array of results per request. All-or-nothing semantics.
- **JSONB path filtering**: Filtering on top-level keys within `testCaseData` JSONB column using dot-notation (e.g., `testCaseData.prompt:contains:hello`).

## ADDED Requirements

### Requirement: Database schema for test case run results
The analytics database SHALL contain a `test_case_run_results` table storing flat, denormalized, append-only execution results.

#### Scenario: Table structure
- **WHEN** the analytics Flyway migration is applied
- **THEN** the `test_case_run_results` table SHALL be created with columns: `id` (VARCHAR(36)), `test_suite_run_id` (VARCHAR(36), NOT NULL), `test_suite_id` (VARCHAR(36), NOT NULL), `test_case_id` (VARCHAR(36), NOT NULL), `test_case_name` (VARCHAR(255), NOT NULL), `run_index` (INTEGER, NOT NULL), `test_case_data` (JSONB, NOT NULL), `request_body` (JSONB, nullable), `response_body` (JSONB, nullable), `response_status_code` (INTEGER, nullable), `execution_status` (VARCHAR(20), NOT NULL), `exec_started_at_ms` (BIGINT, NOT NULL), `exec_completed_at_ms` (BIGINT, NOT NULL), `exec_duration_ms` (BIGINT, NOT NULL), `trace_id` (VARCHAR(128), nullable), `created_at_ms` (BIGINT, NOT NULL). Primary key: `(created_at_ms, id)` — composite PK with `created_at_ms` as leading column to enable future time-based partitioning without PK migration

#### Scenario: Indexes
- **WHEN** the migration is applied
- **THEN** an index on `(test_suite_id, test_suite_run_id, test_case_name)` SHALL be created. A standalone index on `(id)` SHALL be created — the composite PK `(created_at_ms, id)` has `created_at_ms` as the leading column, so `findById(UUID id)` cannot use it efficiently without a standalone index. No separate `(created_at_ms DESC, id DESC)` index is needed — the composite PK supports keyset pagination via backward index scan

#### Scenario: UNIQUE constraint for idempotent writes
- **WHEN** the migration is applied
- **THEN** a UNIQUE constraint SHALL be created on `(test_suite_run_id, test_case_id, run_index, created_at_ms)`. Each test case is evaluated exactly once per run index within a run. The constraint includes `created_at_ms` from the initial migration so that future time-based RANGE partitioning needs no constraint migration. Because all results for a run share the same `created_at_ms`, this does not weaken uniqueness. This enables idempotent batch writes via `INSERT ... ON CONFLICT DO NOTHING`.

#### Scenario: No foreign keys
- **WHEN** the migration is applied
- **THEN** no foreign key constraints SHALL be created (soft references to meta DB only, for OLAP compatibility)

#### Scenario: No updated_at column
- **WHEN** the migration is applied
- **THEN** the table SHALL NOT have an `updated_at_ms` column (results are immutable/append-only)

### Requirement: Batch write test case run results
The service SHALL provide `POST /api/v1/analytics/test-case-results` to persist a batch of test case run results. The endpoint SHALL accept an envelope object containing `testSuiteId`, `testSuiteRunId`, and a `results` array (batch-only, no single-result endpoint). `testSuiteId` and `testSuiteRunId` are declared once at the top level — not repeated in every result item — enforcing batch uniformity by schema. **Batch write is all-or-nothing** — if any record fails validation, the entire batch is rejected. Duplicate rows (matching UNIQUE constraint `(test_suite_run_id, test_case_id, run_index, created_at_ms)`) are silently skipped via `INSERT ... ON CONFLICT DO NOTHING`, enabling idempotent retries.

#### Scenario: Successful batch write
- **WHEN** client calls `POST /api/v1/analytics/test-case-results` with a valid envelope `{ "testSuiteId": "<uuid>", "testSuiteRunId": "<uuid>", "results": [...] }`
- **THEN** system SHALL merge the envelope's `testSuiteId`/`testSuiteRunId` into each result, insert all results into the analytics database atomically (skipping duplicates via `ON CONFLICT DO NOTHING`) and return HTTP 201 with `BatchWriteResponseDto` containing `totalItems` (the number of items in the request's `results` array). This echoes the input count — not the DB-level insert count, which is unreliable with `reWriteBatchedInserts=true`.

#### Scenario: Empty results array
- **WHEN** client calls `POST /api/v1/analytics/test-case-results` with an envelope whose `results` array is empty `[]`
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Batch item count limit exceeded
- **WHEN** client submits an envelope whose `results` array has more items than the configured `analytics.results.batch.max-items` (default 10000)
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and a message indicating the maximum batch size

#### Scenario: Request body size limit exceeded
- **WHEN** client submits a request body larger than the configured `analytics.results.batch.max-request-size-bytes` (default 10MB)
- **THEN** system SHALL return HTTP 413 (Payload Too Large) before deserialization, enforced by `MaxRequestBodyFilter`

#### Scenario: Validation of required fields
- **WHEN** client submits an envelope missing required top-level fields (`testSuiteId`, `testSuiteRunId`) or a result item missing required fields (`testCaseId`, `testCaseName`, `testCaseData`, `runIndex`, `executionInfo.status`, `executionInfo.startedAt`, `executionInfo.completedAt`)
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Validation that completedAt >= startedAt
- **WHEN** client submits a result where `executionInfo.completedAt` < `executionInfo.startedAt`
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and a message indicating that completedAt must be >= startedAt. This prevents negative `durationMs` from being stored permanently in the append-only table.

#### Scenario: Validation that testCaseData is a JSON object
- **WHEN** client submits a result where `testCaseData` is a JSON array, string, number, boolean, or null (not an object)
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and a message indicating that testCaseData must be a JSON object. This is required because JSONB path filtering (`->>key`) only operates on object structures.

#### Scenario: Run existence validation
- **WHEN** a batch write is processed
- **THEN** the service SHALL read the `test_suite_runs` record from meta DB using the envelope's `testSuiteRunId`. If the run does not exist, the system SHALL return HTTP 404 with error code `NOT_FOUND`.

#### Scenario: Suite ID mismatch validation
- **WHEN** a batch write is processed and the run exists
- **THEN** the service SHALL validate that the envelope's `testSuiteId` matches the run's `testSuiteId` (from the `test_suite_runs` record). If they differ, the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and a message indicating that the provided testSuiteId does not match the run's test suite. This prevents silent data corruption where results are stored with an incorrect `test_suite_id`, which would corrupt suite-level queries.

#### Scenario: Timestamp assignment from run
- **WHEN** a batch write is processed for a valid run
- **THEN** all records in the batch SHALL receive the run's `created_at_ms` (from the `test_suite_runs` record in meta DB) as their `created_at_ms` value. This ensures all results for a run share the same `created_at_ms`, enabling future partition-safe UNIQUE constraints and exact partition pruning. UUIDs SHALL be generated for each record's `id`. `exec_duration_ms` SHALL be computed server-side as `exec_completed_at_ms - exec_started_at_ms`.

#### Scenario: JDBC batch insert
- **WHEN** the batch is persisted to the database
- **THEN** the system SHALL use `NamedParameterJdbcTemplate.batchUpdate()` with `INSERT ... ON CONFLICT (test_suite_run_id, test_case_id, run_index, created_at_ms) DO NOTHING`. The analytics JDBC URL includes `reWriteBatchedInserts=true` for Postgres driver optimization.

#### Scenario: Idempotent retry
- **WHEN** client retries a batch write that was already successfully persisted (e.g., after a network failure where the response was lost)
- **THEN** system SHALL return HTTP 201 with `{"totalItems": N}` (all rows silently skipped as duplicates via `ON CONFLICT DO NOTHING`). No error, no duplicate data. The `totalItems` reflects the input count, not the number of newly inserted rows.

#### Scenario: Intra-batch duplicates
- **WHEN** a single batch contains two or more items with the same `(testCaseId, runIndex)` combination (combined with the envelope's `testSuiteRunId`)
- **THEN** the first item SHALL be inserted and subsequent duplicates SHALL be silently skipped via `ON CONFLICT DO NOTHING`. The response is still HTTP 201 with `{"totalItems": N}` reflecting the input count — the client is not notified of skipped duplicates. This is intentional: the data integrity guarantee (one result per case per run index) is preserved by the DB constraint regardless of client behavior.

### Requirement: Request DTO structure for batch write
The batch write request SHALL follow the `BatchWriteRequestDto` envelope structure with `testSuiteId`, `testSuiteRunId`, and a `results` array of `TestCaseRunResultItemDto` elements.

#### Scenario: Envelope DTO fields
- **WHEN** client submits a batch write request
- **THEN** the envelope SHALL contain: `testSuiteId` (UUID, required), `testSuiteRunId` (UUID, required), `results` (array of result items, required, non-empty, max size from `analytics.results.batch.max-items`)

#### Scenario: Result item DTO fields
- **WHEN** client submits a result item in the `results` array
- **THEN** each item SHALL contain: `testCaseId` (UUID, required), `testCaseName` (String, required, max 255), `runIndex` (Integer, required, min 0, max 99999), `testCaseData` (JSON object, required — MUST be a JSON object, not array/string/number/null), `requestBody` (JSON object, nullable), `responseBody` (JSON object, nullable — contains response body or error body), `responseStatusCode` (Integer, nullable), `executionInfo` (object, required) containing `status` (ExecutionStatus enum, required — one of SUCCESS, FAILED, TIMEOUT, ERROR), `startedAt` (Long, required — epoch ms), `completedAt` (Long, required — epoch ms, MUST be >= `startedAt`), `traceId` (String, nullable, max 128). Note: `testSuiteId` and `testSuiteRunId` are NOT in the item — they come from the envelope. `durationMs` is NOT in the request — it is computed server-side as `completedAt - startedAt`. For ERROR status (e.g., DNS failure, connection refused), `startedAt` and `completedAt` should reflect the time the attempt was initiated and abandoned.

### Requirement: List test case run results with keyset pagination and generic filters
The service SHALL provide `GET /api/v1/analytics/test-case-results` to list results using cursor-based (keyset) pagination and the existing `filter=field:operator:value` query syntax.

#### Scenario: First page (no cursor)
- **WHEN** client calls `GET /api/v1/analytics/test-case-results?filter=suiteId:eq:<uuid>&filter=runId:eq:<uuid>&size=50`
- **THEN** system SHALL return the first page of results ordered by `created_at_ms DESC, id DESC`, with `nextCursor` if more results exist

#### Scenario: Subsequent pages (with cursor)
- **WHEN** client calls `GET /api/v1/analytics/test-case-results?filter=suiteId:eq:<uuid>&filter=runId:eq:<uuid>&size=50&cursor=<encoded>`
- **THEN** system SHALL return results after the cursor position, ordered by `created_at_ms DESC, id DESC`

#### Scenario: Cursor encoding
- **WHEN** the system generates a cursor
- **THEN** it SHALL be a Base64-encoded JSON `{"createdAt":<ms>,"id":"<uuid>"}`, opaque to the client

#### Scenario: Invalid cursor
- **WHEN** client provides a malformed or undecodable `cursor` value
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Size parameter bounds
- **WHEN** client specifies `size`
- **THEN** it SHALL be between 1 and the configured maximum (default from `pagination.max-size`). Missing `size` defaults to `pagination.default-size`.

#### Scenario: Sort is fixed
- **WHEN** client queries results
- **THEN** results SHALL always be sorted by `created_at_ms DESC, id DESC`. No user-configurable sort parameter. This fixed sort is required for keyset pagination correctness.

#### Scenario: Sort parameter rejected
- **WHEN** client provides a `sort` query parameter on the analytics list endpoint
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR` indicating that sort is not supported on this endpoint

#### Scenario: Within-run sort order (temporary limitation)
- **WHEN** client queries results for a single run (with `runId` filter)
- **THEN** results are ordered by `created_at_ms DESC, id DESC`. Since all results for a run share the same `created_at_ms`, within-run ordering is effectively by UUID (random). The FE SHALL fetch all pages and sort client-side for within-run browsing. This is viable for typical within-run sizes (< 2,000 items). **This is a temporary limitation** — a future iteration will add server-side sort support for within-run queries.

#### Scenario: Required filter — suiteId always required
- **WHEN** client calls `GET /api/v1/analytics/test-case-results` without a `filter` containing `suiteId:eq:...`
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR` indicating that `suiteId` filter is required. This ensures the composite index `(test_suite_id, ...)` is always utilized.

#### Scenario: Filter by runId
- **WHEN** client includes `filter=runId:eq:<uuid>`
- **THEN** system SHALL return only results belonging to that test suite run

#### Scenario: Filter by suiteId
- **WHEN** client includes `filter=suiteId:eq:<uuid>`
- **THEN** system SHALL return results belonging to that test suite (across all runs)

#### Scenario: Filter by executionStatus
- **WHEN** client includes `filter=executionStatus:eq:FAILED`
- **THEN** system SHALL return only results with the specified execution status

#### Scenario: Filter by testCaseName (contains)
- **WHEN** client includes `filter=testCaseName:contains:login`
- **THEN** system SHALL return results where `test_case_name` contains the specified substring (case-insensitive)

#### Scenario: Filter by testCaseId
- **WHEN** client includes `filter=testCaseId:eq:<uuid>`
- **THEN** system SHALL return only results with the specified test case ID. Most efficient when combined with `runId` filter (uses the UNIQUE index).

#### Scenario: Filter by runIndex
- **WHEN** client includes `filter=runIndex:eq:0`
- **THEN** system SHALL return only results with the specified run index

#### Scenario: Filter by testCaseData JSONB path (top-level keys)
- **WHEN** client includes `filter=testCaseData.prompt:contains:hello`
- **THEN** system SHALL return results where `test_case_data->>'prompt'` contains the specified substring (case-insensitive). Only top-level JSONB keys are supported in v1. The JSONB key SHALL be parameterized (bound via named parameter), never interpolated into SQL, to prevent SQL injection.

#### Scenario: Filter by testCaseData — empty JSONB key
- **WHEN** client includes a filter with empty JSONB key (e.g., `testCaseData.:eq:value`)
- **THEN** system SHALL return HTTP 400 with error code `INVALID_FILTER` and a message indicating that the JSONB key must not be empty

#### Scenario: Filter by testCaseData — unsupported nested path
- **WHEN** client includes a filter with nested JSONB path (e.g., `testCaseData.meta.category:eq:test`)
- **THEN** system SHALL return HTTP 400 with error code `INVALID_FILTER` and a message indicating that nested JSONB paths are not supported in v1. This uses the same error code as other filter syntax errors (empty JSONB key, unknown field, unsupported operator) for consistency.

#### Scenario: Multiple filters combined with AND
- **WHEN** client includes multiple `filter` parameters
- **THEN** all filter conditions SHALL be combined with AND semantics

#### Scenario: Filter validation via whitelist
- **WHEN** client includes a filter with an unrecognized field or unsupported operator
- **THEN** system SHALL return HTTP 400 with error code `INVALID_FILTER` including details about the invalid field/operator

#### Scenario: Run-anchored partition pruning on reads (run exists in meta)
- **WHEN** client queries results with a `runId` filter and the run exists in meta DB
- **THEN** the service SHALL look up the run's `createdAt` from meta DB and add `WHERE created_at_ms = :runCreatedAt` to the analytics query transparently (exact partition pruning optimization, not visible in API). If the user also provides explicit `createdAt` filters, both conditions apply with AND semantics — user-provided filters take precedence as they further narrow the result set.

#### Scenario: Run-anchored partition pruning on reads (run deleted from meta — orphan scenario)
- **WHEN** client queries results with a `runId` filter but the run does not exist in meta DB
- **THEN** the service SHALL skip partition pruning and query the analytics DB with the `runId` filter only (no `created_at_ms` equality condition). This keeps orphaned results (run deleted from meta after results were written) queryable until the future cleanup job removes them. Returning an empty result set would make orphaned results invisible through the API. Partition pruning is an optimization, not a correctness requirement — skipping it for orphaned runs has negligible performance impact.

#### Scenario: Batch uniformity enforced by schema
- **WHEN** a batch write is submitted
- **THEN** `testSuiteId` and `testSuiteRunId` are declared once in the envelope — all result items in the batch belong to the same suite and run by design. No runtime uniformity validation is needed.

### Requirement: Response DTO structure for result listing
The response SHALL use `CursorPageResponseDto` with keyset pagination metadata.

#### Scenario: Response structure
- **WHEN** system returns a page of results
- **THEN** the response SHALL contain: `content` (array of `TestCaseRunResultResponseDto`), `size` (int — the **requested** page size, not the actual content array length; reflects the `size` query parameter or configured default, providing API discoverability), `nextCursor` (String, nullable — null if no more results), `hasMore` (boolean)

#### Scenario: Response DTO fields
- **WHEN** system returns a `TestCaseRunResultResponseDto`
- **THEN** it SHALL contain: `id` (UUID), `testSuiteRunId` (UUID), `testSuiteId` (UUID), `testCaseId` (UUID), `testCaseName` (String), `runIndex` (Integer), `testCaseData` (JSON object), `requestBody` (JSON object, nullable), `responseBody` (JSON object, nullable — contains response body or error body on failure), `responseStatusCode` (Integer, nullable), `executionInfo` (nested object with `status` (ExecutionStatus enum), `startedAt`, `completedAt`, `durationMs` (computed server-side), `traceId`), `createdAt` (Long — epoch ms)

### Requirement: Get single test case run result by ID
The service SHALL provide `GET /api/v1/analytics/test-case-results/{id}` to retrieve a single result.

#### Scenario: Existing result
- **WHEN** client calls `GET /api/v1/analytics/test-case-results/{id}` for an existing result
- **THEN** system SHALL return HTTP 200 with the `TestCaseRunResultResponseDto`

#### Scenario: Non-existent result
- **WHEN** client calls `GET /api/v1/analytics/test-case-results/{id}` for a non-existent id
- **THEN** system SHALL return HTTP 404 with error code `NOT_FOUND`

### Requirement: Count results
The service SHALL provide `GET /api/v1/analytics/test-case-results/count` to return the total result count matching the provided filters.

#### Scenario: Count results with filters
- **WHEN** client calls `GET /api/v1/analytics/test-case-results/count?filter=suiteId:eq:<uuid>&filter=runId:eq:<uuid>`
- **THEN** system SHALL return HTTP 200 with `ResultCountResponseDto` (`{"count": <number>}`). The count query SHALL use the same run-anchored partition pruning as the list query when `runId` filter is present.

#### Scenario: No results matching filters
- **WHEN** client calls with filters that match no results
- **THEN** system SHALL return HTTP 200 with `{"count": 0}`

#### Scenario: Count requires suiteId
- **WHEN** client calls count without `suiteId:eq:...` filter
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

### Requirement: Configuration properties for analytics results
The service SHALL expose configurable properties for batch limits under the `analytics.results` prefix.

#### Scenario: Batch limit properties
- **WHEN** the application starts
- **THEN** it SHALL read `analytics.results.batch.max-items` (default 10000) and `analytics.results.batch.max-request-size-bytes` (default 10485760)

### Requirement: ExecutionStatus enum
The execution status SHALL be restricted to a defined set of values.

#### Scenario: Valid execution statuses
- **WHEN** a result is written with an `executionInfo.status` value
- **THEN** it SHALL be one of: `SUCCESS`, `FAILED`, `TIMEOUT`, `ERROR`

#### Scenario: Invalid execution status
- **WHEN** a result is submitted with an unrecognized `executionInfo.status` value
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

### Requirement: Filter framework extension for JSONB path filtering
The existing filter framework SHALL be extended to support JSONB path filtering on designated columns.

#### Scenario: New JSONB_STRING filter field type
- **WHEN** a filter field is defined with type `JSONB_STRING` in the whitelist
- **THEN** `WhereBuilder` SHALL support dot-notation field names (e.g., `testCaseData.prompt`) and generate parameterized JSONB accessor SQL (`test_case_data->>:jsonbKeyParam` where `:jsonbKeyParam` is bound to the key string). The existing `buildPredicate` method SHALL be extended to accept `FilterFieldType` so it can decide between regular column access and JSONB accessor syntax. The `parseValue` switch SHALL include `case JSONB_STRING -> rawValue` (treat JSONB values as strings).

#### Scenario: JSONB path filtering operators
- **WHEN** a JSONB path filter is applied
- **THEN** it SHALL support EQ, NE, and CONTAINS operators, treating all JSONB values as strings for comparison. The existing `WhereBuilder.validateOperator` guard (which only allows CONTAINS for `STRING` type) SHALL be updated to also allow CONTAINS for `JSONB_STRING`.

#### Scenario: Analytics filter whitelist
- **WHEN** the analytics list endpoint processes filters
- **THEN** it SHALL validate filters against `FilterWhitelists.ANALYTICS_RESULTS` which defines: system props (`runId`, `suiteId`, `testCaseId`, `testCaseName`, `executionStatus`, `runIndex`, `createdAt`, `execDurationMs`, `responseStatusCode`) and JSONB path (`testCaseData`). `testCaseId` supports EQ only — efficient when combined with `runId` (uses UNIQUE index `(test_suite_run_id, test_case_id, ...)`); without `runId`, the query falls back to `test_suite_id` index and full scan for `test_case_id`.

#### Scenario: InvalidFilterException handled by exception handler
- **WHEN** `WhereBuilder` throws `InvalidFilterException` during analytics filter processing
- **THEN** `DefaultExceptionHandler` SHALL handle it — mapping to HTTP 400 with `INVALID_FILTER` error code and the exception message. `DefaultExceptionHandler` SHALL be extended with an `InvalidFilterException` handler (the existing handler only covers `FilterValidationException`). No conversion to `FilterValidationException` is needed — the analytics service lets `InvalidFilterException` propagate directly.

### Requirement: Request body size limit via MaxRequestBodyFilter
The service SHALL enforce a configurable request body size limit on the batch write endpoint before deserialization.

#### Scenario: MaxRequestBodyFilter registered for batch write path
- **WHEN** the application starts
- **THEN** a `MaxRequestBodyFilter` SHALL be registered via `FilterRegistrationBean` only for `POST /api/v1/analytics/test-case-results`, with an order that ensures it runs **after** the Spring Security filter chain (which handles CORS). This guarantees CORS headers are present on the 413 response from the eager rejection path — without this ordering, the browser blocks the response due to missing `Access-Control-Allow-Origin`

#### Scenario: Eager Content-Length check (common case)
- **WHEN** a client sends a request with a `Content-Length` header exceeding the configured limit
- **THEN** the filter SHALL reject the request immediately by writing a JSON response directly — `response.setStatus(413)`, `response.setContentType("application/json")`, and writing a serialized `ErrorView` (error code `PAYLOAD_TOO_LARGE`) via `response.getWriter()` — then returning without calling `filterChain.doFilter()`. **Note:** `HttpServletResponse.sendError(413)` MUST NOT be used because it triggers Tomcat's default error page mechanism and does not produce JSON. The filter SHALL inject `ObjectMapper` for serialization. This avoids the exception wrapping chain entirely for the common case (~95% of requests).

#### Scenario: Lazy stream wrapping (chunked encoding fallback)
- **WHEN** a client sends a request without a `Content-Length` header (chunked transfer encoding)
- **THEN** the filter SHALL wrap `getInputStream()` with a byte-counting decorator that throws `PayloadTooLargeException` lazily when bytes are read during deserialization. This ensures the exception is thrown within the Spring MVC controller context (during `@RequestBody` argument resolution), where `DefaultExceptionHandler` can catch it.

#### Scenario: PayloadTooLargeException mapped to HTTP 413 via cause chain inspection (lazy path)
- **WHEN** `PayloadTooLargeException` is thrown during deserialization (chunked encoding path)
- **THEN** Jackson wraps it in `JsonProcessingException` and Spring wraps that in `HttpMessageNotReadableException`. `DefaultExceptionHandler`'s existing `handleWrongJsonError` method SHALL inspect the root cause chain. If `PayloadTooLargeException` is found → HTTP 413 with error code `PAYLOAD_TOO_LARGE` (added to `ErrorCode` enum). Otherwise, fall through to the existing handling (HTTP 400). **Signature change required:** The method's return type SHALL change from `ErrorView` to `ResponseEntity<ErrorView>` and the `@ResponseStatus(HttpStatus.BAD_REQUEST)` annotation SHALL be removed — when `@ExceptionHandler` returns `ResponseEntity`, the status comes from the entity, not the annotation.

### Requirement: Controller annotations
The analytics controller SHALL follow project conventions for annotations.

#### Scenario: LogExecution annotation
- **WHEN** the `AnalyticsResultController` is defined
- **THEN** it SHALL be annotated with `@LogExecution` per project conventions

#### Scenario: Validated annotation on list endpoint
- **WHEN** the list endpoint accepts filter and size parameters
- **THEN** the controller SHALL be annotated with `@Validated`, and filter params SHALL use `@Size(max = ValidationConstants.MAX_LIST_FILTER_PARAMS)`

## Implementation Notes
- Design reference: `design.md` decisions D4, D5, D6, D7, D8, D9, D10, D12, D13.
- Controller: `com.epam.aidial.evaluation.web.controller.AnalyticsResultController` — `@LogExecution`, `@Validated`. Batch write accepts `BatchWriteRequestDto` envelope (testSuiteId, testSuiteRunId, results), returns HTTP 201 with `BatchWriteResponseDto` (`{"totalItems": N}` echoing the input count).
- Service: `com.epam.aidial.evaluation.service.domain.analytics.AnalyticsResultService` — `@LogExecution`. Write: reads run from meta DB for existence validation and `created_at_ms` timestamp (outside analytics TX), then writes to analytics via `@Transactional("analyticsTransactionManager")`. Read methods use `@Transactional(value = "analyticsTransactionManager", readOnly = true)`. Computes `exec_duration_ms` server-side as `completedAt - startedAt`. Validates `completedAt >= startedAt` and `testCaseData` is a JSON object. For read queries with `runId` filter, looks up run's `createdAt` from meta for partition pruning (`WHERE created_at_ms = :runCreatedAt`); if run not found in meta (orphan scenario), skips partition pruning and queries with `runId` filter only.
- Repository interface: `com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository` — `saveAll` returns void (not int), since `reWriteBatchedInserts=true` makes row counts unreliable. `findAll` accepts `Long runCreatedAtMs` for partition pruning (null when no `runId` filter). Returns `CursorPage` with raw `Cursor` object (not encoded String) — cursor encoding/decoding is a service-layer concern handled by `CursorCodec`.
- Postgres implementation: `com.epam.aidial.evaluation.data.db.analytics.repository.PostgresTestCaseRunResultRepository` — `@LogExecution`, uses `@Qualifier("analyticsJdbcTemplate")`, JDBC `batchUpdate()` with `ON CONFLICT DO NOTHING`. Uses `PostgresJsonbSqlParameter.fromJson()` for JSONB column writes. Does NOT inject `TransactionTimestampContext` — timestamp (`created_at_ms`) is set by the service layer from the run's creation time per D8.
- Model: `com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult` (includes `runIndex`, no `errorPayload`). JSONB fields (`testCaseData`, `requestBody`, `responseBody`) stored as `String` (raw JSON) — consistent with existing `TestSuiteRun` pattern. RowMapper reads `PGobject.getValue()` as `String`. Mapper converts between `String` (model) and `JsonNode` (DTOs).
- Cursor: `com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor` — pure data record (no encoding/decoding logic, free of Jackson dependency). `CursorPage.nextCursor` is `Cursor` type (not String) — the repository returns raw cursor positions; encoding/decoding to opaque Base64 strings is handled by `CursorCodec` in `service.domain.analytics`.
- RowMapper: `com.epam.aidial.evaluation.data.db.analytics.mapper.TestCaseRunResultRowMapper` — `@LogExecution`
- DTOs: `BatchWriteRequestDto` (envelope: testSuiteId, testSuiteRunId, results list), `BatchWriteResponseDto` (`totalItems` — echoes input count), `TestCaseRunResultItemDto` (single result item — no suiteId/runId, includes `runIndex` with `@Min(0) @Max(99999)`, no `durationMs` — computed server-side), `TestCaseRunResultResponseDto`, `ExecutionInfoRequestDto`/`ExecutionInfoResponseDto` (uses `ExecutionStatus` enum for `status`), `ResultCountResponseDto`, `CursorPageResponseDto` (in `service.domain.dto.analytics`)
- Run existence, suite ID validation, and timestamp: `AnalyticsResultService` reads the run from meta DB (via `TestSuiteRunRepository.findById()`) before batch write. Validates `envelope.testSuiteId == run.getTestSuiteId()` — mismatch → HTTP 400 (`VALIDATION_ERROR`). The run's `createdAt` becomes `created_at_ms` for all results. No `TestSuiteRunTimeRangeService` — the existing `TestSuiteRunRepository` is sufficient for read-only access.
- Mapper: `com.epam.aidial.evaluation.service.domain.mapper.TestCaseRunResultMapper` (MapStruct) — computes `execDurationMs` from `completedAt - startedAt`
- Filter whitelist: `FilterWhitelists.ANALYTICS_RESULTS` (use `Map.ofEntries()`) with JSONB_STRING support for `testCaseData` and `responseStatusCode` (LONG). `testCaseId` (UUID, EQ) included — efficient when combined with `runId` (uses UNIQUE index `(test_suite_run_id, test_case_id, ...)`); without `runId`, falls back to `test_suite_id` index.
- Filter framework extension: `FilterFieldType.JSONB_STRING`, `WhereBuilder` extended for JSONB path access with **parameterized JSONB key** (prevents SQL injection), `parseValue` handles `JSONB_STRING` case, CONTAINS validation updated for JSONB_STRING
- Filter errors: `InvalidFilterException` from `WhereBuilder` — add `InvalidFilterException` handler to `DefaultExceptionHandler` (currently only handles `FilterValidationException`). Maps to HTTP 400 with `INVALID_FILTER`. No service-layer conversion needed.
- Error code: Add `PAYLOAD_TOO_LARGE` to `ErrorCode` enum
- Request body filter: `com.epam.aidial.evaluation.web.filter.MaxRequestBodyFilter` — registered via `FilterRegistrationBean` with order after Spring Security (e.g., `Ordered.LOWEST_PRECEDENCE - 1`) to ensure CORS headers are present on 413 responses. Two-phase enforcement: (1) eager `Content-Length` header check → reject immediately by writing JSON response directly via `response.setStatus(413)` + `response.setContentType("application/json")` + `response.getWriter()` (do NOT use `sendError()` — it triggers Tomcat's error page, not JSON); (2) lazy stream wrapping fallback for chunked encoding (exception thrown during deserialization). **Modify the existing** `handleWrongJsonError` method in `DefaultExceptionHandler` (do NOT add a second handler for `HttpMessageNotReadableException`) to inspect the cause chain for `PayloadTooLargeException` → HTTP 413 (only triggered by the lazy path). **Change the method's return type** from `ErrorView` to `ResponseEntity<ErrorView>` and remove `@ResponseStatus(HttpStatus.BAD_REQUEST)` to support conditional status codes.
- Analytics Flyway migration: `V1.1__CreateTestCaseRunResultsTable.sql` (in `db/migration/analytics/POSTGRES/`) — PK `(created_at_ms, id)`, UNIQUE constraint on `(test_suite_run_id, test_case_id, run_index, created_at_ms)`, standalone index on `(id)` for efficient `findById` lookups, no separate `(created_at_ms DESC, id DESC)` index (covered by PK backward scan). Column comment on `created_at_ms`: "Run creation timestamp from meta DB — all results for a run share this value"
