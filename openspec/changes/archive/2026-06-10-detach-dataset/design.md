## Context

A test suite can be bound to a PUBLIC dataset — one visible in the catalogue and shareable across suites. When a suite owner wants to iterate on test cases independently (add cases, change schema) without affecting other consumers of that dataset, there is no way to fork it atomically today. The "detach" operation fills this gap by forking the suite's bound PUBLIC dataset into a new PRIVATE clone and rebinding the suite in a single request.

The change also refactors `DatasetCloneService.cloneRowAndTestCases()` to accept an explicit `name` parameter. Currently the method hard-codes name derivation via `deriveCloneName()` internally. Making the name explicit is a prerequisite for the detach endpoint (which should accept an optional user-provided name) and a general improvement that will allow a future `POST /datasets/{id}/clone` endpoint to use the same primitive.

**Current state:**
- `DatasetCloneService.cloneRowAndTestCases(Dataset, UUID, String createdBy, long)` derives the clone name internally.
- `TestSuiteCloneService.executeDbWrites()` calls it when auto-cloning a PRIVATE dataset alongside a suite clone.
- No endpoint exists to fork a PUBLIC dataset into a PRIVATE one and rebind the suite in-place.

**Constraints:**
- No Flyway migrations — no schema changes required.
- No new packages — all new classes fit into existing packages.
- DIAL file I/O is not transactional and must happen before the meta DB transaction.
- The schema is copied verbatim, so TSMDs remain valid — no async revalidation task is needed.

## Goals / Non-Goals

**Goals:**
- Refactor `DatasetCloneService.cloneRowAndTestCases()` to accept an explicit `name` parameter (before `createdBy`) with no behavior change for existing callers.
- Add `POST /api/v1/test-suites/{id}/detach-dataset` that forks the suite's PUBLIC dataset into a PRIVATE clone, rebinds the suite, and returns the updated `TestSuiteResponseDto`.
- Provide an optional `name` field in the request body; when absent, derive the name via `deriveCloneName`.
- Add functional tests covering the full success path, custom name, derived name, PRIVATE-bound 409, unbound 409, and 404.

**Non-Goals:**
- Detaching from a PRIVATE dataset (already exclusive — no fork needed; this is a 409).
- A general-purpose `POST /datasets/{id}/clone` endpoint (this change makes `cloneRowAndTestCases` ready for it, but does not add it).
- Async revalidation — schema is copied verbatim, so TSMDs stay valid.
- Updating `disabledTestCaseIds` beyond remapping old→new test-case ids (same logic as suite clone).

## Decisions

### D1 — `name` parameter inserted before `createdBy` in `cloneRowAndTestCases`

**Decision:** Add `String name` as the third parameter, shifting `String createdBy` to position four and `long timestamp` to position five.

**Rationale:** The parameter order matches the conceptual ownership hierarchy — `name` is a property of the clone artifact, `createdBy` is the actor performing the write, `timestamp` is the write time. Inserting before `createdBy` keeps `createdBy` and `timestamp` as a natural trailing pair (authorship context), matching the conventions in other repository/service method signatures in this codebase.

**Alternative considered:** Overloading the method with a name-less variant that calls `deriveCloneName` internally. Rejected — two methods with subtly different behavior add confusion; explicit parameter is cleaner and aligns with the AGENTS.md guideline to prefer descriptive parameters over hidden side effects.

### D2 — `TestSuiteService.detachDataset()` as the orchestrating entry point

**Decision:** Add `detachDataset()` to `TestSuiteService` (not a new `DatasetDetachService`).

**Rationale:** The operation is semantically a mutation of the test suite — it changes the suite's `datasetId`. `TestSuiteService` already owns suite mutation methods; it gains `DatasetCloneService` via constructor injection. This respects the cross-domain rule: `TestSuiteService` calls `DatasetCloneService` (the dataset-domain's narrow write surface) and reads the source dataset through `DatasetQueryService.findById` rather than injecting `DatasetRepository` or writing dataset rows directly.

**Alternative considered:** A dedicated `DatasetDetachService`. Rejected — the operation is not semantically "about a dataset"; it is about severing a suite's dependency on a shared resource. Adding a new service class for a single method that simply orchestrates two existing services would be premature.

### D3 — Orchestration pattern: pre-TX file copy → programmatic `TransactionTemplate` for DB writes

**Decision:** Follow the same pattern used by `TestSuiteCloneService.clone()`:
1. Pre-TX: `datasetCloneService.copyDatasetFiles(sourceId, newDatasetId)` — non-transactional DIAL file I/O.
2. In-TX (via `TransactionTemplate` with `metaTransactionManager`): `cloneRowAndTestCases(...)` + remap `disabledTestCaseIds` + update suite's `datasetId` via `testSuiteRepository`.
3. On failure: best-effort `fileService.deleteAllByDatasetId(newDatasetId)` in `finally`.

**Rationale:** `TestSuiteService` is already `@Transactional("metaTransactionManager")` at the class/method level for most operations, but the pre-TX file copy must happen outside any transaction. Using a `TransactionTemplate` (with the manager injected via `@Qualifier("metaTransactionManager")`) gives programmatic control of the TX boundary without polluting the surrounding method with `@Transactional(propagation = NOT_SUPPORTED)` gymnastics. This exactly mirrors `TestSuiteCloneService.executeDbWrites()`.

**Handling `TransactionTimestampContext`:** `detachDataset()` must bind the timestamp to `TRANSACTION_TIMESTAMP_KEY` before executing the template (and unbind in `finally`) — exactly as `executeDbWrites()` does. The timestamp is read by the `TransactionTimestampAspect` to stamp `createdAt`/`updatedAt` consistently within the TX.

**Alternative considered:** Annotating `detachDataset()` with `@Transactional("metaTransactionManager")` and making `copyDatasetFiles` a pre-call step in the controller. Rejected — pushing infrastructure concerns (file I/O ordering, TX boundary) into the controller violates layering. Service owns orchestration.

### D4 — Suite rebind via `testSuiteRepository` update (not a full `save`)

**Decision:** Inside the transaction, update only `dataset_id`, `disabled_test_case_ids` (remapped old→new), and `version`/`updated_at_ms` for the suite row instead of doing a full `createWithId`/`save`. The suite entity remains unchanged except for those columns.

**Rationale:** A targeted update is safer under concurrent writes — it touches only the columns that change, avoiding accidental overwrites of other fields modified by a concurrent request. `disabled_test_case_ids` must be rewritten atomically alongside `dataset_id` because cloning produces fresh test-case IDs that the disabled set must reference. `TestSuiteRepository.updateDatasetId(suiteId, newDatasetId, disabledTestCaseIds, updatedAt)` was added for this purpose.

### D5 — 409 error codes

**Decision:** Reuse `DatasetVisibilityRuleException` with existing `DatasetVisibilityErrorCode` values:

| Condition | Code |
|-----------|------|
| Suite has no dataset bound | `SUITE_HAS_NO_DATASET` (existing) |
| Bound dataset is PRIVATE | `PRIVATE_DATASET_REBIND_FORBIDDEN` (existing) |

**Rationale:** `DatasetVisibilityRuleException` already maps to HTTP 409 in the global exception handler. Reusing the same exception type keeps the error-handling path consistent and avoids a new exception class for a closely related scenario.

### D6 — Schema copied verbatim → no TSMDs revalidation

**Decision:** After detach, skip TSMD revalidation.

**Rationale:** The clone's `testCaseSchema` is identical to the source's — the only structural change is that test case IDs are new (the data is the same). TSMDs validate against the schema, not the row IDs, so their validity verdict is unchanged. This is the same decision made for the "auto-clone PRIVATE dataset" path in `TestSuiteCloneService` (no `RevalidationTask` spawned when `tsmdRevalidationRequired = false`).

## Risks / Trade-offs

- **Race between name-uniqueness check and insert** → The `uq_datasets_name` unique index is the authoritative guard; `deriveCloneName`'s `existsByNameIgnoreCase` loop is a best-effort pre-check. A `DataIntegrityViolationException` on name collision is caught and re-thrown as a 409 (same pattern as `DatasetService.create()`).
- **Non-transactional file copy + TX failure** → Files are copied before the DB transaction. If the TX rolls back, `fileService.deleteAllByDatasetId(newDatasetId)` is called best-effort in `finally`. A crash between copy and cleanup leaves orphaned files; this is accepted (same risk exists in `TestSuiteCloneService`).
- **`TestSuiteService` grows new dependencies on `DatasetCloneService` and `DatasetQueryService`** → Both honor the cross-domain rule (narrow write surface + read facade); `FileService` and `metaTransactionManager` were already present. Constructor injection is used throughout.

## Migration Plan

- No Flyway migrations.
- No configuration changes.
- Deploy as a standard rolling update — the new endpoint is additive and does not modify existing behavior.
- Rollback: remove the endpoint; no data cleanup required since existing data is unaffected.

## Open Questions

None — design is fully specified.
