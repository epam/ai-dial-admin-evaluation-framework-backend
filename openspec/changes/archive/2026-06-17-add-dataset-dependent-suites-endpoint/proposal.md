## Why

Test suites bind to a dataset via `test_suites.dataset_id`, so a dataset can have any number of dependent suites. Today there is no read endpoint to ask "which test suites depend on this dataset?" — the relationship is only reachable indirectly (e.g., by scanning suites). Clients and the admin UI need a direct way to inspect a dataset's dependent suites. This change exposes them via a lightweight read endpoint.

## What Changes

- Add `GET /api/v1/datasets/{datasetId}/test-suites` returning the test suites bound to the dataset (suites whose `dataset_id` equals the path id).
- Response is a **lightweight summary list** — each item is `{ id, name, description }` — not the full `TestSuiteResponseDto`. Empty array means the dataset has no dependent suites.
- Non-paginated: the dependent set is small and bounded (a PRIVATE dataset binds to at most one suite; PUBLIC datasets bind to a handful). Mirrors the existing internal `TestSuiteService.getReferencingDataset` usage.
- Returns HTTP 404 (`NOT_FOUND`) when the dataset does not exist.
- Backed by a **selective-column projection** query (id, name, description only) to avoid TOAST decompression of the suite's large JSONB columns.
- No DB schema change, no new config property, no breaking change.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `datasets`: add a requirement for a dataset-scoped endpoint that lists the test suites depending on (bound to) the dataset, returning a lightweight `{ id, name, description }` summary.

## Impact

- **API**: new read endpoint `GET /api/v1/datasets/{datasetId}/test-suites` under the existing "Datasets" tag; OpenAPI annotations + example. No query-param customizer entry (non-paginated, no filter/sort).
- **Code** (all following existing patterns; no new packages):
  - `web.controller.DatasetController` — new `@GetMapping("/{id}/test-suites")`.
  - `service.domain.DatasetService` — new read method delegating to `TestSuiteService` (cross-domain read via the owning service, per best-practices spec; `DatasetService` already injects `TestSuiteService`).
  - `service.domain.TestSuiteService` — new method returning the lightweight summaries.
  - `data.db.repository.TestSuiteRepository` + `PostgresTestSuiteRepository` — new selective-projection query by `dataset_id`.
  - New DTO `service.domain.dto.DatasetDependentSuiteDto` (`{ id, name, description }`).
  - New domain projection `data.db.model.TestSuiteSummary` (pure carrier; keeps DTOs out of the repository layer).
- **Tests**: functional tests under `functional/tests` (returns bound suites; empty list when none; 404 for unknown dataset).
- **Docs**: no `docs/database-schema.md` or `docs/configuration.md` change (no schema/config change). OpenAPI example added per openapi-examples spec.
- **Dependencies / security**: none changed.
