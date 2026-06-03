## Context

The metric evaluation feature (Phase 2 of `TestSuiteEvaluationJob`) was introduced with a complex concurrency model: result-level parallelism (each result dispatched as `CompletableFuture`) with nested TSMD-level parallelism (a second virtual thread executor inside each result). This creates hard-to-reason-about concurrency and misses `Context.taskWrapping()` for OpenTelemetry propagation. The test case result query also uses `created_at_ms` as a proxy for run identity rather than the semantically correct `test_suite_run_id`.

## Goals / Non-Goals

**Goals:**
- Flatten the concurrency model to a single `Context.taskWrapping()` executor
- Iterate test case results sequentially, dispatch TSMD evaluations in parallel (matching `InProcessEvaluationExecutor` pattern)
- Filter test case results by `test_suite_run_id` via `FilterCondition` instead of `createdAtMs`
- Remove `createdAtMs` from `MetricEvaluationContext`

**Non-Goals:**
- Changing the repository interface or `findAll` method signature
- Modifying the API-facing `AnalyticsResultService` query path
- Changing retry/backoff logic in `MetricEvaluationWorker`
- Adding new tests (existing tests cover the behavior; this is internal refactoring)

## Decisions

### D1: Single flat executor with `Context.taskWrapping()`

**Choice**: One `ExecutorService executor = Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())` shared across all TSMD dispatches.

**Why**: The current nested model creates a new virtual thread executor per result inside `evaluateAndBuild()`. This is unnecessary — virtual threads are cheap, and provider-level semaphores already bound concurrency. A single executor is simpler and propagates OTel context correctly.

**Alternative considered**: Keep result-level parallelism but add `Context.taskWrapping()` to both executors. Rejected — adds complexity for no throughput gain (TSMD evaluations are the bottleneck, not result iteration).

### D2: Sequential result iteration, parallel TSMD dispatch

**Choice**: Iterate test case results one by one in the main thread. For each result, submit each TSMD evaluation as a `CompletableFuture` to the shared executor. Wait for all TSMD futures before moving to the next result.

**Flow**:
```
executor = Context.taskWrapping(newVirtualThreadPerTaskExecutor())
semaphores = buildProviderSemaphores(context)

cursor loop:
    page = resultRepository.findAll(...)
    for each result in page:            ← sequential
        if cancelled → break
        if non-success → buffer propagated item
        if success:
            for each TSMD:              ← parallel via executor
                semaphore.acquire()
                submit to executor
            wait for TSMD futures
            build eval summary from responses
            add to buffer
    flush if needed
flush remaining
executor.close()                        ← implicit via try-with-resources
```

**Why**: Mirrors `InProcessEvaluationExecutor`'s proven pattern. Result-level parallelism provided no meaningful benefit since TSMD evaluations (HTTP calls to metric providers) are the actual bottleneck, and those are already bounded by provider semaphores.

### D3: Filter by `test_suite_run_id` via `FilterCondition`

**Choice**: Build a `FilterCondition(field="runId", operator=EQ, rawValue=<runId>)` and pass it in the filters list to `resultRepository.findAll()`. Pass `null` for `runCreatedAtMs`.

**Why**: The `ANALYTICS_RESULTS` filter whitelist already maps `runId` → `test_suite_run_id` (UUID, EQ). Using `created_at_ms` as a run identity proxy is semantically incorrect — two runs could theoretically share the same creation timestamp. The existing filter infrastructure handles this cleanly with no repository API changes.

### D4: Remove `createdAtMs` from `MetricEvaluationContext`

**Choice**: Remove the `createdAtMs` field entirely. It was only used for the `findAll` query, which now uses `runId` filter instead.

**Why**: Dead field after D3. Keeping it would be confusing.

### D5: Keep `computedAtMs` in context, sourced from `clock.millis()`

**Choice**: Keep `computedAtMs` in `MetricEvaluationContext`, populated via `clock.millis()` in `TestSuiteEvaluationJob.buildMetricEvaluationContext()`. No changes to `TestSuiteEvaluationJob`'s Clock dependency.

**Why**: `computedAtMs` is used when writing `RunMetricSnapshots` and `EvalSummary` records — it needs to be consistent across the entire metric evaluation. Clock injection follows the project convention.

### D6: Add OpenTelemetry tracing to `MetricEvaluationWorker`

**Choice**: Inject `OpenTelemetry` into `MetricEvaluationWorker`. In the `evaluate()` method, create a span `metric.tsmd.evaluate` with attributes: `tsmd.name`, `tsmd.provider.id`, `eval.run.id` (from context), `result.id`. Wrap the `invokeWithRetries` call in `try (Scope scope = span.makeCurrent())`. Record status and end span in finally.

**Why**: `EvaluationWorker` already creates spans per test case execution (`eval.testcase.execute`). The metric evaluation path has no tracing, making it invisible in distributed traces. Since `Context.taskWrapping()` propagates the parent context to virtual threads, child spans created in the worker will correctly nest under the parent trace.

**Pattern** (matching `EvaluationWorker`):
```java
Span span = openTelemetry.getTracer("com.epam.aidial.evaluation")
        .spanBuilder("metric.tsmd.evaluate")
        .setAttribute("tsmd.name", tsmd.getName())
        .setAttribute("tsmd.provider.id", tsmd.getDeclarationProviderId())
        .setAttribute("eval.run.id", context.getTestSuiteRunId().toString())
        .setAttribute("result.id", result.getId().toString())
        .startSpan();
try (Scope scope = span.makeCurrent()) {
    return invokeWithRetries(tsmd, result, context);
} catch (Exception e) {
    span.setStatus(StatusCode.ERROR, e.getMessage());
    span.recordException(e);
    throw e;
} finally {
    span.end();
}
```

### D7: No per-result timeout — rely on cancellation signal and worker retries

**Choice**: Remove `waitForFutures` with `cancellationGracePeriodMs` timeout. Instead, just `CompletableFuture.allOf(tsmdFutures).join()` for each result's TSMD futures.

**Why**: Each `MetricEvaluationWorker.evaluate()` already has its own retry/timeout logic and checks the cancellation signal during backoff. The outer timeout was a belt-and-suspenders measure that added complexity. The cancellation signal provides the same interrupt capability.

## Risks / Trade-offs

- **[Slightly slower for many results with few TSMDs]** → Sequential result iteration means no overlap between result processing. Mitigated: TSMD HTTP calls dominate wall-clock time, not result iteration. Provider semaphores are the real throughput limiter.
- **[No outer timeout for stuck TSMD futures]** → If a metric provider hangs indefinitely beyond retry limits, the executor blocks. Mitigated: Worker has max retry count + max delay cap. Cancellation signal can interrupt during backoff. Virtual thread executor `close()` in try-with-resources provides final cleanup.
