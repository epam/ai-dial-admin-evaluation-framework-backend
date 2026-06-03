## 1. Database Migrations

- [x] 1.1 Add `V1.19__AddDisplayNameToMetricDeclarations.sql` — `ALTER TABLE metric_declarations ADD COLUMN display_name TEXT` (nullable, no default)
- [x] 1.2 Add `V1.20__AddDisplayNameToMetricDeclarationVersions.sql` — `ALTER TABLE metric_declaration_versions ADD COLUMN display_name TEXT` (nullable, no default)

## 2. Data Layer — Models and RowMappers

- [x] 2.1 Add `displayName` field to `MetricDeclaration` model (`data.db.model`)
- [x] 2.2 Add `displayName` field to `MetricDeclarationVersion` model (`data.db.model`)
- [x] 2.3 Map `display_name` column in `MetricDeclarationRowMapper`
- [x] 2.4 Map `display_name` column in `MetricDeclarationVersionRowMapper`

## 3. Data Layer — Repositories

- [x] 3.1 Update `PostgresMetricDeclarationRepository`: add `display_name` to `INSERT_SQL`, all `SELECT_*` SQL constants; rename `updateDescription` → `updateMetadata(UUID, String description, String displayName)` with atomic UPDATE of both columns
- [x] 3.2 Update `MetricDeclarationRepository` interface: rename `updateDescription` → `updateMetadata(UUID id, String description, String displayName)`
- [x] 3.3 Update `PostgresMetricDeclarationVersionRepository`: add `display_name` to `INSERT_SQL` and all `SELECT_*` SQL constants; bind `displayName` parameter in `save()`

## 4. Client DTO

- [x] 4.1 Add `displayName` field to `MetricsDescriptionDto` (`client.metricprovider.dto`) — `@JsonNaming(SnakeCaseStrategy)` already present so `display_name` maps automatically; no serialization config needed

## 5. Sync Service

- [x] 5.1 Update `MetricProviderSyncService.upsertDeclarationAndVersion`: extract `displayName` from dto; include it in new `MetricDeclaration` on insert; pass to `saveVersion`; pass to `updateMetadata` on update
- [x] 5.2 Update `differsFromLatest`: include `displayName` comparison via `sameString(latest.getDisplayName(), displayName)` — returns `true` when they differ, triggering a new version
- [x] 5.3 Update `saveVersion`: add `displayName` to `MetricDeclarationVersion` builder

## 6. Service / API Layer

- [x] 6.1 Add `displayName` field to `MetricDeclarationResponseDto` with `@Schema(example = "Exact Match")`
- [x] 6.2 Add `displayName` field to `MetricDeclarationVersionResponseDto` with `@Schema(example = "Exact Match")`
- [x] 6.3 `MetricDeclarationMapper` (MapStruct): no code change needed — MapStruct auto-maps same-named fields; verify `displayName` is included in the generated mapping
- [x] 6.4 Update `MetricDeclarationVersionMapper.toDto`: add `.displayName(version.getDisplayName())` to builder

## 7. OpenAPI Examples and Docs

- [x] 7.1 Update OpenAPI example files under `src/main/resources/openapi/examples/` for metric declaration list and detail responses to include `displayName`
- [x] 7.2 Update `docs/database-schema.md`: add `display_name TEXT` column to `metric_declarations` and `metric_declaration_versions` table descriptions

## 8. Tests

- [x] 8.1 Update `MetricProviderSyncServiceTest` (unit): update the two existing `verify(metricDeclarationRepository).updateDescription(...)` calls to `verify(metricDeclarationRepository).updateMetadata(id, description, displayName)` (matching the renamed interface method from task 3.2); add assertions that `displayName` is synced on insert, triggers a new version when changed, and does not trigger a new version when unchanged
- [x] 8.2 Add/update functional tests for `GET /api/v1/metric-declarations` and `GET /api/v1/metric-declarations/{id}/latest` to assert `displayName` is returned in the response
- [x] 8.3 Run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.MetricProviderSyncServiceTest"` and ensure it passes
- [x] 8.4 Run the metric declaration functional test suite and ensure it passes

## 9. Spec Sync

- [x] 9.1 Update `openspec/specs/metric-provider-sync/spec.md` with delta from this change (delta sync)
- [x] 9.2 Update `openspec/specs/metrics-system/spec.md` with delta from this change (delta sync)
