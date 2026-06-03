## MODIFIED Requirements

### Requirement: Metric provider HTTP client

The system SHALL provide an HTTP client that calls GET {baseUrl}/metrics for a given provider. The client SHALL map the response to the MetricsResponse contract (metrics array of name, display_name, description, config_schema, input_schema, output_schema). The client SHALL use the configured base URL and timeouts for that provider. Requests SHALL use the identity of the service (no per-provider authentication in scope). Provider responses MAY supply config_schema, input_schema, and output_schema as either JSON strings or JSON objects; the client SHALL accept both and SHALL normalize them to a JSON string for internal use (e.g. via a custom deserializer) so that downstream comparison and storage use a consistent representation. The client SHALL capture `display_name` from the provider response; if the field is absent it SHALL be treated as `null`.

#### Scenario: Successful fetch

- **WHEN** sync invokes the client for a configured provider and the provider returns HTTP 200 with valid MetricsResponse JSON including `display_name` fields
- **THEN** client SHALL return the parsed response with `displayName` populated for processing

#### Scenario: Provider omits display_name

- **WHEN** a provider returns a metric entry without a `display_name` field
- **THEN** client SHALL map `displayName` to `null` and SHALL NOT fail or skip the metric

#### Scenario: Provider unreachable or error response

- **WHEN** the provider returns a non-2xx status or a timeout/connection error occurs
- **THEN** client SHALL propagate the failure so the sync job can log and continue with the next provider

### Requirement: Sync upserts declarations and versions per provider

For each configured provider, the sync job SHALL call GET /metrics, then for each metric in the response SHALL upsert a MetricDeclaration by (provider_id, name) and SHALL create a new MetricDeclarationVersion when the metric's config_schema, input_schema, output_schema, description, or display_name differ from the current latest version. Schema comparison SHALL be structural (e.g. comparing parsed JSON trees via Jackson JsonNode.equals), NOT textual, to avoid creating spurious versions due to key-ordering or whitespace differences in provider responses. On insert of a new MetricDeclaration: set id, provider_id, name, display_name, description, created_at_ms. On update of an existing declaration: only description and display_name SHALL be updated atomically; id, provider_id, name, and created_at_ms SHALL be preserved. MetricDeclaration.description and MetricDeclaration.display_name SHALL always reflect the latest version's values. Sync SHALL use a single transaction per provider (fetch then persist for that provider).

#### Scenario: New metric from provider

- **WHEN** sync runs for a provider and the response includes a metric name that does not yet exist for that provider_id
- **THEN** system SHALL insert a new MetricDeclaration (provider_id, name, display_name, description) and one MetricDeclarationVersion row including display_name

#### Scenario: Existing metric unchanged

- **WHEN** sync runs for a provider and a metric's config_schema, input_schema, output_schema, description, and display_name all match the latest version for that declaration
- **THEN** system SHALL NOT insert a new MetricDeclarationVersion

#### Scenario: Existing metric display_name changed

- **WHEN** sync runs for a provider and a metric's display_name differs from the latest version but schemas and description are unchanged
- **THEN** system SHALL insert a new MetricDeclarationVersion and SHALL update MetricDeclaration.display_name to the new value

#### Scenario: Existing metric schema or description changed

- **WHEN** sync runs for a provider and a metric's config_schema, input_schema, output_schema, or description differ from the latest version
- **THEN** system SHALL insert a new MetricDeclarationVersion and SHALL update MetricDeclaration.description and MetricDeclaration.display_name atomically to the new version's values
