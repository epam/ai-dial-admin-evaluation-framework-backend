# Test Suite Metric Definitions

## Purpose
This spec describes CRUD management of metric definitions within a test suite. A Test Suite Metric Definition (TSMD) materializes the selection and configuration of a metric declaration within a specific test suite, including parameter bindings that map metric input/config properties to test case columns, response columns, or constant values.

Status: **Implemented**

## Requirements

### Requirement: Create a Test Suite Metric Definition
The system SHALL allow creating a TSMD within a test suite via `POST /api/v1/test-suites/{suiteId}/metric-definitions`. The request body SHALL include `name` (required, max 255 characters), `metricDeclarationId` (required, UUID referencing an existing metric declaration), `metricDeclarationVersionId` (required, UUID referencing an existing version belonging to the given metric declaration), `configBindings` (optional, defaults to `[]`), `inputBindings` (optional, defaults to `[]`), and `enabled` (optional boolean, defaults to `true`). The system SHALL validate that `metricDeclarationVersionId` belongs to the referenced `metricDeclarationId`; if the version does not exist or does not belong to the declaration, the system SHALL respond with HTTP 404. The system SHALL hard-validate that `configBindings` and `inputBindings` contain no duplicate `property` values within each list (HTTP 400 on violation). After persisting, the system SHALL compute and store a soft-validation result (`is_valid`, `validation_warnings`). The response SHALL return the created TSMD with all fields including `enabled`, `valid`, and `validationWarnings`.

#### Scenario: Successful creation
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with a valid body containing `name`, `metricDeclarationId`, and `metricDeclarationVersionId`
- **THEN** system SHALL create the TSMD using the client-supplied `metricDeclarationVersionId`, set `createdAt` and `updatedAt` to current epoch ms, compute `is_valid` / `validation_warnings`, and respond with HTTP 201 and the created TSMD including `enabled`, `valid`, `validationWarnings`

#### Scenario: Creation with bindings
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with `configBindings` and `inputBindings` containing valid binding entries
- **THEN** system SHALL persist the bindings as JSONB and return them in the response

#### Scenario: Default enabled on creation
- **WHEN** client creates a TSMD without specifying `enabled`
- **THEN** the response SHALL include `enabled = true`

#### Scenario: Test suite not found
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with a non-existent `suiteId`
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Metric declaration version not found or mismatch
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with a `metricDeclarationVersionId` that does not exist or does not belong to the referenced `metricDeclarationId`
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Duplicate name within suite
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with a `name` that already exists (case-insensitive) in the same test suite
- **THEN** system SHALL respond with HTTP 409 and error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: Missing required fields
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` without `name`, without `metricDeclarationId`, or without `metricDeclarationVersionId`
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Duplicate property in bindings rejected
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with `configBindings` or `inputBindings` containing two entries with the same `property` value
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

### Requirement: Get a Test Suite Metric Definition by ID
The system SHALL allow retrieving a single TSMD via `GET /api/v1/test-suites/{suiteId}/metric-definitions/{id}`.

#### Scenario: Successful retrieval
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions/{id}` for an existing TSMD belonging to that suite
- **THEN** system SHALL respond with HTTP 200 and the full TSMD including `id`, `testSuiteId`, `metricDeclarationId`, `metricDeclarationVersionId`, `name`, `configBindings`, `inputBindings`, `enabled`, `valid`, `validationWarnings`, `createdAt`, `updatedAt`

#### Scenario: TSMD not found
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a non-existent `id`
- **THEN** system SHALL respond with HTTP 404

#### Scenario: TSMD belongs to different suite
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions/{id}` where the TSMD exists but belongs to a different suite
- **THEN** system SHALL respond with HTTP 404

### Requirement: List Test Suite Metric Definitions
The system SHALL allow listing TSMDs for a test suite via `GET /api/v1/test-suites/{suiteId}/metric-definitions` with pagination, filtering, and sorting.

#### Scenario: Paginated list
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?page=0&size=50`
- **THEN** system SHALL return a paginated response with TSMDs belonging to that suite

#### Scenario: Filter by name
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?filter=name:contains:accuracy`
- **THEN** system SHALL return only TSMDs whose name matches the filter

#### Scenario: Filter by metric declaration name (exact match)
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?filter=metricDeclarationName:eq:Accuracy`
- **THEN** system SHALL return only TSMDs whose underlying metric declaration name equals "Accuracy"

#### Scenario: Filter by metric declaration name (substring)
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?filter=metricDeclarationName:contains:accur`
- **THEN** system SHALL return only TSMDs whose underlying metric declaration name contains the substring (case-insensitive)

#### Scenario: Filter by metric declaration name (not equal)
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?filter=metricDeclarationName:ne:Latency`
- **THEN** system SHALL return only TSMDs whose underlying metric declaration name is not "Latency"

#### Scenario: Sort by name
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?sort=name,asc`
- **THEN** system SHALL return TSMDs sorted by name ascending

#### Scenario: Sort by createdAt
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?sort=createdAt,desc`
- **THEN** system SHALL return TSMDs sorted by creation timestamp descending

#### Scenario: Default sort order
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions` without sort parameter
- **THEN** system SHALL return results sorted by `createdAt,desc`

#### Scenario: Invalid filter field
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?filter=unknownField:eq:value`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Empty list
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions` and the suite has no TSMDs
- **THEN** system SHALL respond with HTTP 200 and an empty page result

#### Scenario: List response items include validation state fields
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions`
- **THEN** each item in the response SHALL include `enabled`, `valid`, and `validationWarnings` alongside all other TSMD response fields

### Requirement: Update a Test Suite Metric Definition
The system SHALL allow updating a TSMD via `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}`. The update body SHALL include `name`, `metricDeclarationId`, `metricDeclarationVersionId`, `configBindings`, `inputBindings`, and `enabled`. The system SHALL validate that `metricDeclarationVersionId` belongs to the referenced `metricDeclarationId`; if the version does not exist or does not belong to the declaration, the system SHALL respond with HTTP 404. The system SHALL hard-validate no duplicate `property` values within each binding list (HTTP 400 on violation). After persisting, the system SHALL recompute the soft-validation result and store the new `is_valid` / `validation_warnings`. The system SHALL use the client-supplied `metricDeclarationVersionId` without auto-resolving to latest.

#### Scenario: Successful update
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a valid body containing `metricDeclarationId` and `metricDeclarationVersionId`
- **THEN** system SHALL update the TSMD, recompute validation state, update `updatedAt`, and respond with HTTP 200 and the updated TSMD including `enabled`, `valid`, `validationWarnings`

#### Scenario: Update name to duplicate
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a `name` that already belongs to a different TSMD in the same suite (case-insensitive)
- **THEN** system SHALL respond with HTTP 409 and error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: TSMD not found on update
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a non-existent `id`
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Change metric declaration and version reference
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a different `metricDeclarationId` and corresponding `metricDeclarationVersionId`
- **THEN** system SHALL update both references using the client-supplied values and recompute validation state

#### Scenario: Toggle enabled flag
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with `enabled = false`
- **THEN** system SHALL set `is_enabled = false` and return `enabled = false` in the response

#### Scenario: Metric declaration version not found or mismatch on update
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a `metricDeclarationVersionId` that does not exist or does not belong to the referenced `metricDeclarationId`
- **THEN** system SHALL respond with HTTP 404

### Requirement: TSMD name MUST NOT contain `::` (double colon)
The service SHALL reject Test Suite Metric Definition create and update requests in which `name` contains the `::` (double-colon) sequence. The `::` sequence is reserved as the column-family separator in the evaluation summary CSV export; a single colon `:` is permitted in the name. Validation applies uniformly to `POST /api/v1/test-suites/{suiteId}/metric-definitions` and `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}`. Pre-existing rows with `::`-bearing names are NOT migrated; any subsequent update of such a TSMD SHALL fail validation until the name is changed.

#### Scenario: Create rejected when TSMD name contains a double colon
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with `name = "Acc::uracy"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, and a field-bound message identifying the `name` field

#### Scenario: Update rejected when TSMD name contains a double colon
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with `name = "Acc::uracy"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, and a field-bound message identifying the `name` field

#### Scenario: Create accepted when TSMD name contains a single colon
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with a `name` containing a single colon `:` but no `::` sequence (e.g. `"Acc:uracy"`)
- **THEN** system SHALL persist the TSMD and respond with HTTP 201

### Requirement: Delete a Test Suite Metric Definition
The system SHALL allow deleting a TSMD via `DELETE /api/v1/test-suites/{suiteId}/metric-definitions/{id}`.

#### Scenario: Successful deletion
- **WHEN** client calls `DELETE /api/v1/test-suites/{suiteId}/metric-definitions/{id}` for an existing TSMD
- **THEN** system SHALL delete the TSMD and respond with HTTP 204

#### Scenario: TSMD not found on delete
- **WHEN** client calls `DELETE /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a non-existent `id`
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Cascade delete with test suite
- **WHEN** a test suite is deleted
- **THEN** system SHALL automatically delete all TSMDs belonging to that suite via CASCADE

### Requirement: Parameter binding model
Each TSMD SHALL store `configBindings` and `inputBindings` as JSONB arrays. Each binding entry SHALL have a `property` field (String, the flat top-level key in the metric's config or input schema) and a `source` object with a `$type` discriminator. The system SHALL support three source types:
- `TestCase`: with `columnName` (String) referencing a column from the test suite's `testCaseSchema`
- `Response`: with `columnName` (String) referencing a column from the test suite's `responseColumns`
- `Constant`: with `value` (any JSON value — string, number, boolean, object, array, or `null`). A `null` value is accepted as stored state; soft validation determines whether it is valid based on the target property's required status in the metric schema.

#### Scenario: TestCase binding source
- **WHEN** a TSMD is created with a binding `{"property": "reference", "source": {"$type": "TestCase", "columnName": "expected_output"}}`
- **THEN** system SHALL persist and return the binding with the `TestCase` source type

#### Scenario: Response binding source
- **WHEN** a TSMD is created with a binding `{"property": "actual", "source": {"$type": "Response", "columnName": "model_answer"}}`
- **THEN** system SHALL persist and return the binding with the `Response` source type

#### Scenario: Constant binding source
- **WHEN** a TSMD is created with a binding `{"property": "threshold", "source": {"$type": "Constant", "value": 0.8}}`
- **THEN** system SHALL persist and return the binding with the `Constant` source type

#### Scenario: Constant with complex JSON value
- **WHEN** a TSMD is created with a binding `{"property": "options", "source": {"$type": "Constant", "value": {"key": "val"}}}`
- **THEN** system SHALL persist and return the binding with the object value intact

#### Scenario: Constant with null value accepted
- **WHEN** a TSMD is created with a binding `{"property": "model", "source": {"$type": "Constant", "value": null}}`
- **THEN** system SHALL accept the request (HTTP 201) and persist the null constant value

### Requirement: condition field on a Test Suite Metric Definition
A Test Suite Metric Definition SHALL carry an optional `condition` string (max 2000 chars), persisted in a new nullable `test_suite_metric_definitions.condition VARCHAR(2000)` column and exposed on the request and response DTOs. Null/blank means the metric always runs. Runtime semantics are specified in the `conditional-metric-execution` spec; write-time syntax validation is specified in `tsmd-validation`.

#### Scenario: Condition round-trips through the API
- **WHEN** a metric definition is created with a `condition` and read back
- **THEN** the response includes the same `condition` string; single-turn/unconditional definitions omit or return null

### Requirement: TSMD response DTO shape
The TSMD response SHALL include: `id` (UUID), `testSuiteId` (UUID), `metricDeclarationId` (UUID), `metricDeclarationVersionId` (UUID), `name` (String), `metricDeclarationName` (String — the human-readable name of the referenced metric declaration), `configBindings` (list of binding objects), `inputBindings` (list of binding objects), `enabled` (boolean), `valid` (boolean), `validationWarnings` (list of `ValidationWarningDto`, always present, empty array when valid), `createdAt` (epoch ms), `updatedAt` (epoch ms). The `configBindings` and `inputBindings` fields SHALL be serialized as JSON arrays (not as raw JSON strings).

#### Scenario: Response includes all fields
- **WHEN** client retrieves a TSMD via GET
- **THEN** the response body SHALL contain all specified fields with correct types, including `metricDeclarationName`, `enabled`, `valid`, and `validationWarnings`

#### Scenario: validationWarnings is empty array when TSMD is valid
- **WHEN** client retrieves a TSMD that has no validation warnings
- **THEN** `validationWarnings` SHALL be `[]` (not null)

#### Scenario: metricDeclarationName populated on create
- **WHEN** client creates a TSMD via POST
- **THEN** the response body SHALL include `metricDeclarationName` with the name of the referenced metric declaration

#### Scenario: metricDeclarationName populated on update
- **WHEN** client updates a TSMD via PUT (potentially changing the metric declaration reference)
- **THEN** the response body SHALL include `metricDeclarationName` reflecting the current metric declaration

### Requirement: OpenAPI documentation
The controller SHALL include OpenAPI annotations (`@Tag`, `@Operation`, `@ApiResponse`, `@Schema`) for all endpoints. Filter and sort query parameters SHALL be documented via `OpenApiQueryParamCustomizer` registration.

#### Scenario: Swagger UI shows TSMD endpoints
- **WHEN** developer opens Swagger UI
- **THEN** all TSMD CRUD endpoints SHALL be listed with descriptions, request/response schemas, and query parameter documentation
