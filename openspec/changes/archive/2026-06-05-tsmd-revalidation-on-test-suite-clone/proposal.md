## Why

When a test suite is cloned, its test suite metric definitions (TSMDs) are copied with `isValid` hardcoded to `false` (`TestSuiteCloneService.executeDbWrites`). This discards the source TSMD's known-good verdict and never recomputes it, so every cloned suite reports its metrics as invalid even when nothing affecting validity changed — forcing users to manually re-trigger validation after a clone. The suite row itself is already validated synchronously at clone time, so the TSMD behavior is both incorrect and inconsistent with the rest of the clone path.

## What Changes

- Replace the unconditional `isValid = false` on cloned TSMDs with a **conditional copy-vs-recompute** rule driven by a single condition, **"TSMD revalidation required"**:
  - **TSMD revalidation NOT required** — the clone supplies neither a `datasetId` override nor a `responseColumns` override (the common path, including the private-dataset auto-clone where the dataset schema is copied verbatim). Every input to TSMD validation is identical to the source, so the clone **copies the source TSMD's `valid` and `validationWarnings` verbatim**. Single insert, no recompute.
  - **TSMD revalidation required** — the clone supplies a `datasetId` override (possibly a different `testCaseSchema`) and/or a `responseColumns` override. Cloned TSMDs are inserted, then **revalidated synchronously inside the clone transaction** against the resolved dataset schema and response columns via the existing `TestSuiteMetricDefinitionService.revalidateAllForSuite(...)` path.
- Fix `validationWarnings` on cloned TSMDs to carry the copied/recomputed warnings instead of always `"[]"`.
- Inject `TestSuiteMetricDefinitionService` into `TestSuiteCloneService` for the recompute branch (remove the half-wired `RevalidationService` dependency currently in the working tree).
- No new async `RevalidationTask` is spawned — validation stays synchronous and within the clone transaction, consistent with the existing "Post-clone validation is synchronous only" requirement.

## Capabilities

### New Capabilities
<!-- None — this changes the behavior of an existing capability. -->

### Modified Capabilities
- `test-suite-clone`: The "TSMD cloning" requirement changes from "`isValid` SHALL be set to `false`" to the conditional copy-vs-recompute rule above, aligning it with the already-stated "Post-clone validation is synchronous only" requirement.

## Impact

- **Code**: `service/domain/TestSuiteCloneService.java` (conditional TSMD validity handling, new collaborator); reuses `service/domain/TestSuiteMetricDefinitionService.revalidateAllForSuite(...)` and `MetricDefinitionValidationService` unchanged.
- **API**: No contract change. `POST /api/v1/test-suites/{sourceId}/clone` response is unchanged in shape; cloned TSMDs now report accurate `isValid`/`validationWarnings`.
- **Data / schema**: No migration. No new tables, columns, or indexes.
- **Config**: None.
- **Tests**: `TestSuiteCloneFunctionalTests` TSMD assertions flip to expect accurate validity (valid copy on vanilla clone; recompute on `datasetId`/`responseColumns` override). `TestSuiteCloneServiceTest` updated for the new branch.
