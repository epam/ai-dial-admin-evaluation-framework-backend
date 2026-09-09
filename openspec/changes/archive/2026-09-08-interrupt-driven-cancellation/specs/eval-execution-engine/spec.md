## MODIFIED Requirements

### Requirement: Evaluation executor interface
The system SHALL define an `EvaluationExecutor` interface with a single `execute(EvaluationContext)` method. The `EvaluationContext` SHALL carry: `runId`, `testSuiteId`, execution settings (concurrency, timeout, retry, rate limit), the run's worker executor (owned and shut down by the run owner, never by the executor implementation), a progress callback, and a result sink. This interface enables swapping in-process execution with K8s Job submission without changing orchestration code.
Status: **Implemented**

#### Scenario: In-process executor is the default
- **WHEN** the application starts with default configuration
- **THEN** the `InProcessEvaluationExecutor` bean SHALL be the active `EvaluationExecutor` implementation

#### Scenario: Executor receives fully populated context
- **WHEN** `TestSuiteEvaluationJob` dispatches a run
- **THEN** it SHALL construct an `EvaluationContext` from the run's `RunConfigDto` (with system defaults for omitted fields) carrying the run's registered worker executor, and pass it to the executor. The run's executor SHALL be registered before async dispatch so a cancel request can reach it even if the job thread has not started yet.

### Requirement: Retry policy execution
When a `RetryPolicyDto` is configured with `maxRetries > 0`, the worker SHALL retry failed calls according to the policy.
Status: **Implemented**

#### Scenario: No retry configured (default)
- **WHEN** `retry` is null or `maxRetries = 0`
- **THEN** the worker SHALL NOT retry failed calls — a single failure is recorded immediately

#### Scenario: Retry on retryable failure
- **WHEN** a call fails with a retryable condition (timeout, network error, HTTP 429, HTTP 5xx) and `maxRetries > 0`
- **THEN** the worker SHALL retry up to `maxRetries` times with exponential backoff: delay = `min(retryDelayMs * retryBackoffMultiplier^(attemptIndex - 1), maxRetryDelayMs)` where `maxRetryDelayMs` is the system-configured cap on computed retry delay (default 60000ms from `test-suite-run.retry.max-retry-delay-ms`). This cap prevents exponential growth from producing unreasonable delays (e.g., multiplier=3, 10 retries → uncapped attempt 10 would be 5.5 hours).

#### Scenario: Non-retryable failure
- **WHEN** a call fails with HTTP 4xx (except 429 and 401/403)
- **THEN** the worker SHALL NOT retry — the result is recorded immediately with `executionStatus = FAILED`

#### Scenario: Authentication failure (401/403)
- **WHEN** a call fails with HTTP 401 or 403
- **THEN** the worker SHALL set `executionStatus = ERROR` (not FAILED — this is an infrastructure/auth issue, not a target endpoint error), store the response body and status code, and NOT retry (the JWT is likely expired and retries won't help). The run continues executing remaining cases per the continue-on-failure policy.

#### Scenario: All retries exhausted
- **WHEN** all retry attempts fail
- **THEN** the worker SHALL record the result from the last attempt (preserving the final HTTP status, response body, and execution timing from the last attempt)

#### Scenario: Retry respects cancellation
- **WHEN** the run is cancelled while a retry is pending (backoff sleep)
- **THEN** the worker thread SHALL be interrupted, the backoff sleep SHALL end immediately, the worker SHALL NOT issue another attempt, and the interrupted case SHALL NOT produce a result row (see "Immediate cancellation")

### Requirement: Batch result writing
The executor SHALL buffer completed `TestCaseRunResult` records and flush them to the analytics database in configurable batches. The executor SHALL perform exactly one final flush at the end of execution, after all dispatched worker tasks have terminated (normally or via interruption) — eliminating the race where late-arriving worker writes land in a buffer already drained by the final flush.
Status: **Implemented**

#### Scenario: Batch flush on size
- **WHEN** the result buffer reaches `result-batch-size` (system config, default 100)
- **THEN** the executor SHALL flush the buffer to the analytics DB via `TestCaseRunResultRepository.saveAll()` in an analytics transaction

#### Scenario: Final flush on completion
- **WHEN** all test cases have been executed (run completes)
- **THEN** the executor SHALL flush any remaining buffered results — exactly once, AFTER all worker tasks have completed

#### Scenario: Flush on cancellation
- **WHEN** the run is cancelled and the interrupted workers have terminated
- **THEN** the single final flush SHALL persist all accumulated results — real rows from workers that completed before the interrupt plus any synthetic ERROR rows from non-cancellation worker exceptions — before `execute()` returns to the job, which then marks the run CANCELLED. There is NO separate cancellation-specific flush; cancellation reuses the same final-flush invocation as normal completion.

#### Scenario: No flush during shutdown ordering window
- **WHEN** the executor reaches its `finally` block
- **THEN** the single final flush SHALL run only after the wait on all dispatched worker futures has returned (normally, or because the run's executor was shut down and the workers were interrupted). Flushing earlier is forbidden — it re-introduces the race where a late `addResult` lands in a buffer that was already drained.

#### Scenario: Batch write failure
- **WHEN** a batch write to analytics DB fails
- **THEN** the executor SHALL stop dispatching new calls, let the failure propagate to the job, log the error, and the job SHALL mark the run as FAILED with error category `INTERNAL` and code `ANALYTICS_WRITE_FAILED`. The failure SHALL NOT be expressed as a cancellation: an analytics write failure is never reported as `CANCELLED`.

### Requirement: Diagnostic logging for unfinished cases on cancel
When a run is cancelled with dispatched worker tasks still incomplete, the executor SHALL emit a single WARN log line naming the count of dispatched test case tasks whose futures had not completed normally. This count is the authoritative diagnostic signal for "how many cases were interrupted by cancellation." The executor SHALL NOT synthesize result rows for these cases — absence of rows in `test_case_run_results`, combined with the run's `status = CANCELLED`, IS the signal.
Status: **Implemented**

#### Scenario: Unfinished count logged on cancel
- **WHEN** the executor's wait for worker futures returns after the run's executor was shut down and some tasks were interrupted
- **THEN** the executor SHALL emit one log line at WARN level: `"Run {runId} cancelled with {unfinishedCount} test case(s) interrupted before completion"`

#### Scenario: No synthetic rows for unfinished cases
- **WHEN** worker tasks are interrupted by cancellation
- **THEN** the executor SHALL NOT iterate the futures list to write synthetic `CANCELLED`, `INTERRUPTED`, or any other status row for those cases. The cases simply do not appear in `test_case_run_results`.

#### Scenario: Absence + run status carries the signal
- **WHEN** an operator inspects a run with `status = CANCELLED` and finds `count(test_case_run_results WHERE run_id = X)` < `numberOfTestCases × numberOfRuns`
- **THEN** the missing rows correspond to test cases that were either never dispatched (executor already shut down) or interrupted mid-flight. The run's `status = CANCELLED` and the WARN log line are the authoritative explanation; no per-case row is required to convey this.

### Requirement: Catastrophic executor failures are rethrown
If an exception escapes the dispatch loop itself (e.g., `findByRunId` throws because the meta DB connection died, an OOM in path-resolution code), the executor SHALL best-effort flush the buffer and re-throw the original exception so the evaluation job marks the run `FAILED` via its outer catch. Rejection of a task by the run's executor is NOT a catastrophic failure: it is the cancellation signal and is handled inside the dispatch loop (`submit` reports it and the loop ends), so it never reaches this path. The current code swallows such exceptions — that behaviour is removed.
Status: **Implemented**

#### Scenario: Dispatch-loop exception rethrown
- **WHEN** an exception escapes the dispatch loop (e.g., from `testCaseRunInputRepository.findByRunId`)
- **THEN** the executor's `catch (Exception e)` SHALL log the failure with the exception as last SLF4J argument, attempt one final `resultBatchWriter.flush(buffer)` inside a `try/catch` that logs and continues on failure, and then **re-throw** the original exception (unwrapped, no new exception class introduced)

#### Scenario: Run marked FAILED by outer catch
- **WHEN** the executor rethrows a catastrophic failure and the run has not been cancelled
- **THEN** the evaluation job's outer `catch (Exception e)` SHALL log it and call `repository.updateToFailed(runId, e.getMessage(), errorDetails, now, now)` with `code = "UNEXPECTED_ERROR"` and `category = INTERNAL` — preserving the existing error path

#### Scenario: Catastrophic failure during cancellation ends CANCELLED
- **WHEN** the executor rethrows a dispatch-loop exception while the run's executor has already been shut down by a cancel request
- **THEN** the evaluation job SHALL record the run as CANCELLED, not FAILED — the user's cancellation takes precedence over an incidental failure of the aborting run (an analytics batch-write failure is the one exception and still yields FAILED / `ANALYTICS_WRITE_FAILED`)

## REMOVED Requirements

### Requirement: Graceful cancellation
**Reason**: The grace-period drain (`cancellationGracePeriodMs`) and the polled `AtomicBoolean` cancellation signal are replaced by immediate shutdown of the run's own worker executor; there is no drain window to configure and no signal to register.
**Migration**: See "Immediate cancellation" below. Remove `test-suite-run.execution.cancellation-grace-period-ms` from deployment configuration (a stale key is ignored).

## ADDED Requirements

### Requirement: Immediate cancellation
When a run is cancelled, the run's worker executor SHALL be shut down immediately: no new test case tasks are accepted, every in-flight worker task is interrupted, and the executor SHALL wait for the interrupted tasks to terminate before its single final flush. There is no grace period and no configurable drain window. Cancellation SHALL be deliverable before the async job thread has started (the run's executor is registered before dispatch — see test-suite-runs "Evaluation job orchestration"). The executor SHALL NOT synthesize result rows for cases interrupted by cancellation — see "Diagnostic logging for unfinished cases on cancel". Only the worker tasks are interrupted; the job/executor thread that orchestrates a run SHALL NOT be interrupted, so its final flush and status writes are unaffected.
Status: **Implemented**

#### Scenario: Cancellation stops new dispatches
- **WHEN** the run's executor has been shut down
- **THEN** any attempt to submit a further test case task SHALL be rejected and the dispatch loop SHALL end; no further pages of inputs are fetched

#### Scenario: In-flight calls are interrupted immediately
- **WHEN** cancellation is requested while HTTP or MCP calls are in flight
- **THEN** the worker threads SHALL be interrupted at once — a retry backoff sleep, the HTTP send, or the streaming response read all end with an interruption — and the executor SHALL wait for those tasks to terminate before flushing. The dispatching thread is not interrupted: its pacing waits (concurrency semaphore, rate limiter) end because interrupted workers release their permits, and its next submission is rejected

#### Scenario: Long-running uncancelled run does NOT time out
- **WHEN** a run executes for a long time and is never cancelled
- **THEN** the executor SHALL wait for all dispatched futures without any overall timeout. Per-call wall-clock bounds remain the responsibility of `requestTimeoutMs` per test case.

#### Scenario: Interrupted cases produce no rows
- **WHEN** a worker task is interrupted by cancellation (before, during or after its call)
- **THEN** the executor SHALL NOT write any result row for that case — neither a real row from a partially received response nor a synthetic ERROR row — the case remains absent from `test_case_run_results`

#### Scenario: Partial results preserved
- **WHEN** a run is cancelled at any point
- **THEN** all results that completed and were written to analytics DB before cancellation SHALL be preserved (not deleted), and results of workers that completed before the interrupt but were still buffered SHALL be persisted by the final flush

#### Scenario: Run registration cleanup
- **WHEN** the async run task completes (success, failure, or cancellation)
- **THEN** the run's executor SHALL be shut down (if not already) and its registration removed in a `finally` block

## Implementation Notes
- `runner.job.EvaluationContext` carries the run's `ExecutorService` (`executor`) instead of an `AtomicBoolean`
  signal and a grace period; `TestCaseRunner` no longer creates or shuts down an executor — the owner
  (`TestSuiteEvaluationJob` via `RunHandle`, or the CLI's `RunOrchestrationService`) does. `awaitCompletion()` is an
  interruptible wait on all dispatched futures.
- The only remaining cancellation-related check in the runner is the interrupt-status test in `TestCaseRunner`'s
  worker wrapper (drop the case's results when the worker was interrupted). Retry loops in `EvaluationWorker` (MCP)
  and `DeploymentTurnInvoker` (HTTP) keep their existing `InterruptedException → re-interrupt` handling and no longer
  poll a flag; `TurnLoopExecutor` no longer special-cases a cancelled turn.
- `EvaluationRunProperties.Execution.cancellationGracePeriodMs` and the
  `test-suite-run.execution.cancellation-grace-period-ms` key are removed.
