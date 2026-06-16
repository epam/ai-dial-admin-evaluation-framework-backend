# Metric Provider Sync

## Purpose

This spec defines how the system configures multiple metric provider services, calls their GET /metrics API, and syncs metric declarations and versions into the meta database via a scheduled job that runs after startup.

## Key Terms

- **Metric provider**: External service exposing GET /metrics returning MetricsResponse (metrics: MetricsDescription[]).
- **Provider id**: Stable identifier for a provider from configuration (e.g. used for UNIQUE(provider_id, name) on MetricDeclaration).
- **Sync job**: Scheduled task that, for each configured provider, fetches metrics and upserts MetricDeclarations and MetricDeclarationVersions.

## Requirements

### Requirement: Configure metric providers

The system SHALL support configuration of multiple metric provider services via a **map** keyed by provider id (e.g. `metric-providers.<provider-id>.base-url`). Each entry SHALL include base URL and optional connect/read timeouts. The provider id SHALL be the map key (not a field inside the entry). Sync SHALL be configurable (e.g. enabled flag and cron expression or fixed delay) under `metric-providers.sync`. Defaults SHALL be defined in application configuration, not in code.

#### Scenario: Multiple providers configured

- **WHEN** application is configured with two or more metric provider map entries (distinct provider id keys and base URLs)
- **THEN** system SHALL use each entry when running the sync job, using the map key as the provider id

#### Scenario: Sync disabled

- **WHEN** sync is disabled via configuration
- **THEN** system SHALL NOT run the metric provider sync job

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

### Requirement: Scheduled sync runs after startup

The system SHALL run the metric provider sync job after application startup. Startup of the service SHALL NOT be blocked or delayed by the sync job (e.g. sync runs via scheduled cron or a delay after context is ready).

#### Scenario: Sync does not block startup

- **WHEN** the application starts
- **THEN** the service SHALL become ready to serve requests before the first sync run completes (if sync runs on a schedule or delayed)

#### Scenario: Sync executes on schedule

- **WHEN** sync is enabled and the configured schedule triggers (e.g. cron or fixed rate)
- **THEN** system SHALL run the sync job for all configured providers

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

### Requirement: One provider failure does not block others

When the sync job runs, failure of one provider (timeout, 4xx, 5xx, or invalid response) SHALL NOT prevent the job from processing the remaining configured providers. The system SHALL log the failure for the failed provider and SHALL continue with the next provider.

#### Scenario: Second provider succeeds after first fails

- **WHEN** sync runs with two providers configured and the first provider returns 503
- **THEN** system SHALL log the failure for the first provider and SHALL run sync for the second provider

#### Scenario: All providers fail

- **WHEN** sync runs and every configured provider fails
- **THEN** system SHALL log each failure and SHALL complete the job without throwing (no catalog updates for that run)

### Requirement: No removal of declarations when metric missing from response

The system SHALL NOT remove or delete a MetricDeclaration when a provider's GET /metrics response no longer includes that metric. Sync SHALL only add or update declarations and versions based on the current response; removal of declarations is out of scope.

#### Scenario: Provider stops returning a metric

- **WHEN** on a previous sync run a metric was present and on the current run the provider's response does not include that metric
- **THEN** system SHALL leave the existing MetricDeclaration and its versions unchanged (no delete)

### Requirement: Document environment variables for first provider and sync schedule

The configuration documentation SHALL list the environment variable names that override metric provider entries using the **map-based pattern** `METRIC_PROVIDERS_<UPPER_ID>_<PROPERTY>`. For the default `dial` provider, the documented variables SHALL include at least: base URL, and optional connect and read timeouts (`METRIC_PROVIDERS_DIAL_BASE_URL`, `METRIC_PROVIDERS_DIAL_CONNECT_TIMEOUT_MS`, `METRIC_PROVIDERS_DIAL_READ_TIMEOUT_MS`). The provider id SHALL be the YAML map key (`dial`), not configurable via an environment variable. The `metric-providers.sync.enabled` property SHALL be sourced from an environment variable (e.g. `METRIC_PROVIDERS_SYNC_ENABLED`, default `false`) and the `metric-providers.sync.cron` property from an environment variable (e.g. `METRIC_PROVIDERS_SYNC_CRON`); both SHALL be documented in the configuration documentation.

#### Scenario: Single provider configured via env vars

- **WHEN** an operator sets the documented environment variables for the `dial` provider (e.g. `METRIC_PROVIDERS_DIAL_BASE_URL`)
- **THEN** the application SHALL use those values for the `dial` provider when running the sync job (no YAML edit required)

#### Scenario: Env var names documented

- **WHEN** an operator reads the configuration documentation for metric providers
- **THEN** the documentation SHALL include the exact environment variable names using the map-based pattern (`METRIC_PROVIDERS_<UPPER_ID>_<PROPERTY>`) so they can override base-url and timeouts for any configured provider

#### Scenario: Sync cron from environment

- **WHEN** an operator sets the environment variable for the sync cron schedule (e.g. `METRIC_PROVIDERS_SYNC_CRON=0 */5 * * * *`)
- **THEN** the application SHALL use that value for `metric-providers.sync.cron` (no YAML edit required)
