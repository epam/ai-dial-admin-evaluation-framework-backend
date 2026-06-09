## Context

`TestSuiteCloneService` clones a suite's execution-time configuration plus its TSMDs (test cases stay in the shared dataset). Today the TSMD copy loop in `executeDbWrites` hardcodes `.valid(false)` and `.validationWarnings("[]")` on every cloned TSMD (`TestSuiteCloneService.java:271`, `276`), so a cloned suite always reports its metrics as invalid until the user manually retriggers validation — even when nothing affecting validity changed.

Meanwhile the suite row itself is already validated synchronously at clone time (`applySuiteValidation`, stamped onto the entity before insert), and the spec's "Post-clone validation is synchronous only" requirement explicitly mentions `TestSuiteMetricDefinitionService` "where applicable" — but that path was never wired in for TSMDs.

TSMD validity is a pure function of five inputs (`MetricDefinitionValidationService.validate`): the TSMD's `configBindings`/`inputBindings`, the metric version's config/input/output schemas, the dataset `testCaseSchema`, and the suite `responseColumns`. On a clone:

- bindings are copied verbatim (suite-scoped file-ref rewriting is validity-neutral — that validator checks property/column names, not file existence);
- version schemas are fixed by the unchanged `metricDeclarationVersionId`;
- so validity can change **only** via a `datasetId` override (different `testCaseSchema`) or a `responseColumns` override.

The working tree currently has a half-finished attempt (`RevalidationService` injected, a broken `validateSuite(newSuiteEntity, new)` call) that this change replaces.

## Goals / Non-Goals

**Goals:**
- Cloned TSMDs report accurate `isValid`/`validationWarnings`.
- Avoid unnecessary recompute on the common path (no override) by copying the source verdict verbatim.
- Recompute synchronously, inside the clone transaction, only when an override can change validity.
- Reuse the existing `TestSuiteMetricDefinitionService.revalidateAllForSuite(...)` path verbatim.

**Non-Goals:**
- No async `RevalidationTask` for clone (consistent with the existing synchronous-only requirement).
- No change to TSMD validation logic itself (`MetricDefinitionValidationService`).
- No change to suite-level validation ordering — it stays in-memory before the insert.
- No API contract, DB schema, or config change.

## Decisions

**D1 — Conditional copy-vs-recompute ("TSMD revalidation required").**
Compute one boolean in `clone(...)`:
```
tsmdRevalidationRequired =
    (dto.getDatasetId() != null && !dto.getDatasetId().equals(sourceDatasetId))
    || dto.getResponseColumns() != null
```
- *Not required* → in the copy loop set `.valid(tsmd.isValid())` and `.validationWarnings(tsmd.getValidationWarnings())`, copying the source verdict verbatim. One insert per TSMD, no recompute.
- *Required* → insert TSMDs (placeholder validity), then call `revalidateAllForSuite(newId, datasetSchemaJson, responseColumnsJson)` after the loop, still inside `transactionTemplate.execute(...)`.

*Alternative considered — always recompute (the earlier "Option A").* Simpler control flow, but pays an aggregated SELECT + an UPDATE per TSMD on every clone, including the overwhelmingly common no-override case where the source verdict is provably identical. Rejected as wasteful.

*Alternative considered — always validate in-memory in the loop (the earlier "Option B").* Single write per TSMD even on recompute, but requires switching the source read to the aggregated query and re-implementing binding parsing + warnings serialization in the clone loop. More new code, less reuse; deferred unless the recompute UPDATE is measured to matter.

**D2 — Recompute via `revalidateAllForSuite`, after inserts, in the same transaction.**
`revalidateAllForSuite` reads `findAllAggregatedByTestSuiteId` (the join that exposes version schemas) and UPDATEs each TSMD's validity. It can only see the cloned rows once they're inserted, so it must run after the copy loop. It is `@Transactional("metaTransactionManager")` with default REQUIRED propagation, so invoked from inside the existing `TransactionTemplate` it joins the clone transaction — TSMD updates commit/roll back atomically with the suite, dataset, and test-case writes.

**D3 — Collaborator: inject `TestSuiteMetricDefinitionService`; drop `RevalidationService`.**
The recompute belongs to the TSMD domain service, which already owns `revalidateAllForSuite`. The `RevalidationService` reference in the working tree is removed (its async dataset-rooted flow is not what clone needs).

**D4 — Dataset schema JSON source for the recompute branch.**
`revalidateAllForSuite` takes the raw `testCaseSchema` JSON string. The resolved dataset id is already known in `clone(...)` (`datasetToValidateAgainst`): the source dataset for a private auto-clone, otherwise the resolved/override dataset. Load that dataset's `getTestCaseSchema()` (the service already injects `DatasetRepository`) and pass it down to `executeDbWrites`. Response columns come from `newSuiteEntity.getResponseColumns()`.

**D5 — Suite-level validation stays before the insert.**
It is pure in-memory (needs no persisted row) and lets the suite be written once already-valid. Moving it after the writes would add an extra UPDATE on the suite row for no correctness benefit, so it is left unchanged.

## Risks / Trade-offs

- **[Stale source verdict copied on the no-override path]** → The source TSMD's stored validity is the system's authoritative verdict for the identical (schema, responseColumns) context, kept current by suite-PUT-on-schema-change and dataset revalidation. With every validation input identical, a recompute would reproduce it byte-for-byte, so copying is correct.
- **[`datasetId` override to a same-schema dataset triggers an unnecessary recompute]** → Acceptable: the recompute is bounded to the suite's TSMDs and runs only on the rare override path; we conservatively recompute rather than compare schemas.
- **[`revalidateAllForSuite` loads all TSMDs at once (not paginated) on recompute]** → Pre-existing behavior of that method, used identically by async Phase-2 revalidation and suite-PUT; TSMD counts per suite are small. Out of scope to change here.
- **[Recompute relies on inserted rows being visible mid-transaction]** → Guaranteed: same transaction, same connection, so the aggregated read sees the just-inserted rows.

## Migration Plan

Pure behavior fix; no schema or config migration. Deploy is a code change only. Rollback is reverting the commit. Existing already-cloned suites are unaffected (they can be corrected via the existing dataset revalidation endpoint if desired).

## Open Questions

None.
