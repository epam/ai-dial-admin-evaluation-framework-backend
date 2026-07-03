## 1. Service layer

- [x] 1.1 Add a `force` parameter to `DatasetService.delete(...)` (e.g. `delete(UUID id, boolean force)`); keep the existing transaction, `transactionTimestampContext.initializeIfAbsent()`, post-commit `schemaValidationService.invalidateSchemaCache(id)` and `fileService.deleteAllByDatasetId(id)` scaffolding intact.
- [x] 1.2 In the delete transaction, when `force == true`: skip the PUBLIC `getReferencingDataset`/409 pre-check and instead call `testSuiteService.unbindAllFromDataset(id)` then `datasetCascadeService.deleteById(id)` (unbind-all + delete in one transaction, any visibility, any binding count).
- [x] 1.3 When `force == false`: preserve the current branches exactly (PRIVATE unbind-and-delete; PUBLIC 409 RESTRICT pre-check + race re-check). Do not alter the not-found 404 behavior.
- [x] 1.4 Confirm unbinding routes only through `testSuiteService` (never `testSuiteRepository`) per the layering rule.

## 2. Web layer

- [x] 2.1 Add `@RequestParam(defaultValue = "false") boolean force` to `DatasetController.delete(...)` and pass it to `datasetService.delete(id, force)`.
- [x] 2.2 Update the endpoint's OpenAPI `@Operation` description and add a `@Parameter` for `force`, documenting that `force=true` unbinds all referencing suites and returns 204 while default stays 409 RESTRICT.

## 3. Tests & verification

- [x] 3.1 Run the already-written functional tests `DatasetCrudFunctionalTests.deleteWithForceUnbindsSingleSuite` and `deleteWithForceUnbindsTwoSuites` and confirm they now pass (204 + suites unbound).
- [x] 3.2 Confirm the existing default-path tests still pass: `deleteReturns204OnSuccess`, `deleteReturns409WhenSuiteReferencesDataset`, `deleteReturns404OnUnknownId`, and the PRIVATE delete test in `DatasetVisibilityFunctionalTests`.
- [x] 3.3 Run `./gradlew spotlessApply` and `./gradlew checkstyleMain checkstyleTest`; ensure the full `DatasetCrudTests` / `DatasetVisibilityTests` nested suites are green.
