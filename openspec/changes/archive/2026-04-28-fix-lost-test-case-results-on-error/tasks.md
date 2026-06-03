## 1. TestCaseRunResultFactory component

- [x] 1.1 Create `service.domain.job.TestCaseRunResultFactory` (`@Component`, `@LogExecution`, `@RequiredArgsConstructor`) with one method:
  - `errorResult(TestCaseRunInput input, int runIndex, Throwable cause, long nowMs) -> TestCaseRunResult`
- [x] 1.2 Implementation MUST NOT throw — fixed-shape JSON envelope construction via Jackson `ObjectNode` (`{"error":{"type":"<simple class name>","message":"<msg or empty>","origin":"executor"}}`), no template resolution, no JSON parsing of `testCaseData`. Use injected `ObjectMapper`. Map a `null` exception message to an empty string; never let the constructed envelope be malformed JSON.
- [x] 1.3 Unit test `TestCaseRunResultFactoryTest`: cover the method, assert envelope shape, assert that `null`/empty `cause.getMessage()` is handled gracefully, assert no exception propagates even with adversarial input (e.g. `cause` is a `Throwable` with `getMessage() == null`).

## 2. Refactor InProcessEvaluationExecutor lifecycle

- [x] 2.1 Inject `Clock` into `InProcessEvaluationExecutor` (constructor field added to `@RequiredArgsConstructor`; sourced from existing `ClockConfiguration` bean) so synthetic-row timestamps are testable via `Clock.fixed`.
- [x] 2.2 Inject `TestCaseRunResultFactory` into `InProcessEvaluationExecutor` (constructor field).
- [x] 2.3 In the worker runnable's `catch (Exception e)`, after the existing `log.error(...)` line, call `testCaseRunResultFactory.errorResult(input, ri, e, clock.millis())` to build a synthetic ERROR row and pass it to `resultBatchWriter.addResult(buffer, synthetic)`. Wrap the `addResult` call in its own `try/catch (Exception synthEx)` that logs `"Failed to record synthetic ERROR for test case {} run {}: {}", input.getTestCaseId(), ri, synthEx.getMessage(), synthEx` and continues. Do NOT add a second-level retry. The outer `catch (Exception e)` in the worker runnable remains intentionally broad — comment-document it briefly.
- [x] 2.4 Replace `CompletableFuture.allOf(futures).get(cancellationGracePeriodMs, MILLISECONDS)` with a signal-driven wait:
  - If `cancellationSignal.get() == false` → `executor.shutdown(); CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();`
  - If `cancellationSignal.get() == true` → `executor.shutdown();` then `try { allOf(...).get(grace, MILLISECONDS); } catch (TimeoutException te) { /* fall through to shutdownNow */ }` (catch only `TimeoutException` — other exceptions from `.get()` mean a future itself completed exceptionally and join would have surfaced it; we don't want to swallow those).
- [x] 2.5 After the wait completes, if `cancellationSignal.get() == true` AND any future is not done: call `executor.shutdownNow()`, then compute `long unfinished = futures.stream().filter(f -> !f.isDone()).count()` and emit `log.warn("Run {} cancelled with {} test case(s) interrupted before completion", context.getRunId(), unfinished)`. Do NOT iterate to synthesize rows — the cases simply remain absent from `test_case_run_results`.
- [x] 2.6 Reorder the `finally` block so the single `flush(buffer)` runs AFTER step 2.5. Remove the existing pre-shutdownNow flush.
- [x] 2.7 Outer `catch (Exception e)` (non-`InterruptedException`): best-effort attempt one final `resultBatchWriter.flush(buffer)` inside its own `try/catch` that logs and continues, then **re-throw** the original exception (no new exception class). This is the catastrophic path; the existing outer catch in `TestSuiteEvaluationJob.executeRunAsync` handles it.
- [x] 2.8 Verify the `InterruptedException` branch still sets `cancellationSignal` and falls into the same shutdown/flush path (no separate code duplicated).

## 3. Tests

- [x] 3.1 Add unit test `InProcessEvaluationExecutorTest#shouldNotTimeoutOnLongRun_whenNoCancellation` — dispatch many slow workers (sleeping > grace), assert the run completes, no `TimeoutException`, no synthetic rows produced, all real results flushed.
- [x] 3.2 Add unit test `InProcessEvaluationExecutorTest#shouldSynthesizeErrorRow_whenWorkerThrows` — stub `EvaluationWorker.execute` to throw `RuntimeException("boom")` for one test case, use `Clock.fixed(...)` to assert deterministic timestamps, assert exactly one `TestCaseRunResult` was added with `executionStatus = ERROR`, the expected JSON envelope (`type=RuntimeException`, `message=boom`, `origin=executor`), and zeroed timing. Assert other workers' real results are also added. Assert the run still reaches normal completion (no rethrow).
- [x] 3.3 Add unit test `InProcessEvaluationExecutorTest#shouldNotSynthesizeRows_whenCancelledMidFlight` — set `cancellationSignal` mid-dispatch, stub workers to sleep longer than grace, assert that at the end of `execute()` the buffer received only real rows from completed workers (no synthetic CANCELLED rows), and assert one WARN log line was emitted naming the unfinished count.
- [x] 3.4 Add unit test `InProcessEvaluationExecutorTest#shouldFlushExactlyOnce_atEnd` — capture all `transactionalWriter.saveBatch(...)` invocations across normal completion, cancellation, and worker-exception scenarios; assert the final buffered batch is flushed once after `shutdownNow()` (when applicable).
- [x] 3.5 Add unit test `InProcessEvaluationExecutorTest#shouldRethrow_whenDispatchLoopFails` — stub `testCaseRunInputRepository.findByRunId` to throw `RuntimeException("DB down")` on the second page, assert any rows already in the buffer are flushed best-effort, assert the original exception is rethrown unwrapped, assert no synthetic rows are written for never-dispatched pages.
- [x] 3.6 Add unit test `InProcessEvaluationExecutorTest#shouldNotRetry_whenSynthesisFails` — stub `resultBatchWriter.addResult` to throw on the synthetic-row append (simulate buffer flush downstream failure), assert one ERROR log line is emitted and the executor continues to subsequent test cases (no rethrow, no retry).
- [x] 3.7 Add functional test as a `@Nested` class `EvaluationExecutorFailureModesTests` inside `PostgresFunctionalTests`, covering the three-tier outcome:
  - Run with one transient worker exception → run COMPLETED, all N rows present, exactly one with `executionStatus = ERROR` carrying the synthetic envelope.
  - Run cancelled mid-flight → run CANCELLED; `count(test_case_run_results WHERE run_id = X)` is strictly less than `numberOfTestCases × numberOfRuns`; rows that exist are real (no synthetic CANCELLED rows).
  - Run with simulated dispatch-loop catastrophe (e.g., DB closed mid-page) → run FAILED via existing outer catch, partial rows preserved.
- [x] 3.8 Run unit tests: `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.job.InProcessEvaluationExecutorTest" --tests "com.epam.aidial.evaluation.service.domain.job.TestCaseRunResultFactoryTest"`.
- [x] 3.9 Run the new functional tests: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$EvaluationExecutorFailureModesTests"`.
- [x] 3.10 Run full build to confirm no regressions: `./gradlew clean build`.

## 4. Documentation and convention updates

- [x] 4.1 Update `docs/configuration.md`: clarify `test-suite-run.execution.cancellation-grace-period-ms` semantics — applies only after cancellation, not as overall timeout.
- [x] 4.2 Confirm no AGENTS.md update is required — this change does not introduce a new top-level package, qualifier convention, or pagination strategy. (No task to update AGENTS.md.)
- [x] 4.3 Confirm no `openspec/specs/README.md` update — the modified spec already exists in the index and its summary remains accurate.

## 5. Verification

- [x] 5.1 Run `openspec validate fix-lost-test-case-results-on-error --strict` and confirm no errors.
- [x] 5.2 Run `./gradlew checkstyleMain checkstyleTest` and fix any style violations.
- [ ] 5.3 Manual smoke test in a local environment: trigger a long-running suite (>30 s) via API, confirm it does NOT throw `TimeoutException` and run reaches COMPLETED with all rows.
- [ ] 5.4 Manual smoke test cancellation: start a slow run, call `POST /api/v1/test-suite-runs/{id}/cancel`, confirm run reaches CANCELLED, confirm `count(test_case_run_results)` for the run is less than `numberOfTestCases × numberOfRuns` (no rows synthesized for unfinished cases), and confirm the WARN log line names the unfinished count.
- [ ] 5.5 Manual smoke test worker exception: temporarily monkey-patch a worker to throw on a specific test case, run the suite, confirm an `executionStatus = ERROR` row appears for that case with the synthetic envelope, and confirm the run still reaches COMPLETED.
