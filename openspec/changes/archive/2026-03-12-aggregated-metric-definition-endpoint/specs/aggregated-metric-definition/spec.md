## ADDED Requirements

### Requirement: Aggregated metric definition endpoint
The system SHALL provide a `GET /api/v1/test-suites/{testSuiteId}/metric-definitions/{id}/aggregated` endpoint that returns a test suite metric definition enriched with its referenced metric declaration and metric declaration version in a single response.

Status: Planned

#### Scenario: Successful retrieval of aggregated metric definition
- **WHEN** a client sends `GET /api/v1/test-suites/{testSuiteId}/metric-definitions/{id}/aggregated` with valid `testSuiteId` and `id`
- **THEN** the system SHALL return HTTP 200 with a response containing:
  - Top-level metric definition fields: `id`, `testSuiteId`, `name`, `configBindings`, `inputBindings`, `createdAt`, `updatedAt`
  - Nested `metricDeclaration` object: `id`, `providerId`, `name`, `description`, `createdAt`
  - Nested `metricDeclarationVersion` object: `id`, `metricDeclarationId`, `schemaVersion`, `configSchema` (as JSON object), `inputSchema` (as JSON object), `outputSchema` (as JSON object), `description`, `createdAt`

#### Scenario: Metric definition not found
- **WHEN** a client sends `GET /api/v1/test-suites/{testSuiteId}/metric-definitions/{id}/aggregated` with a non-existent metric definition `id`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

#### Scenario: Metric definition belongs to a different test suite
- **WHEN** a client sends `GET /api/v1/test-suites/{testSuiteId}/metric-definitions/{id}/aggregated` where the metric definition exists but belongs to a different test suite
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

#### Scenario: Test suite does not exist
- **WHEN** a client sends `GET /api/v1/test-suites/{testSuiteId}/metric-definitions/{id}/aggregated` with a non-existent `testSuiteId`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

### Requirement: Aggregated response preserves base metric definition fields
The aggregated response DTO SHALL include the same top-level fields as `TestSuiteMetricDefinitionResponseDto` (`id`, `testSuiteId`, `name`, `metricDeclarationName`, `configBindings`, `inputBindings`, `createdAt`, `updatedAt`) plus `metricDeclarationId` and `metricDeclarationVersionId` for backward compatibility.

Status: Planned

#### Scenario: All base fields present in aggregated response
- **WHEN** a client retrieves an aggregated metric definition
- **THEN** the response SHALL contain all fields from the standard metric definition response, ensuring clients can use the aggregated endpoint as a superset of the standard `GET /{id}` response

### Requirement: JSONB schema fields exposed as JSON objects
The `configSchema`, `inputSchema`, and `outputSchema` fields in the nested `metricDeclarationVersion` object SHALL be returned as JSON objects (not raw strings), consistent with the existing `MetricDeclarationVersionResponseDto` convention.

Status: Planned

#### Scenario: Schema fields are proper JSON objects
- **WHEN** a client retrieves an aggregated metric definition where the referenced version has non-null schemas
- **THEN** `metricDeclarationVersion.configSchema`, `metricDeclarationVersion.inputSchema`, and `metricDeclarationVersion.outputSchema` SHALL each be a JSON object (Map), not a JSON string

#### Scenario: Null schema fields
- **WHEN** a client retrieves an aggregated metric definition where the referenced version has null schemas
- **THEN** the corresponding schema fields SHALL be `null` in the response

### Requirement: OpenAPI documentation for aggregated endpoint
The aggregated endpoint SHALL have OpenAPI annotations including operation summary, description, response schema, and example responses.

Status: Planned

#### Scenario: Endpoint visible in Swagger UI
- **WHEN** a developer opens Swagger UI
- **THEN** the `GET /{id}/aggregated` endpoint SHALL appear under the "Test Suite Metric Definitions" tag with a clear description of its purpose
