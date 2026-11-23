## 1. Reproduce

- [x] 1.1 Add `@MockitoSpyBean EvaluationWorker` to `PostgresFunctionalTests` next to the existing `@MockitoBean` declarations (single shared context; no spy bean existed before). Done: the two pre-existing tests of `PostgresFunctionalTests$EvaluationExecutorFailureModesTests` still pass with the spy in place.
- [x] 1.2 Add a functional test to the existing `EvaluationExecutorFailureModesFunctionalTests` (reuses its fixtures and `@Nested` registration instead of a new class): a suite with 2 test cases; stub the spy with `doThrow(...)` only for the input whose test case name matches (a deployment-invoker exception is NOT enough — the worker turns it into its own ERROR row); Spring resets the spy after each test. Run to `COMPLETED` and assert: (a) a run-scoped `TestCaseRunResultRepository.findAll` returns an ERROR row with the executor envelope for the throwing test case, (b) the SUCCESS row of the other test case is returned too, (c) the throwing test case has an eval summary row with non-SUCCESS `execution_status`. Done: the test runs and FAILS on the current code — run-scoped read returns no rows at all; log shows `NullPointerException ... getTestSuiteId() is null` and "Final flush failed" — confirming the bug.

## 2. Fix

- [x] 2.1 `evaluation-runner-core` `TestCaseRunResultFactory.errorResult`: take the run's suite id and creation timestamp and set `testSuiteId` and `createdAtMs` from them; keep `nowMs` for `execStartedAtMs`/`execCompletedAtMs`; set empty extraction values (`extractedColumns = "{}"`, `extractionWarnings = "[]"` — found during implementation: both columns are NOT NULL and the write failed without them). Keeps the "must not throw" contract (no new parsing or I/O). Javadoc updated. Done: `./gradlew :evaluation-runner-core:compileJava` passes.
- [x] 2.2 `TestCaseRunner.runWorker`: supply `context.getSuiteId()` / `context.getCreatedAtMs()` and `clock.millis()` as now. Done: compiles; no caller of `errorResult` remains with the old signature.

## 3. Tests

- [x] 3.1 Update `TestCaseRunResultFactoryTest`: all calls use the new signature; the envelope/timing test asserts `testSuiteId` equals the run's suite id, `createdAtMs` equals the run creation timestamp and differs from the exec timestamps, and the extraction values are empty. Done: `./gradlew :evaluation-runner-core:test --tests "com.epam.aidial.evaluation.runner.job.TestCaseRunResultFactoryTest"` passes.
- [x] 3.2 Update `TestCaseRunnerTest` (synthetic-row assertions, currently `getCreatedAtMs()` `isEqualTo(FIXED_NOW_MS)` around line 324): build the context with a suite id and a creation timestamp different from the fixed clock; assert the synthetic row's `testSuiteId` and `createdAtMs` equal the context values while exec timestamps equal the clock. Done: `./gradlew :evaluation-runner-core:test --tests "com.epam.aidial.evaluation.runner.job.TestCaseRunnerTest"` passes.
- [x] 3.3 Re-run the functional test from 1.2 and `EvaluationExecutorFailureModesFunctionalTests`; fix the stale javadoc reference `InProcessEvaluationExecutorTest#shouldSynthesizeErrorRow_whenWorkerThrows` in the latter. Done: both nested classes pass via `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$<NestedTests>"`.
- [x] 3.4 Run `./gradlew :eval-cli:test` to confirm the CLI consumer of the runner is unaffected. Done: passes.

## 4. Docs and checks

- [x] 4.1 Sync the delta into `openspec/specs/eval-execution-engine/spec.md` (requirement "Synthetic ERROR result for worker exception", status back to Implemented). Done: main spec contains the suite-id/timestamp wording and the three new scenarios.
- [x] 4.2 Update `openspec/specs/evaluation-runner-core-module/spec.md` where it lists the factory's inputs ("input, run index, exception, and clock millis") to include the run's suite id and creation timestamp. Done: no spec text describes the old input list (`grep -rn "clock millis" openspec/specs`).
- [x] 4.3 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` for the touched modules. Done: no violations.
