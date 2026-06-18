## Context

After datasets were decoupled from suites, test cases became dataset-scoped and `SuiteValidationService` was reduced to **config-only** validation (template / bindings / endpoint). As a side effect, running a suite bound to a dataset with **zero runnable test cases** (none valid+enabled after applying the suite's `disabledTestCaseIds`) produces a run with `numberOfTestCases=0` and no results, with no user-facing error. The run creation path guards on `isValid()` but does not check test-case presence.

Constraints:
- A dataset is **shared** across many suites; `disabledTestCaseIds` is **per-suite**, so the runnable count is per (suite, dataset).
- Cross-domain rule (best-practices spec): `TestSuiteRunService` must not call `testCaseRepository` directly; the count must come through a test-case domain service or component.
- `SuiteValidationService` is config-only and must stay that way — presence is a run-time concern, not a config-validity concern.

## Goals / Non-Goals

**Goals:**
- Block running a suite with zero runnable test cases, returning HTTP 409 `INVALID_OPERATION` with a clear message.
- Keep the check at run creation time only — no writes to `is_valid` or `validationWarnings` in the database.
- Keep `SuiteValidationService` unchanged — suite validity remains config-only.
- Respect the cross-domain rule for the runnable-count read.

**Non-Goals:**
- Persisting `NO_TEST_CASES` in `validationWarnings` or `is_valid` in the database.
- Recomputing suite validity on every test-case mutation.
- A new `ValidationWarningCode` or warning in the suite GET response.
- A Flyway data backfill.
- Changes to `TestCaseService`, `CsvImportService`, or `RevalidationService`.
- Validating unbound suites against test-case count (the run path already rejects them with `SUITE_HAS_NO_DATASET`).

## Decisions

### D1 — Run-time guard in `TestSuiteRunService.createRun`

A new explicit check is added in `createRun` **after** the existing guard sequence (not-found → unbound → invalid). When the suite is bound and config-valid, `RunnableTestCaseCounter.countRunnable(suite.datasetId, suite.disabledTestCaseIds)` is called. If the result is zero, throw `InvalidOperationException("Suite has no valid and enabled test cases")` → HTTP 409 `INVALID_OPERATION`.

Guard order (no change to existing guards):
1. Suite not found → 404
2. Unbound (`datasetId == null`) → 409 `SUITE_HAS_NO_DATASET`
3. Config-invalid (`isValid == false`) → 409 `INVALID_OPERATION`
4. **[NEW]** Zero runnable test cases → 409 `INVALID_OPERATION`
5. Concurrent run limits → 429

This placement mirrors the `SUITE_HAS_NO_DATASET` guard in spirit: a structural precondition for a meaningful run, enforced before any record is persisted or async job is dispatched.

- *Alternative considered:* store `NO_TEST_CASES` in `is_valid` / `validationWarnings` and rely on guard #3 — rejected: requires recomputing suite validity on every test-case mutation across all bound suites, a non-trivial fan-out with writes on a hot path. Run-time check is cheaper and equally correct.

### D2 — `RunnableTestCaseCounter` as the single count read-point

A new `@Component` `RunnableTestCaseCounter` (in `service.domain`) provides `countRunnable(UUID datasetId, List<UUID> disabledIds)` delegating to `testCaseRepository.countValidByDatasetIdExcludingIds`. `TestSuiteRunService` injects `RunnableTestCaseCounter` (a service-layer component), not `testCaseRepository` directly — the cross-domain rule holds. No other service changes needed.

`SuiteValidationService`, `RevalidationService`, `TestCaseService`, and `CsvImportService` are **not modified**.

- *Alternative considered:* add a `countRunnable` method to `TestCaseService` — rejected: `TestCaseService` is focused on CRUD operations; the count read is used solely by the run guard, a different concern. A dedicated thin component keeps the responsibilities clean and is independently testable.

### D3 — No migration, no schema change

`is_valid` stays config-only. No Flyway migration needed. No jOOQ regeneration. No `docs/database-schema.md` change.

A suite with a valid config but zero test cases is `is_valid=true` in the database and `isValid=true` in GET responses. The zero-test-case condition is surfaced only at run-creation time as a 409. This is intentional — it is a run-time precondition, not a validity classification.

## Risks / Trade-offs

- **[Suite appears valid but cannot be run]** → Accepted. `is_valid=true` in GET reflects config-validity, which is the stored semantics. The run-creation error message ("Suite has no valid and enabled test cases") informs the user at the time they try to run. This is a deliberate scope trade-off vs the DB-write approach.
- **[TOCTOU between count check and run record creation]** → Acceptable; same window exists for all run preconditions. The count check is advisory — a race where test cases are deleted between the check and job dispatch would produce a `numberOfTestCases=0` run, same as today, not a crash.
- **[Undetected on suite GET]** → Users do not see a warning in the suite response. Acceptable per Non-Goals; can be added later without changing the run-path behavior.
- **[Corrupt `disabledTestCaseIds` JSONB falls back to empty exclusion list]** → Acceptable. `deserializeDisabledIds` logs a WARN and returns `List.of()` when JSONB is malformed, treating all valid+enabled test cases as runnable (fail-open). This means a corrupt `disabledTestCaseIds` field allows a run to proceed where some "disabled" test cases are unexpectedly included. Logged at WARN; a monitoring alert on that log line is advisable.

## Migration Plan

No migration required. Deploy the code change; the new run-creation guard takes effect immediately on the next run attempt. No rollback concerns — removing the guard is a one-line revert.

## Open Questions

None.
