# Design: First metric provider configuration

## Context

The application already supports `metric-providers.providers` (a list of provider entries with id, base-url, timeouts) and sync settings. Today `application.yml` sets `providers: []`. Operators need to configure a single metric provider for now, with values overridable via environment variables, without changing the config shape or Java code.

## Goals / Non-Goals

**Goals:**

- Add one entry to `metric-providers.providers` in application.yml so a single provider can be configured.
- Make that entry’s values driven by environment variables (Spring relaxed binding: `METRIC_PROVIDERS_PROVIDERS_0_*`).
- Document the first-provider env var names in docs/configuration.md.

**Non-Goals:**

- Introduce a separate “singular” config block; keep a single list with one entry.
- Change Java code (MetricProviderProperties, sync job, RestClient factory).
- Support or document multiple providers in this change.

## Decisions

1. **Use the existing list with one element**  
   No new property or type. Add a single element to `metric-providers.providers` in application.yml. Existing binding and validation apply.

2. **Env-driven via placeholders**  
   Use `${METRIC_PROVIDERS_PROVIDERS_0_ID:}`, `${METRIC_PROVIDERS_PROVIDERS_0_BASE_URL:}`, and optional `${METRIC_PROVIDERS_PROVIDERS_0_CONNECT_TIMEOUT_MS:5000}`, `${METRIC_PROVIDERS_PROVIDERS_0_READ_TIMEOUT_MS:30000}` so operators can override without editing YAML.

3. **Validation and startup**  
   `ProviderEntry` has `@NotBlank` on id and baseUrl. If the single entry is uncommented with empty defaults and env vars are not set, startup will fail validation. **Chosen approach:** add one entry with empty defaults; document that operators must set `METRIC_PROVIDERS_PROVIDERS_0_ID` and `METRIC_PROVIDERS_PROVIDERS_0_BASE_URL` when using the single provider (or leave the list empty by removing the entry for “no provider” runs). Alternative: keep `providers: []` and add a commented single-entry block plus docs so operators uncomment and set env vars; that avoids validation failure when env is unset.

4. **Docs only for config**  
   docs/configuration.md will list the four env vars for the first provider and note that they override the first list entry.

## Risks / Trade-offs

- **Empty id/base-url causes startup failure** — If the YAML entry is present with `${VAR:}` and env vars are unset, validation fails. Mitigation: document clearly; or keep list empty by default and provide a commented example.
- **No behavioral change** — Sync and RestClient already support one-element lists; this change only adds the YAML entry and documentation.
