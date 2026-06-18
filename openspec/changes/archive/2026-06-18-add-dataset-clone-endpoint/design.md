## Context

The deep-copy primitive `DatasetCloneService#cloneRowAndTestCases(Dataset source, UUID newDatasetId, String name, String createdBy, long timestamp)` already exists. It inserts a new dataset row (currently hardcoded to `PRIVATE`) and batch-copies the source's test cases with fresh ids, repointed `datasetId`, and `@ef/datasets/{source}/ → @ef/datasets/{new}/` file-ref rewrites, returning an old→new test-case id map. It is invoked from two places today:

- `TestSuiteService.detachDataset` — forks a suite's bound PUBLIC dataset into a PRIVATE clone and rebinds the suite.
- `TestSuiteCloneService` — auto-clones a suite's PRIVATE dataset when the suite itself is cloned.

Both callers run the same orchestration shape: copy DIAL files **before** the DB transaction (file I/O is non-transactional), then run the clone inside a `TransactionTemplate(metaTransactionManager)` with a `TransactionTimestampContext` binding, and clean up copied files best-effort if the transaction fails.

This change exposes the primitive directly as `POST /api/v1/datasets/{id}/clone` (a `datasets`-domain operation), reusing the existing orchestration shape.

## Goals / Non-Goals

**Goals:**
- Provide a standalone `POST /api/v1/datasets/{id}/clone` returning the cloned dataset as `DatasetResponseDto` (201 + ETag).
- The clone **inherits the source's visibility** and is **unbound** to any suite.
- Allow cloning any source dataset (PUBLIC or PRIVATE).
- Optional `name` override; auto-derive via `deriveCloneName` when omitted.
- Optional `description` override; copy the source's description verbatim when omitted.
- Keep the two existing callers behavior-identical (they continue to produce PRIVATE clones with the source's description).

**Non-Goals:**
- No DB schema change, no Flyway migration, no jOOQ regen.
- No new config property (batching reuses `revalidation.batch-size`).
- No suite binding from this endpoint (the clone is always unbound; callers bind later via existing endpoints).
- No `testCaseSchema` override — schema is copied verbatim by the primitive (only `name` and `description` are overridable).
- No re-validation of the clone (validation state is copied verbatim, matching the primitive's contract — overriding `description` does not trigger re-validation).

## Decisions

### D1 — Thread `visibility` and `description` through the primitive instead of hardcoding
`cloneRowAndTestCases` gains a `String description` and a trailing `DatasetVisibility visibility` parameter; it uses them in the `Dataset.builder()` in place of the hardcoded `DatasetVisibility.PRIVATE` and the internal `source.getDescription()`. The new endpoint passes the resolved description (override-or-source) and `source.getVisibility()`; the two existing callers pass `source.getDescription()` and `DatasetVisibility.PRIVATE`.

- **Why:** The standalone clone must preserve the source's catalogue visibility (typically PUBLIC) and allow a description override, but the suite-driven callers semantically require PRIVATE and copy the source description verbatim. Making both explicit parameters keeps a single primitive and a single clone code path.
- **Alternatives considered:**
  - *Post-clone visibility update* (clone PRIVATE, then `UPDATE … SET visibility`): an extra write and a transient wrong-visibility row; rejected.
  - *Separate public clone method*: duplicates the batch-copy/file-rewrite logic; rejected.
- **Compatibility:** Signature-only change; both call sites updated in the same change. Behavior-preserving for `detach-dataset` and `test-suite-clone` (both pass PRIVATE + the source description), so no spec-level requirement change there.

### D2 — Orchestration lives in `DatasetService.clone(...)`, delegating writes to `DatasetCloneService`
Clone is a `datasets`-domain operation, so the entry point is `DatasetService` (the domain service the controller already calls), mirroring `TestSuiteService.detachDataset`. It loads the source, runs the pre-TX file copy and the transactional write through `DatasetCloneService`, and maps the result.

- **Why:** Respects the layering rule — a domain service drives its own domain's writes through the domain's services/repositories. `DatasetCloneService` stays the "narrow write surface"; `DatasetService` owns orchestration (it already owns `create`).
- **Alternatives considered:** Putting orchestration in `DatasetCloneService` would bloat that class with `transactionTimestampContext`, `AuthorResolver`, tx-manager, and mapper dependencies, diluting its single responsibility; rejected.
- **New injections into `DatasetService`** (as needed): `DatasetCloneService`, `FileService`, and `@Qualifier("metaTransactionManager") PlatformTransactionManager`. (`authorResolver`, `datasetMapper`, `transactionTimestampContext`, and the dataset read path are already present. `timestamp` is obtained via `transactionTimestampContext.initializeIfAbsent()` — `Clock` is NOT injected directly; it is consumed through the context bean per the project pattern.)

### D3 — Orchestration flow (replicates the detach shape)
1. Load source dataset by id; throw `EntityNotFoundException` → **404** if absent.
2. Resolve `newDatasetId = UUID.randomUUID()`, `timestamp = transactionTimestampContext.initializeIfAbsent()`, `createdBy = authorResolver.getCreatedBy(jwt)`, `name = dto.getName() != null ? dto.getName() : datasetCloneService.deriveCloneName(source.getName())`, and `description = dto.getDescription() != null ? dto.getDescription() : source.getDescription()`.
3. **Pre-TX:** `datasetCloneService.copyDatasetFiles(sourceId, newDatasetId)` (non-transactional DIAL I/O, best-effort).
4. **TX:** `TransactionTemplate(metaTransactionManager)` with `TRANSACTION_TIMESTAMP_KEY` bind/unbind guard. Inside: `cloneRowAndTestCases(source, newDatasetId, name, description, createdBy, timestamp, source.getVisibility())`. The returned id-map is discarded (no suite to remap — the clone is unbound).
5. Wrap the TX in `catch (DataIntegrityViolationException)` → `UniqueConstraintViolationDetector.rethrowIfUniqueViolation(...)` → **409** `UNIQUE_CONSTRAINT_VIOLATION` on name collision.
6. **On TX failure:** best-effort `fileService.deleteAllByDatasetId(newDatasetId)` cleanup (matches detach).
7. Load the new dataset back (existing `findById` path) and return `datasetMapper.toDto(...)`.

### D4 — API contract
- `POST /api/v1/datasets/{id}/clone`, `@ResponseStatus(HttpStatus.CREATED)`, returns `ResponseEntity<DatasetResponseDto>` with `eTag(version)` (version is `0` for the freshly cloned row), modeled on the existing `create` handler.
- Request body: new `DatasetCloneRequestDto` with two optional fields — `name` (`@Size(max = ValidationConstants.MAX_DATASET_NAME_LENGTH)`, no `@NotBlank`; null ⇒ auto-derive) and `description` (`@Size(max = 2000)`; null ⇒ copy source). An empty/absent body is valid.
- Responses: **201** cloned, **400** validation (name too long), **404** source not found, **409** name conflict.
- OpenAPI: `@Operation`/`@ApiResponse` annotations plus examples per the `openapi-examples` spec.

## Risks / Trade-offs

- **Cloning a PRIVATE source yields an unbound PRIVATE dataset** → Accepted per requirements. An unbound PRIVATE dataset is a valid transient state (the create flow's bind requirement is enforced only at create time, not by a DB constraint); the caller may bind or delete it later. Documented in the spec.
- **Signature change ripples to two callers** → Mitigation: both call sites are updated in this change to pass `DatasetVisibility.PRIVATE`, and the existing `detach-dataset` / `test-suite-clone` functional tests are run to confirm behavior is unchanged.
- **Files copied but transaction rolls back** → Mitigation: best-effort `deleteAllByDatasetId(newDatasetId)` cleanup in a `finally`, identical to the detach flow. Worst case is orphaned files in a brand-new (never-committed) dataset folder, logged for diagnosis.
- **Large datasets** → Mitigation: the primitive already batches test-case copy via `revalidation.batch-size`; no full-dataset in-memory load is introduced.
