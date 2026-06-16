# First metric provider configuration

## Why

Operators need to configure a single metric provider for the evaluation backend so that metric declarations can be synced from an external GET /metrics endpoint. The configuration should live in application.yaml and be overridable via environment variables (e.g. for deployment and twelve-factor style config). For now we scope to one provider only; additional providers are handled separately.

## What Changes

- Add **one entry** to the existing `metric-providers.providers` list in `application.yml`, with values driven by environment variable placeholders (e.g. `METRIC_PROVIDERS_PROVIDERS_0_ID`, `METRIC_PROVIDERS_PROVIDERS_0_BASE_URL`, and optional timeouts).
- Update **docs/configuration.md** to document the environment variables for the first (single) provider entry so operators can override without editing YAML.
- No new configuration shape (no separate “singular” block); no Java code changes. The existing list-based config and sync job are used as-is.

## Capabilities

### New Capabilities

None. This change only adds one list entry and documents env vars; it does not introduce a new capability or spec.

### Modified Capabilities

- **metric-provider-sync**: Document the environment variable names for the single-provider case (first list entry) in configuration docs. No requirement change to the spec itself—only documentation of how to configure one provider via env vars.

## Impact

- **application.yml**: `metric-providers.providers` gains one element with env-driven placeholders (or a commented example if empty defaults would fail validation).
- **docs/configuration.md**: New or updated subsection for “Metric providers” listing env vars for the first provider (e.g. `METRIC_PROVIDERS_PROVIDERS_0_ID`, `METRIC_PROVIDERS_PROVIDERS_0_BASE_URL`, `METRIC_PROVIDERS_PROVIDERS_0_CONNECT_TIMEOUT_MS`, `METRIC_PROVIDERS_PROVIDERS_0_READ_TIMEOUT_MS`).
- No impact on APIs, Java code, or database.
