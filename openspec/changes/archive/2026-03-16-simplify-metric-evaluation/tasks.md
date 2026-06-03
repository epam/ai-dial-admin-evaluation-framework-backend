## 1. MetricEvaluationContext Cleanup

- [x] 1.1 Remove `createdAtMs` field from `MetricEvaluationContext` (`service.domain.job.MetricEvaluationContext`). Remove the `.createdAtMs(run.getCreatedAt())` line from `TestSuiteEvaluationJob.buildMetricEvaluationContext()`. Done: `MetricEvaluationContext` has no `createdAtMs` field; `TestSuiteEvaluationJob` compiles without it.

## 2. InProcessMetricEvaluationExecutor Refactoring

- [x] 2.1 Rewrite `InProcessMetricEvaluationExecutor.execute()` to follow the `InProcessEvaluationExecutor` pattern. The new structure:

  **Executor creation**: Single `Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())` in a try-with-resources wrapping the entire method body.

  **Result query**: Build a `FilterCondition(field="runId", operator=EQ, rawValue=context.getTestSuiteRunId().toString())` and pass `List.of(runIdFilter)` to `resultRepository.findAll(filters, null, cursor, RESULT_PAGE_SIZE)`. No `createdAtMs` parameter.

  **Cursor pagination loop** (sequential result processing):
  ```
  do {
      check cancellation → break
      page = resultRepository.findAll(runIdFilters, null, cursor, RESULT_PAGE_SIZE)
      for each result in page:
          check cancellation → break
          if non-success → buffer buildPropagatedItem(result, context)
          if success:
              List<CompletableFuture<Void>> tsmdFutures = new ArrayList<>()
              Map<String, Object> tsmdResults = new LinkedHashMap<>() // or ConcurrentHashMap
              boolean hasError = false
              for each TSMD in context.getAggregatedTsmds():
                  Semaphore semaphore = providerSemaphores.get(tsmd.getDeclarationProviderId())
                  semaphore.acquire()
                  CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                      try {
                          EvaluationResponseDto response = worker.evaluate(tsmd, result, semaphore, context)
                          // Note: worker.evaluate() already acquires/releases semaphore internally
                          // — see task 2.2 for semaphore placement decision
                          synchronized (tsmdResults) {
                              tsmdResults.put(tsmd.getName(), response)
                          }
                      } catch (Exception e) {
                          log.warn(...)
                          synchronized (tsmdResults) {
                              tsmdResults.put(tsmd.getName(), e)
                          }
                      }
                  }, executor)
                  tsmdFutures.add(future)
              CompletableFuture.allOf(tsmdFutures.toArray(...)).join()
              // build eval summary from tsmdResults (reuse existing hasError + outputMapper logic)
              buffer.add(buildItem(...))
      flushIfNeeded(buffer, context)
      cursor = page.nextCursor()
  } while (cursor != null)
  flushRemaining(buffer, context)
  ```

  Done: `execute()` has one executor, sequential result iteration, parallel TSMD dispatch. No nested `tsmdExecutor`. No `CompletableFuture.supplyAsync` per result.

- [x] 2.2 Decide semaphore placement: `MetricEvaluationWorker.evaluate()` already does `semaphore.acquire()`/`release()` internally. So the executor should NOT acquire the semaphore before submitting — just pass it through to the worker as today. Remove the `semaphore.acquire()` from the executor-side pseudocode above. The worker handles concurrency control.

  Done: Semaphore is only acquired/released inside `MetricEvaluationWorker.evaluate()`. Executor just submits tasks.

- [x] 2.3 Remove methods that are no longer needed: `waitForFutures()` (was used for per-page future timeout). The `evaluateAndBuild()` method should be either inlined into the loop body or refactored to accept the shared executor instead of creating its own `tsmdExecutor`.

  Done: No `waitForFutures` method. No nested `Executors.newVirtualThreadPerTaskExecutor()` inside any method.

## 3. MetricEvaluationWorker OpenTelemetry Tracing

- [x] 3.1 Inject `OpenTelemetry` into `MetricEvaluationWorker`. In `evaluate()`, create a span `metric.tsmd.evaluate` before acquiring the semaphore, with attributes: `tsmd.name` (tsmd.getName()), `tsmd.provider.id` (tsmd.getDeclarationProviderId()), `eval.run.id` (context.getTestSuiteRunId().toString()), `result.id` (result.getId().toString()). Wrap the `invokeWithRetries` call inside `try (Scope scope = span.makeCurrent())`. On exception: `span.setStatus(StatusCode.ERROR, e.getMessage())` and `span.recordException(e)`. Always `span.end()` in finally.

  Reference pattern: `EvaluationWorker.execute()` lines 64-74 — same tracer name `"com.epam.aidial.evaluation"`, same span lifecycle (create → makeCurrent → setStatus on error → end in finally).

  Done: `MetricEvaluationWorker` has `OpenTelemetry` field; `evaluate()` creates and closes a span. Span attributes include tsmd name, provider ID, run ID, and result ID. Error paths record the exception on the span.

## 4. Verification

- [x] 4.1 Run `./gradlew checkstyleMain checkstyleTest` — verify no violations.
- [x] 4.2 Run `./gradlew test` — verify all existing tests pass.
