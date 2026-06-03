## Why

Some test case run results are silently lost when an evaluation run encounters an error, and the run can be incorrectly marked **COMPLETED** despite missing rows. The reproduction in issue #929 is a `TimeoutException` thrown from
`InProcessEvaluationExecutor.execute()` line 117:

```
WARN  com.epam.aidial.evaluation.service.domain.job.InProcessEvaluationExecutor
    Executor error for run b6a78ecb-...: null
java.util.concurrent.TimeoutException
    at ...InProcessEvaluationExecutor.execute(InProcessEvaluationExecutor.java:117)
```

Three independent defects combine to produce that symptom:

1. `cancellationGracePeriodMs` (default 30 s) is misused as the **overall** wait budget for `CompletableFuture.allOf().get(timeout)`. Any run lasting longer than the grace period throws `TimeoutException` regardless of whether cancellation was requested. Per `git log`, this has been the case since the file's first commit (`42dddcb`) — there is no prior correct behaviour to restore.
2. When the timeout fires, the catch swallows the exception, the cancellation signal is **not** set, control returns to `TestSuiteEvaluationJob.executeRunAsync`, and the run reaches `repository.updateToCompleted(...)` even though many in-flight workers were forcibly terminated by `executor.shutdownNow()`.
3. The worker-level `catch (Exception e)` in `InProcessEvaluationExecutor` (and a TOCTOU race between `flush(buffer)` and late `addResult` calls) silently drop any test case whose worker throws an unexpected exception or finishes after the final flush.

The fix adopts a clear three-tier failure model — but with an important honesty principle: **rows in `test_case_run_results` represent work that actually executed.** Absence is the signal that work didn't happen; the run's status disambiguates *why*. We do NOT try to guarantee a synthetic row for every dispatched task, because that guarantee is impossible to keep under OOM, JVM kill, or analytics-DB outage.

| Scope                       | Run status outcome | Row outcome                                                                  |
|-----------------------------|--------------------|------------------------------------------------------------------------------|
| Per-case worker exception   | **COMPLETED**      | Synthetic `ExecutionStatus.ERROR` row written (best-effort — worker is alive in its catch block, which is when synthesis is most reliable). This makes per-case bugs visible instead of silently dropped. |
| User cancellation           | **CANCELLED**      | Rows that completed before the grace period elapsed are preserved. Cases interrupted by post-grace shutdown produce **no row** — absence + run status `CANCELLED` carries the signal. |
| Catastrophic executor error | **FAILED**         | Whatever was buffered before the failure is flushed best-effort; the rest are absent. The run is marked FAILED via the existing outer catch. |

Per-case worker exceptions therefore do not fail the run; they just leave evidence. Cancellation does not pretend rows were processed. Catastrophic failure marks the run FAILED and stops trying.

## What Changes

- Stop using `cancellationGracePeriodMs` as an overall evaluation timeout. `InProcessEvaluationExecutor` SHALL wait for all dispatched futures via unbounded `join()`; per-call bounding stays the responsibility of `requestTimeoutMs`.
- `cancellationGracePeriodMs` SHALL apply ONLY when `cancellationSignal == true` — implemented via `executor.shutdown()` + `allOf(...).get(grace, MS)` + `shutdownNow()` if still alive.
- Restructure the executor's lifecycle ordering: stop dispatching → wait for workers (or grace, on cancel) → `shutdownNow()` if needed → single final flush. Eliminates the `flush(buffer)` then `shutdownNow()` race that left late results unflushed.
- Replace the worker-level silent-drop catch with synthetic `ExecutionStatus.ERROR` row emission via a new `TestCaseRunResultFactory` component. A row is added to the buffer with the original input, run index, exception class/message, and zeroed timing. Best-effort: if buffering itself fails, log loudly and move on (no further attempts).
- Do NOT synthesize rows for cases interrupted by post-grace cancellation shutdown. After `shutdownNow()`, log a single WARN with the count of unfinished dispatched pairs for diagnostic visibility, then perform the single final flush. Absence of those rows IS the signal — combined with the run's `CANCELLED` status.
- Per-test-case errors SHALL NOT mark the run FAILED. The run is marked FAILED only when the executor itself throws (uncaught exception escapes the dispatch loop). The existing outer `catch (Exception)` in `TestSuiteEvaluationJob.executeRunAsync` already handles this path; the executor SHALL re-throw catastrophic exceptions after best-effort buffer flush instead of swallowing them.
- No change to the `ExecutionStatus` enum (stays SUCCESS / FAILED / TIMEOUT / ERROR). No new analytics aggregator audits, no OpenAPI schema changes for status values.
- No backfill or migration of historical runs (per product decision).

## Capabilities

### New Capabilities
_None._

### Modified Capabilities
- `eval-execution-engine`: tighten "Graceful cancellation" and "Batch result writing" requirements; add a new requirement "Synthetic ERROR result for worker exception" and "Diagnostic logging for unfinished cases on cancel" that codify the failure model above.

## Impact

- **Code**:
  - `service.domain.job.InProcessEvaluationExecutor` — main rewrite (timeout semantics, lifecycle ordering, synthetic ERROR emission on worker exception, rethrow on catastrophe).
  - `service.domain.job.TestCaseRunResultFactory` — new injectable `@Component` with a single `errorResult(...)` method that builds a fixed-shape ERROR `TestCaseRunResult` from input + caught exception + clock. Must not throw.
  - `service.domain.job.EvaluationWorker` — no behavioural change; if helpful, the existing `Resolution failure recorded as ERROR` path can delegate to the new factory for consistency, but this is not required for the fix.
- **Configuration**: `test-suite-run.execution.cancellation-grace-period-ms` semantics clarified in `docs/configuration.md` (no property name change; `application.yml` value unchanged).
- **Database**: no Flyway migration. No enum changes.
- **API**: no DTO/schema changes. Clients may observe runs with `status = "CANCELLED"` and `count(rows) < numberOfTestCases × numberOfRuns` — this is now the authoritative way to detect "didn't run" cases. UI/reporting MAY compute the missing count as `expected_total − actual_rows` if needed.
- **Tests**: new unit tests for `InProcessEvaluationExecutor` (slow-worker no-timeout, worker-exception → synthetic ERROR row, cancellation drops unfinished rows cleanly, catastrophic dispatch-loop error rethrows); new unit test for `TestCaseRunResultFactory`; functional test asserting the three-tier outcomes against a real Postgres.
- **Observability**: WARN log on cancellation that names the count of unfinished dispatched pairs. ERROR log on worker exception (already present), plus the synthetic ERROR row in analytics.
- **Risks**:
  - Per-case bug visibility is *best-effort* via the synthetic ERROR row. If the catch-block synthesis itself fails (rare — flush of a single row from a worker with a stack), the row is lost; we do not chain further fallbacks.
  - Aggregations / eval summaries that compute pass-rate or fail-rate using `count(rows)` as denominator will still be honest under cancellation (smaller sample, but no double-counted nulls). If a downstream consumer wants to flag "incomplete runs," it can compare `count(rows)` against `numberOfTestCases × numberOfRuns`.
