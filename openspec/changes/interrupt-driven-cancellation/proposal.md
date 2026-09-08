## Why

Cancelling a `RUNNING` test suite run today flips an in-memory `AtomicBoolean` that ~23 call sites across 9 classes
(root module and `evaluation-runner-core`) poll cooperatively. The client cannot observe that cancellation was
requested: `POST /cancel` on a RUNNING run does not touch the database, so the run keeps reporting `RUNNING` until the
job finishes draining, and a page reload cannot tell "still running" from "cancel requested, still draining". The
polling design also smears cancellation-awareness across every loop, retry and backoff, and has produced a latent bug:
a Phase 2 analytics batch-write failure *sets* the cancellation signal, so the run ends `CANCELLED` instead of the
spec-mandated `FAILED` / `ANALYTICS_WRITE_FAILED`.

## What Changes

- **New run status `CANCELLING`** written synchronously by `POST /api/v1/test-suite-runs/{id}/cancel` for a RUNNING run
  (`RUNNING → CANCELLING`, optimistic `WHERE status = 'RUNNING'`). The endpoint returns the `CANCELLING` DTO and emits
  an SSE status event. Repeating the call on a `CANCELLING` run is idempotent (200, current DTO). The async job
  finalizes `CANCELLING → CANCELLED`. `CANCELLING` counts as an *active* status for concurrent-run limits and is
  reconciled to `CANCELLED` (not `FAILED`) on application restart.
- **Cancellation becomes interrupt-driven.** Each run owns one virtual-thread `ExecutorService` held in a per-JVM
  registry; cancel = `shutdownNow()` on it. Interrupted workers finish fast, further dispatch throws
  `RejectedExecutionException`, and the orchestrating job thread — which is never interrupted itself — observes
  cancellation only at phase boundaries. Phase 1 and Phase 2 share the run's executor instead of creating their own.
- **Terminal status is decided by guarded DB writes, not by a flag**: `PENDING → RUNNING` and `RUNNING → COMPLETED`
  are optimistic updates; zero affected rows means the run was cancelled meanwhile, and the job writes `CANCELLED`.
  A late cancel (all work finished while `CANCELLING`) therefore ends `CANCELLED`, never `COMPLETED`.
- **BREAKING (behavioral): the cancellation grace-period drain is removed.** Cancellation interrupts in-flight
  deployment/metric-provider calls immediately. Results flushed before the cancel are preserved; the single final
  flush still persists workers that completed before interruption; interrupted cases stay absent from
  `test_case_run_results` (no synthetic rows), as today after the grace period.
- **BREAKING (config): `test-suite-run.execution.cancellation-grace-period-ms` and eval-cli's
  `cli.run.cancellation-grace-period-ms` / mirrored `test-suite-run.execution.cancellation-grace-period-ms` are
  removed.** Spring ignores the unknown YAML key, so existing deployments start unchanged; `docs/configuration.md`,
  `eval-cli/README.md` and both `application.yml` files are updated.
- All `cancellationSignal` reads and the `AtomicBoolean` fields on `EvaluationContext`, `MetricEvaluationContext`,
  `MetricScoreComputationContext` are deleted. `MetricEvaluationWorker.sleepWithCancellation` becomes plain
  `Thread.sleep`. `TestCaseRunner` no longer owns/creates an executor and no longer implements grace logic.
- **Bug fix:** a Phase 2 analytics batch-write failure now propagates and marks the run `FAILED` /
  `ANALYTICS_WRITE_FAILED`.
- `TestSuiteEvaluationJob` drops `@Async` in favour of explicit submission to the existing `testSuiteRunExecutor`
  bean so the job can register the run's executor handle before dispatch and clean it up on rejection.
- New classes: `service.domain.job.RunHandle`, `service.domain.job.ActiveRunRegistry` (root module). No new packages.
  No Flyway migration (`status` is `VARCHAR(20)` with no CHECK constraint).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `test-suite-runs`: run status lifecycle gains `CANCELLING` (RUNNING → CANCELLING → CANCELLED); the cancel endpoint
  writes and returns `CANCELLING`, is idempotent on `CANCELLING`; late cancel yields `CANCELLED`; concurrent-run limits
  count `CANCELLING` as active; startup reconciliation maps `CANCELLING` to `CANCELLED`; evaluation-job orchestration
  requirement describes registry-based interrupt-driven cancellation instead of a pre-registered signal; response DTO
  status enumeration; configuration properties drop the grace period.
- `eval-execution-engine`: "Graceful cancellation" becomes "Immediate cancellation" — no grace period, interrupt
  workers immediately; "Diagnostic logging for unfinished cases on cancel" and the batch-writing shutdown-ordering
  scenarios drop the grace step; "Batch write failure" no longer sets a cancellation signal; retry policy's
  "Retry respects cancellation" becomes interruption-based; execution configuration scenario drops
  `cancellation-grace-period-ms`.
- `metric-evaluation`: "Cancellation with hard shutdown" describes the shared run executor being shut down externally
  (no executor owned by the phase); "Batch write failure" propagates as a run failure; worker retry backoff respects
  interruption rather than a signal.
- `eval-results-import`: imported runs use the same interrupt-driven cancellation and the same `CANCELLING` status
  (wording only — behavior stays "no special-casing").

## Impact

- **API**: `status` on `TestSuiteRunResponseDto` gains the value `CANCELLING`. `POST /cancel` on a RUNNING run now
  returns `status = CANCELLING` (was `RUNNING`). OpenAPI `@Schema`/`@Operation` and example JSON are updated. Clients
  filtering or switching on status must handle the new value.
- **Data**: no schema change. New rows may carry `status = 'CANCELLING'` transiently.
- **Code**: root `service.domain.job.*` (job, both in-process executors, worker, contexts, new `RunHandle` +
  `ActiveRunRegistry`), `service.domain.TestSuiteRunService`, `TestSuiteRunReconciliation`, `data.db.model.RunStatus`,
  `TestSuiteRunRepository` + Postgres impl (guarded updates returning affected-row counts, `markCancelling`,
  `cancelOrphanedCancellingRuns`), `query.service.metricscore.MetricScoreComputationExecutor`, web controller docs.
  `evaluation-runner-core`: `EvaluationContext` (gains `executor`, loses signal + grace), `TestCaseRunner`,
  `EvaluationWorker`, `DeploymentTurnInvoker`, `TurnLoopExecutor`, `EvaluationRunProperties`. `eval-cli`:
  `EvaluationContextFactory`, `RunOrchestrationService` (owns executor lifecycle), `EvalCliProperties`, yml, README.
- **Config**: removes `test-suite-run.execution.cancellation-grace-period-ms` (and eval-cli equivalents);
  `docs/configuration.md` row deleted.
- **Docs**: `docs/database-schema.md` status list; new `docs/patterns/run-cancellation.md` + AGENTS.md pattern row
  (replaces the per-run executor/cancellation convention that currently lives implicitly in `TestCaseRunner`);
  `openspec/changes/ef-as-dial-app/design.md` risk row referencing the `AtomicBoolean` signal.
- **Tests**: unit tests in job/runner/worker/registry; functional cancel tests assert `CANCELLING` then `CANCELLED`;
  reconciliation test for `CANCELLING`; the shortened grace `@TestPropertySource` in `PostgresFunctionalTests` is removed.
- **Deployment**: single-instance assumption unchanged (registry is per-JVM; a cancel landing on another instance
  still writes `CANCELLING`, and the job's guarded terminal write yields `CANCELLED` when it finishes).
- **Rollback**: revert the release. Any run left in `CANCELLING` by the new build is a non-terminal status unknown to
  the old build; the old reconciliation only touches PENDING/RUNNING, so such rows would stay `CANCELLING` until
  cancelled again or cleaned manually — acceptable for a transient status that exists only during an active job.
