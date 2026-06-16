## MODIFIED Requirements

### Requirement: Configure metric providers

The system SHALL support configuration of multiple metric provider services via a **map** keyed by provider id (e.g. `metric-providers.<provider-id>.base-url`). Each entry SHALL include base URL and optional connect/read timeouts. The provider id SHALL be the map key (not a field inside the entry). Sync SHALL be configurable (e.g. enabled flag and cron expression or fixed delay) under `metric-providers.sync`. Defaults SHALL be defined in application configuration, not in code.

#### Scenario: Multiple providers configured

- **WHEN** application is configured with two or more metric provider map entries (distinct provider id keys and base URLs)
- **THEN** system SHALL use each entry when running the sync job, using the map key as the provider id

#### Scenario: Sync disabled

- **WHEN** sync is disabled via configuration
- **THEN** system SHALL NOT run the metric provider sync job

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
