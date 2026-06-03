## MODIFIED Requirements

### Requirement: Get latest metric declaration version by declaration id
The service SHALL provide an endpoint GET /api/v1/metric-declarations/{id}/latest that returns the latest MetricDeclarationVersion for the metric declaration with the given id. Latest SHALL be determined by the greatest schema_version for that metric_declaration_id. The response SHALL include the version's id, metric_declaration_id, schema_version, config_schema, input_schema, output_schema, description, and created_at (or equivalent epoch-ms timestamp). The config_schema, input_schema, and output_schema fields in the response SHALL be serialized as JSON objects (not as JSON strings), consistent with how other JSONB-backed schema fields (e.g. test case data, test suite schemas) are returned by the API.

#### Scenario: Latest version returned with object-typed schemas
- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and the metric declaration exists and has at least one version with non-null schemas
- **THEN** system SHALL respond with HTTP 200 and the latest MetricDeclarationVersion where configSchema, inputSchema, and outputSchema are JSON objects in the response body (not escaped JSON strings)

#### Scenario: Empty schemas returned as empty objects
- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and the latest version has empty schemas (stored as `{}` in DB; columns are NOT NULL)
- **THEN** system SHALL return empty JSON objects for those fields (not empty strings or the string `"{}"`)

#### Scenario: Metric declaration not found
- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and no metric declaration exists with that id
- **THEN** system SHALL respond with HTTP 404

#### Scenario: No versions for declaration
- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and the metric declaration exists but has no MetricDeclarationVersion rows
- **THEN** system SHALL respond with HTTP 404

### Requirement: List metric declarations (stub)
The service SHALL provide a paginated endpoint to list metric declarations available for discovery. Each listed declaration SHALL include id, provider_id, name, description, and created_at. The endpoint MAY support an optional filter by provider_id. The endpoint MAY include the latest MetricDeclarationVersion's config_schema, input_schema, and output_schema for discovery (exact shape to be defined in implementation). When these schema fields are included, they SHALL be serialized as JSON objects (not as JSON strings). Previously seeded (stub) records SHALL have been removed by migration; the list SHALL contain only provider-synced metrics.

#### Scenario: Empty catalog
- **WHEN** client calls `GET /api/v1/metric-declarations` and no metric declarations exist (e.g. no sync run yet or no providers configured)
- **THEN** system SHALL respond with HTTP 200 and an empty page result

#### Scenario: Pagination and sorting
- **WHEN** client calls `GET /api/v1/metric-declarations?page=<p>&size=<s>&sort=<field>[,<asc|desc>]` (repeatable)
- **THEN** system SHALL apply pagination and safe sorting using a whitelist of allowed fields

#### Scenario: Optional filter by provider_id
- **WHEN** client calls `GET /api/v1/metric-declarations?filter=providerId:eq:<id>` (using the existing generic filter mechanism)
- **THEN** system SHALL return only MetricDeclarations for that provider_id when the filter is present
