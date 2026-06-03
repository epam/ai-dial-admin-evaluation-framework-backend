# Design: Metric Declarations Fetch from Providers

## Context

- **Current state**: The meta database has a `metric_declarations` table (id, name, description, created_at_ms) with seeded stub rows (e.g. Accuracy, Latency, Relevance). There is no version table, no provider concept, and no sync from external services. The list endpoint GET /api/v1/metric-declarations returns these stubs. The entity-relationship model (docs/design/entity-relationship-model.md) already describes MetricDeclarationVersion and provider identity (D9–D12, §5.4.3).
- **External contract**: All metric provider services expose the same API: GET /metrics returns `MetricsResponse { metrics: MetricsDescription[] }` where each `MetricsDescription` has name, description, config_schema, input_schema, output_schema (all required).
- **Constraints**: JDBC-only (meta DB), layered architecture (client → service → data), Flyway for meta migrations in `src/main/resources/db/migration/meta/POSTGRES/`. Sync runs with the application’s identity (no per-provider auth in scope).

## Goals / Non-Goals

**Goals:**

- Add `provider_id` to MetricDeclaration and enforce `UNIQUE(provider_id, name)`; remove previously seeded rows so the catalog lists only provider-synced metrics.
- Introduce `metric_declaration_versions` table and entity with schema_version, config_schema, input_schema, output_schema, description, created_at; create a new version when any of these change.
- Configure multiple metric providers (provider_id, base URL, timeouts) and run a scheduled job that calls GET /metrics per provider, upserting declarations and appending versions on change. One provider failure must not block others.
- Keep serving the catalog via GET /api/v1/metric-declarations (with optional provider_id filter and/or latest-version schemas as specified in specs).

**Non-Goals:**

- Removing declarations when a metric disappears from a provider response.
- Per-provider authentication (use service identity only).
- implementation_version and implementation_ref on MetricDeclarationVersion.
- Changing the external GET /metrics contract or supporting multiple provider API shapes.

## Decisions

### 1. Provider configuration shape

- **Choice**: List of provider entries under a single prefix (e.g. `metric-providers.providers` as a list of objects with `id`, `base-url`, `connect-timeout-ms`, `read-timeout-ms`). Sync enabled/cron under e.g. `metric-providers.sync.enabled` and `metric-providers.sync.cron`.
- **Rationale**: Matches need for multiple named providers; list order is stable for iteration; per-item timeouts allow tuning per provider. Alternative (map by id) was considered; list is clearer for “iterate and sync each” and aligns with existing DIAL config style (single logical client vs many providers).

### 2. HTTP client strategy for metric providers

- **Choice**: Build one `RestClient` per configured provider at startup (programmatic beans or a factory that returns a client per provider id), so each sync step uses a fixed base URL and timeouts. No user token propagation (sync is system-initiated).
- **Rationale**: Same pattern as DIAL Core (RestClient with baseUrl + timeouts); multiple providers imply multiple clients. Alternative: single RestClient with dynamic baseUrl per request—possible but pushes base URL into every call and complicates request interceptors if we add them later.

### 3. Migration order and seed removal

- **Choice**: Single migration (or two ordered migrations): (1) Create `metric_declaration_versions` table. (2) Add `provider_id` to `metric_declarations` (NOT NULL after backfill), add UNIQUE(provider_id, name), then DELETE all existing rows (seeded stubs). No separate “backfill” for old rows—they are removed so only sync-populated data remains.
- **Rationale**: Ensures the metric catalog (what the API returns) is populated only from configured metric provider services—no legacy seeded stubs—and avoids maintaining a “seeded” sentinel. Alternative (keep seeds with provider_id = 'system') was rejected per proposal.

### 4. schema_version semantics

- **Choice**: Integer per declaration, incremented when a new version row is inserted (e.g. next max+1 for that metric_declaration_id, or a sequence). Stored on each MetricDeclarationVersion row.
- **Rationale**: Simple, deterministic ordering for “latest version” and optional pinning. No separate implementation_version in scope.

### 5. Sync job transaction and failure handling

- **Choice**: One transaction per provider: fetch GET /metrics, then for that provider upsert declarations and insert new versions in a single meta transaction. If a provider fails (timeout, 4xx/5xx), log and continue to the next provider; do not fail the entire job. When updating an existing MetricDeclaration (same provider_id, name), only description is updated; id, provider_id, name, and created_at_ms are preserved.
- **Rationale**: Isolates one bad provider; keeps idempotent upserts per provider; preserving created_at_ms maintains stable creation time for auditing. Alternative (one big transaction for all providers) would roll back everything on first failure.

### 6. Where sync logic lives

- **Choice**: Dedicated service (e.g. `MetricProviderSyncService`) in `service.domain` that orchestrates “sync one provider” and “sync all”; a scheduled component (e.g. `@Scheduled` in a `MetricProviderSyncJob` or similar) invokes it. Client layer only does HTTP GET and DTO mapping; service layer performs repository calls and version comparison.
- **Rationale**: Keeps scheduling and orchestration in service layer; client stays a thin HTTP boundary. Aligns with existing job/orchestration patterns (e.g. revalidation, test run execution).

### 7. Sync timing vs startup

- **Choice**: Sync runs after startup (e.g. via scheduled cron or a fixed delay after application context is ready), not during startup. Service startup must not be blocked or delayed by the sync job.
- **Rationale**: Keeps startup fast and predictable; sync can be slow or fail without affecting availability of the API.

### 8. Latest version API

- **Choice**: Expose the latest MetricDeclarationVersion for a declaration via a dedicated endpoint GET /api/v1/metric-declarations/{id}/latest. Return 404 when the declaration does not exist or has no versions.
- **Rationale**: Keeps list response lean; clients that need full schema (config_schema, input_schema, output_schema) for a declaration can fetch the latest version by id.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| Empty catalog after migration if sync has not run or all providers fail | Document that operators must configure at least one provider and run sync (or run on startup once). API returns empty list until first successful sync. |
| Provider returns invalid JSON or schema mismatch | Client/service validate response shape; log and skip that provider for this run; do not persist malformed data. |
| Clock skew or version ordering across providers | schema_version is per-declaration only; no cross-provider ordering required. |
| Large response from GET /metrics | Apply reasonable read timeout and optional response size limit; consider streaming only if a spec requirement appears. |

## Migration Plan

1. **Flyway (meta)**  
   - Migration V1.x: Create `metric_declaration_versions` (id, metric_declaration_id, schema_version, config_schema, input_schema, output_schema, description, created_at_ms), with FK to metric_declarations and index on (metric_declaration_id, schema_version) for “latest version” lookups.  
   - Migration V1.y: Add `provider_id` to `metric_declarations` (e.g. VARCHAR(255) NOT NULL after backfill). Add UNIQUE(provider_id, name). Remove seeded rows: `DELETE FROM metric_declarations`. If provider_id is added as nullable first, backfill is unnecessary because we delete all rows; then alter to NOT NULL. Prefer: add column as NOT NULL with a temporary default for the delete step, then delete all rows (so no rows need default), then drop default if any. Simplest: add column NOT NULL with default 'legacy', DELETE FROM metric_declarations, then alter column drop default (or add column, delete, then add constraint so no default needed).

   Practical sequence: (a) Create `metric_declaration_versions`. (b) Add `provider_id` to `metric_declarations` (nullable or with default), run DELETE FROM metric_declarations, alter to NOT NULL if needed, add UNIQUE(provider_id, name). Order avoids FK issues (versions reference declarations; no versions exist yet when we delete declarations).

2. **Deployment**  
   - Deploy application with new migrations; ensure at least one metric provider is configured and sync is enabled (or run sync manually once). No rollback of data required if we only add table/column and delete seeds; rollback would require restoring seeds from backup if someone depended on them (out of scope).

3. **Rollback**  
   - Application rollback: revert to previous version; schema remains (new table and column). Old app will not use versions or provider_id; list may break if old code expects old schema. Prefer deploying with feature flag or sync disabled until providers are configured, then enabling.

## Open Questions

- Exact response shape of GET /api/v1/metric-declarations list (e.g. include minimal latest-version fields or keep list lean)—optional; list already supports optional providerId filter; full schemas available via GET /api/v1/metric-declarations/{id}/latest.