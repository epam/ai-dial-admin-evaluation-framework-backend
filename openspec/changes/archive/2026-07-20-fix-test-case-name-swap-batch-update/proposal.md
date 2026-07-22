## Why

Swapping the names of two test cases in a single batch update (e.g. `test44 → test33` and `test33 → test44`) fails with HTTP 409 "A test case name collision was detected during batch update", even though the final state has no duplicate names ([issue #95](https://github.com/epam/ai-dial-admin-evaluation-framework-backend/issues/95)). A name permutation is a legitimate operation and must succeed.

## What Changes

- Batch test-case updates SHALL accept any name **permutation** (swap, rotation, arbitrary cycle) as long as the *final* set of names is unique within the dataset. Genuine duplicates (final-state collisions) still return HTTP 409.
- Fix the root cause: the batch persists names via sequential per-row `UPDATE`s, and the non-deferrable unique index `(dataset_id, LOWER(test_case_name))` is checked after **each** statement — so an intermediate state momentarily holds two rows with the same name and is rejected, even though the committed end state is valid.
- Introduce a **two-phase write within the existing transaction**: phase 1 "parks" each row's name at a collision-proof temporary value; phase 2 applies the final names (and other fields). Both phases share the one open `@Transactional` connection, so atomicity/rollback is preserved and no schema change is needed.
- Apply the same fix to the per-item `bulkPatch` path (`itemOperations`), which has the same latent transient-collision bug for swaps.

## Capabilities

### New Capabilities
- _None._

### Modified Capabilities
- `test-cases`: the batch update / name-uniqueness requirement gains a scenario — a valid name permutation within a single batch SHALL succeed; only final-state duplicates return 409. Existing 409 behavior for genuine duplicates is unchanged.

## Impact

- **Code:**
  - `data.db.repository.PostgresTestCaseRepository` — new `parkTestCaseNames(List<TestCase>)` helper; `batchUpdate` becomes two-phase (park → apply).
  - `data.db.repository.TestCaseRepository` — add `parkTestCaseNames` to the interface.
  - `service.domain.TestCaseService` — `bulkPatch` item-operations loop restructured into prepare → park → apply passes; `batchUpdate`/`batchPatch` unchanged at the service layer (fix lives in the repo they already call via `persistBatch`).
- **APIs:** no contract change. `PUT`/`PATCH /api/v1/datasets/{datasetId}/test-cases` and the bulk endpoint keep their request/response shapes; a previously-failing swap now returns success.
- **DB / migrations:** none. No Flyway migration, no jOOQ regeneration, no config properties.
- **Tests:** new functional tests for name swap (PATCH + PUT) and a 3-way cycle; a swap via `bulkPatch` `itemOperations`. Existing 409 tests (`shouldReturn409ForDuplicateNamesWithinBatch`, `shouldReturn409ForNameCollisionWithExisting`, `shouldReturn409WhenRenamingToUnchangedBatchItemName`, `shouldRollBackAllChangesOnFailure`) remain green.
- **Docs:** none (no schema/config/API-surface change).
