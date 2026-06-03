## Context

`InProcessEvaluationExecutor` is the in-process strategy implementing `EvaluationExecutor`. It dispatches one virtual-thread task per `(testCaseId, runIndex)` pair, semaphore-bounded by concurrency, and writes results through `ResultBatchWriter`. Issue #929 surfaces three latent defects in this executor — described in the proposal — that combine to silently lose test case results AND to mark the run COMPLETED while results are missing.

The executor's failure-handling code has been wrong since file inception (`git log --diff-filter=A` → first commit `42dddcb`). There is no prior correct behaviour to restore; this is forward fix only.

The fix is purely internal to `service.domain.job.*`. No DB schema change. No `ExecutionStatus` enum change. No API/DTO removals.

A guiding principle: **rows in `test_case_run_results` represent work that actually executed.** We do not synthesize a row to mark "this case didn't run" because we cannot guarantee the synthesis will succeed (OOM, JVM kill, analytics-DB outage). The combination of (run status) + (count delta against `numberOfTestCases × numberOfRuns`) is the authoritative signal for "incomplete runs." We do, however, synthesize a row for *worker exceptions* (per-case bugs) — best-effort — because that is the only way to make a silent worker bug visible without scanning logs.

## Goals / Non-Goals

**Goals:**

- Eliminate the spurious `TimeoutException` from `allOf().get(grace)` — runs that take longer than the grace period are no longer punished.
- Restore the intended semantic of `cancellationGracePeriodMs`: it bounds the time we wait for **already-running** workers to drain **after `cancellationSignal` is set** — never as an overall budget.
- Surface per-case worker exceptions as written `ExecutionStatus.ERROR` rows (best-effort) instead of silent drops.
- Keep the run status correct under all three failure tiers: per-case worker errors → COMPLETED, user cancellation → CANCELLED, executor catastrophe → FAILED (existing path).
- Eliminate the `flush(buffer)` → `shutdownNow()` race so any late `addResult` from a finished worker lands in the single final flush.

**Non-Goals:**

- No backfill, repair, or migration of historical runs that are already incorrectly marked COMPLETED. (User confirmed.)
- No rename of the `cancellation-grace-period-ms` config property (still semantically correct under the new behavior).
- No change to the `EvaluationExecutor` interface, `EvaluationContext`, or any controller/DTO contracts.
- No change to `ExecutionStatus` enum. No new value `CANCELLED`. Cancellation is recorded by the run's `status = CANCELLED` plus the absence of rows for unfinished cases.
- No restructuring of `ResultBatchWriter` beyond what is required to close the flush race.
- No new "completed-vs-expected" surface on the run record. Callers that want to detect incomplete runs MAY compute `expected_total − count(rows)` themselves.
- No guarantee that every dispatched task produces a row. We make the *common* failure modes (worker throws an exception inside its catch block) produce a row best-effort; we do not chain fallbacks for that synthesis itself.

## Decisions

### D1. Wait for all dispatched futures unbounded; no timeout on `allOf().get`

```java
// Before:
CompletableFuture.allOf(futures...).get(context.getCancellationGracePeriodMs(), MILLISECONDS);
// After (no cancel):
CompletableFuture.allOf(futures...).join();          // no per-call timeout
```

Per-call bounding is already the responsibility of `EvaluationWorker.executeOneCall(...)` via `requestTimeoutMs`. The executor must trust the worker's bound — adding a separate global ceiling either (a) cuts off a legitimate long run, as in #929, or (b) duplicates a bound that should be tuned per-call.

`Alternatives considered:`
- *Use `cancellationSignal`-aware unbounded poll loop instead of `join()`*. Rejected — unnecessary complexity. `cancellationSignal` is checked inside the dispatch loop and inside the worker; a worker that observes it returns promptly and its future completes naturally.
- *Add a separate `maxRunDurationMs` config property*. Rejected — out of scope; would re-introduce the same risk we just removed without an established product reason.

### D2. `cancellationGracePeriodMs` only applies after cancellation

The shutdown sequence becomes signal-driven:

```
Dispatch loop ─┬── normal completion ───▶ wait for futures (unbounded) ──┐
               │                                                          │
               └── cancellationSignal observed ──▶ stop dispatching ──▶ │
                                                                          │
   ┌──────────────────────────────────────────────────────────────────────┘
   ▼
 executor.shutdown()                          // no new tasks accepted
   │
   ▼
 wait for in-flight via allOf(futures).get(grace) IF cancelled
                                  OR  allOf(futures).join() otherwise
   │
   ▼   (cancelled & still alive after grace)
 executor.shutdownNow()                       // interrupts virtual threads
   │
   ▼
 (cancelled) log WARN with count of unfinished dispatched pairs
   │
   ▼
 flush(buffer)                                // single, final flush
```

The grace period guards the *post-cancel drain* only. Normal completion has no time bound. Catastrophic executor exceptions skip cleanly to flush + rethrow.

> **Spec impact note**: in the `eval-execution-engine` delta, the baseline scenario `Grace period for in-flight calls` is renamed/restated as `Grace period applied only after cancellation`, and a new scenario `Long-running uncancelled run does NOT time out` is added. Semantics tighten — the grace period is no longer applied to runs that were never cancelled.

`Alternatives considered:`
- *Keep the timeout but rename the property to `runMaxDurationMs`*. Rejected — would require config docs migration and silently change ops behaviour for any user who relied on the old default; user confirmed no such overall-bound behavior is desired.
- *Use `awaitTermination(grace)` instead of `allOf(...).get(grace, MS)`*. Equivalent for our case; we choose `allOf().get(grace)` because we already track the futures list and want their per-future `isDone()` state to drive the diagnostic count for the cancellation log.

### D3. Synthesize `ExecutionStatus.ERROR` row when a worker throws unexpectedly

Today, the inline `catch (Exception e)` inside the runnable at `InProcessEvaluationExecutor.java:100` logs and returns — the test case's row simply does not exist. The fix is best-effort synthesis from inside that same catch block. Rationale: the worker thread is alive and inside a Java catch frame at this point, so it almost always has memory and stack to allocate one row and append it to the buffer. This makes per-case worker bugs visible in the analytics surface — the original #929 visibility gap — without making any guarantee we cannot keep.

```java
try {
    TestCaseRunResult result = evaluationWorker.execute(input, context, ri, responseColumns);
    resultBatchWriter.addResult(buffer, result);
} catch (Exception e) {
    log.error("Worker failed for test case {} run {}: {}", input.getTestCaseId(), ri, e.getMessage(), e);
    try {
        TestCaseRunResult synthetic = testCaseRunResultFactory.errorResult(input, ri, e, clock.millis());
        resultBatchWriter.addResult(buffer, synthetic);
    } catch (Exception synthEx) {
        log.error("Failed to record synthetic ERROR for test case {} run {}: {}",
                input.getTestCaseId(), ri, synthEx.getMessage(), synthEx);
    }
} finally {
    semaphore.release();
}
```

The synthetic row carries:
- `executionStatus = ERROR`
- `responseBody` = JSON envelope `{"error":{"type":"<exception class>","message":"<msg>","origin":"executor"}}` (mirrors existing ERROR responseBody convention from `EvaluationWorker`).
- `responseStatusCode = null`
- `execStartedAtMs = execCompletedAtMs = nowMs` (no real timing — task didn't reach the network).
- `execDurationMs = 0`
- `retryCount = 0`, `logDetails = null`.

**Rationale for `catch (Exception)` here** (broader than the AGENTS.md default of "catch specific exceptions"): the contract of this catch IS "any unexpected runtime failure escaping the worker." A narrower catch re-introduces the silent-drop bug. This is the deliberate exception to the rule, called out in the spec.

`Alternatives considered:`
- *Re-throw and let the outer catch handle it*. Rejected — that path leads to FAILED for the whole run, violating the "per-case errors don't fail the run" rule.
- *Inline the synthesis in the executor without a factory component*. Rejected — fixed-shape construction is testable in isolation and reusable. Extract a `TestCaseRunResultFactory` (`@Component`, `service.domain.job`).

### D4. Cancellation: do NOT synthesize rows; log a count and let absence carry the signal

After `executor.shutdown()` and `allOf(futures).get(grace, MS)`, if the wait threw `TimeoutException` (cancelled run, workers still alive after grace) we:

1. Call `executor.shutdownNow()` to interrupt virtual threads.
2. Compute `unfinishedCount = futures.stream().filter(f -> !f.isDone()).count()` and emit `log.warn("Run {} cancelled with {} test case(s) interrupted before completion", runId, unfinishedCount)`.
3. Perform the single final `flush(buffer)`.
4. Return normally — `TestSuiteEvaluationJob.executeRunAsync` reads `cancellationSignal == true` and marks the run `CANCELLED`.

We deliberately do NOT iterate the unfinished pairs to write synthetic CANCELLED rows. Reasons:

- The promise "every dispatched task produces a row" cannot be honoured under OOM / JVM kill / analytics-DB outage. Pretending we can leads to misleading specs and brittle tests.
- The information "this case didn't run because of cancellation" is fully recoverable from the pair (run status `CANCELLED`, count delta against `numberOfTestCases × numberOfRuns`). No new persisted state is needed.
- It removes the need for a `CANCELLED` enum value, an aggregator audit, and OpenAPI updates — all of which the previous design had.

`Alternatives considered:`
- *Add `ExecutionStatus.CANCELLED` and synthesize one row per unfinished pair*. Rejected — over-promises a guarantee we can't keep, and adds incidental complexity (enum audit, aggregator classification, OpenAPI churn) for marginal observability gain over the count delta + log line.
- *Synthesize from inside the worker on `InterruptedException`*. Rejected — by the time `shutdownNow()` fires, the worker may be inside a blocked socket read; even if the interruption surfaces, calling `addResult` from an interrupted virtual thread is racy with the executor's own final flush.

### D5. The `RuntimeException` semantic for catastrophic executor failure

If anything in the dispatch loop itself throws (DB unavailable when reading `test_case_run_inputs`, OOM, etc.), the existing `catch (Exception e)` in `executeRunAsync` (`TestSuiteEvaluationJob.java:158`) already marks the run FAILED. The executor's job is to:
- Best-effort flush whatever is buffered.
- Re-throw the original exception so the outer catch can mark FAILED with `UNEXPECTED_ERROR` (existing code path).

The current code swallows everything. Change: at end of the executor's `catch (Exception e)` (non-`InterruptedException`), after best-effort flush, **re-throw** the original exception unwrapped. We do not introduce a new `EvaluationExecutorException` class — plain rethrow preserves the existing error code path and adds no new symbol. The outer catch's logging already records the exception type and message.

`Alternatives considered:` introducing a typed `EvaluationExecutorException` was considered and rejected — it would add a new symbol with no caller-side benefit (the outer catch already handles `Exception` generically).

### D6. Single, final flush — never flush mid-shutdown

Move `resultBatchWriter.flush(buffer)` to AFTER any `shutdownNow()` call. The current code's "`finally { flush; shutdownNow; }`" creates the race we documented in the proposal — late-arriving `addResult` calls land in a buffer that's already been drained.

After D2/D3/D4, ordering is deterministic:

1. `executor.shutdown()` (no new tasks)
2. wait for futures: bounded (grace) if cancelled, unbounded otherwise
3. `executor.shutdownNow()` only if cancelled+still running
4. (cancelled) WARN log with unfinished count
5. single final `flush(buffer)`

By step 5 the buffer's only writer is the main thread, so the `addResult`-during-`flush` window is gone.

## Risks / Trade-offs

- **Risk: Best-effort synthesis of the ERROR row from the worker's catch block can itself fail.** → Mitigation: `TestCaseRunResultFactory.errorResult(...)` MUST not throw; it builds a fixed-shape envelope from input + exception + clock, no JSON parsing of test case data, no template resolution. The outer `try { addResult } catch { log }` covers buffer-add failure as a last line of defence. We do not attempt further fallbacks beyond logging — the OOM honesty principle.
- **Risk: A buggy worker that fails on every case in a run will produce N synthetic ERROR rows but the run will still be COMPLETED.** → This is intentional. The user's "per-case errors don't fail the run" rule. Operators detect the situation by sorting/filtering rows by `executionStatus = ERROR`.
- **Risk: Aggregations (eval summaries, run-level metrics) compute rates from `count(rows)` and a cancelled run produces a smaller sample than expected.** → Mitigation: this is consistent with the new model — rows represent executed work. If consumers want to display "X of Y completed," they compute the delta against `numberOfTestCases × numberOfRuns` themselves. No spec-level rule needed.
- **Trade-off: Cancellation of a long run with many unfinished cases produces no per-case audit trail in `test_case_run_results`.** → Acceptable; the WARN log line names the count, and the run's `status = CANCELLED` is the authoritative signal.

## Migration Plan

1. Roll out the executor changes — backward-compatible (no schema change, no enum change, no API-breaking change).
2. Verify in staging: trigger a long-running suite (3+ minute eval) and confirm it no longer hits `TimeoutException`. Trigger a cancel mid-flight and confirm:
   - Run reaches `status = CANCELLED`.
   - `count(rows)` for that run is less than `numberOfTestCases × numberOfRuns`.
   - WARN log line names the unfinished count.
3. Verify a deliberately-buggy worker (e.g., monkey-patched to throw on a specific test case) now produces an `ExecutionStatus = ERROR` row instead of disappearing.
4. No rollback step beyond reverting the change. No data fixup required for the fix itself; historical runs already-marked-COMPLETED-with-missing-rows remain as-is per product decision.

## Resolved Open Questions

- **Should CANCELLED rows count toward `numberOfTestCases` progress reporting via SSE?** Resolved by D4: there are no CANCELLED rows. Progress events fire on real-row + synthetic-ERROR-row flushes, so per-case worker bugs are visible to SSE consumers; cancelled cases simply stop producing progress events at the cancellation boundary. The run's `status = CANCELLED` notification carries the rest.
- **Plain rethrow vs. typed `EvaluationExecutorException`?** Resolved by D5: plain rethrow.
