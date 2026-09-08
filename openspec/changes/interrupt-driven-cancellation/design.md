## Context

See proposal.md — Why. Relevant current state:

- `TestSuiteEvaluationJob.executeRunAsync` is `@Async("testSuiteRunExecutor")` and returns `void`, so no handle to
  the running job exists. Cancellation is a `ConcurrentHashMap<UUID, AtomicBoolean>` registered by
  `TestSuiteRunService.dispatchEvaluation` before dispatch; `interruptRun` flips the flag.
- Phase 1 (`InProcessEvaluationExecutor` → runner-core `TestCaseRunner`) and Phase 2
  (`InProcessMetricEvaluationExecutor`) each create their own `Context.taskWrapping(newVirtualThreadPerTaskExecutor())`.
  Phase 1 implements a grace-period drain; Phase 2 does `shutdownNow()` in `finally`. Phase 3
  (`MetricScoreComputationExecutor`) is sequential jOOQ.
- Every blocking primitive on the worker path is interruptible: `Semaphore.acquire`, Bucket4j
  `asBlocking().consume`, `Thread.sleep` in backoffs, JDK `HttpClient.send` (Spring's `JdkClientHttpRequest`
  re-interrupts and throws `IOException`), the streaming-body `InputStream` read, `CompletableFuture.get`.
- JDBC is not interruptible, but HikariCP's connection acquisition is (`SynchronousQueue.poll`): an interrupted
  thread may fail to obtain a connection. This rules out interrupting the job thread.
- `RunStatus` has no `CANCELLING`; `status` column is `VARCHAR(20)` with no CHECK constraint.
- `openspec/changes/ef-as-dial-app/design.md` plans to reuse the `AtomicBoolean` signal to fail a run when the
  DIAL Core SSE connection drops.

## Goals / Non-Goals

**Goals:**
- Exactly one place decides a run's terminal status, driven by guarded DB writes.
- Cancellation-awareness limited to: (a) the cancel endpoint, (b) the job's phase boundaries, (c) one
  interrupt-status check in the Phase 1 worker wrapper. Everything else is ordinary `InterruptedException` hygiene.
- `CANCELLING` visible on every read surface (GET, list, SSE) within the cancel request's transaction.

**Non-Goals:**
- Cross-instance cancellation (registry stays per-JVM; see Risks).
- Graceful drain of in-flight calls.
- Structured concurrency (`StructuredTaskScope` is still preview in Java 25).
- CLI-side cancellation UX (Ctrl-C hook is an optional extra, not required).

## Decisions

### D1. Cancellation = `shutdownNow()` on a per-run executor owned by a `RunHandle`
`RunHandle` (root, `service.domain.job`) wraps `Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())`
and exposes `executor()`, `cancel()` (= `shutdownNow()`), `isCancelled()` (= `isShutdown()` before normal close),
`throwIfCancelled()` (→ `CancellationException`), `close()`. `ActiveRunRegistry` (`@Component @LogExecution`)
keeps `ConcurrentHashMap<UUID, RunHandle>` with `register`, `cancel` (no-op if absent), `remove`.
*Alternatives:* keep the flag but centralize into a `CancellationToken` (still N check sites); DB-status polling
(cross-instance safe but adds DB round-trips and keeps polling); `Future.cancel(true)` on the job (interrupts the
job thread — rejected, see D3).

### D2. Both phases dispatch on the run's executor
`EvaluationContext.executor` (runner-core) and `MetricEvaluationContext.executor` carry the handle's executor.
`TestCaseRunner` and `InProcessMetricEvaluationExecutor` stop creating/shutting down executors; the owner closes it
once in the job's `finally`. Consequence: after cancel, `CompletableFuture.runAsync(..., executor)` throws
`RejectedExecutionException`, so page loops die without checks. *Alternative:* per-phase executors registered with
the handle — more moving parts for no benefit.

### D3. The job thread is never interrupted
Cancellation reaches the orchestrator only through (a) workers finishing fast → `allOf(futures).get()` returns,
(b) `RejectedExecutionException` on the next dispatch, (c) `handle.throwIfCancelled()` between phases. The job's
own final flush and status writes run on a clean thread, so Hikari acquisition is safe. `TestCaseRunner.awaitCompletion`
uses `get()` (interruptible, for CLI use) but in the server the job thread carries no interrupt.

### D4. Terminal status by guarded writes, "CANCELLED wins"
Repository changes (`PostgresTestSuiteRunRepository`):
- `int updateToRunning(id, startedAt, updatedAt)` → `WHERE status = 'PENDING'`
- `int updateToCompleted(id, completedAt, updatedAt)` → `WHERE status = 'RUNNING'`
- `int updateToCancelled(id, completedAt, updatedAt)` → `WHERE status IN ('RUNNING','CANCELLING')`
- `int markCancelling(id)` → `SET status='CANCELLING', updated_at_ms WHERE status='RUNNING'` (no `completed_at`)
- `int cancelOrphanedCancellingRuns()` → `SET status='CANCELLED', completed_at_ms, updated_at_ms WHERE status='CANCELLING'`
- `updateToFailed` unchanged (unguarded; the job only calls it when not cancelled).

Job flow:
```
handle = registry.register(runId)            // caller thread (dispatch)
submit → run():
  snapshot phase (unchanged; on failure → FAILED as today)
  if updateToRunning(..) == 0 → return       // cancelled while PENDING
  handle.throwIfCancelled(); phase1
  handle.throwIfCancelled(); phase2
  handle.throwIfCancelled(); phase3 (non-fatal, unchanged)
  if updateToCompleted(..) == 0 → updateToCancelled(..)
catch CancellationException | RejectedExecutionException, or any Exception while handle.isCancelled()
  → updateToCancelled(..)
catch Exception → updateToFailed(.. ANALYTICS_WRITE_FAILED if AnalyticsWriteException else UNEXPECTED_ERROR ..)
finally handle.close(); registry.remove(runId); SSE notify
```
Phase 2 `doFlush` rethrows (wrapped in a dedicated unchecked `AnalyticsWriteException` in `service.domain.job` so the
job can map the code) instead of setting a signal.
*Alternative:* COMPLETED wins on a late cancel — rejected by the user; a client that asked to cancel expects CANCELLED.

### D5. Cancel endpoint writes `CANCELLING` synchronously
`TestSuiteRunService.cancelRun` (still `@Transactional("metaTransactionManager")`):
```
terminal              → 409 InvalidOperationException (unchanged)
PENDING               → updateStatusOptimistic(PENDING→CANCELLED); if won: registry.cancel; SSE; return
                        else re-read and continue
CANCELLING            → return current DTO (idempotent 200)
RUNNING               → if markCancelling(id) == 0: re-read, recurse once on the new status
                        registry.cancel(id); SSE notify(CANCELLING); return DTO
```
`registry.cancel` after the DB write: if the job finishes between the two, its guarded `updateToCompleted` sees
`CANCELLING`, affects zero rows, and writes CANCELLED — consistent with D4. The SSE notify happens inside the tx as
today (existing behaviour for PENDING cancel).

### D6. `RunStatus.ACTIVE_STATUSES` and reconciliation
`ACTIVE_STATUSES = {PENDING, RUNNING, CANCELLING}` used by `enforceConcurrencyLimits`. `TestSuiteRunReconciliation`
adds a second statement: `CANCELLING → CANCELLED` (no error payload). Order: fail orphans first, then finalize
cancelling; both in the same meta transaction.

### D7. Explicit submission instead of `@Async`
`TestSuiteEvaluationJob` injects `@Qualifier("testSuiteRunExecutor") AsyncTaskExecutor` and exposes
`dispatch(runId, token, skipDeploymentPhase)`: register handle → `executor.execute(runnable)`; on any exception
remove the handle and rethrow. `TestSuiteRunService.dispatchEvaluation` keeps its `RejectedExecutionException`
compensation and no longer knows about registration. `ContextPropagatingTaskDecorator` still applies because the
bean's `execute` is used. The `Dedicated async executor` requirement is unaffected (same bean, same rejection policy).
*Alternative:* `@Async` returning `CompletableFuture` — Spring's returned future is not linked to interruption and
we do not need a future at all (D3).

### D8. Worker-side "no row for interrupted cases"
`TestCaseRunner`'s wrapper: after `evaluationWorker.execute(...)` returns or throws, if
`Thread.currentThread().isInterrupted()` or the exception is/wraps `InterruptedException`, do not call
`resultsWriter.addResults` and do not synthesize an ERROR row. This is the single remaining cancellation-related
check on the worker path and is interrupt-based, not signal-based. Retry loops in `EvaluationWorker`/`DeploymentTurnInvoker`
keep `catch InterruptedException → interrupt(); break` (the wrapper then drops the case). `TurnLoopExecutor` loses
its cancel-specific `abortBeforeRequest` and `requestIssued || !cancelled` suppression.

### D9. Runner-core / CLI executor ownership
`EvaluationContext` gains `ExecutorService executor` (required) and loses `cancellationSignal`,
`cancellationGracePeriodMs`. eval-cli's `EvaluationContextFactory` creates the executor; `RunOrchestrationService`
closes it after `awaitCompletion()` (try/finally). No CLI cancellation path is added.

### D10. Configuration removal
Delete `EvaluationRunProperties.Execution.cancellationGracePeriodMs` (+ yml, docs row),
`EvalCliProperties.Run.cancellationGracePeriodMs` (+ both eval-cli yml keys, README rows). Spring ignores unknown
keys, so stale deployment YAML is harmless. No `openspec/config.yaml` change: this follows existing conventions.

### D11. Observability
- SSE: two events for a RUNNING cancel (`CANCELLING`, then `CANCELLED`).
- WARN line "Run {} cancelled with {} test case(s) interrupted before completion" kept in `TestCaseRunner`
  (counted as futures not completed normally).
- OpenAPI: `@Operation` on cancel says it returns `CANCELLING` for RUNNING runs; run response `status` schema
  enumerates the six values; add/adjust example JSON under `src/main/resources/openapi/examples/` for the cancel
  endpoint if one exists for it (verify by filename convention).

### D12. Pattern documentation
New `docs/patterns/run-cancellation.md` (handle/registry, guarded writes, "never interrupt the job thread",
CANCELLING semantics, multi-instance caveat) + one row in the AGENTS.md Unique Patterns table; retire the grace-period
wording in `docs/configuration.md`. Update `openspec/changes/ef-as-dial-app/design.md:163` to reference
`ActiveRunRegistry.cancel` + `updateToFailed` for the SSE-disconnect case.

## Risks / Trade-offs

- [Interrupted `RestClient` call surfaces as `IOException`/`ResourceAccessException`, not `InterruptedException`;
  a turn could be recorded as ERROR] → D8 checks the thread's interrupt flag (Spring re-sets it) and drops the case;
  functional test asserts no row for interrupted cases beyond those completed.
- [Job proceeds into Phase 2 setup (`writeRunMetricSnapshots`, first cursor page) before hitting a rejected dispatch]
  → `handle.throwIfCancelled()` at each phase boundary; residual window is one snapshot write, harmless.
- [Metric-less run (no TSMDs) never dispatches in Phase 2, so a cancel during Phase 2 is only observed at the
  Phase 3 boundary] → acceptable: Phase 2 for zero TSMDs is DB-only and fast; terminal write still yields CANCELLED.
- [Per-JVM registry in a multi-instance deployment] → cancel still writes `CANCELLING`; owning instance finalizes
  `CANCELLED` via the guarded write; documented in spec. Same assumption as reconciliation today.
- [Old build rolled back with rows in `CANCELLING`] → transient status only exists during an active job; documented
  in proposal; manual `UPDATE` to `CANCELLED` if ever needed.
- [Hikari acquisition interrupted] → job thread is never interrupted (D3); worker threads never touch the meta DB.
  `PostgresResultBatchWriter.flush()` is called by the job thread only.
- [Tests that raced the grace period (`shouldNotSynthesizeRows_whenCancelledMidFlight`) become deterministic but need
  rewriting] → replaced by `shutdownNow`-driven tests.

## Migration Plan

1. Deploy; no DB migration. Existing runs unaffected. Stale `cancellation-grace-period-ms` YAML keys are ignored.
2. Clients: handle `status = CANCELLING` (treat as non-terminal, "stopping"). The cancel response body now shows
   `CANCELLING` instead of `RUNNING` for a RUNNING run.
3. Rollback: redeploy previous build; any leftover `CANCELLING` rows must be set to `CANCELLED` manually (see proposal).
