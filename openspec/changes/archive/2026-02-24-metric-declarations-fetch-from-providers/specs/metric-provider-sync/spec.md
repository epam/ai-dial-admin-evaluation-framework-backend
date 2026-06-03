# Metric Provider Sync

## Purpose

This spec defines how the system configures multiple metric provider services, calls their GET /metrics API, and syncs metric declarations and versions into the meta database via a scheduled job that runs after startup.

## Key Terms

- **Metric provider**: External service exposing GET /metrics returning MetricsResponse (metrics: MetricsDescription[]).
- **Provider id**: Stable identifier for a provider from configuration (e.g. used for UNIQUE(provider_id, name) on MetricDeclaration).
- **Sync job**: Scheduled task that, for each configured provider, fetches metrics and upserts MetricDeclarations and MetricDeclarationVersions.

## ADDED Requirements

### Requirement: Configure metric providers

The system SHALL support configuration of multiple metric provider services via a list of provider entries. Each entry SHALL include provider id, base URL, and optional connect/read timeouts. Sync SHALL be configurable (e.g. enabled flag and cron expression or fixed delay). Defaults SHALL be defined in application configuration, not in code.

#### Scenario: Multiple providers configured

- **WHEN** application is configured with two or more metric provider entries (distinct provider ids and base URLs)
- **THEN** system SHALL use each entry when running the sync job

#### Scenario: Sync disabled

- **WHEN** sync is disabled via configuration
- **THEN** system SHALL NOT run the metric provider sync job

### Requirement: Metric provider HTTP client

The system SHALL provide an HTTP client that calls GET {baseUrl}/metrics for a given provider. The client SHALL map the response to the MetricsResponse contract (metrics array of name, description, config_schema, input_schema, output_schema). The client SHALL use the configured base URL and timeouts for that provider. Requests SHALL use the identity of the service (no per-provider authentication in scope).

#### Scenario: Successful fetch

- **WHEN** sync invokes the client for a configured provider and the provider returns HTTP 200 with valid MetricsResponse JSON
- **THEN** client SHALL return the parsed response for processing

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

For each configured provider, the sync job SHALL call GET /metrics, then for each metric in the response SHALL upsert a MetricDeclaration by (provider_id, name) and SHALL create a new MetricDeclarationVersion when the metric’s config_schema, input_schema, output_schema, or description differ from the current latest version. Schema comparison SHALL be structural (e.g. comparing parsed JSON trees via Jackson JsonNode.equals), NOT textual, to avoid creating spurious versions due to key-ordering or whitespace differences in provider responses. On insert of a new MetricDeclaration: set id, provider_id, name, description, created_at_ms. On update of an existing declaration: only description SHALL be updated; id, provider_id, name, and created_at_ms SHALL be preserved. MetricDeclaration.description SHALL always reflect the latest version’s description. Sync SHALL use a single transaction per provider (fetch then persist for that provider).

#### Scenario: New metric from provider

- **WHEN** sync runs for a provider and the response includes a metric name that does not yet exist for that provider_id
- **THEN** system SHALL insert a new MetricDeclaration (provider_id, name, description) and one MetricDeclarationVersion row

#### Scenario: Existing metric unchanged

- **WHEN** sync runs for a provider and a metric’s config_schema, input_schema, output_schema, and description match the latest version for that declaration
- **THEN** system SHALL NOT insert a new MetricDeclarationVersion

#### Scenario: Existing metric schema or description changed

- **WHEN** sync runs for a provider and a metric’s config_schema, input_schema, output_schema, or description differ from the latest version
- **THEN** system SHALL insert a new MetricDeclarationVersion and SHALL update MetricDeclaration.description to the new version’s description

### Requirement: One provider failure does not block others

When the sync job runs, failure of one provider (timeout, 4xx, 5xx, or invalid response) SHALL NOT prevent the job from processing the remaining configured providers. The system SHALL log the failure for the failed provider and SHALL continue with the next provider.

#### Scenario: Second provider succeeds after first fails

- **WHEN** sync runs with two providers configured and the first provider returns 503
- **THEN** system SHALL log the failure for the first provider and SHALL run sync for the second provider

#### Scenario: All providers fail

- **WHEN** sync runs and every configured provider fails
- **THEN** system SHALL log each failure and SHALL complete the job without throwing (no catalog updates for that run)

### Requirement: No removal of declarations when metric missing from response

The system SHALL NOT remove or delete a MetricDeclaration when a provider’s GET /metrics response no longer includes that metric. Sync SHALL only add or update declarations and versions based on the current response; removal of declarations is out of scope.

#### Scenario: Provider stops returning a metric

- **WHEN** on a previous sync run a metric was present and on the current run the provider’s response does not include that metric
- **THEN** system SHALL leave the existing MetricDeclaration and its versions unchanged (no delete)
