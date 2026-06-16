## MODIFIED Requirements

### Requirement: Support MetricDeclaration versioning

The system SHALL version MetricDeclarations to support reproducibility and recalculation. MetricDeclarationVersion SHALL be persisted with id, metric_declaration_id, schema_version, config_schema, input_schema, output_schema, description, display_name, and created_at. A new version SHALL be created when config_schema, input_schema, output_schema, description, or display_name change. implementation_version and implementation_ref are out of scope.
Status: **Planned**

#### Scenario: Reproducibility

- **WHEN** a MetricResult is stored
- **THEN** it SHALL reference the MetricDeclarationVersion used to compute it

#### Scenario: Compatible recalculation

- **WHEN** metric logic changes but schemas remain compatible
- **THEN** system SHALL allow recalculating metrics over existing run data

#### Scenario: Latest version metadata on declaration

- **WHEN** a MetricDeclaration has one or more MetricDeclarationVersions
- **THEN** MetricDeclaration.description SHALL reflect the description of the latest version (by schema_version) and MetricDeclaration.display_name SHALL reflect the display_name of the latest version

### Requirement: List metric declarations (stub)

The service SHALL provide a paginated endpoint to list metric declarations available for discovery. Each listed declaration SHALL include id, provider_id, name, display_name, description, and created_at. The endpoint MAY support an optional filter by provider_id. The endpoint MAY include the latest MetricDeclarationVersion's config_schema, input_schema, and output_schema for discovery (exact shape to be defined in implementation). When these schema fields are included, they SHALL be serialized as JSON objects (not as JSON strings). Previously seeded (stub) records SHALL have been removed by migration; the list SHALL contain only provider-synced metrics. The display_name field SHALL be nullable in the response (providers may omit it).
Status: **Partial** (provider_id and versioning added; filter/schemas optional)

#### Scenario: Empty catalog

- **WHEN** client calls `GET /api/v1/metric-declarations` and no metric declarations exist (e.g. no sync run yet or no providers configured)
- **THEN** system SHALL respond with HTTP 200 and an empty page result

#### Scenario: Pagination and sorting

- **WHEN** client calls `GET /api/v1/metric-declarations?page=<p>&size=<s>&sort=<field>[,<asc|desc>]` (repeatable)
- **THEN** system SHALL apply pagination and safe sorting using a whitelist of allowed fields

#### Scenario: Optional filter by provider_id

- **WHEN** client calls `GET /api/v1/metric-declarations?filter=providerId:eq:<id>` (using the existing generic filter mechanism)
- **THEN** system SHALL return only MetricDeclarations for that provider_id when the filter is present

#### Scenario: displayName included in response

- **WHEN** client calls `GET /api/v1/metric-declarations` and a declaration has a display_name
- **THEN** system SHALL include the displayName field in each response item

#### Scenario: displayName null when provider did not supply it

- **WHEN** client calls `GET /api/v1/metric-declarations` and a declaration has no display_name (provider omitted it or sync has not run yet)
- **THEN** system SHALL return null (or omit) the displayName field for that item

### Requirement: Get latest metric declaration version by declaration id

The service SHALL provide an endpoint GET /api/v1/metric-declarations/{id}/latest that returns the latest MetricDeclarationVersion for the metric declaration with the given id. Latest SHALL be determined by the greatest schema_version for that metric_declaration_id. The response SHALL include the version's id, metric_declaration_id, schema_version, config_schema, input_schema, output_schema, description, display_name, and created_at (or equivalent epoch-ms timestamp). The config_schema, input_schema, and output_schema fields in the response SHALL be serialized as JSON objects (not as JSON strings), consistent with how other JSONB-backed schema fields (e.g. test case data, test suite schemas) are returned by the API. The display_name field SHALL be nullable in the response.

#### Scenario: Latest version returned with object-typed schemas and displayName

- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and the metric declaration exists and has at least one version
- **THEN** system SHALL respond with HTTP 200 and the latest MetricDeclarationVersion where configSchema, inputSchema, and outputSchema are JSON objects and displayName reflects the value stored in that version (may be null)

#### Scenario: Empty schemas returned as empty objects

- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and the latest version has empty schemas (stored as `{}` in DB; columns are NOT NULL)
- **THEN** system SHALL return empty JSON objects for those fields (not empty strings or the string `"{}"`)

#### Scenario: Metric declaration not found

- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and no metric declaration exists with that id
- **THEN** system SHALL respond with HTTP 404

#### Scenario: No versions for declaration

- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and the metric declaration exists but has no MetricDeclarationVersion rows
- **THEN** system SHALL respond with HTTP 404
