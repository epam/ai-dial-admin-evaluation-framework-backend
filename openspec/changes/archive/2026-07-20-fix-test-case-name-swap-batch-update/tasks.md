## 1. Batch PUT/PATCH path — two-phase write in the repository

- [x] 1.1 Add a failing functional test for a pairwise name swap via batch PATCH in `src/test/java/.../functional/tests/TestCaseBatchPatchFunctionalTests.java` (done: `shouldSwapNamesViaBatchPatch` expects HTTP 200 and swapped names; fails on current code with 409).
- [x] 1.2 Add `parkTestCaseNames(List<TestCase> testCases)` to the `TestCaseRepository` interface (`data.db.repository.TestCaseRepository`) — phase-1 park (done: interface method present with Javadoc).
- [x] 1.3 Implement `parkTestCaseNames` in `PostgresTestCaseRepository` as a single jOOQ `dsl.batch(...)` of per-row `UPDATE test_case_name = '__tc_batch_'+token+'_'+id` (token = one `UUID.randomUUID()` dashless per call), scoped by `id AND dataset_id` (done: sets only `test_case_name`, no-op on empty list).
- [x] 1.4 Make `PostgresTestCaseRepository.batchUpdate` two-phase: call `parkTestCaseNames(testCases)` before the existing apply batch, both on the same DSLContext/transaction (done: method parks then applies).
- [x] 1.5 Run the new test and the swap-via-PUT case; add `shouldSwapNamesViaBatchUpdate` (PUT) and `shouldApplyMultiWayRenameCycle` (3-way) functional tests (done: all pass, `./gradlew test --tests "*TestCaseBatchPatchFunctionalTests*"`).
- [x] 1.6 Confirm existing 409/rollback tests remain green: `shouldReturn409ForDuplicateNamesWithinBatch`, `shouldReturn409ForNameCollisionWithExisting`, `shouldReturn409WhenRenamingToUnchangedBatchItemName`, `shouldRollBackAllChangesOnFailure` (done: pass unchanged).

## 2. Composite bulk patch — swap-safe itemOperations

- [x] 2.1 Add a failing functional test for a name swap via `itemOperations` in `src/test/java/.../functional/tests/TestCaseBulkPatchFunctionalTests.java` (done: `shouldSwapNamesViaItemOperations` expects HTTP 200 and swapped names; fails on current code).
- [x] 2.2 Restructure `TestCaseService.bulkPatch` item-operations loop into prepare → park → apply within the existing transaction: prepare (fetch/snapshot/merge-patch/validate, no writes), then `parkTestCaseNames(renamedSubset)`, then per-item `repo.update(...)` (done: preserves `itemResults`/`changed` semantics and the per-item `DataIntegrityViolationException → 409` catch; keeps post-pass `validateBatchNameUniqueness`).
- [x] 2.3 Run the new test plus existing bulk-patch tests (done: all pass, `./gradlew test --tests "*TestCaseBulkPatchFunctionalTests*"`).

## 3. Verification & housekeeping

- [x] 3.1 Run `./gradlew spotlessApply` and `./gradlew checkstyleMain checkstyleTest` (done: no violations).
- [x] 3.2 Run the full test suite `./gradlew test` (done: green, including `LoggingConventionTest`, `LayeredArchitectureTest`, `JdbcTemplateFenceTest`).
- [x] 3.3 Sync the `test-cases` delta into `openspec/specs/test-cases/spec.md` at archive time (done: main spec carries the "Batch name permutation within a single operation succeeds" requirement).
