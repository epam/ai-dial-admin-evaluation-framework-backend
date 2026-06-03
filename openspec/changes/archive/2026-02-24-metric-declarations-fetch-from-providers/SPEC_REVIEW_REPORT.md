# Spec Review: metric-declarations-fetch-from-providers

## Review Cycle 1 — Fixes

### Major fix: MetricDeclaration upsert semantics

**Issue:** The spec did not state what happens to an existing MetricDeclaration when sync updates it (same provider_id, name). An implementer could overwrite `id`, `provider_id`, `name`, or `created_at_ms` on every sync, breaking stable identity and creation-time semantics.

**Changes:**

- **specs/metric-provider-sync/spec.md** — In the requirement "Sync upserts declarations and versions per provider", added: "On insert of a new MetricDeclaration: set id, provider_id, name, description, created_at_ms. On update of an existing declaration: only description SHALL be updated; id, provider_id, name, and created_at_ms SHALL be preserved."
- **design.md** — In Decision 5 (Sync job transaction and failure handling), added: "When updating an existing MetricDeclaration (same provider_id, name), only description is updated; id, provider_id, name, and created_at_ms are preserved." Rationale extended to mention preserving created_at_ms for auditing.

---

## Review Cycle 2 — Fixes

### Major fix 1: providerId filter — use existing generic filter infrastructure

**Issue:** The metrics-system delta spec and tasks described adding a dedicated `?providerId=<id>` query parameter to the list endpoint. However, the codebase already has a mature generic filter infrastructure (`FilterWhitelists`, `WhereBuilder`, `FilterParser`, `filter=<field>:<op>:<value>` repeatable params) used consistently across all entities. A dedicated parameter would: (1) create two ways to filter the same field, (2) break API consistency, (3) require extra controller/service code to merge with generic filters.

**Changes:**

- **specs/metrics-system/spec.md** — Updated "Scenario: Optional filter by provider_id" to use the generic filter mechanism: `GET /api/v1/metric-declarations?filter=providerId:eq:<id>` instead of a dedicated `?providerId=<id>` parameter.
- **tasks.md** — Updated tasks 2.3, 6.1, 6.3 to add `providerId` to `FilterWhitelists.METRIC_DECLARATIONS` (STRING, EQ operator, maps to `provider_id` column) instead of adding a dedicated query parameter.

### Major fix 2: JSON schema comparison must be structural, not textual

**Issue:** The spec said "insert new version when schemas differ" but did not specify how JSON comparison should work. Textual comparison (`String.equals`) would create spurious versions when providers change JSON key ordering or whitespace without changing the logical schema. This would cause unnecessary version churn.

**Changes:**

- **specs/metric-provider-sync/spec.md** — In the upsert requirement, added: "Schema comparison SHALL be structural (e.g. comparing parsed JSON trees via Jackson JsonNode.equals), NOT textual, to avoid creating spurious versions due to key-ordering or whitespace differences in provider responses."
- **tasks.md** — Updated task 5.1 to specify "use structural JSON comparison (Jackson JsonNode.equals) for schema fields to avoid spurious versions from formatting differences."

---

## Latest iteration: non-critical findings (not fixed)

1. **ER model divergence on provider_id nullability**: The ER model (§3.3.1) describes `provider_id` as "Optional; null or sentinel for seeded/built-in declarations." This change makes it NOT NULL after deleting all seeded rows. Both are correct for their context (ER model is forward-looking; this change is specific). Consider updating the ER model after this change ships. Not blocking.

2. **No updated_at_ms on MetricDeclaration**: When sync updates a declaration's description, no timestamp tracks when the update happened. Consumers must check the latest version's `created_at_ms`. This is a deliberate simplicity choice — consistent with the existing model which has no `updated_at_ms`. Not blocking.

3. **Empty providers list when sync enabled**: The spec does not explicitly say that when sync is enabled but the providers list is empty, the job SHALL complete with no-op. Task 5.3 already requires wiring so the job runs only when "sync.enabled is true and providers list is non-empty", making behavior safe. Optional to add an explicit scenario. Not blocking.

---

## Verdict

**The change is ready for implementation.** All major issues have been fixed across two review cycles. Proposal, design, specs, and tasks are aligned. Migration order, upsert semantics, API contracts, JSON comparison strategy, and filter mechanism are specified sufficiently and consistently with existing codebase patterns.
