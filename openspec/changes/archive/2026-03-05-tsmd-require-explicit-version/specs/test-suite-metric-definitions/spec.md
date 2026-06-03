## MODIFIED Requirements

### Requirement: Create a Test Suite Metric Definition
The system SHALL allow creating a TSMD within a test suite via `POST /api/v1/test-suites/{suiteId}/metric-definitions`. The request body SHALL include `name` (required, max 255 characters), `metricDeclarationId` (required, UUID referencing an existing metric declaration), `metricDeclarationVersionId` (required, UUID referencing an existing version belonging to the given metric declaration), `configBindings` (optional, defaults to `[]`), and `inputBindings` (optional, defaults to `[]`). The system SHALL validate that `metricDeclarationVersionId` belongs to the referenced `metricDeclarationId`; if the version does not exist or does not belong to the declaration, the system SHALL respond with HTTP 404. The response SHALL return the created TSMD with all fields including the client-supplied `metricDeclarationVersionId`.

#### Scenario: Successful creation
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with a valid body containing `name`, `metricDeclarationId`, and `metricDeclarationVersionId`
- **THEN** system SHALL create the TSMD using the client-supplied `metricDeclarationVersionId`, set `createdAt` and `updatedAt` to current epoch ms, and respond with HTTP 201 and the created TSMD

#### Scenario: Creation with bindings
- **WHEN** client calls `POST /api/v1/test-suites/{suiteId}/metric-definitions` with `configBindings` and `inputBindings` containing valid binding entries
- **THEN** system SHALL persist the bindings as JSONB and return them in the response

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

### Requirement: Update a Test Suite Metric Definition
The system SHALL allow updating a TSMD via `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}`. The update body SHALL include `name`, `metricDeclarationId`, `metricDeclarationVersionId`, `configBindings`, and `inputBindings`. The system SHALL validate that `metricDeclarationVersionId` belongs to the referenced `metricDeclarationId`; if the version does not exist or does not belong to the declaration, the system SHALL respond with HTTP 404. The system SHALL use the client-supplied `metricDeclarationVersionId` without auto-resolving to latest.

#### Scenario: Successful update
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a valid body containing `metricDeclarationId` and `metricDeclarationVersionId`
- **THEN** system SHALL update the TSMD using the client-supplied `metricDeclarationVersionId`, update `updatedAt`, and respond with HTTP 200 and the updated TSMD

#### Scenario: Update name to duplicate
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a `name` that already belongs to a different TSMD in the same suite (case-insensitive)
- **THEN** system SHALL respond with HTTP 409 and error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: TSMD not found on update
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a non-existent `id`
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Change metric declaration and version reference
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a different `metricDeclarationId` and corresponding `metricDeclarationVersionId`
- **THEN** system SHALL update both references using the client-supplied values

#### Scenario: Metric declaration version not found or mismatch on update
- **WHEN** client calls `PUT /api/v1/test-suites/{suiteId}/metric-definitions/{id}` with a `metricDeclarationVersionId` that does not exist or does not belong to the referenced `metricDeclarationId`
- **THEN** system SHALL respond with HTTP 404
