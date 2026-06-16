## Context

`TestSuiteCloneService.clone(...)` clones a suite's config + TSMDs and, by default, makes the clone **share** the source's `datasetId` (`TestSuiteMapper.toCloneEntity:172`). Test cases are owned by the dataset (`test_cases.dataset_id` FK), so they are not copied — sharing the dataset is intentional and correct for **PUBLIC** datasets.

A **PRIVATE** dataset, however, may bind to exactly one suite. This is enforced by the DB trigger `tg_test_suites_private_binding_guard` (`V1.22__IntroduceDataset.sql`), which raises `PRIVATE_DATASET_ALREADY_BOUND` (SQLState `P0001`, mapped to HTTP 409) on any second binding. Consequently, cloning a suite bound to a PRIVATE dataset fails at the cloned-suite insert. The existing clone functional tests never hit this because `MetaTestDataHelper.createDataset(...)` defaults to PUBLIC.

Constraints: Java 25 / Spring Boot, JDBC + jOOQ only (no JPA), strict layering (a domain service injects only its own domain's repository; cross-domain writes go through the owning domain's service), `@Transactional` on the meta tx manager, UUIDs as `VARCHAR(36)`, epoch-ms timestamps via `TransactionTimestampContext`.

## Goals / Non-Goals

**Goals:**
- When a clone's source is bound to a PRIVATE dataset and no `datasetId` override is given, clone the dataset too: new PRIVATE dataset, copied test cases (new UUIDs), copied dataset-scoped files with ref rewrite, clone bound to the new dataset.
- Preserve the PUBLIC-share path and the explicit-`datasetId`-override path byte-for-byte.
- Keep the operation atomic and the failure cleanup correct.
- No DB schema change, no API contract change.

**Non-Goals:**
- No re-validation of the cloned dataset's test cases (the schema and data are identical to the source — validation state is copied verbatim).
- No new public dataset-clone endpoint; dataset cloning is an internal side effect of suite cloning.
- No change to PUBLIC sharing semantics or to the explicit-override behavior (including its existing 409 if the override targets an already-bound PRIVATE dataset).
- No data migration; existing `@ef/suites/...` legacy refs in test-case data are left untouched.

## Decisions

### D1. Trigger auto-clone by source-dataset visibility, not by a new request flag
Clone the dataset **iff** `dto.datasetId == null && source.datasetId != null && sourceDataset.visibility == PRIVATE`.
- Rationale: matches user intent ("when associated with a private dataset, also clone the dataset") with zero API surface change. PRIVATE-source clone is otherwise impossible, so there is no behavior to preserve — only a 409 to fix.
- Alternative considered: an explicit `cloneDataset` boolean on the request DTO. Rejected — adds API surface, and the only correct value for a PRIVATE source is "true" (sharing is impossible).

### D2. New `DatasetCloneService` (dataset-domain write surface), not logic inside `TestSuiteCloneService`
Introduce `service.domain.DatasetCloneService`, depending only on `DatasetRepository`, `TestCaseRepository`, `FileService`, and `RevalidationProperties` (batch size). It owns: clone-name derivation, dataset-row insert, paginated test-case copy (new UUIDs + `@ef/datasets` ref rewrite), and returning the old→new test-case id map.
- Rationale: honors the layering rule — `TestSuiteCloneService` (suite domain) must not drive `DatasetRepository`/`TestCaseRepository` writes directly. Mirrors the existing narrow `DatasetCascadeService`. `TestCaseRepository` is within the dataset bounded context (`test_cases.dataset_id` FK), so this service legitimately owns that write loop.
- Alternative considered: methods on `DatasetService`. Rejected — `DatasetService` already depends on `TestSuiteService` (bind/unbind), and folding a heavy test-case copy loop there bloats it; a focused service keeps the write surface narrow and unit-testable.

### D3. Split the work across the existing pre-tx / in-tx phases of `TestSuiteCloneService`
- **Pre-transaction** (mirrors current file-copy phase): `fileService.copyFilesBetweenSuites(...)` (existing) plus, when auto-cloning, `fileService.copyFilesBetweenDatasets(source.datasetId, newDatasetId)` (new). The orchestrator only pre-generates `newDatasetId` here.
- **In-transaction** (inside the existing programmatic `transactionTemplate`): when auto-cloning, first `datasetCloneService.cloneRowAndTestCases(...)` (joins the active tx via `REQUIRED`) — which derives the unique clone name (via `DatasetRepository.existsByNameIgnoreCase`) and inserts the row — then remap `disabledTestCaseIds`, then the existing suite insert (now binds a fresh PRIVATE dataset → trigger passes) and TSMD copy. (Name derivation lives inside `DatasetCloneService` for cohesion; its dedup reads run in-tx, which is consistent and cheap — no write locks held until the insert.)
- Rationale: DIAL file I/O must not run inside the DB transaction (it is not transactional and can be slow); this is the pattern the suite clone already uses.

### D4. Validate the suite against the **source** dataset's schema
`applySuiteValidation` runs before the transaction, where the cloned dataset row does not yet exist. Add a `schemaDatasetId` parameter: pass `source.getDatasetId()` when auto-cloning (the clone's schema is identical), else `entity.getDatasetId()` (override or shared).
- Alternative considered: insert the dataset first, then validate against the new id. Rejected — would force dataset writes before file copy and complicate cleanup ordering; the schemas are provably identical so reading the source is equivalent and simpler.

### D5. Remap `disabledTestCaseIds` through the old→new id map
Test cases are re-keyed, so the suite's inherited `disabledTestCaseIds` (stored on `TestSuite` as a JSONB-encoded JSON array of UUID strings) must be translated old→new. Introduce a new PUBLIC `TestSuiteMapper.remapDisabledIds(String json, Map<UUID, UUID> idMap)` that reuses the existing private `deserializeDisabledIds`/`serializeDisabledIds` round-trip: it deserializes the stored JSON, maps each id through `idMap` (dropping any unmapped id defensively), and re-serializes back to the JSONB-ready string — no serialization logic is duplicated. `TestSuiteCloneService` calls this, then sets `entity.setDatasetId(newDatasetId)` and `entity.setDisabledTestCaseIds(remappedJson)` on the `@Data` entity after the mapper builds it — `toCloneEntity` keeps its existing signature.
- Rationale: without this, the clone would disable nonexistent test cases. Exposing the remap on `TestSuiteMapper` keeps it in the suite domain (which owns `disabledTestCaseIds`) and reuses the existing round-trip instead of adding a duplicate UUID-list method to `JsonbMapper` (which has none) or a private helper in the orchestrator.

### D6. Clone name derivation with dedup
`deriveCloneName(sourceName)` (a public method on `DatasetCloneService`, called from within `cloneRowAndTestCases`) → base `"<sourceName> (clone)"`; on collision, `"(clone 2)"`, `"(clone 3)"`, …; truncate the base so the total stays within `VARCHAR(263)` (`ValidationConstants.MAX_DATASET_NAME_LENGTH`). Checked via `existsByNameIgnoreCase`; the `uq_datasets_name` unique index remains the backstop (race-safe via the existing `DataIntegrityViolationException` handling).

### D7. Failure cleanup
Extend the existing `finally` cleanup: when auto-cloning, also `fileService.deleteAllByDatasetId(newDatasetId)`. DB rows roll back with the transaction; FK `ON DELETE CASCADE` removes any partial test cases. Only the (non-transactional) copied DIAL files need explicit cleanup.

## Risks / Trade-offs

- **[Cloned dataset name collision under concurrency]** Two concurrent clones could both pre-check the same free name and one insert then fails the unique index → existing `DataIntegrityViolationException` → 409. → Acceptable: surfaced as a clear unique-name error; the user can retry. The dedup loop minimizes the window.
- **[Large datasets increase clone latency/memory]** Copying many test cases + files lengthens the request. → Mitigated by the existing paginated batch loop (`RevalidationProperties.batchSize`) — no full-dataset load into memory; files copied one-by-one (best-effort, same as suite copy).
- **[Partial DIAL file copy on crash mid-copy]** Files copied before a transaction failure must be cleaned up. → Best-effort `deleteAllByDatasetId` in `finally`, mirroring the suite-file cleanup contract (warn-and-continue).
- **[`disabledTestCaseIds` referencing an id absent from the source dataset]** Defensive remap drops unmapped ids rather than failing. → A pre-existing inconsistency would self-heal in the clone; acceptable.
- **[Cross-domain coupling]** `TestSuiteCloneService` reading source dataset visibility. → Reuses the already-injected `DatasetRepository` for a read only; all dataset/test-case *writes* go through `DatasetCloneService`, satisfying the layering rule.

## Migration Plan

- No DB migration, no config, no API contract change. Pure service-layer behavior change for the PRIVATE-source clone path (previously always 409).
- Rollback: revert the code change; no persisted state depends on it. Any datasets already cloned remain valid independent datasets.

## Open Questions

- None blocking. (Naming scheme and file-copy strategy were confirmed: `"<name> (clone)"` with dedup, and copy-files-and-rewrite-refs.)
