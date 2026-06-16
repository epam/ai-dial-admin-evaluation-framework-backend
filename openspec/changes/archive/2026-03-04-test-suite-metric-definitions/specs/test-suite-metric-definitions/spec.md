## ADDED Requirements

### Requirement: Create a Test Suite Metric Definition
The system SHALL allow creating a TSMD within a test suite via `POST /api/v1/test-suites/{suiteId}/metric-definitions`. The request body SHALL include `name` (required, max 255 characters), `metricDeclarationId` (required, UUID referencing an existing metric declaration), `configBindings` (optional, defaults to `[]`), and `inputBindings` (optional, defaults to `[]`). The system SHALL resolve `metricDeclarationVersionId` server-side to the latest `MetricDeclarationVersion` (by greatest `schema_version`) for the given `metricDeclarationId`. The response SHALL return the created TSMD with all fields including the resolved `metricDeclarationVersionId`.

#### Scenario: Successful creation
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with a valid body containing `name` and `metricDeclarationId`
- **THEN** system SHALL create the TSMD, resolve `metricDeclarationVersionId` to the latest version, set `createdAt` and `updatedAt` to current epoch ms, and respond with HTTP 201 and the created TSMD

#### Scenario: Creation with bindings
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with `configBindings` and `inputBindings` containing valid binding entries
- **THEN** system SHALL persist the bindings as JSONB and return them in the response

#### Scenario: Test suite not found
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with a non-existent `suiteId`
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Metric declaration not found
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with a `metricDeclarationId` that does not exist
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Metric declaration has no versions
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with a `metricDeclarationId` that exists but has no `MetricDeclarationVersion` rows
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Duplicate name within suite
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with a `name` that already exists (case-insensitive) in the same test suite
- **THEN** system SHALL respond with HTTP 409 and error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: Missing required fields
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` without `name` or without `metricDeclarationId`
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

### Requirement: Get a Test Suite Metric Definition by ID
The system SHALL allow retrieving a single TSMD via `GET /api/v1/test-suites/{suiteId}/metric-definitions/{id}`.

#### Scenario: Successful retrieval
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions/{id}` for an existing TSMD belonging to that suite
- **THEN** system SHALL respond with HTTP 200 and the full TSMD including `id`, `testSuiteId`, `metricDeclarationId`, `metricDeclarationVersionId`, `name`, `configBindings`, `inputBindings`, `createdAt`, `updatedAt`

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

### Requirement: Update a Test Suite Metric Definition
The system SHALL allow updating a TSMD via `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}`. The update body SHALL include `name`, `metricDeclarationId`, `configBindings`, and `inputBindings`. The system SHALL re-resolve `metricDeclarationVersionId` to the latest version on update.

#### Scenario: Successful update
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a valid body
- **THEN** system SHALL update the TSMD, re-resolve `metricDeclarationVersionId` to latest, update `updatedAt`, and respond with HTTP 200 and the updated TSMD

#### Scenario: Update name to duplicate
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a `name` that already belongs to a different TSMD in the same suite (case-insensitive)
- **THEN** system SHALL respond with HTTP 409 and error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: TSMD not found on update
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a non-existent `id`
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Change metric declaration reference
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a different `metricDeclarationId` than the current one
- **THEN** system SHALL update the reference and resolve `metricDeclarationVersionId` to the latest version of the new metric declaration

#### Scenario: Metric declaration not found on update
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a `metricDeclarationId` that does not exist
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Metric declaration has no versions on update
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a `metricDeclarationId` that exists but has no `MetricDeclarationVersion` rows
- **THEN** system SHALL respond with HTTP 404

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
- `Constant`: with `value` (any non-null JSON value — string, number, boolean, object, or array). Java `null` is not a valid constant value due to the global `NON_NULL` Jackson serialization setting; clients needing a JSON null semantic should use a sentinel or leave the binding unset.

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

### Requirement: TSMD response DTO shape
The TSMD response SHALL include: `id` (UUID), `testSuiteId` (UUID), `metricDeclarationId` (UUID), `metricDeclarationVersionId` (UUID), `name` (String), `configBindings` (list of binding objects), `inputBindings` (list of binding objects), `createdAt` (epoch ms), `updatedAt` (epoch ms). The `configBindings` and `inputBindings` fields SHALL be serialized as JSON arrays (not as raw JSON strings).

#### Scenario: Response includes all fields
- **WHEN** client retrieves a TSMD via GET
- **THEN** the response body SHALL contain all specified fields with correct types

### Requirement: OpenAPI documentation
The controller SHALL include OpenAPI annotations (`@Tag`, `@Operation`, `@ApiResponse`, `@Schema`) for all endpoints. Filter and sort query parameters SHALL be documented via `OpenApiQueryParamCustomizer` registration.

#### Scenario: Swagger UI shows TSMD endpoints
- **WHEN** developer opens Swagger UI
- **THEN** all TSMD CRUD endpoints SHALL be listed with descriptions, request/response schemas, and query parameter documentation
