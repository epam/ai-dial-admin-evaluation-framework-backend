## 1. Wire up the TSMD-domain collaborator

- [x] 1.1 In `TestSuiteCloneService`, replace the working-tree `RevalidationService` field/constructor param with `TestSuiteMetricDefinitionService` (constructor injection).
- [x] 1.2 Remove the broken `validateSuite(newSuiteEntity, new)` call and the `.valid(tsmd.isValid())` working-tree edit so the file compiles from a clean baseline.

## 2. Compute the "TSMD revalidation required" decision

- [x] 2.1 In `clone(...)`, derive `tsmdRevalidationRequired = (dto.getDatasetId() != null && !dto.getDatasetId().equals(sourceDatasetId)) || dto.getResponseColumns() != null`.
- [x] 2.2 Resolve the dataset `testCaseSchema` JSON for the recompute branch from `datasetToValidateAgainst` (source dataset for private auto-clone; otherwise resolved/override dataset via `DatasetRepository`), and thread it plus `tsmdRevalidationRequired` into `executeDbWrites(...)`.

## 3. Conditional copy-vs-recompute in executeDbWrites

- [x] 3.1 In the TSMD copy loop, when revalidation is NOT required, set the cloned TSMD's `valid` and `validationWarnings` from the source TSMD (`tsmd.isValid()`, `tsmd.getValidationWarnings()`) instead of `false`/`"[]"`.
- [x] 3.2 When revalidation IS required, keep a deterministic placeholder validity on insert, then after the copy loop call `testSuiteMetricDefinitionService.revalidateAllForSuite(newId, datasetSchemaJson, newSuiteEntity.getResponseColumns())` inside the same `transactionTemplate.execute(...)` block.
- [x] 3.3 Confirm the recompute joins the clone transaction (REQUIRED propagation) and runs after the inserts; return appropriately from the transaction callback.

## 4. Tests

- [x] 4.1 Update `TestSuiteCloneServiceTest` for the two branches: verbatim copy when no override; `revalidateAllForSuite` invoked when `datasetId`/`responseColumns` overridden.
- [x] 4.2 Add/adjust `TestSuiteCloneFunctionalTests`: vanilla clone preserves source TSMD validity (valid stays valid, invalid stays invalid with warnings); private-dataset auto-clone copies verbatim.
- [x] 4.3 Add functional coverage for the recompute path: `datasetId` override with a differing schema flips a test-case-bound TSMD to invalid; `responseColumns` override removing a referenced column flips a response-bound TSMD to invalid.
- [x] 4.4 Run `./gradlew test --tests "*TestSuiteCloneServiceTest"` and the relevant `PostgresFunctionalTests` clone nested suite; confirm green.

## 5. Spec sync & docs

- [x] 5.1 Sync the modified "TSMD cloning" requirement into `openspec/specs/test-suite-clone/spec.md` (replace the `isValid SHALL be set to false` wording with the conditional copy-vs-recompute rule).
- [x] 5.2 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` and a clean build to verify formatting, context startup, and no regressions.
