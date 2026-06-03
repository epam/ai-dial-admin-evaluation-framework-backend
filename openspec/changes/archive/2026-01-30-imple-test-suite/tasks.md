## 1. Data model and migrations (jsonb)

- [x] 1.1 Design Postgres tables/columns for new TestSuite aggregate:
    - `test_suites`: id, name, description, deployment_ref (jsonb), endpoint_ref (jsonb), test_cases_definition (jsonb), version, created_by, created_at_ms, updated_at_ms
    - **JSONB-only approach**: No projection columns for deployment_ref or endpoint_ref fields
- [x] 1.2 Add migrations for `test_cases` table:
    - Columns: id, test_suite_id (FK), test_case_name, parameters (jsonb), facts (jsonb), is_enabled, is_valid, validation_warnings (text[]), created_at_ms, updated_at_ms
- [x] 1.3 Add migrations for `revalidation_tasks` table:
    - Columns: id, test_suite_id (FK), status, total_cases, processed_cases, valid_count, invalid_count, started_at_ms, completed_at_ms, error_message
- [x] 1.4 Add migrations for `metric_definitions` table:
    - Columns: id, name, description, created_at_ms
    - Seed data: "Accuracy", "Latency", "Relevance"
- [x] 1.5 Add indexes:
    - B-tree: `idx_test_cases_test_suite_id`, `idx_test_suites_created_at_ms`, `idx_test_cases_created_at_ms`
    - GIN: `idx_test_cases_parameters`, `idx_test_cases_facts` (for future JSON filtering)
    - UUIDs as `VARCHAR(36)`

## 2. Shared infrastructure: jsonb helper + filtering + pagination + config + dependencies

### 2.1 JSONB Helper
- [x] 2.1.1 Implement `PostgresJsonbSqlParameter` helper (bind jsonb via `PGobject`, safe null handling)

### 2.2 Pagination Infrastructure
- [x] 2.2.1 Add `PaginationProperties` configuration class:
    - `pagination.default-size` (default: 100)
    - `pagination.max-size` (default: 1000)
- [x] 2.2.2 Update `PageResponseDto<T>` to support optional total count:
    - `content`, `page` (0-based), `size`
    - `totalElements` (nullable), `totalPages` (nullable)
- [x] 2.2.3 Add `includeTotalCount` query param support to list endpoints

### 2.3 Filtering Infrastructure
- [x] 2.3.1 Add `FilterOperator` enum: `EQ`, `NE`, `CONTAINS`, `GT`, `GTE`, `LT`, `LTE`
- [x] 2.3.2 Add `FilterCondition` model: `field`, `operator`, `rawValue`, `parsedValue`
- [x] 2.3.3 Implement `FilterParser`:
    - Parse repeatable `filter=<field>:<op>:<value>` params
    - Split by first two `:` only (value can contain `:`)
    - URL-decode values
- [x] 2.3.4 Implement `WhereBuilder`:
    - Accept `List<FilterCondition>` + entity whitelist
    - Generate parameterized SQL WHERE clause
    - Validate field against whitelist (400 if unknown)
    - Validate operator against field's allowed operators (400 if invalid)
    - Type-coerce value based on field type
- [x] 2.3.5 Define operator SQL mappings:
    - `EQ` → `= ?`
    - `NE` → `<> ?`
    - `CONTAINS` → `ILIKE '%' || ? || '%'` (case-insensitive)
    - `GT/GTE/LT/LTE` → `> ? / >= ? / < ? / <= ?`
- [x] 2.3.6 Define entity whitelists:
    - **TestSuites**: `name` (eq, ne, contains), `createdBy` (eq, ne), `createdAt` (gt, gte, lt, lte)
    - **TestCases**: `testCaseName` (eq, ne, contains), `isEnabled` (eq, ne), `isValid` (eq, ne), `createdAt` (gt, gte, lt, lte)
- [x] 2.3.7 Implement filter error responses (400 with field, reason details)

### 2.4 Sorting Infrastructure
- [x] 2.4.1 Ensure `SortParser` handles repeatable `sort=<field>[,<direction>]` per sorting spec
- [x] 2.4.2 Implement `OrderByBuilder`:
    - Accept `List<SortKey>` + entity whitelist
    - Generate safe SQL ORDER BY clause
    - Add deterministic tie-breaker (`id ASC`) if not unique
- [x] 2.4.3 Define default sort orders per entity:
    - TestSuite: `createdAt,desc`
    - TestCase: `createdAt,desc`
    - MetricDefinition: `createdAt,desc`

### 2.5 Configuration Classes
- [x] 2.5.1 Add `DialCoreProperties` configuration class for `dial.components.core.base-url`
- [x] 2.5.2 Add `CsvImportProperties` configuration class (if not done in section 6)
- [x] 2.5.3 Add `ValidationProperties` configuration class:
    - `validation.max-warnings-per-case` (default: 5)
- [x] 2.5.4 Add `RevalidationProperties` configuration class:
    - `validation.revalidation.batch-size` (default: 500)
    - `validation.revalidation.timeout-minutes` (default: 5)
- [x] 2.5.5 Add `JwtSecurityProperties` configuration class:
    - `security.jwt.user-claim` (default: `sub`)

### 2.6 Type System
- [x] 2.6.1 Add `SchemaFieldType` enum (STRING, INTEGER, NUMBER, BOOLEAN, OBJECT, ARRAY)
- [x] 2.6.2 Add `SchemaFieldDto` model class (name, type, required, description)

### 2.7 Dependencies
- [x] 2.7.1 Add dependency: `org.apache.commons:commons-csv:1.12.0` (for CSV import/export)
- [x] 2.7.2 Add dependency: `com.networknt:json-schema-validator:1.5.4` (for JSON Schema validation, Draft-07)
- [x] 2.7.3 Add dependency: `io.swagger.parser.v3:swagger-parser:2.1.25` (for OpenAPI parsing and $ref resolution)

## 3. TestSuites API (BREAKING contract)

- [x] 3.1 Define new TestSuite DTOs:
    - **DeploymentReferenceDto**: id (required), name (required), version (optional) - NO url field
    - **EndpointContractDto**: method (HttpMethod enum), relativeUrl, operationId, parameters (List<ParameterDefinitionDto>), requestBodySchema (Map<String, Object>), responseBodySchema (Map<String, Object>)
    - **ParameterDefinitionDto**: name, in (ParameterLocation enum: QUERY/PATH/HEADER), required, schema (Map<String, Object>)
    - **TestCasesDefinitionDto**: factFields (List<SchemaFieldDto>) stored; parameterFields computed at runtime
    - Use `io.swagger.parser.v3:swagger-parser` (v2.1.22+) for $ref resolution on import only
- [x] 3.2 Implement `EndpointSchemaExtractor` utility:
    - Extract parameterFields from endpoint parameters + requestBodySchema
    - Top-level flattening only (nested objects remain as OBJECT type)
- [x] 3.3 Remove `status` from TestSuite (DTOs, DB schema, repository queries, tests)
- [x] 3.4 Update TestSuite DB model + RowMapper for new columns/jsonb fields
- [x] 3.5 Update repository to support pagination/safe sorting + filtering spec (`filter` only in v1)
- [x] 3.6 Update service layer:
    - Mapping/validation for required embedded refs
    - Compute parameterFields from endpointRef when reading TestSuite
    - Resolve $refs in endpoint schemas on create/update
- [x] 3.7 Update controller/OpenAPI annotations and update functional tests for new payload shape
- [x] 3.8 Implement createdBy attribution:
    - Extract user identity from configurable JWT claim (default: `sub`)
    - In `oidc` mode: require valid JWT with claim; return 401 if missing
    - In `none` mode: use "anonymous" as fallback
    - createdBy is mutable (can be reassigned to another maintainer)
- [x] 3.9 Implement cascade delete for TestSuite children (test cases, metric bindings; later runs/analytics)
- [x] 3.10 Implement Optimistic Locking:
    - Add `version` column to TestSuite (BIGINT, incremented on update)
    - Support `If-Match` header for PUT, PATCH, CSV Import
    - Return `ETag` header and `version` field in responses
    - Return 409 Conflict with `VERSION_CONFLICT` error code on mismatch
- [x] 3.11 Implement cascade delete for TestSuite:
    - DB CASCADE for: test_cases, revalidation_tasks
    - Return deletion counts in response: `{ deleted: true, deletedTestCases: N, deletedRevalidationTasks: M }`
- [x] 3.12 Add `ErrorResponseDto` and standard error codes:
    - Codes: VALIDATION_ERROR, INVALID_FILTER, INVALID_SORT, INVALID_SCHEMA, CSV_PARSE_ERROR, CSV_EMPTY, CSV_TOO_LARGE, AUTHENTICATION_REQUIRED, ACCESS_DENIED, NOT_FOUND, VERSION_CONFLICT, INTERNAL_ERROR
    - Update global exception handler to use standardized format

## 4. TestCasesDefinition API & Schema Validation

### 4.1 TestCasesDefinition Embedding
- [x] 4.1.1 Add embedded TestCasesDefinition to TestSuite:
    - Store only `factFields` in `test_cases_definition` jsonb column
    - Compute `parameterFields` at runtime from `endpoint_ref`
    - No standalone /test-case-definitions API
- [x] 4.1.2 Implement updating embedded TestCasesDefinition via TestSuite PUT:
    - Detect schema changes (compare old vs new)
    - Trigger async re-validation job when schema changes
    - Optional dedicated `PATCH /api/v1/test-suites/{id}/test-cases-definition` endpoint

### 4.2 Schema Validation Service
- [x] 4.2.1 Add dependency: `com.networknt:json-schema-validator`
- [x] 4.2.2 Implement `SchemaValidationService`:
    - Use JSON Schema Draft-07
    - Validate `Map<String, Object>` data against `Map<String, Object>` schema
    - Return `ValidationResult` with `isValid`, `List<String> warnings`
    - Truncate warnings to `validation.max-warnings-per-case` limit
- [x] 4.2.3 Implement schema caching:
    - Compile schema once per TestSuite
    - Cache compiled schemas in memory (e.g., Caffeine cache)
    - Invalidate cache entry when TestSuite schema changes
- [x] 4.2.4 Implement validation rules:
    - **Invalid**: missing required field, wrong type, extra fields not in schema
    - **Valid**: empty objects (if no required fields), null for optional fields
- [x] 4.2.5 Implement combined validation for TestCase:
    - Validate `parameters` against `endpointRef` schema (parameters + requestBodySchema)
    - Validate `facts` against `factFields` schema
    - If either fails → whole TestCase is `isValid=false`

### 4.3 TestSuite Schema Validation (on save)
- [x] 4.3.1 Validate embedded schemas on TestSuite create/update:
    - `endpointRef.requestBodySchema` must be valid JSON Schema Draft-07
    - `endpointRef.responseBodySchema` must be valid JSON Schema Draft-07
    - `endpointRef.parameters[*].schema` must be valid JSON Schema Draft-07
    - `testCasesDefinition.factFields` must be well-formed list
- [x] 4.3.2 Return 400 Bad Request if schema is malformed (do NOT persist)

### 4.4 Async Re-validation Infrastructure
- [x] 4.4.1 Create `RevalidationTask` entity:
    - Columns: id, test_suite_id, status, total_cases, processed_cases, valid_count, invalid_count, started_at_ms, completed_at_ms, error_message
- [x] 4.4.2 Create `RevalidationTaskRepository`
- [x] 4.4.3 Create DTOs:
    - `RevalidationTaskDto`: taskId, testSuiteId, status, totalCases, processedCases, validCount, invalidCount, startedAt, completedAt, errorMessage
    - `RevalidationStatus` enum: PENDING, RUNNING, COMPLETED, FAILED, TIMED_OUT
- [x] 4.4.4 Implement `RevalidationService`:
    - Start async job (returns task ID immediately)
    - Process TestCases in batches (configurable batch size)
    - Update task progress periodically
    - Handle timeout (mark as TIMED_OUT)
    - Handle errors (mark as FAILED with message)
- [x] 4.4.5 Implement controller endpoints:
    - `GET /api/v1/test-suites/{id}/revalidation-tasks/{taskId}` - get task status
    - `GET /api/v1/test-suites/{id}/revalidation-tasks` - list recent tasks
- [x] 4.4.6 Update TestSuite PUT:
    - Detect schema change
    - Return `202 Accepted` with `RevalidationTaskDto` if re-validation triggered
- [x] 4.4.7 Add functional tests for re-validation:
    - Schema change triggers async re-validation
    - Task status polling
    - Batch processing correctness
    - Timeout handling

## 5. TestCase API (full CRUD + PATCH)

- [x] 5.1 Create separate Request/Response DTOs for TestCase:
    - **TestCaseRequestDto**: testCaseName (required), parameters, facts, isEnabled
    - **TestCaseResponseDto**: id, testCaseName, parameters, facts, isEnabled, isValid, validationWarnings, createdAt, updatedAt
    - All timestamps as Long (epoch millis) per API convention
- [x] 5.2 Create TestCase DB model and RowMapper
- [x] 5.3 Implement TestCaseRepository with:
    - `findAllByTestSuiteId(UUID testSuiteId, PageRequest, FilterSpec)` - list with pagination/filtering
    - `findByIdAndTestSuiteId(UUID id, UUID testSuiteId)` - scoped lookup (returns 404 if wrong suite)
    - `save(TestCase)` - create
    - `update(TestCase)` - full replacement
    - `deleteByIdAndTestSuiteId(UUID id, UUID testSuiteId)` - single delete
    - `deleteAllByTestSuiteId(UUID testSuiteId, FilterSpec)` - bulk delete with optional filter
    - `countByTestSuiteId(UUID testSuiteId)` - count for pagination
- [x] 5.4 Implement TestCaseService with:
    - Scope validation (testCaseId must belong to testSuiteId)
    - Call `SchemaValidationService` on create/update/patch to calculate `isValid`
    - Store `validationWarnings` in DB (truncated to max limit)
    - Support `includeWarnings=true` query param on GET endpoints
- [x] 5.5 Implement TestCaseController:
    - `POST /test-suites/{testSuiteId}/test-cases` - create
    - `GET /test-suites/{testSuiteId}/test-cases` - list (paginated, filtered, sorted)
    - `GET /test-suites/{testSuiteId}/test-cases/{id}` - get by ID
    - `PUT /test-suites/{testSuiteId}/test-cases/{id}` - full replacement
    - `PATCH /test-suites/{testSuiteId}/test-cases/{id}` - partial update (RFC 7396)
    - `DELETE /test-suites/{testSuiteId}/test-cases/{id}` - delete single
    - `DELETE /test-suites/{testSuiteId}/test-cases` - bulk delete (with filter)
    - Support `includeWarnings=true` query param on GET endpoints to include `validationWarnings`
- [x] 5.6 Implement RFC 7396 JSON Merge Patch for TestCase:
    - Allowed fields: testCaseName, isEnabled, parameters, facts
    - Recalculate isValid after patch
- [x] 5.7 Implement filtering whitelist for TestCases:
    - `testCaseName`: eq, contains
    - `isEnabled`: eq
    - `isValid`: eq
    - `createdAt`: gt, lt, gte, lte
- [x] 5.8 Implement sortable fields for TestCases:
    - testCaseName, createdAt, updatedAt, isEnabled, isValid
- [x] 5.9 Add functional tests:
    - CRUD operations (create, read, update, delete)
    - PATCH (toggle isEnabled, patch facts only)
    - Scope validation (404 when testCaseId not in testSuiteId)
    - Bulk delete with filters
    - Filtering and sorting
    - Validation: invalid parameters → isValid=false
    - Validation: invalid facts → isValid=false
    - Validation: empty objects with no required fields → isValid=true
    - Validation: empty objects with required fields → isValid=false
    - Validation: includeWarnings=true returns warnings
    - Validation: PATCH recalculates isValid
- [x] 5.10 Add functional tests for TestSuite schema validation:
    - Invalid JSON Schema → 400 Bad Request
    - Valid JSON Schema → TestSuite saved

## 6. CSV export/import for TestCasesDefinition

**Library**: Apache Commons CSV (`org.apache.commons:commons-csv`)

### 6.0 Configuration and DTOs
- [x] 6.0.1 Add `CsvImportProperties` configuration class:
    - `csv.import.max-file-size` (default: 10MB)
    - `csv.import.max-rows` (default: 100000)
    - `csv.import.batch-size` (default: 1000)
- [x] 6.0.2 Create CSV response DTOs:
    - **CsvImportResultDto**: totalRows, validCount, invalidCount, warnings
    - **CsvImportWarningDto**: rowNumber, columnName, message
    - **CsvImportPreviewDto**: detectedColumns, totalRows, sampleRows (10), warnings
    - **CsvColumnInfoDto**: headerName, mappedTo (parameter/fact), fieldName, inferredType

### 6.1 CSV Export
- [x] 6.1.1 Implement export endpoint `GET /api/v1/test-suites/{testSuiteId}/test-cases/export.csv`:
    - Response `Content-Type: text/csv; charset=UTF-8`
    - Response `Content-Disposition: attachment; filename="test-cases-{testSuiteId}.csv"`
- [x] 6.1.2 Implement column ordering: testCaseName → parameterFields → factFields → (optional isEnabled)
- [x] 6.1.3 Support query parameters:
    - `delimiter` (char, default: `,`)
    - `includeIsEnabled` (boolean, default: false)
    - Standard filter params (reuse filtering infrastructure)
- [x] 6.1.4 Handle empty suite: return header-only CSV (HTTP 200)

### 6.2 CSV Import
- [x] 6.2.1 Implement import endpoints (two methods, typed responses):
    - `POST .../test-cases/import/preview`: multipart `file`, query `delimiter` → `CsvImportPreviewDto`
    - `POST .../test-cases/import`: multipart `file`, query `delimiter` → `CsvImportResultDto`
- [x] 6.2.2 Implement streaming CSV parse using Apache Commons CSV:
    - UTF-8 encoding, configurable delimiter, double-quote escaping
    - Validate file size and row count against configured limits
- [x] 6.2.3 Implement column disambiguation (hybrid approach):
    - If header has `param.` prefix → parameter field (strip prefix)
    - If header has `fact.` prefix → fact field (strip prefix)
    - Otherwise: lookup in parameterFields first, then default to fact
- [x] 6.2.4 Implement testCaseName handling:
    - If `testCaseName` column exists → use values
    - If missing → generate sequential: "Row 1", "Row 2", etc.
- [x] 6.2.5 Implement isEnabled column handling:
    - If present → parse as boolean
    - If missing → default to `true`

### 6.3 Type Inference
- [x] 6.3.1 Implement auto-detection (primitive types only):
    - `INTEGER`: matches `^-?\d+$`
    - `NUMBER`: matches `^-?\d+\.?\d*$`
    - `BOOLEAN`: matches `true/false/1/0` (case-insensitive)
    - `STRING`: default fallback
    - Cells with `{...}` or `[...]` remain as STRING (no OBJECT/ARRAY auto-inference)
- [x] 6.3.2 Implement schema-driven parsing (when schema exists):
    - If field type is OBJECT/ARRAY: attempt JSON parsing
    - If JSON parsing fails: set `isValid=false` (soft validation)

### 6.4 Import Persistence
- [x] 6.4.1 Implement replace-all policy:
    - Delete all existing TestCases in suite
    - Insert new rows in batches (configurable batch-size)
    - Wrap in single atomic transaction
- [x] 6.4.2 Implement optimistic locking check on TestSuite version
- [x] 6.4.3 Calculate `isValid` for each imported case
- [x] 6.4.4 Build `CsvImportResultDto` response with warnings

### 6.5 Import Preview (separate endpoint)
- [x] 6.5.1 Implement `POST .../test-cases/import/preview`:
    - Parse and validate file without database changes
    - Return `CsvImportPreviewDto` with:
      - `detectedColumns` (list of CsvColumnInfoDto)
      - `totalRows`
      - `sampleRows` (first 10 parsed TestCaseResponseDto)
      - `warnings` (validation issues)

### 6.6 Error Handling
- [x] 6.6.1 Return HTTP 400 for:
    - Empty CSV (header only, no data rows)
    - Malformed CSV (unparseable structure)
    - File exceeds size/row limits
- [x] 6.6.2 Soft validation for data errors:
    - Invalid rows get `isValid=false`
    - All rows persisted (no rejection)
    - Warnings included in response

### 6.7 Functional Tests
- [x] 6.7.1 Export tests:
    - Happy path export
    - Export with filtering
    - Export with custom delimiter
    - Export with includeIsEnabled
    - Export empty suite (header-only)
- [x] 6.7.2 Import tests:
    - Happy path import/export roundtrip
    - Import with column prefixes (param./fact.)
    - Import without testCaseName (fallback)
    - Import with isEnabled column
    - Import with custom delimiter
    - Import preview endpoint (POST .../import/preview)
- [x] 6.7.3 Error handling tests:
    - Empty CSV (400)
    - Malformed CSV (400)
    - Invalid rows (soft validation, 200 with warnings)
    - OBJECT/ARRAY fields with schema (JSON parsing)

## 7. Metric definitions stub (read-only with seed data)

- [x] 7.1 Create MetricDefinition entity and DTOs:
    - Fields: id (UUID), name (String), description (String), createdAt (Long)
    - MetricDefinitionResponseDto
- [x] 7.2 Create metric_definitions table migration:
    - Columns: id, name, description, created_at_ms
- [x] 7.3 Seed sample metrics via migration or startup:
    - "Accuracy" - Measures correctness of responses
    - "Latency" - Measures response time in milliseconds
    - "Relevance" - Measures relevance score
- [x] 7.4 Implement MetricDefinitionRepository (read-only):
    - `findAll(PageRequest, FilterSpec)` - list with pagination/filtering
    - `findById(UUID)` - get by ID
- [x] 7.5 Implement MetricDefinitionService and Controller:
    - `GET /api/v1/metric-definitions` - list (paginated, filtered, sorted)
    - `GET /api/v1/metric-definitions/{id}` - get by ID
- [x] 7.6 Implement filtering whitelist for MetricDefinitions:
    - `name` (eq, ne, contains)
    - `createdAt` (gt, gte, lt, lte)
- [x] 7.7 Add functional tests:
    - List returns seeded metrics
    - Filtering and sorting work correctly
    - Get by ID returns correct metric

## 8. Documentation and cleanup

- [x] 8.1 Update OpenSpec status and ensure artifacts are complete
- [x] 8.2 Update any impacted design docs if needed and keep configuration docs unchanged unless new config knobs are added

