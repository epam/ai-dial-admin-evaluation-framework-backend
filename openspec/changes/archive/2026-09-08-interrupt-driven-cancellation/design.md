## Context

See proposal.md — Why. Relevant current state:

- `TestSuiteEvaluationJob.executeRunAsync` is `@Async("testSuiteRunExecutor")` and returns `void`, so no handle to
  the running job exists. Cancellation is a `ConcurrentHashMap<UUID, AtomicBoolean>` registered by
  `TestSuiteRunService.dispatchEvaluation` before dispatch; `interruptRun` flips the flag.
- Phase 1 (`InProcessEvaluationExecutor` → runner-core `TestCaseRunner`) and Phase 2
  (`InProcessMetricEvaluationExecutor`) each create their own `Context.taskWrapping(newVirtualThreadPerTaskExecutor())`.
  Phase 1 implements a grace-period drain; Phase 2 does `shutdownNow()` in `finally` (which is also what kills
  per-result-timeout orphans today, since `CompletableFuture.cancel(true)` does not interrupt a running task). Phase 3
  (`MetricScoreComputationExecutor`) is sequential jOOQ.
- Worker-path blocking primitives: `Semaphore.acquire` and Bucket4j `asBlocking().consume` run on the *submitting*
  (job) thread; `Thread.sleep` in backoffs, the DIAL Core HTTP send/stream read (JDK `HttpClient` via
  `JdkClientHttpRequestFactory`; Spring re-sets the interrupt flag and throws `IOException`), and the MCP call (reactor
  blocking subscriber, re-interrupts) run on worker threads and are interruptible. **The metric-provider `RestClient`
  uses `SimpleClientHttpRequestFactory` (`HttpURLConnection`), which is not interruptible** — an interrupted Phase 2
  call would block until `read-timeout-ms` (default 150 000 ms).
- JDBC is not interruptible, but HikariCP's connection acquisition is (`SynchronousQueue.poll`): an interrupted
  thread may fail to obtain a connection. This rules out interrupting the job thread.
- `RunStatus` has no `CANCELLING`; `status` column is `VARCHAR(20)` with no CHECK constraint. `updateToFailed`,
  `updateToCompleted`, `updateToCancelled`, `updateToRunning` have no status guard.
- `openspec/changes/ef-as-dial-app/design.md` plans to reuse the `AtomicBoolean` signal to fail a run when the
  DIAL Core SSE connection drops.

## Goals / Non-Goals

**Goals:**
- Exactly one place decides a run's terminal status, driven by guarded DB writes.
- Cancellation-awareness limited to: (a) the cancel endpoint, (b) the job's phase boundaries, (c) one
  interrupt-status check in the Phase 1 worker wrapper, (d) `RejectedExecutionException` handling in `TestCaseRunner.submit`.
  Everything else is ordinary `InterruptedException` hygiene.
- `CANCELLING` visible on every read surface (GET, list, SSE) as soon as the cancel request commits.
- Cancellation latency bounded by the slowest interruptible call, not by HTTP read timeouts.

**Non-Goals:**
- Cross-instance cancellation (registry stays per-JVM; see Risks).
- Graceful drain of in-flight calls.
- Structured concurrency (`StructuredTaskScope` is still preview in Java 25).
- CLI-side cancellation UX (Ctrl-C hook is an optional extra, not required).

## Decisions

### D1. Cancellation = `shutdownNow()` on a per-run executor owned by a `RunHandle`
`RunHandle` (root, `service.domain.job`, plain class, not a bean) wraps
`Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())` (OTel context propagation preserved) and exposes:
- `executor()`;
- `cancel()` = `shutdownNow()`;
- `isCancelled()` = true once `cancel()` was called (tracked by a flag, so a normal `close()` is not mistaken for a cancel);
- `throwIfCancelled()` → `CancellationException`;
- `close()` = `shutdownNow()` as well — **never** `ExecutorService.close()`, whose default implementation waits
  unboundedly and only escalates when the calling thread is interrupted, which D3 forbids. `shutdownNow()` on close
  is also what reaps per-result-timeout orphans in Phase 2 (`f.cancel(true)` does not interrupt them).
`ActiveRunRegistry` (`@Component @LogExecution`) keeps `ConcurrentHashMap<UUID, RunHandle>` with `register`,
`cancel` (no-op if absent), `remove`, `find`.
*Alternatives:* keep the flag but centralize into a `CancellationToken` (still N check sites); DB-status polling
(cross-instance safe but adds DB round-trips and keeps polling); `Future.cancel(true)` on the job (interrupts the
job thread — rejected, see D3).

### D2. Both phases dispatch on the run's executor; rejection ends dispatch, not the flush ordering
`EvaluationContext.executor` (runner-core) and `MetricEvaluationContext.executor` carry the handle's executor.
`TestCaseRunner` and `InProcessMetricEvaluationExecutor` stop creating/shutting down executors; the owner closes it
once in the job's `finally`.

`TestCaseRunner.submit(page)` returns `boolean` — `false` when the executor rejected a task
(`RejectedExecutionException` caught inside `submit`, permit released, loop left). `InProcessEvaluationExecutor`'s
page loop is `do { page = fetch; accepting = runner.submit(page); } while (accepting && page.size() == PAGE_SIZE)`
and `runner.awaitCompletion()` **always** runs before any flush, so the "single final flush after all workers have
terminated" requirement holds on the cancel path too. The catch/finally flush structure for genuine dispatch-loop
failures (meta DB error) is unchanged.

`InProcessMetricEvaluationExecutor` lets `RejectedExecutionException` propagate (its `finally` still flushes fully
assembled rows); the job maps it to CANCELLED (D4).
*Alternative:* per-phase executors registered with the handle — more moving parts for no benefit.

### D3. The job thread is never interrupted
Cancellation reaches the orchestrator only through (a) workers finishing fast → `allOf(futures).get()` returns,
(b) `submit` returning `false` / `RejectedExecutionException` on the next dispatch, (c) `handle.throwIfCancelled()`
before the snapshot phase and between phases. The job's own final flush and status writes run on a clean thread,
so Hikari acquisition is safe. Pacing calls on the job thread (`semaphore.acquire`, rate-limit `consume`) are not
interrupted; they end because interrupted workers release their permits in `finally` (bounded by the workers'
own read timeouts) and the rate limiter yields within one second. `TestCaseRunner.awaitCompletion()` keeps a
`public void` signature: it re-interrupts and throws `CancellationException` on `InterruptedException` (CLI/Ctrl-C
path) and wraps an impossible `ExecutionException` in `IllegalStateException`.

### D4. Terminal status by guarded writes, "CANCELLED wins", analytics write failure always FAILED
Repository changes (`PostgresTestSuiteRunRepository`), all returning affected-row counts:
- `updateToRunning(id, startedAt, updatedAt)` → `WHERE status = 'PENDING'`
- `updateToCompleted(id, completedAt, updatedAt)` → `WHERE status = 'RUNNING'`
- `updateToCancelled(id, completedAt, updatedAt)` → `WHERE status IN ('RUNNING','CANCELLING')`
- `updateToFailed(id, msg, details, completedAt, updatedAt)` → `WHERE status IN ('PENDING','RUNNING','CANCELLING')`
  (never overwrites a terminal status; still allowed on CANCELLING for the analytics-write case below)
- `markCancelling(id)` → `SET status='CANCELLING', updated_at_ms = <TransactionTimestampContext> WHERE status='RUNNING'`
- `cancelOrphanedCancellingRuns()` → `SET status='CANCELLED', completed_at_ms, updated_at_ms = <TransactionTimestampContext>
  WHERE status='CANCELLING'` (mirrors `failOrphanedRuns`, called inside the reconciliation's `@Transactional`).

Job flow:
```
handle = registry.register(runId)                       // caller thread (dispatch)
submit → run():
  try
    handle.throwIfCancelled()                           // cancelled while PENDING before we even started
    snapshot phase (unchanged; on failure → updateToFailed as today; 0 rows ⇒ already terminal, just log)
    if updateToRunning(..) == 0 → return                // cancelled while PENDING during snapshot
    SSE notify (RUNNING)
    inconsistent-snapshot guard (unchanged; updateToFailed)
    handle.throwIfCancelled(); phase1
    handle.throwIfCancelled(); phase2
    handle.throwIfCancelled(); phase3 (non-fatal, unchanged)
    if updateToCompleted(..) == 0 → updateToCancelled(..)
  catch AnalyticsWriteException e     → updateToFailed(.. ANALYTICS_WRITE_FAILED ..)        // takes precedence
  catch CancellationException | RejectedExecutionException → updateToCancelled(..)
  catch Exception e                   → handle.isCancelled() ? updateToCancelled(..)
                                                            : updateToFailed(.. UNEXPECTED_ERROR ..)
  finally handle.close(); registry.remove(runId); SSE notify (terminal)
```
Precedence rule: `AnalyticsWriteException` (thrown by Phase 2's flush, including the `finally` flush that runs while
unwinding a cancel) always maps to FAILED / `ANALYTICS_WRITE_FAILED`, even when the handle is cancelled — the spec
forbids reporting an analytics failure as a cancellation. `AnalyticsWriteException` is a new unchecked exception in
`service.domain.job`.
*Alternative:* COMPLETED wins on a late cancel — rejected by the user; a client that asked to cancel expects CANCELLED.

### D5. Cancel endpoint writes `CANCELLING` synchronously; executor teardown after commit
`TestSuiteRunService.cancelRun` (still `@Transactional("metaTransactionManager")`):
```
terminal              → 409 InvalidOperationException (unchanged)
PENDING               → updateStatusOptimistic(PENDING→CANCELLED); if won: afterCommit(registry.cancel); SSE; return
                        else re-read and continue
CANCELLING            → return current DTO (idempotent 200, no side effects)
RUNNING               → if markCancelling(id) == 0: re-read, dispatch once more on the new status
                        afterCommit(registry.cancel(id)); SSE notify(CANCELLING); return DTO
```
`registry.cancel` runs in a `TransactionSynchronization.afterCommit` (same idiom `createRun` uses for dispatch) so a
rolled-back cancel does not leave a dead executor behind a `RUNNING` row. If the job finishes between commit and
teardown, its guarded `updateToCompleted` sees `CANCELLING`, affects zero rows, and writes CANCELLED — consistent
with D4. A `CANCELLING` run whose handle is absent on this instance (foreign instance, or a row inserted by tests) is
left for the owning job or startup reconciliation; the endpoint does not finalize it.

### D6. `RunStatus.ACTIVE_STATUSES` and reconciliation
`ACTIVE_STATUSES = {PENDING, RUNNING, CANCELLING}` used by `enforceConcurrencyLimits`. `TestSuiteRunReconciliation`
adds a second statement in the same meta transaction: fail PENDING/RUNNING orphans first, then
`cancelOrphanedCancellingRuns()`; log both counts. No `Clock` dependency is added (timestamps come from
`TransactionTimestampContext`, as `failOrphanedRuns` already does).

### D7. Explicit submission instead of `@Async`
`TestSuiteEvaluationJob` injects `@Qualifier("testSuiteRunExecutor") AsyncTaskExecutor` and exposes
`dispatch(runId, token, skipDeploymentPhase)`: register handle → `executor.execute(() -> run(...))`; on any exception
remove the handle and rethrow. Spring's `ThreadPoolTaskExecutor.execute` still applies `ContextPropagatingTaskDecorator`
and wraps rejection in `TaskRejectedException extends RejectedExecutionException`, so
`TestSuiteRunService.dispatchEvaluation` keeps its existing compensation unchanged. `@EnableAsync` stays (other
beans use `@Async`). The run body `run(UUID, String, boolean, RunHandle)` is package-private so
`TestSuiteEvaluationJobTest` can drive it synchronously with a real `RunHandle`; class-level `@LogExecution` no
longer wraps the body (self-invocation), which is acceptable — the job logs its own start/finish lines.
*Alternative:* `@Async` returning `CompletableFuture` — Spring's returned future is not linked to interruption and
we do not need a future at all (D3).

### D8. Worker-side "no row for interrupted cases"
`TestCaseRunner`'s wrapper: after `evaluationWorker.execute(...)` returns or throws, if
`Thread.currentThread().isInterrupted()` or the exception is/wraps `InterruptedException`, do not call
`resultsWriter.addResults` and do not synthesize an ERROR row; count the task as interrupted for the WARN line.
This is the single remaining cancellation-related check on the worker path and is interrupt-based, not
signal-based. It relies on the interrupt flag surviving the call stack: Spring's `JdkClientHttpRequest` re-sets it
before throwing `IOException`; reactor's blocking subscriber (MCP) re-sets it; `DeploymentTurnInvoker`/`EvaluationWorker`
retry loops keep `catch InterruptedException → interrupt(); break`. A unit test pins this invariant. `TurnLoopExecutor`
loses its cancel-specific `abortBeforeRequest` and `requestIssued || !cancelled` suppression.

### D9. Runner-core / CLI executor ownership
`EvaluationContext` gains `ExecutorService executor` (required) and loses `cancellationSignal`,
`cancellationGracePeriodMs`. eval-cli's `EvaluationContextFactory` creates
`Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())`; `RunOrchestrationService` calls
`shutdownNow()` on it in a `finally` after `awaitCompletion()`. No CLI cancellation path is added.

### D10. Configuration removal
Delete `EvaluationRunProperties.Execution.cancellationGracePeriodMs` (+ root yml, docs row),
`EvalCliProperties.Run.cancellationGracePeriodMs` (+ both eval-cli yml keys, README rows, eval-cli property-binding
and context-startup tests). Spring ignores unknown keys, so stale deployment YAML is harmless. No
`openspec/config.yaml` change: this follows existing conventions.

### D11. Metric-provider client becomes interruptible
`MetricProviderRestClientConfiguration.buildRestClient` switches from `SimpleClientHttpRequestFactory` to
`JdkClientHttpRequestFactory` over a JDK `HttpClient` (`HTTP_1_1`, connect timeout from properties, read timeout via
`setReadTimeout`) — the same shape `DialCoreDeploymentInvokerConfiguration` already uses. Without this, an
interrupted Phase 2 call keeps its virtual thread blocked for up to `read-timeout-ms` (150 s default) and the run
cannot finalize until then. Same connect/read timeout semantics, same tracing interceptor.
*Alternative:* accept the latency — rejected; it defeats the "immediate" contract and blocks `handle.close()`'s
reaper from doing anything useful.

### D12. Observability
- SSE: `RUNNING` (after the guarded `updateToRunning`), then for a cancelled run `CANCELLING` (from the endpoint)
  and `CANCELLED` (from the job).
- WARN line "Run {} cancelled with {} test case(s) interrupted before completion" kept in `TestCaseRunner.awaitCompletion`
  (count = tasks whose wrapper observed an interrupt).
- OTel: the run executor is `Context.taskWrapping`-wrapped in `RunHandle`; the pool thread still gets
  `ContextPropagatingTaskDecorator`.
- OpenAPI: `@Operation` on cancel says it returns `CANCELLING` for RUNNING runs and is idempotent on CANCELLING; run
  response `status` schema enumerates the six values; a cancel-response example JSON is added and asserted.

### D13. Pattern documentation
New `docs/patterns/run-cancellation.md` (handle/registry, guarded writes, "never interrupt the job thread",
CANCELLING semantics, interruptible HTTP clients, multi-instance caveat) + one row in the AGENTS.md Unique Patterns
table; retire the grace-period wording in `docs/configuration.md`. Update `openspec/changes/ef-as-dial-app/design.md:163`
to reference `ActiveRunRegistry.cancel` + `updateToFailed` for the SSE-disconnect case. Baseline-spec prose outside
requirement blocks (Key Terms / Implementation Notes mentioning `executeRunAsync` or the cancellation signal) is
hand-edited at archive time after `/opsx:sync`; requirement blocks are only changed through the delta specs.

## Risks / Trade-offs

- [Interrupted `RestClient` call surfaces as `IOException`/`ResourceAccessException`, not `InterruptedException`;
  a turn could be recorded as ERROR] → D8 checks the thread's interrupt flag and drops the case; unit test pins it;
  functional test asserts no row beyond completed ones.
- [Job proceeds into Phase 2 setup (`writeRunMetricSnapshots`, first cursor page) before hitting a rejected dispatch]
  → `handle.throwIfCancelled()` at each phase boundary; residual window is one snapshot write, harmless.
- [Metric-less run (no TSMDs) never dispatches in Phase 2; Phase 3 is sequential jOOQ with no checks] → a cancel
  arriving during Phase 2/3 of such a run is observed only at the next phase boundary or at the guarded terminal
  write. Both phases are DB-only and short; terminal status is still CANCELLED.
- [`submit` returns `false` after `semaphore.acquire()` succeeded but `runAsync` was rejected] → `submit` releases
  the permit in that path; `awaitCompletion` waits only on futures that were actually created.
- [Per-JVM registry in a multi-instance deployment] → cancel still writes `CANCELLING`; owning instance finalizes
  `CANCELLED` via the guarded write; a genuine exception on the owning instance would write FAILED over CANCELLING
  (its handle is not cancelled). Documented; single-instance is the standing assumption (reconciliation, SSE).
- [`CANCELLING` row with no live handle anywhere (test fixture, crashed owner)] → stays CANCELLING until startup
  reconciliation finalizes it; counts toward concurrency limits meanwhile. Functional tests clean up such rows.
- [Old build rolled back with rows in `CANCELLING`] → transient status only exists during an active job; documented
  in proposal; manual `UPDATE` to `CANCELLED` if ever needed.
- [Hikari acquisition interrupted] → the job thread never touches the DB with an interrupt flag set (D3). The
  size-triggered analytics batch flush in `PostgresResultBatchWriter.addResults` runs on a worker thread, so a
  worker interrupted by `shutdownNow()` mid-flush can lose up to `batchSize` completed results of that batch —
  acceptable for cancellation (results are absent, never synthetic). The final flush after `awaitCompletion()`
  still runs on the job thread.
- [Switching the metric-provider HTTP factory changes low-level client behaviour] → same timeouts, HTTP/1.1 pinned,
  same interceptor; covered by existing metric-evaluation functional tests against the mock provider.
- [Tests that raced the grace period (`shouldNotSynthesizeRows_whenCancelledMidFlight`) become deterministic but need
  rewriting] → replaced by `shutdownNow`-driven tests.

## Migration Plan

1. Deploy; no DB migration. Existing runs unaffected. Stale `cancellation-grace-period-ms` YAML keys are ignored.
2. Clients: handle `status = CANCELLING` (treat as non-terminal, "stopping"). The cancel response body now shows
   `CANCELLING` instead of `RUNNING` for a RUNNING run.
3. Rollback: redeploy previous build; any leftover `CANCELLING` rows must be set to `CANCELLED` manually (see proposal).
