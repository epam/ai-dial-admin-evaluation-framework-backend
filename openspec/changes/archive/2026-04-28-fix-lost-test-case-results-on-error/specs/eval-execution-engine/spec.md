## ADDED Requirements

### Requirement: Synthetic ERROR result for worker exception
When a test case worker throws an unexpected exception (an exception that escapes the worker's own internal handling and reaches the executor's per-task `catch (Exception e)`), the executor SHALL emit a synthetic `TestCaseRunResult` with `executionStatus = ERROR` to the result buffer in best-effort fashion. This makes per-case worker bugs visible in the analytics surface — without it, a buggy worker silently drops cases. The synthesis is best-effort: if appending the synthetic row to the buffer itself fails, the executor SHALL log the secondary failure and continue without further fallback. The executor SHALL NOT chain additional retry / fallback layers beyond the single best-effort attempt — the JVM may be in any state, and the next safe action is to keep going so other cases can still finish.

#### Scenario: Worker throws unexpected exception
- **WHEN** the runnable inside `InProcessEvaluationExecutor` catches an exception escaping `evaluationWorker.execute(...)`
- **THEN** the executor SHALL build a synthetic `TestCaseRunResult` via `TestCaseRunResultFactory.errorResult(...)` with `executionStatus = ERROR`, `responseBody` set to a JSON error envelope `{"error":{"type":"<exception class name>","message":"<exception message>","origin":"executor"}}`, `responseStatusCode = null`, `execStartedAtMs = execCompletedAtMs = clock.millis()`, `execDurationMs = 0`, `retryCount = 0`, `logDetails = null`
- **AND** the executor SHALL pass that result to `resultBatchWriter.addResult(buffer, synthetic)` so it is persisted alongside other results

#### Scenario: Per-case error does not fail the run
- **WHEN** one or more synthetic `ERROR` rows are produced for a run because workers threw unexpected exceptions
- **THEN** the run SHALL still reach `COMPLETED` status (per-case errors do NOT mark the whole run FAILED)

#### Scenario: TestCaseRunResultFactory must not throw
- **WHEN** the executor invokes `TestCaseRunResultFactory.errorResult(...)`
- **THEN** the factory SHALL build the synthetic row from the input, the run index, the caught exception, and the current clock millis via fixed-shape construction — no template resolution, no JSON parsing of test case data, no DB access — guaranteeing it cannot throw and double-drop the case

#### Scenario: Buffer append failure is logged, not retried
- **WHEN** the inner `resultBatchWriter.addResult(buffer, synthetic)` call itself throws (e.g., the buffer's downstream batch flush triggered by reaching `result-batch-size` threshold fails)
- **THEN** the executor SHALL log the secondary failure at `ERROR` level (with the exception as last SLF4J argument) and continue. It SHALL NOT raise, SHALL NOT retry, and SHALL NOT make a second synthesis attempt.

#### Scenario: Broad catch is intentional
- **WHEN** static analysis or code review questions the broad `catch (Exception e)` in the worker runnable
- **THEN** the catch IS intentional and documented as the deliberate exception to AGENTS.md's "catch specific exceptions" rule. The contract of this catch is "any unexpected runtime failure escaping the worker" — narrowing it would re-introduce the silent-drop bug that this requirement exists to prevent. The catch SHALL remain `catch (Exception e)`.

### Requirement: Diagnostic logging for unfinished cases on cancel
When the post-grace cancellation path executes `executor.shutdownNow()`, the executor SHALL emit a single WARN log line naming the count of dispatched test case tasks whose futures had not completed at that point. This count is the authoritative diagnostic signal for "how many cases were interrupted by cancellation." The executor SHALL NOT synthesize result rows for these cases — absence of rows in `test_case_run_results`, combined with the run's `status = CANCELLED`, IS the signal.
Status: **Implemented**

#### Scenario: Unfinished count logged on cancel
- **WHEN** cancellation is signaled, the grace period elapses, and the executor calls `shutdownNow()`
- **THEN** the executor SHALL compute `unfinishedCount = futures.stream().filter(f -> !f.isDone()).count()` and emit one log line at WARN level: `"Run {runId} cancelled with {unfinishedCount} test case(s) interrupted before completion"`

#### Scenario: No synthetic rows for unfinished cases
- **WHEN** the cancellation grace period expires with unfinished workers
- **THEN** the executor SHALL NOT iterate the futures list to write synthetic `CANCELLED`, `INTERRUPTED`, or any other status row for those cases. The cases simply do not appear in `test_case_run_results`.

#### Scenario: Absence + run status carries the signal
- **WHEN** an operator inspects a run with `status = CANCELLED` and finds `count(test_case_run_results WHERE run_id = X)` < `numberOfTestCases × numberOfRuns`
- **THEN** the missing rows correspond to test cases that were either never dispatched (cancellation observed in dispatch loop) or interrupted by post-grace shutdown. The run's `status = CANCELLED` and the WARN log line are the authoritative explanation; no per-case row is required to convey this.

### Requirement: Catastrophic executor failures are rethrown
If an exception escapes the dispatch loop itself (e.g., `findByRunId` throws because the meta DB connection died, an OOM in path-resolution code), the executor SHALL best-effort flush the buffer and re-throw the original exception so `TestSuiteEvaluationJob.executeRunAsync` marks the run `FAILED` via its existing outer catch. The current code swallows such exceptions — that behaviour is removed.

#### Scenario: Dispatch-loop exception rethrown
- **WHEN** an exception escapes the dispatch loop (e.g., from `testCaseRunInputRepository.findByRunId`)
- **THEN** the executor's `catch (Exception e)` SHALL log the failure with the exception as last SLF4J argument, attempt one final `resultBatchWriter.flush(buffer)` inside a `try/catch` that logs and continues on failure, and then **re-throw** the original exception (unwrapped, no new exception class introduced)

#### Scenario: Run marked FAILED by outer catch
- **WHEN** the executor rethrows a catastrophic failure
- **THEN** `TestSuiteEvaluationJob.executeRunAsync`'s existing `catch (Exception e)` SHALL log it and call `repository.updateToFailed(runId, e.getMessage(), errorDetails, now, now)` with `code = "UNEXPECTED_ERROR"` and `category = INTERNAL` — preserving the existing error path

## MODIFIED Requirements

### Requirement: Graceful cancellation
When a run is cancelled, the executor SHALL stop dispatching new calls, wait for in-flight calls to complete (up to a grace period bounded by `cancellationGracePeriodMs`), then abort remaining calls. The cancellation signal SHALL be registered before async dispatch to prevent race conditions. The `cancellationGracePeriodMs` value SHALL apply ONLY when `cancellationSignal == true`; it SHALL NOT be used as an overall evaluation timeout. A run that takes longer than the grace period without being cancelled SHALL continue to completion. The executor SHALL NOT synthesize result rows for cases interrupted by post-grace shutdown — see "Diagnostic logging for unfinished cases on cancel".
Status: **Implemented**

#### Scenario: Cancellation signal registered before async dispatch
- **WHEN** `TestSuiteRunService` triggers an evaluation run
- **THEN** `TestSuiteEvaluationJob.registerCancellationSignal(runId)` SHALL be called synchronously BEFORE calling the `@Async executeRunAsync(runId, token)` method. The `@Async` annotation means the entire `executeRunAsync()` body runs in the executor thread, so signal registration must happen in the caller's thread. `executeRunAsync()` SHALL retrieve the pre-registered signal from the `activeCancellationSignals` map. This prevents `interruptRun(runId)` from silently losing the cancellation if called before the async thread starts. If `executeRunAsync()` dispatch fails (exception, executor rejection), the caller SHALL clean up the registered signal to prevent map leaks.

#### Scenario: Cancellation stops new dispatches
- **WHEN** cancellation is signaled
- **THEN** the executor SHALL immediately stop submitting new test case calls to the worker pool

#### Scenario: Grace period applied only after cancellation
- **WHEN** cancellation is signaled and there are in-flight HTTP calls
- **THEN** the executor SHALL call `executor.shutdown()` (no new tasks accepted) and wait on `CompletableFuture.allOf(futures).get(cancellationGracePeriodMs, MILLISECONDS)` for in-flight workers to drain

#### Scenario: Long-running uncancelled run does NOT time out
- **WHEN** a run executes for longer than `cancellationGracePeriodMs` and `cancellationSignal` is NEVER set
- **THEN** the executor SHALL wait for all dispatched futures via unbounded `CompletableFuture.allOf(futures).join()` (no timeout). The run SHALL NOT be aborted simply because total wall-clock execution exceeded the grace-period value. Per-call wall-clock bounds remain the responsibility of `requestTimeoutMs` per test case.

#### Scenario: Abort after grace period
- **WHEN** the grace period expires and in-flight calls are still running
- **THEN** the executor SHALL call `executor.shutdownNow()` to interrupt remaining virtual threads
- **AND** the executor SHALL log the unfinished count once at WARN level (see "Diagnostic logging for unfinished cases on cancel")
- **AND** the executor SHALL NOT synthesize result rows for the unfinished cases — they remain absent from `test_case_run_results`

#### Scenario: Partial results preserved
- **WHEN** a run is cancelled at any point
- **THEN** all results that completed and were written to analytics DB before cancellation SHALL be preserved (not deleted)

#### Scenario: Cancellation signal cleanup
- **WHEN** the async run task completes (success, failure, or cancellation)
- **THEN** the cancellation signal SHALL be removed from the `cancellationSignals` map in a `finally`/`whenComplete` block

### Requirement: Batch result writing
The executor SHALL buffer completed `TestCaseRunResult` records and flush them to the analytics database in configurable batches. The executor SHALL perform exactly one final flush at the end of execution, after all virtual threads have terminated (or been interrupted via `shutdownNow()`) — eliminating the race where late-arriving worker writes land in a buffer already drained by the final flush.
Status: **Implemented**

#### Scenario: Batch flush on size
- **WHEN** the result buffer reaches `result-batch-size` (system config, default 100)
- **THEN** the executor SHALL flush the buffer to the analytics DB via `TestCaseRunResultRepository.saveAll()` in an analytics transaction

#### Scenario: Final flush on completion
- **WHEN** all test cases have been executed (run completes)
- **THEN** the executor SHALL flush any remaining buffered results — exactly once, AFTER worker shutdown has completed

#### Scenario: Flush on cancellation
- **WHEN** the run is cancelled and in-flight calls complete (or are interrupted)
- **THEN** the single final `flush(buffer)` at step (5) of the shutdown ordering window (see "No flush during shutdown ordering window" below) SHALL persist all accumulated results — real rows from completed workers plus any synthetic ERROR rows from worker exceptions that surfaced during the cancel window — before `execute()` returns to `TestSuiteEvaluationJob.executeRunAsync`, which then marks the run CANCELLED. There is NO separate cancellation-specific flush; cancellation reuses the same final-flush invocation as normal completion.

#### Scenario: No flush during shutdown ordering window
- **WHEN** the executor reaches its `finally` block
- **THEN** the order SHALL be: (1) `executor.shutdown()` (no new tasks), (2) wait for futures bounded by grace if cancelled or unbounded otherwise, (3) `executor.shutdownNow()` only if cancelled and futures still incomplete, (4) WARN log with unfinished count if cancelled, (5) single final `flush(buffer)`. Flushing before step (5) is forbidden — it re-introduces the race where a late `addResult` lands in a buffer that was already drained.

#### Scenario: Batch write failure
- **WHEN** a batch write to analytics DB fails
- **THEN** the executor SHALL set the cancellation signal, stop dispatching new calls, drain in-flight calls (up to grace period), log the error, and mark the run as FAILED with error category `INTERNAL` and code `ANALYTICS_WRITE_FAILED`. This prevents workers from continuing to execute and buffer results that can never be persisted.
