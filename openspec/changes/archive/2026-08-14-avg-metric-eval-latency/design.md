## Context

`test_case_eval_summaries` already carries `exec_duration_ms`, a whole-test-case Phase-1 execution duration copied verbatim from `TestCaseRunResult` into every `EvalSummaryBatchWriteItemDto` (see `InProcessMetricEvaluationExecutor.buildItem`, `EvalSummaryMapper`, `PostgresEvalSummaryRepository`). It is also aggregated as `avgExecDurationMs` in `PostgresEvalSummaryRepository.countMatches` for run comparison. None of this measures the Phase-2 metric evaluation step itself: `MetricEvaluationWorker.evaluate(...)` calls `metricProviderClient.evaluate(providerId, request)` (with retry/backoff inside `invokeWithRetries`) and returns or throws with no timing captured anywhere in the call chain (`MetricEvaluationWorker` → `InProcessMetricEvaluationExecutor.evaluateAndBuild` → `TsmdEvaluationResult`).

`InProcessMetricEvaluationExecutor.evaluateAndBuild` dispatches one `CompletableFuture.runAsync` per TSMD against a shared virtual-thread executor, bounded by a per-provider `Semaphore`. Each future's outcome lands in a `ConcurrentHashMap<String, TsmdEvaluationResult>` keyed by TSMD name, via one of three sealed variants: `Success`, `Failure` (transport/interrupt error), or `ConditionError` (the TSMD's JSONata `condition` didn't evaluate cleanly — never dispatched to a provider at all). A `CompletableFuture.allOf(...).get(timeoutMs)` join reconciles any TSMD that never completed in time into a synthetic `Failure`.

## Goals / Non-Goals

**Goals:**
- Measure elapsed wall-clock time for each dispatched TSMD's provider call, including calls that fail (transport error, interrupt) or are still in flight when the per-result timeout fires.
- Aggregate those durations into a single `avgMetricEvalDurationMs` scalar per `(TestCaseRunResult, computation)` and persist it on the corresponding `test_case_eval_summaries` row.
- Make the new column filterable/aggregatable (`avg`, etc.) through the existing structured Query DSL with no resolver/registry code changes, by relying on `JooqTableSchemaResolver`'s automatic column discovery — the same mechanism that already picked up `turn_index`/`total_turns` (`V1.14`) with zero DSL wiring.
- Preserve `execDurationMs` semantics untouched; this is an additive, independent measurement.

**Non-Goals:**
- Per-metric (per-TSMD-name) latency breakdown/storage — the user explicitly chose a single averaged scalar per row, not a JSONB map keyed by metric name.
- Changing `MetricEvaluationWorker`'s public method contract (`evaluate(...)` still returns `EvaluationResponseDto` or throws) — timing is captured at the call site in the executor instead, to avoid touching the worker's retry/span logic.
- New Query DSL functions, resolvers, or whitelists — none are needed for this column to be queryable.
- Extending run-comparison (`EvalSummaryMatchStats`/`RunComparisonRunDto`) — left as a natural, separately-scoped follow-up; not required for the DSL-based summary display this change targets.

## Decisions

### 1. Time at the call site in `InProcessMetricEvaluationExecutor`, not inside `MetricEvaluationWorker`
Wrapping `worker.evaluate(tsmd, result, semaphore, context)` with `clock.millis()` before/after inside the existing per-TSMD `CompletableFuture.runAsync` lambda (in `evaluateAndBuild`) captures the full call — semaphore wait, retries, backoff sleeps, and the terminal outcome — in one place, without changing `MetricEvaluationWorker`'s method signature, its OpenTelemetry span lifecycle, or its retry loop. The alternative (timing inside `MetricEvaluationWorker` and threading a duration back through a new success-carrier type plus a new checked-exception-with-duration type) touches more files for no benefit, since the executor already owns the boundary where `TsmdEvaluationResult` variants are constructed.

`InProcessMetricEvaluationExecutor` gains a `Clock clock` constructor dependency (via `@RequiredArgsConstructor`, from the existing `ClockConfiguration` bean), per the project's no-`System.currentTimeMillis()` rule.

### 2. Carry `durationMs` on `TsmdEvaluationResult.Success` and `Failure`; exclude `ConditionError`
`ConditionError` means the TSMD's `condition` evaluation itself failed *before* dispatch — no provider call, no semaphore acquisition, nothing to time. Giving it a `durationMs()` of `0` would silently pull the average toward zero for rows with condition errors, which is misleading. `TsmdEvaluationResult` becomes:
```java
sealed interface TsmdEvaluationResult permits Success, Failure, ConditionError {
    List<String> outputFieldNames();

    record Success(EvaluationResponseDto response, List<String> outputFieldNames, long durationMs) implements TsmdEvaluationResult {}
    record Failure(Exception error, List<String> outputFieldNames, long durationMs) implements TsmdEvaluationResult {}
    record ConditionError(String message, List<String> outputFieldNames) implements TsmdEvaluationResult {}
}
```
The average in `evaluateAndBuild` filters to `Success`/`Failure` instances only.

### 3. Timeout/unfinished TSMDs still contribute real elapsed time
The existing reconciliation loop (`dispatchedTsmds.forEach(tsmd -> tsmdResults.putIfAbsent(tsmd.getName(), new Failure(...)))`, run after the `allOf(...).get(timeoutMs)` join) currently builds a synthetic `Failure` with no timing information, because the future never completed. To honor "still record actual elapsed time" for this case: capture `long dispatchStartedAtMs = clock.millis()` immediately before each `CompletableFuture.runAsync(...)` call (per TSMD, alongside the existing per-TSMD dispatch loop), and when reconciling an unfinished TSMD, compute `clock.millis() - dispatchStartedAtMs` as its `durationMs`. This reports "how long we waited before giving up," which is the real latency cost attributable to that TSMD for this result, consistent with `execDurationMs`'s existing convention of recording a duration even on non-SUCCESS completion.

### 4. Average computed once per result, after reconciliation, before `buildItem`
```java
long avgMetricEvalDurationMs = (long) tsmdResults.values().stream()
        .filter(r -> !(r instanceof TsmdEvaluationResult.ConditionError))
        .mapToLong(r -> switch (r) {
            case TsmdEvaluationResult.Success s -> s.durationMs();
            case TsmdEvaluationResult.Failure f -> f.durationMs();
            case TsmdEvaluationResult.ConditionError ce -> throw new IllegalStateException();
        })
        .average()
        .orElse(0.0);
```
(Filtered stream never reaches the `ConditionError` branch; exact switch shape decided at implementation time — a small private helper on `TsmdEvaluationResult` such as a default `durationMs()` returning `0` for `ConditionError` plus the `filter` above is an equally acceptable, simpler shape.) Rows with zero dispatched TSMDs (metric-less runs, `buildPropagatedItem` for non-SUCCESS results) get `0L`.

### 5. New column via plain Flyway migration; no DSL registration needed
`V1.16__AddAvgMetricEvalDurationToEvalSummaries.sql`:
```sql
ALTER TABLE test_case_eval_summaries ADD COLUMN avg_metric_eval_duration_ms BIGINT NOT NULL DEFAULT 0;
```
Confirmed by inspecting `PostgresEvalSummaryEntityResolver` (delegates entirely to `JooqTableSchemaResolver.bindings(TEST_CASE_EVAL_SUMMARIES)`, which walks `table.fields()` and builds a `QueryFieldBinding` per column automatically) and by the `V1.14` precedent (added `turn_index`/`total_turns` with zero touches to any DSL resolver/registry file): once `./gradlew generateJooq` regenerates `TestCaseEvalSummaries`/`TestCaseEvalSummariesRecord`, the new column is immediately selectable, filterable, and aggregatable (`avg`, `sum`, `min`, `max` — all generic `QueryFunction` beans in `BuiltInQueryFunctions`) via the structured Query DSL with no code change beyond the migration + codegen.

### 6. Wire through the same file set as `execDurationMs`
To keep the new field consistent with every other `test_case_eval_summaries` column, mirror `execDurationMs`'s touchpoints one-for-one:
- `EvalSummary` model — add `Long avgMetricEvalDurationMs`.
- `EvalSummaryRecordMapper` — add to all four mapping methods (`map`, `mapList`, `mapExport`, `mapExportWithBodies`).
- `PostgresEvalSummaryRepository` — add to the batch insert `.set(...)`.
- `EvalSummaryBatchWriteItemDto` — add `@NotNull Long avgMetricEvalDurationMs`.
- `EvalSummaryMapper` — add the MapStruct `@Mapping`.
- `EvalSummaryResponseDto` / `EvalSummaryDetailResponseDto` — add the field with an OpenAPI `@Schema(example = ...)`.
- `EvalSummaryExportColumnPlanner` — add a `plain("avgMetricEvalDurationMs", ...)` descriptor for CSV export parity.

## Risks / Trade-offs

- **[Risk]** Timing at the call site (outside `MetricEvaluationWorker`) means the measured window includes semaphore-acquisition wait time inside `worker.evaluate` (the semaphore `acquire()` happens inside the worker, before `invokeWithRetries`). → **Mitigation**: this is consistent with `execDurationMs`'s own semantics (whole end-to-end attempt, not isolated network time), and avoiding it would require restructuring `MetricEvaluationWorker` to expose acquisition and invocation as separate steps — not justified for this change's scope.
- **[Risk]** The reconciliation-loop `dispatchStartedAtMs - clock.millis()` measurement for timed-out TSMDs measures "time until the overall per-result timeout fired," not the individual TSMD's own timeout — if TSMD A finishes in 50ms but TSMD B is still running at the 30s overall timeout, both a completed-late TSMD and a truly-stuck one could be conflated only in the sense that the *timeout* value is shared, not the measured duration (each still gets its own accurate elapsed time from its own dispatch instant). → No mitigation needed; this is correct given the per-result (not per-TSMD) timeout design already in place.
- **[Risk]** Existing rows backfill to `0` via `DEFAULT 0` on the new column, which could look like "zero latency" rather than "not measured" in older data when displayed in a "since when" comparison. → **Mitigation**: acceptable and consistent with how other additive columns (`turn_index`, `total_turns`) backfill in this codebase; the summary display should be understood as reflecting values only for runs computed after this change ships.

## Migration Plan

1. Add `V1.16__AddAvgMetricEvalDurationToEvalSummaries.sql`; run `./gradlew generateJooq` and commit the regenerated sources under `src/main/java-generated/`.
2. Land the `TsmdEvaluationResult`, `InProcessMetricEvaluationExecutor`, and downstream DTO/mapper/repository changes in the same PR (they're interdependent — the migration alone doesn't populate the column with real data).
3. Update `docs/database-schema.md` with the new column, per AGENTS.md's rule for schema-changing migrations.
4. No feature flag or phased rollout needed — additive column with a safe default, no breaking API/schema change.
5. Rollback: a follow-up migration dropping the column would be safe (no other schema depends on it); reverting the code changes is a standard git revert.

## Open Questions

- Should `EvalSummaryMatchStats`/`RunComparisonRunDto`/`RunComparisonService` also gain an `avgMetricEvalDurationMs` sibling to `avgExecDurationMs`, for parity in the run-comparison summary? The proposal calls this optional; deferring it to a follow-up change unless the UI work consuming this column needs it immediately.
