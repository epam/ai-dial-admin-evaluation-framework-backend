## Context

Test suites bind to a dataset via `test_suites.dataset_id` (nullable FK to `datasets.id`), so a dataset can have multiple dependent suites. There is currently no read endpoint to list the suites that depend on a given dataset.

The data access and service plumbing already exist:
- `TestSuiteRepository.findSuitesReferencingDataset(UUID)` / `PostgresTestSuiteRepository` — `SELECT * FROM test_suites WHERE dataset_id = ?`.
- `TestSuiteService.getReferencingDataset(UUID)` — maps the above to `List<TestSuiteResponseDto>`.
- `DatasetService` already injects `TestSuiteService` (cross-domain reads go through the owning service per the best-practices spec).

This change adds a standalone lightweight read endpoint that lists a dataset's dependent suites. It is a single feature spanning controller → service → repository with no schema, config, security, or dependency changes; this design exists mainly to lock in the response-shape and query-projection decisions before coding.

## Goals / Non-Goals

**Goals:**
- Add `GET /api/v1/datasets/{datasetId}/test-suites` returning the suites bound to a dataset.
- Return a minimal `{ id, name, description }` summary per suite — enough to answer "which suites depend on this dataset?" and identify each one, without the cost of the full suite DTO.
- Return HTTP 404 when the dataset does not exist.
- Keep the query cheap (avoid TOAST decompression of the suite's large JSONB columns).
- Follow existing layering: controller → `DatasetService` → `TestSuiteService` → `TestSuiteRepository`.

**Non-Goals:**
- No pagination, filtering, or sorting (dependent set is small and bounded; no `OpenApiQueryParamCustomizer` entry, no `FilterWhitelists`/`SortWhitelists` change).
- No change to any existing endpoint or its behavior.
- No aggregation of other dependent kinds (runs, snapshots) — only test suites.
- No new `datasetId` filter on the existing `/api/v1/test-suites` list endpoint.

## Decisions

### D1 — Path: sub-resource `GET /api/v1/datasets/{datasetId}/test-suites`
Mounted on `DatasetController`, mirroring the existing `/api/v1/datasets/{datasetId}/test-cases` and `/{id}/revalidation-tasks` sub-resources. Reads naturally as "the suites of this dataset."
- *Alternative considered:* `/api/v1/test-suites?datasetId=...` on `TestSuiteController`. Rejected — it would force the paginated list path plus a new `datasetId` filter whitelist entry, more surface for a simple dependency check.
- *Alternative considered:* path `/dependents`. Rejected — the response is a homogeneous, well-typed suite list; `test-suites` names the resource and stays consistent with the sibling sub-resource. "Dependency check" intent lives in the OpenAPI description.

### D2 — Response shape: lightweight `List<DatasetDependentSuiteDto>` = `{ id, name, description }`
The endpoint answers "which suites depend on this dataset?"; the full ~20-field `TestSuiteResponseDto` is unnecessary weight. `{ id, name, description }` lets a UI list and identify each dependent suite with context.
- *Alternative considered:* count/boolean summary `{ hasDependents, count }`. Rejected — loses suite identity, forcing a second call to name the dependents.
- *Alternative considered:* full `TestSuiteResponseDto[]`. Rejected — heaviest; no consumer needs full suite details here.

### D3 — Non-paginated plain `List<T>` response
The dependent set is bounded (PRIVATE dataset → at most one suite; PUBLIC → a handful), matching the existing `getReferencingDataset` usage which already returns a plain list. A plain JSON array keeps the contract simple and avoids a `PageResponseDto` envelope that would imply navigation that does not exist.

### D4 — New lightweight query with selective column projection
Add `TestSuiteRepository.findSuiteSummariesReferencingDataset(UUID)` selecting only `ID, NAME, DESCRIPTION` (not `selectFrom(TEST_SUITES)`), per the Selective Column Projection pattern (`docs/patterns/selective-column-projection.md`). This avoids decompressing the suite's large JSONB/TOAST columns for a query that never reads them.
- *Alternative considered:* reuse `findSuitesReferencingDataset` and map down to the summary. Rejected — it fetches and maps every column (TOAST cost) only to discard most of it.

### D5 — New pure-carrier projection `data.db.model.TestSuiteSummary`
Repository returns `List<TestSuiteSummary>` (record of `id`, `name`, `description`); the service maps it to `DatasetDependentSuiteDto`. Keeps DTOs out of the data layer (layering rule) and keeps the projection a pure carrier.

### D6 — Cross-domain access via `DatasetService` → `TestSuiteService`
`DatasetController` calls `DatasetService.getDependentSuites(id)`, which performs the dataset existence check (reusing the existing not-found path, e.g. `getById`, to yield 404 `NOT_FOUND`) and delegates to a new `TestSuiteService.getDependentSuiteSummaries(id)`. `DatasetService` must not touch `TestSuiteRepository` directly; it already depends on `TestSuiteService`, so no new wiring. Read methods annotated `@Transactional(value = "metaTransactionManager", readOnly = true)`.

### D7 — Projection→DTO mapping location
Map `TestSuiteSummary` → `DatasetDependentSuiteDto` in `TestSuiteService` (or `TestSuiteMapper` for consistency with the existing mapper convention). Either is acceptable; keep it in an injectable/testable place, not a private static helper in the controller.

## Risks / Trade-offs

- **Information exposure** (any authenticated caller can list a dataset's bound suites, including for PRIVATE datasets) → Mitigation: consistent with existing behavior — `GET /api/v1/datasets/{id}` already returns PRIVATE datasets by id. Visibility is a catalogue-vs-scratch distinction, not access control (per `datasets` spec).
- **Summary omits fields a future consumer might want** → Mitigation: `DatasetDependentSuiteDto` is additive; new fields can be added without breaking the array contract. The full data remains available via the existing `/api/v1/test-suites` endpoints.
- **Duplicate-looking query alongside `findSuitesReferencingDataset`** → Mitigation: the two differ deliberately by projection (full row vs. id/name/description); the lightweight one is documented as the TOAST-avoiding variant. Acceptable per the selective-projection pattern already used elsewhere.
- **404-vs-empty ambiguity** → Resolved by D6: unknown dataset → 404; existing dataset with no bound suites → 200 with `[]`.

## Migration Plan

No DB migration, no jOOQ regeneration (no schema change), no config property. Pure additive code + tests. Deploy is a standard rollout; rollback is reverting the commit (the new endpoint is independent of existing paths and changes no existing behavior).

## Open Questions

None. Mapping location (D7) is an implementation-detail preference, not a blocker.
