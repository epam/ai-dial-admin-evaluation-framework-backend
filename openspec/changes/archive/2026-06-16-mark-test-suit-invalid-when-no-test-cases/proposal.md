## Why

A test suite is currently marked `valid=true` based **only** on its configuration (template, bindings, endpoint). A suite bound to a dataset with **zero runnable test cases** (none valid+enabled) can still be run — the run completes immediately with `numberOfTestCases=0` and no results. Users expect a meaningful error when attempting to run such a suite.

The simplest correct fix is a **run-time guard**: check the runnable count at run-creation time and reject with 409 if zero. Suite validity (`isValid`) stays config-only — no writes on test-case mutations, no Flyway backfill, no fan-out.

## What Changes

- **`RunnableTestCaseCounter`** (`service/domain`) — thin `@Component` with `countRunnable(UUID datasetId, List<UUID> disabledIds)` delegating to `testCaseRepository.countValidByDatasetIdExcludingIds`.
- **Guard #4 in `TestSuiteRunService.createRun`** — after the existing `isValid == false` guard, count runnable test cases for the bound dataset (honoring `suite.disabledTestCaseIds`). If count is zero, throw `InvalidOperationException("Suite has no valid and enabled test cases")` → HTTP 409 `INVALID_OPERATION`. No run record is persisted and no async job is dispatched.
- Suite validity (`isValid` / `validationWarnings`) is **config-only** and is never updated on test-case mutations. A bound suite with zero runnable test cases is `isValid=true` in the database and in GET responses; the presence check fires only at run-creation time.
- Unbound suites (`datasetId == null`) are unaffected — the run path already rejects them with `SUITE_HAS_NO_DATASET` before guard #4.

No breaking API shape changes: response DTOs are unchanged; only the run-creation rejection path gains a new guard.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `test-suites`: Suite validity (`isValid` / `validationWarnings`) is **config-only**. A bound suite with zero runnable test cases is `isValid=true`; test-case mutations do not affect suite validity.
- `test-suite-runs`: Run creation enforces **guard #4** — after the `isValid == false` check, count runnable test cases for the bound dataset; if zero, reject with HTTP 409 `INVALID_OPERATION` ("Suite has no valid and enabled test cases").

## Impact

- **Code (service layer):**
  - `service/domain/RunnableTestCaseCounter.java` — **new** `@Component`: `countRunnable(UUID datasetId, List<UUID> disabledIds)` delegating to `testCaseRepository.countValidByDatasetIdExcludingIds`.
  - `service/domain/TestSuiteRunService.java` — guard #4 added after the `isValid == false` check: call `runnableTestCaseCounter.countRunnable(suite.datasetId, suite.disabledTestCaseIds)`; if zero, throw `InvalidOperationException("Suite has no valid and enabled test cases")` → HTTP 409 `INVALID_OPERATION`.
- **Tests:** new `RunnableTestCaseCounterTest` (unit); `TestSuiteRunFunctionalTests` — zero-runnable suite → 409, same suite after adding a test case → 202. AGENTS.md inline convention updated.
- **No schema changes, no Flyway migrations, no config properties, no new dependencies, no security impact.**
