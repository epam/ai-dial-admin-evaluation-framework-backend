## 1. New implementation — RunnableTestCaseCounter

- [x] 1.1 `RunnableTestCaseCounter` (`service/domain/RunnableTestCaseCounter.java`) with `countRunnable(UUID datasetId, List<UUID> disabledIds)` delegating to `testCaseRepository.countValidByDatasetIdExcludingIds`. `RunnableTestCaseCounterTest` (unit test).

## 2. New implementation — run-time guard in TestSuiteRunService

- [x] 2.1 Inject `RunnableTestCaseCounter` into `TestSuiteRunService`; add guard #4 after the existing `isValid()` check: count runnable test cases for the bound dataset (honoring `suite.disabledTestCaseIds`) and throw `InvalidOperationException("Suite has no valid and enabled test cases")` (→ 409 `INVALID_OPERATION`) when count is zero (`service/domain/TestSuiteRunService.java`).

## 3. Tests

- [x] 3.1 `TestSuiteRunFunctionalTests`: scenario — bound, config-valid suite with zero runnable test cases → `POST /runs` → 409 `INVALID_OPERATION`; scenario — same suite after adding one test case → 202 Accepted.

## 4. Docs and build

- [x] 4.1 Update AGENTS.md "Suite validity" inline convention — document that test-case presence is enforced at run-creation time only (guard #4 in `TestSuiteRunService.createRun`).
- [x] 4.2 `./gradlew spotlessApply clean build` green; `openspec validate`.

## 5. Spec sync and index

- [x] 5.1 Run `opsx:sync` to merge delta specs into main specs (`openspec/specs/test-suites/spec.md`, `openspec/specs/test-suite-runs/spec.md`, `openspec/specs/test-cases/spec.md`).
- [x] 5.2 Update `openspec/specs/README.md` test-suites summary to reflect config-only suite validity and the run-time zero-runnable guard.
