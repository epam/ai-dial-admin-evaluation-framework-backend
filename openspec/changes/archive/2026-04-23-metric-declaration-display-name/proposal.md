## Why

The metrics provider GET /metrics response includes a `display_name` field that is currently silently dropped by `MetricsDescriptionDto` (via `@JsonIgnoreProperties`), so clients have no human-readable label for metric declarations beyond the technical `name` slug.

## What Changes

- `MetricsDescriptionDto`: add `displayName` field (SnakeCaseStrategy maps `display_name` automatically)
- Flyway migration: add `display_name TEXT` column to `metric_declarations` and `metric_declaration_versions`
- `MetricDeclaration` / `MetricDeclarationVersion` models: add `displayName` field
- `MetricDeclarationRowMapper` / `MetricDeclarationVersionRowMapper`: map `display_name` column
- `PostgresMetricDeclarationRepository`: update INSERT, SELECT, and update SQLs to include `display_name`
- `PostgresMetricDeclarationVersionRepository`: update INSERT and SELECT SQLs to include `display_name`
- `MetricProviderSyncService`: include `displayName` in `differsFromLatest`, `upsertDeclarationAndVersion`, and `saveVersion`; rename `updateDescription` to `updateMetadata` (updates both `description` and `display_name` atomically)
- `MetricDeclarationResponseDto` / `MetricDeclarationVersionResponseDto`: expose `displayName`
- `MetricDeclarationMapper` / `MetricDeclarationVersionMapper`: pass through `displayName`
- OpenAPI examples and `docs/database-schema.md` updated

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `metric-provider-sync`: Sync now captures `display_name` from provider response; `differsFromLatest` check includes `displayName`; declaration update covers `display_name` alongside `description`
- `metrics-system`: MetricDeclaration list and detail API responses include `displayName`; versioning tracks `displayName` alongside schemas and `description`

## Impact

- **DB schema**: two Flyway migrations (one per table) adding a nullable `display_name TEXT` column — backward-compatible; existing rows get `NULL` on first sync pass until next sync populates the value
- **API**: `MetricDeclarationResponseDto` and `MetricDeclarationVersionResponseDto` gain a new nullable `displayName` field — additive, non-breaking
- **Sync service**: `MetricDeclarationRepository.updateDescription` renamed to `updateMetadata`; callers (sync service only) updated accordingly
- **No config changes**, no new packages
