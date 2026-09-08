## Context

See proposal.md — Why. Three sites build executors today: `AsyncConfiguration.testSuiteRunExecutor` (platform `ThreadPoolTaskExecutor`), `RunHandle` (root, `Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())`) and eval-cli `EvaluationContextFactory` (same inline expression). The archived `interrupt-driven-cancellation` change fixed the cancellation contract on top of these executors (per-run `shutdownNow()`, job thread never interrupted, guarded DB writes) — that contract must survive unchanged.

Constraints:
- Spring Boot 4.1 exposes `org.springframework.boot.thread.Threading.VIRTUAL.isActive(Environment)` — the canonical reader of `spring.threads.virtual.enabled` (Boot default `false`; root `application.yml` sets `${VIRTUAL_THREADS_ENABLED:true}`).
- `evaluation-runner-core` is DB-free and must stay consumable by eval-cli without EF-backend types.
- The functional test base (`FunctionalTests.afterEachTest`) drains the job executor before `restoreDb()` to avoid a `DROP SCHEMA` deadlock with the snapshot transaction; today it introspects `ThreadPoolTaskExecutor.getActiveCount()`/queue, which a thread-per-task executor does not expose.

## Goals / Non-Goals

**Goals:**
- One switch (`spring.threads.virtual.enabled`) flips job executor + all worker executors + Boot's own executors.
- Identical cancellation/interrupt semantics in both modes.
- Remove the accidental concurrency cap (pool core size) so `test-suite-run.limits.*` is the only admission control.

**Non-Goals:**
- Per-run or per-phase thread-mode overrides.
- Changing `DeploymentService.getDeployment` fan-out (three short probes on a request-scoped virtual executor — not a run executor; left as is).
- Any change to run limits, `RunHandle` cancellation API, or the `CANCELLING` lifecycle.

## Decisions

### D1 — `RunExecutorFactory` lives in `evaluation-runner-core`, package `runner.job`
Plain class with constructor `RunExecutorFactory(boolean virtualThreads)` (unit-testable without Spring); API:
```java
public boolean isVirtualThreads()
public ExecutorService newWorkerExecutor()   // one per run
```
Bean method in `EvaluationRunnerAutoConfiguration`, `@ConditionalOnMissingBean`, argument `Threading.VIRTUAL.isActive(environment)`.
*Why here*: both consumers of worker executors (root `ActiveRunRegistry`, eval-cli `EvaluationContextFactory`) already depend on runner-core; putting it in the root module would force eval-cli to duplicate it. *Alternative rejected*: a `@ConfigurationProperties` of our own (`test-suite-run.executor.virtual-threads`) — a second switch that could disagree with Boot's, and the user explicitly asked for one JVM-wide knob.

### D2 — Worker executor = `Context.taskWrapping(Executors.newThreadPerTaskExecutor(threadFactory))` in both modes
`threadFactory` = `Thread.ofVirtual().name("run-worker-", 0).factory()` or `Thread.ofPlatform().daemon(true).name("run-worker-", 0).factory()`.
*Why thread-per-task for platform too*: `shutdownNow()` semantics (interrupt every live task, reject new ones) are identical to the virtual case, so the cancellation pattern needs no mode-specific branch. Platform thread count is already bounded by the per-run concurrency semaphore (Phase 1) and per-provider semaphores (Phase 2), so a pool buys nothing. Daemon so a lingering worker never blocks JVM exit. OTel `Context.taskWrapping` stays (observability spec: propagation into worker threads).
*Alternative rejected*: a cached/fixed platform pool for platform mode — different `shutdownNow()` timing (queued tasks), extra config surface.

### D3 — `RunHandle(ExecutorService)`; `ActiveRunRegistry` owns the factory
Drop the no-arg constructor. `ActiveRunRegistry` (already a `@Component`) injects `RunExecutorFactory` and calls `new RunHandle(factory.newWorkerExecutor())` in `register`. `RunHandle` keeps `executor()/cancel()/isCancelled()/throwIfCancelled()/close()` unchanged.
*Why*: keeps `RunHandle` a plain session object (tests construct it with any executor, e.g. `Executors.newVirtualThreadPerTaskExecutor()`), and the registry is the only production creator.

### D4 — `testSuiteRunExecutor` = `SimpleAsyncTaskExecutor`, thread-per-run, no concurrency limit
```java
var executor = new SimpleAsyncTaskExecutor("test-suite-run-");
executor.setVirtualThreads(factory.isVirtualThreads());
executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
executor.setCancelRemainingTasksOnClose(true);   // context close interrupts in-flight job threads
executor.setDaemon(true);                        // platform mode: never block JVM exit
```
Bean type `AsyncTaskExecutor`. **No `concurrencyLimit`**: `SimpleAsyncTaskExecutor`'s limit *blocks* the caller — here the HTTP thread inside `afterCommit` — instead of rejecting; admission is already enforced by `TestSuiteRunService.enforceConcurrencyLimits` (429). Rejection therefore only happens after `close()` (`TaskRejectedException extends RejectedExecutionException`), so the existing `dispatchEvaluation(..., onRejected)` → `EXECUTOR_REJECTED` path stays as the shutdown guard.
`setCancelRemainingTasksOnClose(true)` was verified against Spring Framework 7.0.9 sources: `close()` interrupts every tracked active thread immediately — the same effect the old pool's `shutdownNow()` had at context close, and the case the `Thread.interrupted()` clears in `TestSuiteEvaluationJob.run`'s catch blocks already cover. Update that comment to name `SimpleAsyncTaskExecutor.close()` instead of the pool.
`dispatch`'s compensation catch widens to `RuntimeException | Error` (cleanup + rethrow) so a platform-mode `OutOfMemoryError: unable to create native thread` at `newThread().start()` cannot strand a PENDING run with a leaked handle; the rethrown `Error` is left for the caller — that case is out of the `EXECUTOR_REJECTED` contract and is reconciled at next startup.
*Alternative rejected*: `ThreadPoolTaskExecutor.setVirtualThreads` does not exist; a `ThreadPoolTaskExecutor` with core = max = `max-concurrent-runs-global` duplicates the limit in two places and keeps a platform pool in the default case.

### D5 — Functional-test drain switches to `ActiveRunRegistry`
`ActiveRunRegistry` gains `public int activeCount()` (size of the handle map — a legitimate "runs in flight on this instance" signal, also usable for a gauge later). `FunctionalTests.drainTestSuiteRunExecutor()` polls `activeCount() == 0` instead of pool introspection. `TestSuiteEvaluationJob.run`'s `finally` must therefore call `registry.remove(runId)` as its **last** statement — today it runs before `notifySse`, which does a `repository.findById` SELECT; reordering closes that window (a cancel landing between `handle.close()` and `remove` is a harmless second `shutdownNow()`). `dispatch` registers the handle synchronously inside `afterCommit` before the HTTP response returns, so `activeCount()` is non-zero by the time a test proceeds.

### D6 — Configuration surface
Remove `TestSuiteRunProperties.Executor` and the `test-suite-run.executor.*` YAML/docs. Add `docs/configuration.md` row for `spring.threads.virtual.enabled` / `VIRTUAL_THREADS_ENABLED` (default `true`, Required `No`, Applied when `-`) under §2 Spring Framework Configuration, describing the profiling use of `false`. eval-cli `application.yml` switches its hardcoded `true` to `${VIRTUAL_THREADS_ENABLED:true}`; README documents it. Drop `io.opentelemetry:opentelemetry-context` from `eval-cli/build.gradle` if `EvaluationContextFactory` was its only user (verified: it is).

## Component interaction

```
POST /runs ──tx commit──▶ afterCommit ──▶ TestSuiteEvaluationJob.dispatch
   registry.register(runId) ──▶ new RunHandle(factory.newWorkerExecutor())   // thread mode from factory
   taskExecutor.execute(run)  ──▶ SimpleAsyncTaskExecutor: one "test-suite-run-N" thread (virtual|platform)
        run(): Phase 1/2 dispatch onto handle.executor() ("run-worker-N", virtual|platform)
        finally: handle.close(); registry.remove(runId)
```
No transaction boundary changes; no data model changes; no API contract changes.

## Risks / Trade-offs

- [More concurrent runs than before (20 vs effective 5) put more load on DIAL Core / DB] → this is the configured, documented limit finally being honoured; operators lower `max-concurrent-runs-global` if needed.
- [HikariCP pools are unsized (default 10 connections per datasource, `MetaPostgresConfiguration`/`AnalyticsPostgresConfiguration` set nothing) and `max-concurrent-runs-global` is counted DB-wide while threads are per instance] → 20 runs × (snapshot tx + batch writers) contend for 10 connections per datasource; Hikari queues acquirers for `connectionTimeout` (30 s) before failing. Connections are held per statement/transaction, not for the run's lifetime, so this is contention, not deadlock. Called out in Migration Plan; making `maximum-pool-size` configurable (with a `docs/configuration.md` row) is a follow-up, not part of this change.
- [Rejection is not the only dispatch failure: platform-mode thread exhaustion surfaces as `OutOfMemoryError`] → `dispatch` cleans up and rethrows; the run stays PENDING until startup reconciliation. Accepted gap (platform mode is a troubleshooting opt-out).
- [Platform mode creates one platform thread per in-flight worker] → bounded by run `concurrencyLevel` × concurrent runs and provider semaphores; mode is a troubleshooting opt-out, not the default.
- [`SimpleAsyncTaskExecutor` never rejects while active] → intended; admission is the 429 guard. `EXECUTOR_REJECTED` remains for the shutdown window.
- [Context close now interrupts job threads mid-DB-write] → unchanged from the previous pool's `shutdownNow()`; `Thread.interrupted()` clears + startup reconciliation already handle it.
- [Boot's `spring.threads.virtual.enabled=false` also flips Tomcat/`applicationTaskExecutor` to platform threads] → desired: one switch for the whole JVM during profiling.

## Migration Plan

Deploy as a normal release. Remove `TEST_SUITE_RUN_EXECUTOR_*` env vars from deployment manifests at leisure (ignored if present). Before relying on 20 concurrent runs, size the HikariCP pools (default 10 per datasource) or lower `test-suite-run.limits.max-concurrent-runs-global`; multi-instance deployments multiply the per-instance thread count but share the DB-wide limit. Rollback = previous image; no schema involved.
