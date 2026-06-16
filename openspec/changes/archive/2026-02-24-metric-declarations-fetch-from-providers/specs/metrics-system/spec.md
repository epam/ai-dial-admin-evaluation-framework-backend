# Metrics System (delta)

Delta for change **metric-declarations-fetch-from-providers**: provider identity, MetricDeclarationVersion persistence, catalog populated only by sync (seeded records removed), list endpoint optional filter and schemas.

## MODIFIED Requirements

### Requirement: Maintain a catalog of MetricDeclarations

The system SHALL maintain a catalog of MetricDeclarations available for use in TestSuites. Each MetricDeclaration SHALL be identified by (provider_id, name); provider_id SHALL be required and SHALL come from configuration when synced from a metric provider. The catalog SHALL contain only metrics synced from configured metric provider services; previously seeded (stub) records SHALL be removed by migration and SHALL NOT be returned by the API.

Status: **Planned**

#### Scenario: Declarations are discoverable

- **WHEN** clients query metric catalog
- **THEN** system SHALL expose available MetricDeclarations and their schemas

#### Scenario: Catalog contains only provider-synced metrics

- **WHEN** clients query metric catalog after migration and sync
- **THEN** system SHALL return only MetricDeclarations that were synced from configured metric providers (no legacy seeded stubs)

### Requirement: Support MetricDeclaration versioning

The system SHALL version MetricDeclarations to support reproducibility and recalculation. MetricDeclarationVersion SHALL be persisted with id, metric_declaration_id, schema_version, config_schema, input_schema, output_schema, description, and created_at. A new version SHALL be created when config_schema, input_schema, output_schema, or description change. implementation_version and implementation_ref are out of scope.

Status: **Planned**

#### Scenario: Reproducibility

- **WHEN** a MetricResult is stored
- **THEN** it SHALL reference the MetricDeclarationVersion used to compute it

#### Scenario: Compatible recalculation

- **WHEN** metric logic changes but schemas remain compatible
- **THEN** system SHALL allow recalculating metrics over existing run data

#### Scenario: Latest version and description on declaration

- **WHEN** a MetricDeclaration has one or more MetricDeclarationVersions
- **THEN** MetricDeclaration.description SHALL reflect the description of the latest version (by schema_version)

### Requirement: List metric declarations (stub)

The service SHALL provide a paginated endpoint to list metric declarations available for discovery. Each listed declaration SHALL include id, provider_id, name, description, and created_at. The endpoint MAY support an optional filter by provider_id. The endpoint MAY include the latest MetricDeclarationVersion’s config_schema, input_schema, and output_schema for discovery (exact shape to be defined in implementation). Previously seeded (stub) records SHALL have been removed by migration; the list SHALL contain only provider-synced metrics.

Status: **Implemented (stub)** → **Partial** (provider_id and versioning added; filter/schemas optional)

#### Scenario: Empty catalog

- **WHEN** client calls `GET /api/v1/metric-declarations` and no metric declarations exist (e.g. no sync run yet or no providers configured)
- **THEN** system SHALL respond with HTTP 200 and an empty page result

#### Scenario: Pagination and sorting

- **WHEN** client calls `GET /api/v1/metric-declarations?page=<p>&size=<s>&sort=<field>[,<asc|desc>]` (repeatable)
- **THEN** system SHALL apply pagination and safe sorting using a whitelist of allowed fields

#### Scenario: Optional filter by provider_id

- **WHEN** client calls `GET /api/v1/metric-declarations?filter=providerId:eq:<id>` (using the existing generic filter mechanism)
- **THEN** system SHALL return only MetricDeclarations for that provider_id when the filter is present

## ADDED Requirements

### Requirement: MetricDeclaration has provider identity

Each MetricDeclaration SHALL have a non-null provider_id. The system SHALL enforce UNIQUE(provider_id, name) so the same metric name from different providers is distinct. provider_id SHALL be set from configuration when declarations are synced from a metric provider.

#### Scenario: Same metric name from two providers

- **WHEN** two configured providers both expose a metric named "exact_match"
- **THEN** system SHALL store two MetricDeclaration rows (one per provider_id) and SHALL list both via the API

#### Scenario: Uniqueness constraint

- **WHEN** sync attempts to insert or update a MetricDeclaration with (provider_id, name) that already exists
- **THEN** system SHALL upsert (update existing) rather than fail with a duplicate key error

### Requirement: Get latest metric declaration version by declaration id

The service SHALL provide an endpoint GET /api/v1/metric-declarations/{id}/latest that returns the latest MetricDeclarationVersion for the metric declaration with the given id. Latest SHALL be determined by the greatest schema_version for that metric_declaration_id. The response SHALL include the version’s id, metric_declaration_id, schema_version, config_schema, input_schema, output_schema, description, and created_at (or equivalent epoch-ms timestamp).

#### Scenario: Latest version returned

- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and the metric declaration exists and has at least one version
- **THEN** system SHALL respond with HTTP 200 and the latest MetricDeclarationVersion (by schema_version) for that declaration

#### Scenario: Metric declaration not found

- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and no metric declaration exists with that id
- **THEN** system SHALL respond with HTTP 404

#### Scenario: No versions for declaration

- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and the metric declaration exists but has no MetricDeclarationVersion rows
- **THEN** system SHALL respond with HTTP 404
