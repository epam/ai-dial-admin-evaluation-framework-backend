# Proposal: Metric Declarations Fetch from Providers

## Why

The evaluation framework needs to consume metric declarations from one or more external metric provider services (same GET /metrics contract) so that the catalog is populated and kept up to date without manual seeding. A flexible configuration for multiple providers and a scheduled sync job will allow operators to plug in different metric backends while the existing metric declaration API continues to serve the unified catalog.

## What Changes

- **MetricDeclaration identity**: Add `provider_id` (from config; required for all catalog entries). Enforce `UNIQUE(provider_id, name)` so the same metric name from different providers is distinct. Previously seeded (stub) records are removed so the catalog contains only metrics synced from configured providers.
- **MetricDeclaration description**: Keep `description` on the declaration as the current/latest value (denormalized from the latest version).
- **MetricDeclarationVersion**: Introduce a new entity and table: immutable version rows with `metric_declaration_id`, `schema_version`, `config_schema`, `input_schema`, `output_schema`, `description`, `created_at`. New version when any of these change. (implementation_version and implementation_ref are out of scope.)
- **Metric provider configuration**: Add a list of metric provider entries (e.g. `provider_id`, `base-url`, timeouts). No per-provider auth in scope; requests use the identity of the service.
- **Metric provider client**: HTTP client(s) calling GET {baseUrl}/metrics against each configured provider, mapping response to internal models.
- **Scheduled sync job**: Cron (or fixed-rate) job that, for each configured provider, fetches GET /metrics, upserts MetricDeclarations by (provider_id, name), and creates new MetricDeclarationVersions when schema or description change. No removal of declarations when a metric is missing from provider response (out of scope).
- **Existing API**: Continue to serve metric declarations via GET /api/v1/metric-declarations; optionally support filter by `provider_id` and expose latest version schemas for discovery. **BREAKING** if response shape or query params change in a backward-incompatible way (to be decided in specs).
- **Migrations**: New Flyway migration(s) for `metric_declarations` (add `provider_id`, adjust uniqueness) and new `metric_declaration_versions` table. Remove previously seeded records so that only provider-synced metrics are listed via the API.

## Capabilities

### New Capabilities

- **metric-provider-sync**: Configuration of multiple metric provider services (provider_id, base URL, timeouts), HTTP client(s) for GET /metrics, and a scheduled job that pulls metrics from each provider and upserts MetricDeclarations and MetricDeclarationVersions into the meta database. Covers sync policy (upsert + new version on schema/description change; no deletion of missing metrics) and failure handling (e.g. one provider failing does not block others).

### Modified Capabilities

- **metrics-system**: MetricDeclaration gains `provider_id` and uniqueness constraint (provider_id, name). MetricDeclarationVersion is introduced as a first-class persisted entity with config_schema, input_schema, output_schema, description, and schema_version. List metric declarations endpoint may support optional filter by provider_id and may expose latest version schemas. Previously seeded (stub) records are removed; catalog is populated exclusively by sync—only real metrics from configured providers are listed via the API.

## Impact

- **Database (meta)**: New column(s) on `metric_declarations`; new table `metric_declaration_versions`; new indexes/constraints. Migration removes previously seeded rows so the catalog contains only provider-synced metrics.
- **Data layer**: New/updated repositories and RowMappers for MetricDeclaration and MetricDeclarationVersion; meta transaction manager.
- **Service layer**: MetricDeclarationService (and any new sync service) updated for provider and version handling; mapping from provider API response to declarations and versions.
- **Client layer**: New metric provider HTTP client(s) and DTOs matching the external GET /metrics contract.
- **Configuration**: New properties for metric provider list and sync schedule (e.g. cron, enabled flag). Documented in `docs/configuration.md`.
- **API**: GET /api/v1/metric-declarations possibly extended (query params, response fields). OpenAPI and examples updated.
- **Documentation**: `docs/design/entity-relationship-model.md` already updated (D9–D12, §5.4.3); `docs/database-schema.md` to be updated when migrations are added.
