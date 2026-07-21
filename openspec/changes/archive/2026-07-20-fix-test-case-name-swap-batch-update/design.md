## Context

Batch test-case updates persist names via a sequence of per-row `UPDATE` statements (`PostgresTestCaseRepository.batchUpdate`, run through jOOQ `dsl.batch(...).execute()`). The dataset-scoped case-insensitive uniqueness is enforced by a **non-deferrable functional unique index** `uq_test_cases_dataset_name ON test_cases (dataset_id, LOWER(test_case_name))` (`V1.22__IntroduceDataset.sql`). PostgreSQL checks a plain unique index immediately after **each** statement, so any intermediate state that momentarily holds two rows with the same lowercased name is rejected — even when the committed end state is valid.

The service already guards final-state uniqueness before writing: `TestCaseService.validateBatchNameUniqueness` rejects in-batch duplicates and collisions with rows outside the batch. A name **swap** (`A↔B`) passes this guard (final names are unique) but then fails at the DB during the sequential writes, surfacing as HTTP 409 "A test case name collision was detected during batch update" (`persistBatch`). This is [issue #95](https://github.com/epam/ai-dial-admin-evaluation-framework-backend/issues/95).

Constraints: JDBC-only (jOOQ DSLContext), layered architecture (SQL mechanics live in `data.db`), `VARCHAR(255)` name column, no `Instant.now()`/`Math.random()` in production code (UUIDs are fine).

## Goals / Non-Goals

**Goals:**
- A valid name permutation (swap, rotation, arbitrary cycle) within a single batch operation succeeds, as long as the final state has no duplicate names.
- Fix both affected write paths: batch PUT/PATCH (`persistBatch → repo.batchUpdate`) and composite bulk patch `itemOperations` (`TestCaseService.bulkPatch`).
- No schema change, no migration, no jOOQ regeneration, no config, no API contract change.
- Preserve atomicity: any genuine failure rolls back the whole transaction.

**Non-Goals:**
- Changing the uniqueness rule itself, the DB index, or making it deferrable.
- Changing the final-state 409 behavior for genuine duplicates.
- Touching `bulkOperations` (selector-scoped) — those cannot set `testCaseName` under the current empty whitelist.

## Decisions

### Decision 1: Two-phase in-transaction write (park → apply), not a deferrable constraint
Split the persist into two batches within the already-open `@Transactional("metaTransactionManager")` connection:
1. **Park** — one `UPDATE` per affected row setting **only** `test_case_name` to a collision-proof temporary value.
2. **Apply** — the existing batch setting final `test_case_name`, `data`, `is_valid`, `validation_warnings`, `updated_at`.

After phase 1, no row holds a name that any phase-2 statement will assign, so each phase-2 `UPDATE` sees a conflict-free table.

**Alternatives considered:**
- *Deferrable unique constraint + `SET CONSTRAINTS DEFERRED`.* Rejected: a functional (`LOWER(...)`) uniqueness cannot be a plain-column `UNIQUE` constraint, and only constraints (not `CREATE UNIQUE INDEX`) can be deferrable. It would require a generated lowercase column + migration + jOOQ regen — disproportionate for a P4 bug.
- *Reorder updates to avoid conflicts.* Rejected: cycles (e.g. `A↔B`) cannot be linearized into a conflict-free order; parking is the general solution.

### Decision 2: Temporary-name scheme
Temp name = `"__tc_batch_" + <token> + "_" + <rowId>`, where `<token>` is a single `UUID.randomUUID()` (dashes stripped) generated once per park call.
- Per-row `id` suffix → guaranteed unique among the parked rows (no intra-batch collision).
- Fresh random token → cannot match any pre-existing row's name (unguessable), so no collision with rows outside the batch.
- ~80 chars, well under `VARCHAR(255)`; it **replaces** the name (no append → no overflow).
- `UUID.randomUUID()` is permitted in production code (only time-source calls are banned; a `Clock` is unrelated here).

### Decision 3: Where each fix lives
- **Repo (`PostgresTestCaseRepository`)**: add `parkTestCaseNames(List<TestCase>)` (phase 1) and make `batchUpdate` call it before the existing apply batch. This covers batch PUT and PATCH because both service methods reach the DB through `persistBatch → repo.batchUpdate`. SQL mechanics stay in `data.db` per layering rules. Add `parkTestCaseNames` to the `TestCaseRepository` interface.
- **Service (`TestCaseService.bulkPatch`)**: the item-operations path writes per-row via `repo.update(...)` inside a loop, so there is no single batch to intercept. Restructure the loop into three passes within the same transaction:
  1. **Prepare (no writes):** fetch each item's existing row, snapshot `before`, `applyMergePatch`, `runValidation` when relevant, compute `changed`; collect the working entities.
  2. **Park:** call `repo.parkTestCaseNames(renamedSubset)` for items whose name actually changes.
  3. **Apply:** loop again calling `repo.update(existing)` with final values; keep the per-item `DataIntegrityViolationException → 409` catch.
  The existing post-pass `validateBatchNameUniqueness(renamed)` remains as a defense-in-depth guard.

### Decision 4: Reuse the existing final-state guard unchanged
`validateBatchNameUniqueness` continues to run before any write in the batch PUT/PATCH paths and is the authority that rejects genuine duplicates (returns 409 before the DB is touched). The two-phase write only removes the *transient* violation; it never weakens duplicate detection.

## Risks / Trade-offs

- **[Extra writes]** Parking doubles the number of `UPDATE`s for the name column on affected rows → Mitigation: batches are bounded by `test-case.batch.max-items` (default 256) and run on one connection in one transaction; negligible for realistic batch sizes.
- **[Temp name visible mid-transaction]** A concurrent reader could in principle observe a parked name → Mitigation: writes hold row locks and the whole thing commits atomically; under READ COMMITTED other transactions never see the uncommitted parked value. No external read path exposes it.
- **[`bulkPatch` restructure regression risk]** Splitting the loop changes control flow → Mitigation: preserve exact per-item result semantics (`itemResults`, `changed`) and the DIV→409 catch; cover with functional tests for swap plus the existing bulk-patch tests.
- **[Token collision]** Astronomically improbable that a real row is named `__tc_batch_<random-uuid>_<id>` → Mitigation: the per-call random UUID makes it unguessable and effectively impossible.

## Migration Plan

No DB migration or config change. Pure code change deployed with the application; rollback is a straight revert of the code (no schema/state to unwind). No data backfill.

## Open Questions

- None.
