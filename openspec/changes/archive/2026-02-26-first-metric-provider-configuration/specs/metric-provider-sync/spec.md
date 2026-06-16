# Metric Provider Sync (delta)

Delta for change **first-metric-provider-configuration**: document environment variables for the single-provider (first list entry) configuration.

## ADDED Requirements

### Requirement: Document environment variables for first provider

The configuration documentation SHALL list the environment variable names that override the first entry of `metric-providers.providers`, so operators can configure a single metric provider without editing YAML. The documented variables SHALL include at least: provider id, base URL, and optional connect and read timeouts (e.g. `METRIC_PROVIDERS_PROVIDERS_0_ID`, `METRIC_PROVIDERS_PROVIDERS_0_BASE_URL`, `METRIC_PROVIDERS_PROVIDERS_0_CONNECT_TIMEOUT_MS`, `METRIC_PROVIDERS_PROVIDERS_0_READ_TIMEOUT_MS`).

#### Scenario: Single provider configured via env vars

- **WHEN** an operator sets the documented environment variables for the first provider entry (id and base-url required)
- **THEN** the application SHALL use those values for the single provider when running the sync job (no YAML edit required)

#### Scenario: Env var names documented

- **WHEN** an operator reads the configuration documentation for metric providers
- **THEN** the documentation SHALL include the exact environment variable names for the first list entry (index 0) so they can override id, base-url, and timeouts
