# Proposal: Metric Providers Map Configuration

## Why

Metric providers configuration currently uses a list (`metric-providers.providers[0]`) and index-based environment variables (`METRIC_PROVIDERS_PROVIDERS_0_*`), which is inconsistent with other provider-style configuration in the application (e.g. `dial.components.core`, `postgres.meta`) that use a map keyed by identifier. Aligning the structure improves consistency and allows clearer, id-based env overrides: `METRIC_PROVIDERS_<ID>_BASE_URL` instead of index-based names.

## What Changes

- **Config shape:** Replace `metric-providers.providers` (list of entries with `id`, `base-url`, timeouts) with a **map** under `metric-providers` where the **provider id is the key**. Example:
  ```yaml
  metric-providers:
    dial:
      base-url: ${METRIC_PROVIDERS_DIAL_BASE_URL:http://localhost:8086}
      connect-timeout-ms: 5000
      read-timeout-ms: 150000
    # other-provider:
    #   base-url: ...
  ```
- **Default single provider:** Preserve the existing single-provider setup with provider id fixed to **`dial`** (no env variable for id). The one configured provider in `application.yml` is under the key `dial`.
- **Environment variable renaming (BREAKING):** Replace index-based env vars with id-based pattern:
  - **Old:** `METRIC_PROVIDERS_PROVIDERS_0_BASE_URL`, `METRIC_PROVIDERS_PROVIDERS_0_ID`, `METRIC_PROVIDERS_PROVIDERS_0_CONNECT_TIMEOUT_MS`, `METRIC_PROVIDERS_PROVIDERS_0_READ_TIMEOUT_MS`
  - **New pattern:** `METRIC_PROVIDERS_<ID>_BASE_URL`, `METRIC_PROVIDERS_<ID>_CONNECT_TIMEOUT_MS`, `METRIC_PROVIDERS_<ID>_READ_TIMEOUT_MS` (provider id is fixed in config, so no env for id). For the default `dial` provider: `METRIC_PROVIDERS_DIAL_BASE_URL`, `METRIC_PROVIDERS_DIAL_CONNECT_TIMEOUT_MS`, `METRIC_PROVIDERS_DIAL_READ_TIMEOUT_MS`.
- Sync settings (`metric-providers.sync.enabled`, `metric-providers.sync.cron`, `metric-providers.sync.fixed-delay-ms`) and their env vars (`METRIC_PROVIDERS_SYNC_ENABLED`, `METRIC_PROVIDERS_SYNC_CRON`) remain unchanged.
- **Java:** `MetricProviderProperties` (and any consumers) updated to bind a map keyed by provider id instead of a list. Backward compatibility with the old list shape is not required (breaking change).

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- **metric-provider-sync:** Configuration structure and environment variable naming. Requirements for config shape (map keyed by provider id), default provider id `dial`, and env var pattern `METRIC_PROVIDERS_<ID>_*` (e.g. `METRIC_PROVIDERS_DIAL_BASE_URL`) must be reflected in the spec and configuration documentation.

## Impact

- **Configuration:** `application.yml` (metric-providers section), `docs/configuration.md`.
- **Code:** `MetricProviderProperties`, `MetricProviderRestClientFactory`, `MetricProviderSyncJob`, and any code that reads or iterates over providers (must use map iteration instead of list).
- **Tests:** Functional tests and any test config that set `metric-providers.providers[0].*` or use `METRIC_PROVIDERS_PROVIDERS_0_*` env vars must be updated to the new map shape and `METRIC_PROVIDERS_DIAL_*` (or other id) env vars.
- **Operators:** Anyone using `METRIC_PROVIDERS_PROVIDERS_0_*` must switch to `METRIC_PROVIDERS_DIAL_*` (or the relevant provider id) for the default provider.
