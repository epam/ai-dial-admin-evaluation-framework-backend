## Why

Cloning a test suite (`POST /api/v1/test-suites/{sourceId}/clone`) currently makes the clone **share** the source suite's `datasetId`. That is correct for **PUBLIC** datasets (shareable across many suites), but it is **broken for PRIVATE datasets**: a PRIVATE dataset may be bound to exactly one suite (enforced by the DB trigger `tg_test_suites_private_binding_guard`). Cloning a suite bound to a PRIVATE dataset therefore fails — the new suite's insert trips the trigger and the request dies with `PRIVATE_DATASET_ALREADY_BOUND` (SQLState `P0001`) → HTTP 409. The clone of a private-dataset suite is currently impossible.

## What Changes

- When the source suite is bound to a **PRIVATE** dataset **and** no explicit `datasetId` override is supplied, the clone SHALL also **clone the dataset**:
  - Create a new PRIVATE dataset named `"<source name> (clone)"`, appending a dedup suffix (`(clone 2)`, `(clone 3)`, …) on a name collision, respecting the `VARCHAR(263)` limit. The new dataset copies the source's `testCaseSchema`, `valid`, and `validationWarnings` verbatim.
  - Copy **all test cases** of the source dataset into the new dataset, each with a **new UUID** and `datasetId` repointed to the new dataset, in paginated batches.
  - Copy dataset-scoped DIAL files (`@ef/datasets/{src}/…` → `@ef/datasets/{new}/…`) and rewrite those references inside each cloned test case's `data`.
  - Bind the cloned suite to the new dataset and **remap** the suite's `disabledTestCaseIds` from old test-case ids to the new ids.
- **PUBLIC** datasets keep the current behavior (the clone shares the source's dataset; no copy).
- An explicit `datasetId` override keeps the current behavior (clone binds to the supplied dataset; no dataset clone), regardless of source visibility.
- The whole operation stays atomic: dataset row + test cases + suite + TSMDs commit in one transaction; DIAL file copies happen before the transaction and are cleaned up best-effort on failure.

## Capabilities

### New Capabilities
<!-- None — this extends existing clone behavior; no new spec folder. -->

### Modified Capabilities
- `test-suite-clone`: the "Clone a TestSuite" requirement gains conditional dataset cloning — a PRIVATE-dataset source (no `datasetId` override) now produces a brand-new PRIVATE dataset with copied test cases bound to the clone, instead of sharing. New scenarios for PRIVATE-source clone, test-case copy with new UUIDs, `disabledTestCaseIds` remap, dataset-file copy + ref rewrite, name-collision dedup, and the unchanged PUBLIC-share / explicit-override paths.
- `dial-file-storage`: add a service-level `copyFilesBetweenDatasets(sourceDatasetId, targetDatasetId)` operation (mirror of the existing `copyFilesBetweenSuites`), used by the dataset clone.
- `datasets`: document that a PRIVATE dataset can be created internally as a clone of another dataset (visibility PRIVATE, copied schema/validation state, copied test cases) as a side effect of suite cloning — no new public endpoint.

## Impact

- **Code**:
  - `service.domain.TestSuiteCloneService` — auto-clone decision (resolve source dataset visibility), validate suite against the **source** dataset schema pre-transaction, override the cloned suite's `datasetId`, remap `disabledTestCaseIds`, extend failure cleanup.
  - **New** `service.domain.DatasetCloneService` — narrow dataset-domain write surface (like `DatasetCascadeService`): inserts the cloned dataset row, batch-copies test cases (new UUIDs + `@ef/datasets` ref rewrite), derives the unique clone name, returns the old→new test-case id map. Depends only on `DatasetRepository`, `TestCaseRepository`, `FileService`, `RevalidationProperties`.
  - `service.domain.FileService` — new `copyFilesBetweenDatasets`.
  - `data.db.repository.DatasetRepository` (+ `PostgresDatasetRepository`) — new read `existsByNameIgnoreCase(String)` for collision-safe name derivation.
- **API**: no new endpoints, no request/response contract changes. Behavior of `POST /test-suites/{id}/clone` changes only for PRIVATE-dataset sources (previously 409, now succeeds with a cloned dataset). OpenAPI operation description for clone updated to note dataset cloning.
- **DB**: no schema change, no migration. Relies on existing `datasets`/`test_cases` tables, FK `ON DELETE CASCADE`, and the existing PRIVATE-binding trigger (which now passes because the clone binds a fresh dataset).
- **Config**: none.
- **Docs**: update `docs/patterns/dataset-entity.md` (clone semantics) and `docs/patterns/dial-file-storage.md` (new dataset copy op); `openspec/specs/README.md` only if a summary becomes inaccurate.
- **Tests**: new functional scenarios in `TestSuiteCloneFunctionalTests` using PRIVATE datasets; new `DatasetCloneServiceTest` unit test; existing PUBLIC-share and override regressions stay green.