## Context

The metrics provider `GET /metrics` response has always included `display_name` alongside `name`, but `MetricsDescriptionDto` did not declare it, causing Jackson to silently discard it (`@JsonIgnoreProperties(ignoreUnknown = true)`). Consequently `metric_declarations` and `metric_declaration_versions` have no `display_name` column, and `MetricDeclarationResponseDto` / `MetricDeclarationVersionResponseDto` never expose it.

Current data flow:
```
Provider GET /metrics
  { name, display_name, description, config_schema, input_schema, output_schema }
           │                   ▲ silently dropped
           ▼
MetricsDescriptionDto
  { name, description, configSchema, inputSchema, outputSchema }
           │
           ▼
metric_declarations (id, provider_id, name, description, created_at_ms)
metric_declaration_versions (id, metric_declaration_id, schema_version,
                              config_schema, input_schema, output_schema,
                              description, created_at_ms)
```

Target state adds `displayName` at every level, following the exact same pattern used by `description` today.

## Goals / Non-Goals

**Goals:**
- Capture `display_name` from provider response and persist it in both tables
- Expose `displayName` in declaration and version response DTOs
- Version `displayName` changes alongside schema/description changes (consistent with how `description` is versioned)
- Atomic update of `description` + `display_name` on declaration (no partial-update anomaly)

**Non-Goals:**
- Filtering or sorting by `displayName` (not a filter/sort candidate at this time)
- Back-filling `display_name` for existing rows without re-running sync (sync handles it naturally on next run)
- Any change to the provider client beyond adding the field

## Decisions

### Decision 1: Version `displayName` the same way as `description`

`description` lives on both `metric_declarations` (latest value, fast access) and `metric_declaration_versions` (historical snapshot per version). `displayName` follows the same pattern:
- `metric_declarations.display_name` = current display name (updated whenever a new version is created)
- `metric_declaration_versions.display_name` = the display name at that version snapshot

**Alternative considered**: Store `displayName` only on the declaration (not versioned). Rejected because clients using a specific `MetricDeclarationVersion` for reproducibility would have no display label attached to that version record.

### Decision 2: Rename `updateDescription` → `updateMetadata`

`MetricDeclarationRepository.updateDescription(UUID, String)` updates one field. Adding a second mutable field (`display_name`) means either:
- A) Keep `updateDescription` and add `updateDisplayName` — two separate updates, two round-trips, no atomicity
- B) Rename to `updateMetadata(UUID id, String description, String displayName)` — single `UPDATE` covering both columns atomically

Option B is chosen. It is safer (no window between the two updates) and keeps the update method count low. Only `MetricProviderSyncService` calls this method, so the rename has no ripple effect beyond that class.

### Decision 3: `displayName` is nullable in DB; normalized to `null` (not `""`) when absent

`description` is normalized to `""` in `MetricProviderSyncService.normalizeDescription()` for historical reasons. `displayName` uses `null` instead — providers may legitimately omit it, and `null` vs empty has semantic meaning for a display label. The `differsFromLatest` check uses `sameString()` (null-safe equality), so the existing helper covers the null case correctly.

### Decision 4: Two separate Flyway migrations (one per table)

`metric_declarations` and `metric_declaration_versions` are independent tables. Adding `display_name TEXT` (nullable, no default needed because existing rows are pre-sync stubs or will be re-synced) to each table is done in two focused migrations:
- `V1.19__AddDisplayNameToMetricDeclarations.sql`
- `V1.20__AddDisplayNameToMetricDeclarationVersions.sql`

No data backfill migration is needed; the next sync pass naturally populates the column.

## Component interaction (after change)

```
MetricProviderSyncService.syncOne(providerId)
  └─ MetricProviderClient.getMetrics(providerId)
       └─ MetricsDescriptionDto { name, displayName, description,
                                    configSchema, inputSchema, outputSchema }
  └─ upsertDeclarationAndVersion(providerId, dto)
       ├─ [new] MetricDeclaration { ..., displayName }
       │     → MetricDeclarationRepository.save(declaration)   [INSERT with display_name]
       ├─ [new version trigger] differsFromLatest checks displayName too
       │     → MetricDeclarationVersionRepository.save(version) [INSERT with display_name]
       └─ [update existing] MetricDeclarationRepository.updateMetadata(id, description, displayName)
                             [UPDATE SET description=:d, display_name=:dn WHERE id=:id]

GET /api/v1/metric-declarations
  → MetricDeclarationResponseDto { id, providerId, name, displayName, description, createdAt }

GET /api/v1/metric-declarations/{id}/versions
  → MetricDeclarationVersionResponseDto { ..., displayName, ... }
```

## Risks / Trade-offs

- **Existing rows post-migration**: `display_name` is `NULL` until the sync job next runs. Clients must tolerate `null` in the response. The field is nullable in the DTO (`String displayName`) — no `@NotNull` annotation added.
- **Provider omits `display_name`**: If a provider's response lacks `display_name`, `MetricsDescriptionDto.displayName` will be `null`. The sync stores `null`, which is a valid state. A subsequent sync where `display_name` appears will trigger a new version (via `differsFromLatest`).

## Migration Plan

1. Deploy Flyway migrations — both `ALTER TABLE ... ADD COLUMN display_name TEXT` run automatically on next startup; no downtime required (column is nullable, no default constraint, no rewrite needed for small tables)
2. Existing application instances reading declarations before migration: not applicable (rolling deploy replaces)
3. After deploy, next scheduled sync populates `display_name` for all declarations
4. No rollback complexity — dropping a nullable column is safe if rollback is needed

## Open Questions

None — design is fully resolved.
