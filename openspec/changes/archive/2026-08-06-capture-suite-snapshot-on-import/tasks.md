## 1. Core job logic

- [x] 1.1 In `src/main/java/com/epam/aidial/evaluation/service/domain/job/TestSuiteEvaluationJob.java`, change `attemptSnapshot(UUID runId)` to `attemptSnapshot(UUID runId, boolean captureTestCaseInputs)`: keep the delete-leftover-inputs step, suite/dataset load, `SuiteSnapshotDto` build+serialize, and `repository.updateSuiteSnapshot(...)` call unconditional; wrap the test-case paging loop (`runnableTestCaseSelector.loadRunnablePage`, `testCaseRunInputRepository.insertBatch`) and the `repository.updateNumberOfTestCases(...)` call in `if (captureTestCaseInputs) { ... }`.
- [x] 1.2 Change `executeSnapshotPhase(UUID runId)` to `executeSnapshotPhase(UUID runId, boolean captureTestCaseInputs)`, threading the flag into `attemptSnapshot` on every retry attempt; leave the retry/error-code logic (SQLSTATE `40001` → retry up to `SNAPSHOT_MAX_RETRIES`, else `SNAPSHOT_SERIALIZATION_CONFLICT`/`resolveSnapshotErrorCode`) unchanged.
- [x] 1.3 In `executeRunAsync`, replace `if (!skipDeploymentPhase && !executeSnapshotPhase(runId)) { return; }` with `if (!executeSnapshotPhase(runId, !skipDeploymentPhase)) { return; }`. Leave the inconsistent-snapshot guard and Phase 1 (`buildContext` + `evaluationExecutor.execute`) exactly where they are today, inside `if (!skipDeploymentPhase) { ... }`. Leave Phase 2/Phase 3 unconditional and unchanged.
- [x] 1.4 Run `./gradlew checkstyleMain` to confirm the parameter-list changes comply with the horizontal/vertical formatting rule.

## 2. Unit tests

- [x] 2.1 In `src/test/java/com/epam/aidial/evaluation/service/domain/job/TestSuiteEvaluationJobTest.java`, nested class `ExecuteRunAsyncSkipDeploymentPhase`, test `runsPhase2And3NeverPhase1`: add assertions `verify(repository).updateSuiteSnapshot(eq(runId), any(), anyLong())`, `verify(testCaseRunInputRepository, never()).insertBatch(any())`, `verify(runnableTestCaseSelector, never()).loadRunnablePage(any(), any(), any(), anyInt(), anyInt())`, `verify(repository, never()).updateNumberOfTestCases(any(), anyInt(), anyLong())`.
- [x] 2.2 In the same nested class, test `cancellationDuringPhase2SkipsPhase3`: add the stubs `testSuiteRepository.findById(suiteId)` → `liveSuite`, `datasetRepository.findById(datasetId)` → `liveDataset`, `suiteSnapshotBuilder.build(liveSuite, liveDataset)` → a built `SuiteSnapshotDto`, so the now-unconditional snapshot phase succeeds before reaching Phase 2.
- [x] 2.3 Confirm test `cancellationSkipsPhase2And3` needs no change (it cancels before `executeRunAsync` is called, so the top-of-method early-cancellation check returns before the snapshot phase runs).
- [x] 2.4 Run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.job.TestSuiteEvaluationJobTest"` and confirm all tests pass.
- [x] 2.5 Run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.TestSuiteRunServiceTest"` and confirm it remains green unmodified (it only asserts the `executeRunAsync(..., true)` dispatch call, not internal snapshot behavior).

## 3. Functional test coverage

- [x] 3.1 Locate the existing `PostgresFunctionalTests` nested class covering `POST .../runs/import` (search for `runs/import` under `src/test`). Add or extend a test asserting that after a successful import, `GET /api/v1/test-suites/{testSuiteId}/runs/{runId}` returns a non-null `suiteSnapshot` whose `deploymentRef`/`endpointRef` (or MCP fields) match the suite's configuration at import time.
- [x] 3.2 In the same or a sibling test, assert `numberOfTestCases` on the imported run still equals the CSV row count used in the import request, not the live dataset's runnable-test-case count (use a dataset where the two counts intentionally differ, e.g. by disabling a test case after import but before the GET).
- [x] 3.3 Add an assertion (or confirm existing coverage) that no `test_case_run_inputs` rows exist for an imported run, using `MetaTestDataHelper`/the repository — not raw SQL.
- [x] 3.4 Run the full functional suite for this area: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$<RelevantNestedClass>"`.

## 4. Documentation

- [x] 4.1 Update `docs/patterns/suite-run-snapshot.md`: note that the snapshot phase now always runs (including for CSV-imported runs dispatched with `skipDeploymentPhase=true`), and that `test_case_run_inputs` capture + `number_of_test_cases` overwrite are gated separately, only occurring when the deployment phase will execute.
- [x] 4.2 Update the "Import run" key term in `openspec/specs/eval-results-import/spec.md`'s `## Key Terms` section (not part of the delta-sync `## Requirements` mechanism, so edit this prose directly): remove "no `suiteSnapshot` or `TestCaseRunInput` rows are created for it" / "skips the snapshot phase", replacing with "captures a `suiteSnapshot` but not `TestCaseRunInput` rows."

## 5. Verification

- [x] 5.1 Full build: `./gradlew clean build` (unit + Testcontainers integration tests, checkstyle, spotless check).
- [x] 5.2 Manual end-to-end check: run `eval-cli evaluate` (clone → fetch → run → import) against a local backend targeting a DEPLOYMENT suite, then `GET` the resulting run and confirm `suiteSnapshot` is populated and `numberOfTestCases` matches the number of CSV rows produced by the run step.
