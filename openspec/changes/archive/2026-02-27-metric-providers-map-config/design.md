## Context

Metric providers are currently configured as a list under `metric-providers.providers`, with each entry containing `id`, `base-url`, and timeout fields. The id is bound via an index-based env variable (`METRIC_PROVIDERS_PROVIDERS_0_ID`). Other provider-style configurations in the application (e.g. `dial.components.core`, `postgres.meta`) use a map keyed by identifier. This change restructures `metric-providers` to use a map keyed by provider id, removes the `id` field from entries, and renames env variables from index-based to id-based.

**Affected components:**
- `MetricProviderProperties` — Java binding class (`@ConfigurationProperties(prefix = "metric-providers")`)
- `MetricProviderRestClientConfiguration` — builds `RestClient` per provider
- `MetricProviderSyncJob` — iterates providers
- `application.yml` — config shape
- `docs/configuration.md` — operator docs
- Functional test `@TestPropertySource` entries

## Goals / Non-Goals

**Goals:**
- Restructure `metric-providers` from a list with `id` field to a map keyed by provider id
- Rename env variables from `METRIC_PROVIDERS_PROVIDERS_0_*` to `METRIC_PROVIDERS_<ID>_*`
- Default single-provider id is `dial` (hardcoded in YAML, not env-driven)
- Keep sync settings (`metric-providers.sync.*`) and their env vars unchanged

**Non-Goals:**
- Adding new provider configuration fields (e.g. authentication, headers)
- Changing sync logic or MetricDeclaration/MetricDeclarationVersion persistence
- Backward-compatible support for the old list-based config shape

## Decisions

### Decision 1: Spring Boot map binding via `Map<String, ProviderEntry>`

**Choice:** Replace `List<ProviderEntry>` with `Map<String, ProviderEntry>` in `MetricProviderProperties`. Spring Boot `@ConfigurationProperties` natively supports binding YAML maps to `Map<String, T>` where the key becomes the provider id.

**Rationale:** This is the idiomatic Spring Boot approach for map-shaped config. Spring automatically binds `metric-providers.dial.base-url` to `Map<"dial", ProviderEntry{baseUrl=...}>`. No custom binder or converter needed.

**Alternative considered:** Keep the list and just rename env vars — rejected because it doesn't achieve the structural alignment goal and Spring Boot list env binding is inherently index-based.

### Decision 2: Remove `id` field from `ProviderEntry`

**Choice:** The `id` field is removed from `ProviderEntry` since the map key serves as the provider id. Code that needs the provider id will use the map key.

**Rationale:** Having `id` both as the map key and as a field would be redundant and could become inconsistent.

### Decision 3: Env variable pattern follows Spring Boot relaxed binding

**Choice:** Env variables follow `METRIC_PROVIDERS_<UPPER_ID>_<PROPERTY>` (e.g. `METRIC_PROVIDERS_DIAL_BASE_URL`). This is the natural Spring Boot relaxed binding for map keys (dots/hyphens → underscores, lowercase → uppercase).

**Rationale:** No custom code needed; Spring Boot handles this automatically when the YAML uses a map shape.

### Decision 4: Iteration in SyncJob and RestClientConfiguration

**Choice:** Replace `getProviders()` (List) with `getProviders().entrySet()` iteration. The map key is the provider id, the value is the `ProviderEntry`.

**Rationale:** Minimal code change; `MetricProviderRestClientConfiguration` already builds a `Map<String, RestClient>` internally — now it just gets the key from the map entry instead of `entry.getId()`.

## Risks / Trade-offs

- **[Breaking change for operators]** → Operators using `METRIC_PROVIDERS_PROVIDERS_0_*` env vars must switch to `METRIC_PROVIDERS_DIAL_*`. This is an intentional breaking change. Mitigation: document the migration clearly in `docs/configuration.md` and release notes.
- **[Provider id in YAML key means special characters are limited]** → YAML map keys should be simple kebab-case identifiers. Mitigation: provider ids should already be simple identifiers (no spaces, special chars). No additional validation needed beyond what Spring Boot imposes.
