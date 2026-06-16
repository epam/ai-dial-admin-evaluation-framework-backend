# Design: Rename MetricDefinition to MetricDeclaration

## Context

The entity-relationship model and metrics-system spec define **MetricDeclaration** as the catalog entity (what a metric is: inputs, outputs, configuration) and **TestSuiteMetricDefinition (TSMD)** as the application of a metric within a test suite. The current implementation uses "MetricDefinition" for the catalog entity across the stack: DB table `metric_definitions`, Java model/repo/service/controller/DTO/mapper, API path `/api/v1/metric-definitions`, and OpenAPI/docs. This naming contradicts the design and would clash when TSMD is introduced. The change is a full rename with no new behavior: same list and get-by-id semantics, same meta datasource and Flyway conventions.

## Goals / Non-Goals

**Goals:**

- Align all names (API, code, DB, docs, specs) with design: catalog entity = MetricDeclaration.
- Preserve existing behavior (pagination, filtering, sorting, response shape); only identifiers and labels change.
- Single deployable change: one Flyway migration plus code/docs/spec updates.

**Non-Goals:**

- No backward-compatibility layer (no alias path, no dual DTO names).
- No new endpoints or features.
- No change to TestSuiteMetricDefinition or metric versioning (future work).

## Decisions

### 1. Rename DB table via Flyway migration

**Decision:** Add a new meta migration (e.g. `V1.7__RenameMetricDefinitionsToMetricDeclarations.sql`) that runs `ALTER TABLE metric_definitions RENAME TO metric_declarations`.

**Rationale:** Keeps schema consistent with domain language and avoids a permanent mismatch between table name and Java type. Single migration is simple and reversible. Alternative considered: keep table name `metric_definitions` and only rename Java/API—rejected to avoid long-term inconsistency in schema docs and ER model.

### 2. Single change, no phased rollout

**Decision:** Rename API path, types, table, and docs in one change; no temporary dual paths or deprecated aliases.

**Rationale:** Simplifies implementation and avoids maintaining two names. Consumers are expected to update once; if needed, a short-lived redirect can be added in a follow-up. Alternative: add deprecated `GET /api/v1/metric-definitions` redirect—deferred unless explicitly requested.

### 3. OpenAPI example filenames follow pathKey

**Decision:** Rename example JSON files from `api-v1-metric-definitions-*` to `api-v1-metric-declarations-*` so they match the pathKey derived from the new path by `OpenApiExampleCustomizer`.

**Rationale:** Convention is pathKey-method-type-name.json; pathKey comes from the controller path. No code change in the customizer needed.

### 4. Spec updates as delta text in change specs

**Decision:** Modified capabilities (metrics-system, entity-filtering, test-cases) are updated via delta specs under this change; after archive, sync wording/URLs back to main specs per project delta-sync policy.

**Rationale:** Keeps main specs as source of truth while capturing this change’s requirement-level edits in one place.

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| **Breaking API** — clients using `/api/v1/metric-definitions` or old DTO names fail | Communicate breaking change; version/release notes; optionally add a temporary redirect in a follow-up if needed. |
| **Rollback** — reverting code leaves table as `metric_declarations` | Rollback migration would need to rename table back to `metric_definitions`; document in runbook or keep rollback migration script if rollback is required. |
| **Missed references** — stray "MetricDefinition" in comments or docs | Grep for "MetricDefinition" and "metric-definition" / "metric_definition" during implementation; checkstyle and tests catch Java references. |

## Migration Plan

1. **Implement:** Apply all renames (Java, API path, OpenAPI examples, FilterWhitelists/SortWhitelists, tests, docs, delta specs). Add Flyway migration that renames `metric_definitions` → `metric_declarations`.
2. **Deploy:** Standard deploy; Flyway runs the new migration on startup. No data copy—only table rename.
3. **Rollback (if needed):** Revert application version; add and run a rollback migration that renames `metric_declarations` back to `metric_definitions` so reverted code finds the old table name. No rollback migration is included in this change by default.

## Open Questions

- None blocking. Optional: whether to add a deprecated `GET /api/v1/metric-definitions` → redirect or 301 to new path for a limited period (can be decided per consumer needs).
