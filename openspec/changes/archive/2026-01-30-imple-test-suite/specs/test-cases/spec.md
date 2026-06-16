## Type System Reference

The following types are supported for schema field definitions:

| Type | Java Mapping | JSON Representation | CSV Auto-Inference |
|------|--------------|---------------------|-------------------|
| `STRING` | `String` | `"value"` | Yes (default) |
| `INTEGER` | `Long` | `123` | Yes |
| `NUMBER` | `Double` | `123.45` | Yes |
| `BOOLEAN` | `Boolean` | `true`/`false` | Yes |
| `OBJECT` | `Map<String, Object>` | `{...}` | No (requires schema) |
| `ARRAY` | `List<Object>` | `[...]` | No (requires schema) |

## API Conventions

### Timestamps

All timestamps in API responses use **epoch milliseconds (Long)** for performance and consistency:

```json
{
  "createdAt": 1706612400000,
  "updatedAt": 1706698800000
}
```

### DTO Separation

The API uses separate Request and Response DTOs:
- **RequestDto**: Fields client can set (no id, timestamps, or calculated fields)
- **ResponseDto**: All fields including id, timestamps, and calculated fields like `isValid`

### Pagination

All list endpoints support pagination with:
- `page` (int, 0-based, default: 0)
- `size` (int, default: 100, max: 1000)
- `includeTotalCount` (boolean, default: false)

Response structure:
```json
{
  "content": [...],
  "page": 0,
  "size": 100,
  "totalElements": 1234,  // Only if includeTotalCount=true
  "totalPages": 13        // Only if includeTotalCount=true
}
```

### Filtering

All list endpoints support filtering with repeatable `filter` query parameter:
- Format: `filter=<field>:<operator>:<value>`
- Multiple filters are ANDed together
- Values must be URL-encoded

**Supported operators:**
| Operator | Meaning | Case Sensitivity |
|----------|---------|------------------|
| `eq` | Equals | Case-sensitive |
| `ne` | Not equals | Case-sensitive |
| `contains` | Substring match | Case-insensitive |
| `gt` | Greater than | N/A |
| `gte` | Greater than or equal | N/A |
| `lt` | Less than | N/A |
| `lte` | Less than or equal | N/A |

### Sorting

All list endpoints support sorting with repeatable `sort` query parameter:
- Format: `sort=<field>` or `sort=<field>,<direction>`
- Direction: `asc` or `desc` (default: `asc`)
- Multiple sort params define precedence order
- Default sort (when not specified): `createdAt,desc`

## ADDED Requirements

### Requirement: Manage embedded TestCasesDefinition via TestSuite API
The service SHALL manage TestCasesDefinition properties (schema/metadata) as an embedded part of TestSuite.

**Structure clarification:**
- `parameterFields`: **Computed at runtime** from `endpointRef.parameters` + `endpointRef.requestBodySchema` (not stored)
- `factFields`: **Stored** in `test_cases_definition` jsonb column (user-defined ground truth columns)

#### Scenario: Default empty definition on TestSuite create
- **WHEN** client creates a TestSuite without providing `testCasesDefinition`
- **THEN** system SHALL initialize `testCasesDefinition` with empty `factFields` array
- **AND** system SHALL compute `parameterFields` from the provided `endpointRef`

#### Scenario: Update factFields via TestSuite PUT
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with `testCasesDefinition.factFields` present
- **THEN** system SHALL validate and persist the updated `factFields` as part of the TestSuite
- **AND** system SHALL trigger re-validation of all existing TestCases

#### Scenario: parameterFields are read-only
- **WHEN** client includes `parameterFields` in a create/update request
- **THEN** system SHALL ignore the provided value and compute it from `endpointRef`

#### Scenario: Optional dedicated PATCH for testCasesDefinition
- **WHEN** client calls `PATCH /api/v1/test-suites/{id}/test-cases-definition` with an updated factFields payload
- **THEN** system SHALL validate and persist the updated embedded definition (if this endpoint is enabled)

### Requirement: Create and manage TestCases inside a TestSuite
The service SHALL manage TestCases as children of a TestSuite with full CRUD operations.

#### Scenario: Create a test case
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/test-cases` with a valid body
- **THEN** system SHALL create a TestCase linked to the TestSuite and return it
- **AND** system SHALL require `testCaseName` field (no auto-generation)
- **AND** system SHALL default `parameters` and `facts` to empty objects if not provided
- **AND** system SHALL default `isEnabled` to `true` if not provided
- **AND** system SHALL calculate `isValid` based on schema validation

#### Scenario: List test cases
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases`
- **THEN** system SHALL return a paginated list of TestCases

#### Scenario: Sort and filter test cases
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases?sort=<field>[,<asc|desc>]&filter=<field>:<op>:<value>`
- **THEN** system SHALL apply sorting and filtering according to the sorting and entity-filtering capabilities
- **AND** system SHALL support filtering on: `testCaseName` (eq, ne, contains), `isEnabled` (eq, ne), `isValid` (eq, ne), `createdAt` (gt, lt, gte, lte)
- **AND** system SHALL support sorting on: `testCaseName`, `createdAt`, `updatedAt`, `isEnabled`, `isValid`

#### Scenario: Pagination with optional total count
- **WHEN** client calls `GET .../test-cases?page=0&size=50&includeTotalCount=true`
- **THEN** system SHALL return paginated results with `totalElements` and `totalPages`

#### Scenario: Pagination without total count (default)
- **WHEN** client calls `GET .../test-cases?page=0&size=50` (without includeTotalCount)
- **THEN** system SHALL return paginated results without `totalElements` and `totalPages`

#### Scenario: Range filter with multiple conditions
- **WHEN** client calls `GET .../test-cases?filter=createdAt:gte:1000&filter=createdAt:lte:2000`
- **THEN** system SHALL return test cases created within the specified time range

#### Scenario: Invalid filter field
- **WHEN** client calls `GET .../test-cases?filter=unknownField:eq:value`
- **THEN** system SHALL respond with HTTP 400 Bad Request
- **AND** response SHALL include error details with the invalid field name

#### Scenario: Default sort order
- **WHEN** client calls `GET .../test-cases` without sort parameter
- **THEN** system SHALL return results sorted by `createdAt,desc` (newest first)

#### Scenario: Get test case by id
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}` for an existing TestCase
- **THEN** system SHALL return the TestCase

#### Scenario: Update test case (full replacement)
- **WHEN** client calls `PUT /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}` with a valid body
- **THEN** system SHALL replace the TestCase with the provided data
- **AND** system SHALL recalculate `isValid` based on schema validation
- **AND** system SHALL update `updatedAt` timestamp

#### Scenario: Delete single test case
- **WHEN** client calls `DELETE /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}`
- **THEN** system SHALL delete the TestCase and return HTTP 204

#### Scenario: Bulk delete test cases
- **WHEN** client calls `DELETE /api/v1/test-suites/{testSuiteId}/test-cases` with filter parameters
- **THEN** system SHALL delete all matching TestCases
- **AND** system SHALL return the count of deleted items

#### Scenario: Bulk delete all test cases (no filter)
- **WHEN** client calls `DELETE /api/v1/test-suites/{testSuiteId}/test-cases` without filter parameters
- **THEN** system SHALL delete ALL TestCases in the suite
- **AND** system SHALL return the count of deleted items

### Requirement: Scope validation for TestCase operations
All TestCase operations SHALL validate that the testCaseId belongs to the specified testSuiteId.

#### Scenario: TestCase not in specified TestSuite
- **WHEN** client calls any TestCase endpoint where `testCaseId` exists but belongs to a different `testSuiteId`
- **THEN** system SHALL respond with HTTP 404 Not Found

#### Scenario: TestSuite not found
- **WHEN** client calls any TestCase endpoint where `testSuiteId` does not exist
- **THEN** system SHALL respond with HTTP 404 Not Found

### Requirement: TestCase structure and isEnabled/isValid flags
Each TestCase SHALL have at least: `testCaseName`, `parameters`, `facts`, `isEnabled`, and `isValid`.

#### Scenario: Default flags
- **WHEN** a TestCase is created
- **THEN** system SHALL default `isEnabled` to `true`
- **AND** system SHALL calculate `isValid` based on schema validation

#### Scenario: Disabled cases are preserved
- **WHEN** a TestCase is set to `isEnabled=false`
- **THEN** system SHALL keep it in storage and return it in list/get calls

### Requirement: Partial update (PATCH) for a TestCase
The service SHALL allow partial updates of a TestCase using RFC 7396 JSON Merge Patch.

**Allowed fields for PATCH:**
- `testCaseName`
- `isEnabled`
- `parameters`
- `facts`

**NOT patchable:** `id`, `isValid`, `createdAt`, `updatedAt`

#### Scenario: Toggle isEnabled
- **WHEN** client calls `PATCH /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}` with body `{ "isEnabled": false }`
- **THEN** system SHALL update only the `isEnabled` field and return the updated TestCase

#### Scenario: Patch facts only
- **WHEN** client calls `PATCH ...` with body `{ "facts": { "someFact": 123 } }`
- **THEN** system SHALL replace the `facts` object (merge patch semantics) and return the updated TestCase

#### Scenario: Patch testCaseName
- **WHEN** client calls `PATCH ...` with body `{ "testCaseName": "New Name" }`
- **THEN** system SHALL update only the `testCaseName` field and return the updated TestCase

#### Scenario: Patch recalculates isValid
- **WHEN** client calls `PATCH ...` with body that modifies `parameters` or `facts`
- **THEN** system SHALL recalculate `isValid` based on current schema
- **AND** system SHALL include `validationWarnings` in response if `isValid=false`

#### Scenario: Attempt to patch read-only fields
- **WHEN** client calls `PATCH ...` with body containing `isValid` or `id`
- **THEN** system SHALL ignore those fields and process only allowed fields

### Requirement: CSV export of TestCasesDefinition dataset
The service SHALL allow exporting all TestCases of a TestCasesDefinition as CSV.

**CSV Format**: UTF-8 encoding, configurable delimiter (comma default), double-quote escaping (RFC 4180).

#### Scenario: Successful export
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/export.csv`
- **THEN** system SHALL respond with HTTP 200 and `Content-Type: text/csv; charset=UTF-8`
- **AND** system SHALL include `Content-Disposition: attachment; filename="test-cases-{testSuiteId}.csv"`

#### Scenario: CSV columns reflect schema
- **WHEN** system exports CSV
- **THEN** CSV header SHALL include columns in order: `testCaseName`, then parameterFields, then factFields
- **AND** optionally include `isEnabled` column if `includeIsEnabled=true` query param

#### Scenario: Export with filtering
- **WHEN** client calls `GET .../export.csv?filter=isEnabled:eq:true`
- **THEN** system SHALL export only TestCases matching the filter criteria

#### Scenario: Export with custom delimiter
- **WHEN** client calls `GET .../export.csv?delimiter=;`
- **THEN** system SHALL use semicolon as the column delimiter

#### Scenario: Export empty suite
- **WHEN** client calls export on a TestSuite with no TestCases
- **THEN** system SHALL respond with HTTP 200 and CSV containing only header row (no data rows)

### Requirement: CSV bulk upload into TestCasesDefinition with auto schema detection
The service SHALL allow bulk uploading TestCases into a TestCasesDefinition via CSV with auto schema detection. Import **processes rows in batches** (configurable batch size); the server does not load the entire CSV dataset into memory before persisting.

**Import Policy**: Replace-all (atomic transaction). All existing test cases are deleted and replaced.

**Error Handling**: Soft import - invalid rows get `isValid=false` but are still persisted.

#### Scenario: Import creates/replaces dataset cases
- **WHEN** client uploads a CSV file to `POST /api/v1/test-suites/{testSuiteId}/test-cases/import`
- **THEN** system SHALL delete all existing TestCases in the suite
- **AND** system SHALL parse rows into TestCases and persist them
- **AND** operation SHALL be atomic (all-or-nothing on database error)

#### Scenario: Import preview (no persistence)
- **WHEN** client uploads a CSV file to `POST /api/v1/test-suites/{testSuiteId}/test-cases/import/preview`
- **THEN** system SHALL parse and validate the file WITHOUT modifying the database
- **AND** system SHALL return `CsvImportPreviewDto` with detected columns, type inference, and mapping
- **AND** system SHALL return first 10 parsed rows as sample
- **AND** system SHALL return any validation warnings

#### Scenario: Column disambiguation with prefixes
- **WHEN** CSV header contains columns with `param.` or `fact.` prefixes
- **THEN** system SHALL map `param.*` columns to parameters and `fact.*` columns to facts
- **AND** system SHALL strip the prefix when storing field names

#### Scenario: Column disambiguation without prefixes (lookup-based)
- **WHEN** CSV header contains columns without prefixes
- **THEN** system SHALL first check if column name matches any `parameterFields` name
- **AND** if match found, map to parameters; otherwise map to facts

#### Scenario: Detect testCaseName column
- **WHEN** CSV header contains `testCaseName`
- **THEN** system SHALL treat that column as the TestCase name field

#### Scenario: Missing testCaseName column (fallback)
- **WHEN** CSV header does NOT contain `testCaseName`
- **THEN** system SHALL generate sequential names: "Row 1", "Row 2", etc.

#### Scenario: isEnabled column handling
- **WHEN** CSV header contains `isEnabled` column
- **THEN** system SHALL parse values as boolean and apply to each TestCase
- **AND** if `isEnabled` column is missing, default to `true` for all rows

#### Scenario: Custom delimiter on import
- **WHEN** client uploads CSV with `delimiter=;` query parameter
- **THEN** system SHALL parse using semicolon as the column delimiter

#### Scenario: Infer primitive types only (auto-detection mode)
- **WHEN** CSV is uploaded without a pre-defined schema
- **THEN** system SHALL infer only primitive column types by sampling:
  - `INTEGER`: matches `^-?\d+$`
  - `NUMBER`: matches `^-?\d+\.?\d*$`
  - `BOOLEAN`: matches `true/false/1/0` (case-insensitive)
  - `STRING`: default fallback
- **AND** system SHALL NOT auto-detect `OBJECT` or `ARRAY` types (cells with `{...}` or `[...]` remain as `STRING`)

#### Scenario: Schema-driven import for complex types
- **WHEN** CSV is uploaded AND the TestSuite has a pre-defined schema with `OBJECT` or `ARRAY` field types
- **THEN** system SHALL attempt to parse those cells as JSON
- **AND** if JSON parsing fails, system SHALL set `isValid=false` for that TestCase (soft validation)

#### Scenario: Empty CSV file (error)
- **WHEN** CSV file contains only header row (no data rows)
- **THEN** system SHALL respond with HTTP 400 Bad Request
- **AND** error message SHALL indicate at least one data row is required

#### Scenario: Malformed CSV (error)
- **WHEN** CSV file is structurally malformed (cannot be parsed)
- **THEN** system SHALL respond with HTTP 400 Bad Request
- **AND** error message SHALL describe the parsing failure

#### Scenario: Import response with warnings
- **WHEN** import completes with some invalid rows
- **THEN** system SHALL respond with HTTP 200
- **AND** response SHALL include `totalRows`, `validCount`, `invalidCount`
- **AND** response SHALL include list of warnings with row number, column name, and message

### Requirement: Validate TestCases against schema (Soft Validation)
The service SHALL validate TestCases payloads (API and CSV import) against the TestSuite-embedded TestCasesDefinition schema and update the `isValid` flag.

**Validation Scope**: Both `parameters` AND `facts` are validated. If either fails, the whole TestCase is marked `isValid=false`.

**Validation Rules**:
- **Invalid**: Missing required field, wrong type, extra fields not in schema
- **Valid**: Empty `{}` objects (if schema has no required fields), null values for optional fields

#### Scenario: Invalid parameters are persisted as invalid
- **WHEN** client creates/updates/imports a TestCase with `parameters` that do not conform to the schema requirements
- **THEN** system SHALL persist the case with `isValid=false`
- **AND** system SHALL store validation warnings in DB (up to configured max)
- **AND** respond with HTTP 200/201

#### Scenario: Invalid facts are persisted as invalid
- **WHEN** client creates a TestCase with `facts` containing wrong types or extra fields
- **THEN** system SHALL persist the case with `isValid=false`

#### Scenario: Empty parameters/facts with no required fields
- **WHEN** client creates a TestCase with `parameters: {}` and `facts: {}`
- **AND** the schema has no required fields
- **THEN** system SHALL persist the case with `isValid=true`

#### Scenario: Empty parameters/facts with required fields
- **WHEN** client creates a TestCase with `parameters: {}` or `facts: {}`
- **AND** the schema has required fields
- **THEN** system SHALL persist the case with `isValid=false`
- **AND** validation warnings SHALL include "Missing required field: <fieldName>"

#### Scenario: Get validation warnings on request
- **WHEN** client calls `GET .../test-cases/{id}?includeWarnings=true` for a TestCase with `isValid=false`
- **THEN** system SHALL include `validationWarnings` array in response

#### Scenario: Get without validation warnings (default)
- **WHEN** client calls `GET .../test-cases/{id}` without `includeWarnings` param
- **THEN** system SHALL NOT include `validationWarnings` in response (even if `isValid=false`)

#### Scenario: Schema update triggers async re-validation
- **WHEN** the `TestCasesDefinition` schema or `endpointRef` in the parent `TestSuite` is updated
- **THEN** system SHALL respond with HTTP 202 Accepted
- **AND** system SHALL return a `RevalidationTaskDto` with task ID
- **AND** system SHALL start async re-validation job for all TestCases in the suite

#### Scenario: Track re-validation task status
- **WHEN** client calls `GET /api/v1/test-suites/{id}/revalidation-tasks/{taskId}`
- **THEN** system SHALL return current task status (PENDING, RUNNING, COMPLETED, FAILED, TIMED_OUT)
- **AND** response SHALL include progress (processedCases, validCount, invalidCount)

#### Scenario: PATCH always recalculates isValid
- **WHEN** client calls `PATCH .../test-cases/{id}` with any field (even just `isEnabled`)
- **THEN** system SHALL recalculate `isValid` based on current schema

### Requirement: Validate TestSuite embedded schemas
The service SHALL validate that JSON schemas embedded in TestSuite are well-formed.

#### Scenario: Invalid schema prevents TestSuite save
- **WHEN** client creates/updates a TestSuite with malformed JSON Schema (e.g., invalid `$ref`, unsupported keywords)
- **THEN** system SHALL respond with HTTP 400 Bad Request
- **AND** response SHALL include schema validation error details
- **AND** system SHALL NOT persist the TestSuite

### Requirement: Optimistic locking for TestSuite
The service SHALL implement optimistic locking to prevent lost updates during concurrent modifications.

#### Scenario: Successful update with correct version
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with header `If-Match: "5"`
- **AND** the current version in DB is 5
- **THEN** system SHALL update the resource and increment version to 6
- **AND** response SHALL include `ETag: "6"` header and `version: 6` field

#### Scenario: Version conflict
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with header `If-Match: "4"`
- **AND** the current version in DB is 5
- **THEN** system SHALL respond with HTTP 409 Conflict
- **AND** response SHALL include error code `VERSION_CONFLICT`

#### Scenario: CSV import checks version
- **WHEN** client calls `POST .../test-cases/import` with header `If-Match: "5"`
- **AND** the current TestSuite version is not 5
- **THEN** system SHALL respond with HTTP 409 Conflict

### Requirement: Cascade delete for TestSuite
When a TestSuite is deleted, all child entities SHALL be cascade deleted.

#### Scenario: Delete TestSuite with children
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}`
- **AND** the TestSuite has 42 TestCases and 3 RevalidationTasks
- **THEN** system SHALL delete all child entities
- **AND** response SHALL include deletion counts: `{ deleted: true, deletedTestCases: 42, deletedRevalidationTasks: 3 }`

### Requirement: Mutable TestSuite fields
The service SHALL allow updating most TestSuite fields including deploymentRef and endpointRef.

#### Scenario: Update deploymentRef
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with different `deploymentRef`
- **THEN** system SHALL update the deployment reference

#### Scenario: Update endpointRef triggers re-validation
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with different `endpointRef` schema
- **THEN** system SHALL update the endpoint reference
- **AND** system SHALL trigger async re-validation of all TestCases
- **AND** response SHALL be HTTP 202 Accepted with RevalidationTaskDto

### Requirement: createdBy attribution
The service SHALL track who created/maintains each TestSuite.

#### Scenario: createdBy from JWT claim
- **WHEN** client creates a TestSuite with valid JWT containing configured claim
- **THEN** system SHALL set `createdBy` from the JWT claim value

#### Scenario: createdBy fallback in none mode
- **WHEN** security mode is `none`
- **AND** client creates a TestSuite without JWT
- **THEN** system SHALL set `createdBy` to "anonymous"

#### Scenario: createdBy required in oidc mode
- **WHEN** security mode is `oidc`
- **AND** client creates a TestSuite without valid JWT
- **THEN** system SHALL respond with HTTP 401 Unauthorized

#### Scenario: Reassign createdBy (maintainer transfer)
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with different `createdBy` value
- **THEN** system SHALL update the `createdBy` field (ownership transfer)

### Requirement: MetricDefinitions read-only stub
The service SHALL provide a read-only list of seeded metric definitions for future metric binding.

#### Scenario: List metric definitions
- **WHEN** client calls `GET /api/v1/metric-definitions`
- **THEN** system SHALL return a paginated list of seeded metrics
- **AND** list SHALL include at least: "Accuracy", "Latency", "Relevance"

#### Scenario: Get metric definition by ID
- **WHEN** client calls `GET /api/v1/metric-definitions/{id}` with valid ID
- **THEN** system SHALL return the metric definition with id, name, description, createdAt

#### Scenario: Filter metric definitions
- **WHEN** client calls `GET /api/v1/metric-definitions?filter=name:contains:Acc`
- **THEN** system SHALL return metrics matching the filter

#### Scenario: Sort metric definitions
- **WHEN** client calls `GET /api/v1/metric-definitions?sort=name,asc`
- **THEN** system SHALL return metrics sorted by name ascending

### Requirement: Standard error response format
All error responses SHALL follow a standardized format with machine-readable error codes.

#### Scenario: Error response includes code
- **WHEN** any API endpoint returns an error
- **THEN** response SHALL include: status, error, message, code
- **AND** code SHALL be a machine-readable identifier (e.g., `VALIDATION_ERROR`, `NOT_FOUND`)

