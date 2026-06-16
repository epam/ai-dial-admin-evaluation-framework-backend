## 1. Data access

- [x] 1.1 Add `existsByNameIgnoreCase(String name)` to `DatasetRepository` and implement in `PostgresDatasetRepository` using jOOQ `fetchExists` against `LOWER(name)` (matches the `uq_datasets_name` index). Add a unit/functional assertion that it is case-insensitive.

## 2. File storage

- [x] 2.1 Add `FileService.copyFilesBetweenDatasets(UUID sourceDatasetId, UUID targetDatasetId)` mirroring `copyFilesBetweenSuites` (uses `buildDatasetFolderPath`, best-effort skip+log, returns copied filenames). Done: method compiles and is covered by a functional test.

## 3. Dataset clone service

- [x] 3.1 Create `service.domain.DatasetCloneService` (`@Service @LogExecution`, constructor injection of `DatasetRepository`, `TestCaseRepository`, `FileService`, `RevalidationProperties`). Done: bean wires in the application context.
- [x] 3.2 Implement `deriveCloneName(String sourceName)`: base `"<name> (clone)"`, dedup suffix `(clone 2)`, `(clone 3)`, … via `existsByNameIgnoreCase`, truncated to the dataset name length limit. Add a constant for the name limit in the appropriate constants class (reuse existing if present).
- [x] 3.3 Implement `cloneRowAndTestCases(Dataset source, UUID newDatasetId, String newName, String createdBy, long timestamp)`: insert the cloned dataset via `datasetRepository.createWithId` (visibility PRIVATE; copy `testCaseSchema`/`valid`/`validationWarnings`); paginated copy of test cases via `findBatchByDatasetId` → `batchInsert` with new UUIDs, repointed `datasetId`, and `@ef/datasets/{source}/` → `@ef/datasets/{new}/` ref rewrite in `data`; return the old→new test-case id `Map<UUID,UUID>`. Joins the caller's transaction.
- [x] 3.4 Expose a thin pre-transaction file-copy entry (delegates to `FileService.copyFilesBetweenDatasets`) or call `FileService` directly from the orchestrator — keep file I/O out of the DB transaction.

## 4. Orchestration in TestSuiteCloneService

- [x] 4.1 Resolve the auto-clone decision: clone the dataset iff `dto.datasetId == null && source.datasetId != null && sourceDataset.visibility == PRIVATE` (read source dataset via the already-injected `DatasetRepository`).
- [x] 4.2 When auto-cloning: pre-generate `newDatasetId`, derive the clone name, copy dataset files before the transaction (alongside the existing `copyFilesBetweenSuites`), and override `entity.setDatasetId(newDatasetId)` on the mapped clone entity.
- [x] 4.3 Change `applySuiteValidation` to take a `schemaDatasetId` and resolve the schema from the **source** dataset id when auto-cloning (cloned dataset row does not exist pre-transaction), else from the resolved/override dataset id.
- [x] 4.4a Add a PUBLIC `TestSuiteMapper.remapDisabledIds(String json, Map<UUID, UUID> idMap)` that reuses the existing private `deserializeDisabledIds`/`serializeDisabledIds` round-trip (deserialize the stored JSON → map each id through `idMap`, dropping unmapped ids → re-serialize to the JSONB-ready string). Do NOT duplicate the serialization logic and do NOT add a UUID-list method to `JsonbMapper`.
- [x] 4.4b Inside `executeDbWrites`/the existing `transactionTemplate`: when auto-cloning, call `datasetCloneService.cloneRowAndTestCases(...)` first, then remap via `entity.setDisabledTestCaseIds(testSuiteMapper.remapDisabledIds(entity.getDisabledTestCaseIds(), idMap))` using the returned old→new id map, then proceed with the existing suite insert + TSMD copy.
- [x] 4.5 Extend the failure-cleanup `finally` to also call `fileService.deleteAllByDatasetId(newDatasetId)` when auto-cloning. Preserve the existing `DataIntegrityViolationException` → unique-name 409 handling.

## 5. API docs

- [x] 5.1 Update the `@Operation` description on the clone endpoint in `TestSuiteController` to note that cloning a PRIVATE-dataset suite (without `datasetId` override) also clones the dataset and its test cases.
- [x] 5.2 Forbid silent dataset rebind in `TestSuiteCloneService.clone`: when the source suite is bound to a PRIVATE dataset, a `datasetId` override that differs from the source's bound dataset id SHALL be rejected with HTTP 409 (`DatasetVisibilityRuleException` / `PRIVATE_DATASET_REBIND_FORBIDDEN`, matching the suite-update rebind rule); an omitted or equal `datasetId` clones the PRIVATE dataset. Preserves the invariant that a PRIVATE dataset is never orphaned. Update the clone `@Operation`/`@ApiResponse(409)` docs and the `test-suite-clone` delta spec accordingly.

## 6. Tests

- [x] 6.1 Unit test `DatasetCloneServiceTest`: name derivation + dedup, test-case id remap correctness, `@ef/datasets` ref rewrite, PRIVATE visibility on the cloned dataset.
- [x] 6.2 Functional tests in `TestSuiteCloneFunctionalTests` (use `createDataset(name, schema, DatasetVisibility.PRIVATE)`): clone of a PRIVATE-dataset suite creates a new PRIVATE dataset bound to the clone, source untouched, no 409; test cases copied with new UUIDs across a pagination boundary; `disabledTestCaseIds` remapped to new ids; dataset-scoped file copied + ref rewritten.
- [x] 6.3 Functional regression: PUBLIC dataset clone still shares (no new dataset row, no test-case rows) — existing `cloneInheritsDatasetIdByDefault` / `cloneCreatesNoTestCaseRows` stay green; explicit `datasetId` override still rebinds without cloning.
- [x] 6.4 Functional test for clone-name collision dedup (pre-existing `"<name> (clone)"`).
- [x] 6.5 Run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.DatasetCloneServiceTest"` and the clone functional suite (`PostgresFunctionalTests$TestSuiteCloneTests`); confirm green.

## 7. Quality & docs

- [x] 7.1 `./gradlew spotlessApply checkstyleMain checkstyleTest` clean, then `./gradlew build`.
- [x] 7.2 Update `docs/patterns/dataset-entity.md` (dataset clone semantics) and `docs/patterns/dial-file-storage.md` (new `copyFilesBetweenDatasets` op).
- [x] 7.3 Sync the modified specs (`test-suite-clone`, `dial-file-storage`, `datasets`) back to `openspec/specs/` on archive; update `openspec/specs/README.md` only if a summary becomes inaccurate.
