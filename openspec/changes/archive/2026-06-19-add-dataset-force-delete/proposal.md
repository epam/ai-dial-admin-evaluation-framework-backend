## Why

Deleting a dataset that is still referenced by one or more `TestSuite` rows currently fails: PUBLIC datasets return HTTP 409 (FK RESTRICT), and PRIVATE datasets are constrained by a DB trigger to at most one bound suite. A user who wants to remove a dataset and detach its suites in one step has no API path for it — they must first manually unbind every suite, then delete. This change (gh-52) adds an explicit, opt-in force delete that unbinds all referencing suites and removes the dataset in a single request, while leaving the safe default (409) untouched.

## What Changes

- Add an optional `force` query parameter to `DELETE /api/v1/datasets/{id}` (`@RequestParam(defaultValue = "false") boolean force`).
- When `force=false` (default, unchanged): preserve current behavior — PUBLIC datasets with referencing suites return **409**; PRIVATE datasets unbind their single suite and delete (existing behavior).
- When `force=true`: unbind **all** referencing suites (set `test_suites.dataset_id := NULL`) and delete the dataset (test cases cascade) in a single transaction, returning **204**. Works regardless of how many suites reference the dataset (verified for the 1-suite and 2-suite cases).
- `404` is still returned for an unknown dataset id regardless of `force`.
- Update OpenAPI annotations on the delete endpoint to document the `force` parameter and the unbind-on-force behavior.
- No DB schema change — reuses the existing `TestSuiteRepository.unbindAllByDatasetId(...)` / `TestSuiteService.unbindAllFromDataset(...)` and `DatasetCascadeService.deleteById(...)`.

Not breaking: the default request shape and response codes are unchanged; the new behavior is strictly opt-in.

## Capabilities

### New Capabilities
<!-- None — this modifies the existing dataset delete contract. -->

### Modified Capabilities
- `datasets`: The "Delete a dataset" requirement gains a `force=true` branch that unbinds all referencing suites (not just the single PRIVATE-bound one) and deletes the dataset, returning 204. The default (`force=false`) RESTRICT/unbind behavior is unchanged.

## Impact

- **API**: `DELETE /api/v1/datasets/{id}` gains an optional `force` boolean query param. Default behavior and all existing response codes unchanged.
- **Code**:
  - `web/controller/DatasetController.delete(...)` — add `force` param, pass through to service.
  - `service/domain/DatasetService.delete(...)` — branch on `force`; on `force=true` call `testSuiteService.unbindAllFromDataset(id)` before `datasetCascadeService.deleteById(id)` for the PUBLIC/referenced path instead of throwing the in-use 409. PRIVATE path already unbinds.
  - Reuses existing `TestSuiteService.unbindAllFromDataset` / `TestSuiteRepository.unbindAllByDatasetId`.
- **Tests**: Two functional tests already written in `DatasetCrudFunctionalTests` (`deleteWithForceUnbindsSingleSuite`, `deleteWithForceUnbindsTwoSuites`) assert 204 + suites unbound; they are the executable contract for this change. Existing `deleteReturns409WhenSuiteReferencesDataset` (no flag) remains valid.
- **Docs**: Update OpenAPI examples/annotations on the delete endpoint. No `docs/configuration.md` or `docs/database-schema.md` change (no config property, no migration).
- **Dependencies/Security**: None.
