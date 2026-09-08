# Run Cancellation: executor teardown, guarded status writes

## Problem it solves

Cancelling a `RUNNING` test suite run used to flip an in-memory `AtomicBoolean` polled cooperatively at
~23 call sites across 9 classes (root module and `evaluation-runner-core`). Two problems fell out of that:

- **Unobservable cancel.** `POST /cancel` never touched the database, so the run kept reporting `RUNNING`
  until the job finished draining — a client reloading the page could not tell "still running" from
  "cancel requested, still draining".
- **Smeared cancellation-awareness.** Every loop, retry and backoff had to remember to poll the flag, and
  that produced a latent bug: a Phase 2 analytics batch-write failure *set* the same signal, so the run
  ended `CANCELLED` instead of the spec-mandated `FAILED` / `ANALYTICS_WRITE_FAILED`.

## The mechanism

`RunHandle` (`service.domain.job`, plain class, not a bean) owns one
`Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())` per run — OTel context propagation is
preserved across the wrapped executor. It exposes `executor()`, `cancel()` (= `shutdownNow()` + sets an
internal `cancelled` flag), `isCancelled()`, `throwIfCancelled()` (throws `CancellationException` once
cancelled), and `close()` (= `shutdownNow()` too, but does **not** set `cancelled` — a run that finished on
its own is never reported as cancelled). `shutdownNow()` is used in both paths instead of
`ExecutorService.close()`, whose default implementation waits unboundedly for termination and only
escalates if the calling thread is interrupted — which never happens here (see "Never interrupt the job thread" below). `shutdownNow()`
on `close()` also reaps per-result-timeout worker orphans, since `Future.cancel(true)` alone does not
interrupt an already-running task.

`ActiveRunRegistry` (`@Component @LogExecution`) is a per-JVM `ConcurrentHashMap<UUID, RunHandle>` with
`register`, `cancel` (no-op if the run has no handle on this instance), `remove`, `find`.
`TestSuiteEvaluationJob.dispatch` registers the handle on the caller's thread (so a cancel racing dispatch
still finds it) before submitting `run(...)` to the shared `testSuiteRunExecutor`.

Both execution phases dispatch onto the run's own executor instead of creating their own:
`EvaluationContext.executor` (runner-core) and `MetricEvaluationContext.executor` both carry
`handle.executor()`. `TestCaseRunner.submit(page)` returns `boolean` — `false` as soon as the executor
rejects a submission (caught `RejectedExecutionException`, permit released, loop left), signalling the
caller to stop fetching further pages. `TestCaseRunner.awaitCompletion()` **always** runs before any flush,
on both the happy path and the cancel path, so the "single final flush after all workers have terminated"
invariant holds either way.

## Status lifecycle

`RunStatus` gained `CANCELLING` between `RUNNING` and the terminal statuses:
`PENDING → RUNNING → CANCELLING → CANCELLED` (or `COMPLETED` / `FAILED` without ever passing through
`CANCELLING`). `RunStatus.ACTIVE_STATUSES = {PENDING, RUNNING, CANCELLING}` — a `CANCELLING` run still
counts against concurrency limits until it reaches a terminal status.

`TestSuiteRunService.cancelRun` writes `CANCELLING` **synchronously**, in the same
`@Transactional("metaTransactionManager")` call that services the cancel request: `markCancelling(id)`
(`SET status='CANCELLING' WHERE status='RUNNING'`) for a `RUNNING` run, or an optimistic
`PENDING → CANCELLED` transition for a run that has not started yet. `registry.cancel(runId)` — the actual
`shutdownNow()` — runs in a `TransactionSynchronization.afterCommit` callback, the same idiom `createRun`
uses for dispatch, so a rolled-back cancel never leaves a dead executor behind a still-`RUNNING` row.

`PostgresTestSuiteRunRepository`'s terminal writes are all guarded, optimistic, and return the affected-row
count instead of `void`:

- `updateToRunning` — `WHERE status = 'PENDING'`
- `updateToCompleted` — `WHERE status = 'RUNNING'`
- `updateToCancelled` — `WHERE status IN ('RUNNING', 'CANCELLING')`
- `updateToFailed` — `WHERE status IN ('PENDING', 'RUNNING', 'CANCELLING')` (never overwrites a terminal
  status, but is still allowed to fire on `CANCELLING` for the analytics-write case below)
- `markCancelling` — `WHERE status = 'RUNNING'`
- `cancelOrphanedCancellingRuns` — `WHERE status = 'CANCELLING'`

`TestSuiteEvaluationJob.run` checks `throwIfCancelled()` at every phase boundary and treats a zero-row
`updateToCompleted` as "cancelled meanwhile" — falling through to `updateToCancelled`. **CANCELLED wins**: a
run that raced to completion while `CANCELLING` still ends up `CANCELLED`, never `COMPLETED`, because a
client that asked to cancel expects `CANCELLED`. The one deliberate exception is `AnalyticsWriteException`
(Phase 2 flush failure, including the `finally` flush that runs while unwinding a cancel): it **always**
maps to `FAILED` / `ANALYTICS_WRITE_FAILED`, even when `handle.isCancelled()` is true — an analytics write
failure must never be reported as a cancellation.

Reconciliation (`TestSuiteRunReconciliation.reconcileOrphanedRuns`, on `ApplicationReadyEvent`, one meta
transaction) fails orphaned `PENDING`/`RUNNING` rows first, then finalizes orphaned `CANCELLING` rows to
`CANCELLED` via `cancelOrphanedCancellingRuns()` — mirroring `failOrphanedRuns`.

## Rules for contributors

- **Never interrupt the job thread.** HikariCP connection acquisition (`SynchronousQueue.poll`) is
  interruptible; interrupting the job thread risks it failing to obtain a connection mid-write. Cancellation
  reaches the orchestrator only via workers finishing fast, `submit` returning `false` /
  `RejectedExecutionException`, and `handle.throwIfCancelled()` at phase boundaries — never via
  `Thread.interrupt()` on the thread running `TestSuiteEvaluationJob.run`.
- **Never call `ExecutorService.close()`** on a run's executor. Always `shutdownNow()` (see `RunHandle`
  above) — `close()`'s unbounded wait has no escape hatch given the rule above.
- **Do not add cancellation flags or polled checks.** Everything on the worker path is ordinary
  `InterruptedException` hygiene: catch it, re-interrupt (`Thread.currentThread().interrupt()`), never
  swallow it. The only sanctioned cancellation-aware checks are `handle.throwIfCancelled()` at the job's
  phase boundaries and the interrupt-status check in `TestCaseRunner.runWorker` (see below).
- **Worker-path HTTP clients must stay interruptible.** Use `JdkClientHttpRequestFactory` (JDK
  `HttpClient`), never `SimpleClientHttpRequestFactory` (`HttpURLConnection`, which blocks until
  `read-timeout-ms` regardless of the thread's interrupt status), for any `RestClient` invoked from a worker
  thread. `MetricProviderRestClientConfiguration` and `DialCoreDeploymentInvokerConfiguration` are the
  reference implementations.
- **`TestCaseRunner.runWorker`'s interrupt check is the one worker-side exception.** After
  `evaluationWorker.execute(...)` returns or throws, if the thread is interrupted (or the exception is/wraps
  `InterruptedException`), the case is counted as interrupted and no row — real or synthetic — is written to
  `test_case_run_results`. This is deliberate: "no synthetic rows for unfinished cases" on the cancel path.

## Multi-instance caveat

`ActiveRunRegistry` is per-JVM. In a multi-instance deployment, a cancel request served by an instance that
does not own the run's `RunHandle` still writes `CANCELLING` (the DB write is authoritative) but has nothing
to `shutdownNow()`. The owning instance's job eventually observes `CANCELLING` through its own guarded
terminal write (`updateToCompleted`/`updateToFailed` affecting zero rows) and finalizes `CANCELLED` — or, if
the owning instance crashes first, startup reconciliation finalizes it once that instance restarts. A
`CANCELLING` run with no live handle anywhere still counts toward concurrency limits until it reaches a
terminal status. Single-instance is still the standing assumption for reconciliation and SSE.

## Testing tips

- Simulate a cancel in a unit test by calling `handle.cancel()` (or `handle.executor().shutdownNow()`
  directly) before or during the call under test, then assert the resulting `CancellationException` /
  `RejectedExecutionException` path.
- Mockito returns `0` for an unstubbed `int`-returning repository method. In happy-path tests that don't
  care about the guard, stub `updateToRunning`/`updateToCompleted` to return `1` — otherwise the job takes
  the "zero rows affected ⇒ cancelled" branch and the test asserts the wrong terminal status.

## References

- `openspec/specs/test-suite-runs/spec.md` — cancel endpoint, `CANCELLING` status, concurrency limits.
- `openspec/specs/eval-execution-engine/spec.md` — Phase 1 immediate cancellation, executor rejection.
- `openspec/specs/metric-evaluation/spec.md` — Phase 2 shared-executor shutdown, batch-write failure.
