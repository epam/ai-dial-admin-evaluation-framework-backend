## Context

The evaluation framework currently runs test suite evaluations in-process: `TestSuiteEvaluationJob` → `InProcessEvaluationExecutor` → `EvaluationWorker` → `TestCaseRunResult`. Metric computation (EvalSummary, RunMetricSnapshot) is done by external services posting to batch APIs.

Test Suite Metric Definitions (TSMDs) associate metrics with test suites via polymorphic bindings (TestCase, Response, Constant sources). Metric declarations and versions are synced from external metric providers via `MetricProviderSyncService`. The metric provider API exposes a `POST /evaluate` endpoint that accepts `{metric_name, config, input}` and returns `{metric_name, output}` where output fields are either `{type: "value", value: number, details?: object}` or `{type: "error", message: string}`.

This change adds a second execution phase to the test suite run lifecycle that evaluates metrics in-process using the same job infrastructure patterns.

## Goals / Non-Goals

**Goals:**
- Chain metric evaluation after deployment evaluation within the same run lifecycle
- Reuse the existing virtual-thread + semaphore concurrency pattern, adapted for provider-bounded parallelism
- Write EvalSummary and RunMetricSnapshot records via service-layer client wrappers that delegate to existing batch write services
- Support cancellation and configurable retry for `/evaluate` calls

**Non-Goals:**
- Separate re-evaluation API endpoint (future scope — requires independent status tracking)
- Rate limiting for metric evaluation (semaphore concurrency is sufficient)
- Progress reporting via SSE for the metric evaluation phase (future scope)

## Decisions

### D1: Metric evaluation chained in TestSuiteEvaluationJob with consistent pattern

**Decision:** Add metric evaluation as Phase 2 in `TestSuiteEvaluationJob.executeRunAsync()`, after Phase 1 (deployment evaluation) completes. Both phases follow the same structural pattern: `buildContext()` → `executor.execute(context)`. `MetricEvaluationExecutor` is an interface with `InProcessMetricEvaluationExecutor` as the in-process implementation, mirroring the `EvaluationExecutor` / `InProcessEvaluationExecutor` pattern. RunMetricSnapshot capture is the responsibility of the executor implementation (not the job), keeping the job as a thin orchestrator.

**Rationale:** Simplest path — no new API endpoint, no new status entity. The run status (PENDING → RUNNING → COMPLETED) naturally covers both phases. If Phase 1 is cancelled, Phase 2 is skipped. The interface + implementation pattern enables future K8s Job delegation for metric evaluation.

**Alternative considered:** Separate API trigger with its own status entity. Deferred to future scope (re-evaluation use case).

### D2: Provider-bounded concurrency with cross-result parallelism

**Decision:** Each metric provider gets its own `Semaphore(configuredConcurrency)`. Metric evaluation tasks are dispatched across all test case results and all TSMDs concurrently, with the per-provider semaphore controlling the maximum concurrent `/evaluate` calls per provider.

**Rationale:** Different providers may have different capacity. The semaphore naturally throttles total concurrent calls per provider regardless of which test case result they belong to. Cross-result parallelism maximizes throughput.

**Implementation:**
```
Map<String, Semaphore> providerSemaphores = providers.stream()
    .collect(toMap(providerId, id -> new Semaphore(configuredConcurrency)));
```
Each `MetricEvaluationWorker.execute()` acquires the provider's semaphore before calling `/evaluate` and releases it in a `finally` block.

**Alternative considered:** Per-result sequential evaluation (process all TSMDs for one result before moving to the next). Rejected — underutilizes provider capacity and doesn't match the existing evaluation pattern.

### D3: EvalSummary assembly via cross-result parallel dispatch

**Decision:** Within `InProcessMetricEvaluationExecutor`, for each cursor page, dispatch TSMD evaluations for ALL results concurrently on a virtual thread pool. For each SUCCESS result, compose a `CompletableFuture<EvalSummary>` that internally dispatches all TSMDs, waits for them, merges outputs, and builds the EvalSummary. Wait for all result-level futures with a single `allOf().get(gracePeriod)`.

**Implementation sketch:**
```
for each page of results:
    resultFutures = []
    for each result in page:
        if result.executionStatus != SUCCESS:
            add propagated EvalSummary to buffer
        else:
            resultFuture = supplyAsync(() -> {
                tsmdFutures = for each tsmd: supplyAsync(worker.evaluate(...))
                allOf(tsmdFutures).join()
                collect results (including per-TSMD exceptions)
                merge outputs into metricValues + metricInfos
                build and return EvalSummary
            })
            resultFutures.add(resultFuture)
    allOf(resultFutures).get(gracePeriod, MILLISECONDS)
    add completed EvalSummaries to buffer
```

**Rationale:** Matches the existing `InProcessEvaluationExecutor` pattern — dispatch all futures, single wait point. Cross-result parallelism maximizes provider utilization (e.g., with 5-permit semaphore and 2 TSMDs per result, 2-3 results' TSMDs can be in-flight simultaneously). The provider semaphore naturally throttles total concurrent calls.

### D4: Service-layer client wrappers for batch writes

**Decision:** Create thin `@Component` client wrappers (`EvalSummaryBatchWriteClient`, `RunMetricSnapshotBatchWriteClient`) in `service.domain.job` that convert internal models to the existing batch write DTOs and delegate to `EvalSummaryService.batchCreate()` and `RunMetricSnapshotService.batchCreate()` respectively.

**Rationale:** Single write path — internal and external writes go through the same validation, mapping, and persistence logic. No divergence to maintain. The DTO conversion overhead is negligible compared to the `/evaluate` call latency. Batch size limits from the existing services are respected; the client wrappers chunk items accordingly.

**Alternative considered:** Direct repository writes bypassing the service layer (same pattern as `ResultBatchWriter`). Rejected — introduces a second write path with potential divergence in validation and mapping logic.

### D5: BindingResolver as injectable @Component

**Decision:** Create `BindingResolver` as an injectable `@Component` in `service.domain.job` that resolves TSMD bindings against test case data and extracted columns.

**Rationale:** Per project conventions, specialized conversion logic MUST be top-level injectable classes (not private methods). `BindingResolver` can be unit-tested independently with various binding configurations.

**Resolution logic:**
- Parse `testCaseData` JSON string → `Map<String, Object>`
- Parse `extractedColumns` JSON string → `Map<String, Object>`
- For each binding:
  - `TestCase` source → look up `columnName` in testCaseData map (null if missing)
  - `Response` source → look up `columnName` in extractedColumns map (null if missing)
  - `Constant` source → use literal value as-is
- Return `Map<String, Object>` (property name → resolved value)

### D6: MetricProviderClient.evaluate() method

**Decision:** Add `evaluate(String providerId, EvaluationRequestDto request)` to the existing `MetricProviderClient`, reusing `MetricProviderRestClientFactory` to get the provider's `RestClient`.

**Rationale:** The factory already manages per-provider RestClients with configured timeouts. Adding a method is simpler than creating a new client class. The `/evaluate` endpoint lives on the same base URL as `/metrics`.

### D7: Configuration under `metric-evaluation.*` prefix

**Decision:** New `MetricEvaluationProperties` class with properties:
- `metric-evaluation.default-concurrency-per-provider` (default: 5)
- `metric-evaluation.retry.max-retries` (default: 0)
- `metric-evaluation.retry.retry-delay-ms` (default: 1000)
- `metric-evaluation.retry.retry-backoff-multiplier` (default: 2.0)
- `metric-evaluation.retry.max-retry-delay-ms` (default: 60000)
- `metric-evaluation.batch-size` (default: 100)
- `metric-evaluation.cancellation-grace-period-ms` (default: 30000)

**Rationale:** Separate from `test-suite-run.execution.*` since metric evaluation has different concerns (provider concurrency vs deployment concurrency, different retry semantics).

### D8: New repository method — findAllAggregatedByTestSuiteId

**Decision:** Add `findAllAggregatedByTestSuiteId(UUID testSuiteId)` returning `List<AggregatedMetricDefinition>`. Unpaginated. Reuses the existing 3-table JOIN SQL from `findAggregatedByIdAndTestSuiteId` but without the `WHERE md.id = :id` clause.

**Rationale:** TSMD count per suite is expected to be small (tens, not thousands). A single query loading all aggregated TSMDs avoids N+1 queries. The AggregatedMetricDefinition model already carries `declarationProviderId` needed for provider grouping and `metricDeclarationName` needed for `metric_name` in evaluation requests.

### D9: Output mapping — metricValues and metricInfos

**Decision:**
- `metricValues[tsmdName][outputFieldName]` = numeric value (for `type: "value"`) or `null` (for `type: "error"` or transport failure)
- `metricInfos[tsmdName][outputFieldName]` = `details` object (for `type: "value"` with details) or `{"error": message}` (for `type: "error"` or transport failure)
- Empty details objects are omitted from metricInfos
- Transport failures (worker exception) produce: `metricValues[tsmdName] = {"error": null}` placeholder and `metricInfos[tsmdName] = {"error": exceptionMessage}`

**Constructed using Jackson ObjectNode** (per project convention — never use StringBuilder for JSON). Null values in metricValues preserved via `ObjectNode.putNull()` (the shared ObjectMapper's `NON_NULL` inclusion would drop them). **Returns `ObjectNode` (not `String`)** — the batch write client passes `ObjectNode` directly to `EvalSummaryBatchWriteItemDto.metricValues` (which expects `JsonNode`), avoiding a String→JsonNode→String roundtrip through the service mapper.

### D10: Non-SUCCESS result propagation

**Decision:** For TestCaseRunResults with `executionStatus != SUCCESS`, write an EvalSummary row with:
- `executionStatus` = propagated from the TestCaseRunResult
- `metricValues` = `{}` (empty)
- `metricInfos` = `null`
- All other fields (testCaseData, extractedColumns, execDurationMs, responseStatusCode) copied from the result

**Rationale:** Ensures 1:1 correspondence between results and summaries. The frontend can display "no metrics — deployment call failed" for these rows.

### D11: EvalSummary executionStatus for metric errors

**Decision:** If any metric output field has `type: "error"` OR any TSMD evaluation fails with a transport error (worker exception), the EvalSummary's `executionStatus` is set to `FAILED`. If all metrics succeed, `executionStatus` is `SUCCESS`.

**Rationale:** Simple failure semantics for the initial implementation. Individual metric errors are captured in metricInfos for drill-down.

### D12: Worker throws on transport failure, executor maps to error entries

**Decision:** When the `/evaluate` call fails with an HTTP error (after retries exhausted) or timeout, the `MetricEvaluationWorker` SHALL throw an exception. The executor catches per-TSMD `CompletableFuture` exceptions and maps them to error entries: `metricValues[tsmdName][*] = null`, `metricInfos[tsmdName] = {"error": exceptionMessage}`. The `MetricOutputMapper` accepts a `Map<String, Object>` where values are either `EvaluationResponseDto` (success) or `Exception` (failure).

**Rationale:** `EvaluationResponseDto` represents a successful HTTP 200 response from the provider — it cannot represent transport failures. Throwing exceptions is the natural Java pattern for failure signaling. The executor already handles per-future exception collection via `CompletableFuture` semantics (`handle()`, `exceptionally()`). This cleanly separates the worker (HTTP call + retry) from the executor (assembly + error mapping).

## Risks / Trade-offs

**[Provider unavailability during metric eval]** → Per-test-case error capture in metricInfos. Run still completes. Retry mitigates transient failures.

**[Large test suites × many TSMDs = high call volume]** → Provider semaphores prevent overwhelming. Cursor pagination on result iteration prevents OOM. Batch writing amortizes DB round trips.

**[Binding resolution: missing columns resolve to null]** → Provider decides how to handle null inputs. This is documented behavior; the alternative (fail the metric) was rejected to keep flexibility.

**[Clock usage in production code]** → `computedAtMs` must use injected `Clock` (not `System.currentTimeMillis()`). `computationId` generated via `UUID.randomUUID()`.

**[Existing batch write API still usable]** → The external `POST /api/v1/analytics/eval-summaries` endpoint remains functional. In-process metric eval doesn't break or replace it — both paths write to the same table with idempotent ON CONFLICT DO NOTHING.
