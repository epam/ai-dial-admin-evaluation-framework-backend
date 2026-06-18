## Why

The deep-copy primitive `DatasetCloneService#cloneRowAndTestCases` (dataset row + all test cases with fresh ids and `@ef/datasets/{id}/` file-ref rewrites) already exists, but it is only reachable as a side effect of suite operations (`POST /testsuites/{id}/clone` and the detach flow). There is no way for a client to clone a dataset on its own. The archived change `2026-06-04-clone-private-dataset-with-suite` explicitly anticipated a standalone `POST /datasets/{id}/clone` endpoint reusing this primitive — this change delivers it.

## What Changes

- Add `POST /api/v1/datasets/{id}/clone` — deep-copies a dataset (row + all test cases) and returns the new dataset as `DatasetResponseDto` with HTTP 201 + ETag.
- The clone is **unbound** to any suite and **inherits the source dataset's visibility** (it is not forced to PRIVATE).
- Any source dataset (PUBLIC or PRIVATE) may be cloned.
- Optional `name` in the request body; when omitted, the name is auto-derived via the existing `deriveCloneName` (`"<name> (clone)"`, `"(clone 2)"`, … on collision).
- Optional `description` in the request body; when omitted, the source's description is copied verbatim.
- Thread `visibility` and `description` parameters through `DatasetCloneService#cloneRowAndTestCases` so the caller chooses the clone's visibility and description. The two existing callers (`TestSuiteService.detachDataset`, `TestSuiteCloneService`) pass `PRIVATE` and the source's description to preserve current behavior — **not breaking**.
- Add a new request DTO `DatasetCloneRequestDto`.

## Capabilities

### New Capabilities
- `dataset-clone`: Standalone dataset clone endpoint — request/response contract, visibility-inheritance and unbound-result rules, optional vs auto-derived naming, file-reference rewriting, and error semantics (404 source-not-found, 409 name conflict).

### Modified Capabilities
<!-- None. The signature change to cloneRowAndTestCases is behavior-preserving for the
     detach-dataset and test-suite-clone capabilities (both pass PRIVATE), so no spec-level
     requirement changes there. -->

## Impact

- **API**: new endpoint `POST /api/v1/datasets/{id}/clone` (no breaking changes to existing endpoints).
- **Code**:
  - `web/controller/DatasetController.java` — new `clone` handler + OpenAPI annotations.
  - `service/domain/DatasetService.java` — new `clone(...)` orchestration (pre-TX file copy → `TransactionTemplate` with timestamp binding → in-TX `cloneRowAndTestCases` → best-effort file cleanup on failure → unique-constraint → 409); inject `DatasetCloneService`, `Clock`, `FileService`, `@Qualifier("metaTransactionManager") PlatformTransactionManager` as needed.
  - `service/domain/DatasetCloneService.java` — add `String description` and `DatasetVisibility visibility` parameters to `cloneRowAndTestCases`.
  - `service/domain/TestSuiteService.java`, `service/domain/TestSuiteCloneService.java` — update both call sites to pass `DatasetVisibility.PRIVATE`.
  - New DTO `service/domain/dto/DatasetCloneRequestDto.java`.
- **DB / migrations**: none — no schema change, no Flyway migration, no jOOQ regen.
- **Config**: none — batching reuses the existing `revalidation.batch-size` property; no `docs/configuration.md` change.
- **Docs**: no `docs/database-schema.md` change; add OpenAPI examples per the `openapi-examples` spec.
- **Tests**: unit tests for `DatasetService.clone`; functional tests under `PostgresFunctionalTests`; existing detach / suite-clone tests must continue to pass (signature change is behavior-preserving).
