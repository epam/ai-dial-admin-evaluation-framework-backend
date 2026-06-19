## Context

`DELETE /api/v1/datasets/{id}` today has two paths in `DatasetService.delete(...)`:
- **PRIVATE**: unbinds the single bound suite (`testSuiteService.unbindAllFromDataset(id)`) then `datasetCascadeService.deleteById(id)` — returns 204.
- **PUBLIC**: pre-checks `testSuiteService.getReferencingDataset(id)`; if any suite references it, throws a 409 (`datasetInUseException`), otherwise deletes.

gh-52 needs a way to delete a dataset and detach all its suites in one request. The building blocks already exist — `TestSuiteService.unbindAllFromDataset` / `TestSuiteRepository.unbindAllByDatasetId` unbind by `dataset_id` and the `BEFORE INSERT OR UPDATE OF dataset_id` trigger early-returns on `NULL`, so unbinding any number of suites is already supported. The only gap is an API switch and a service branch.

## Goals / Non-Goals

**Goals:**
- Add an opt-in `force` query parameter to the existing delete endpoint that unbinds **all** referencing suites and deletes the dataset atomically (204), for any binding count and any visibility.
- Keep the default (`force=false`) behavior byte-for-byte identical to today (409 RESTRICT for referenced PUBLIC; existing PRIVATE unbind-and-delete).
- Make the two already-written functional tests (`deleteWithForceUnbindsSingleSuite`, `deleteWithForceUnbindsTwoSuites`) pass.

**Non-Goals:**
- No new endpoint, no new error code, no DB schema/migration change.
- No change to the PRIVATE single-binding invariant or the binding trigger.
- No bulk/async deletion — this is a single synchronous transaction.

## Decisions

- **Query param over request body / new endpoint.** A `@RequestParam(defaultValue = "false") boolean force` on the existing `DELETE /{id}` keeps the contract minimal and backward-compatible (matches the existing `includeTotalCount` boolean-param convention in `DatasetController`). Alternatives considered: a dedicated `POST /{id}/force-delete` (heavier, redundant) or a request body on DELETE (awkward, poor client/proxy support).
- **Branch inside the existing transaction in `DatasetService.delete`.** When `force=true`, skip the `getReferencingDataset` pre-check/409 and instead call `testSuiteService.unbindAllFromDataset(id)` before `datasetCascadeService.deleteById(id)` — the same primitives the PRIVATE path already uses, so unbind-all + delete share one `TransactionTemplate` and remain atomic. The PRIVATE path is unaffected (it already unbinds all-by-dataset, which is ≤1 row). Alternative considered: a separate `forceDelete` service method — rejected to avoid duplicating the not-found/transaction/`schemaValidationService.invalidateSchemaCache`/`fileService.deleteAllByDatasetId` scaffolding.
- **Cross-domain access stays through `TestSuiteService`.** Unbinding goes via `testSuiteService.unbindAllFromDataset(id)`, never `testSuiteRepository` directly, per the best-practices layering rule.
- **OpenAPI updated in place.** Document `force` on the existing `@Operation`/`@Parameter` and note that `force=true` unbinds all referencing suites and returns 204.

## Risks / Trade-offs

- **[Force bypasses the RESTRICT safety net]** → It is strictly opt-in; default remains 409. The behavior is documented in OpenAPI and the spec so callers must consciously pass `force=true`.
- **[Unbinding many suites in one transaction could be large]** → In practice a dataset's referencing-suite count is small; the unbind is a single `UPDATE ... WHERE dataset_id = ?`. No batching needed. If this ever grows, it is a single set-based statement, not row-by-row.
- **[Trigger interaction]** → Setting `dataset_id := NULL` is explicitly allowed by the trigger's early-return on `NULL`, so unbinding is never blocked; the dataset row is deleted after all bindings are cleared.

## Migration Plan

Pure additive code change, no migration. Deploy is safe to roll forward/back: old clients never send `force`, so they keep the current behavior; rollback simply removes the param handling. No data migration, no Flyway change.

## Open Questions

- Flag name is `force` (matches the agreed contract and the existing tests). If product later prefers `unbind`, it is a one-line rename in the controller + the two tests.
