## Context

The eval-runner-job change shipped the real evaluation execution engine, replacing the mock job. Post-deployment testing revealed several bugs (HTTP 505 from DIAL Core, race condition in cancellation, unused timeout parameter) and identified code quality issues (TTFT/TTLT complexity, naive rate limiting, utility method duplication, missing request body storage, no retry tracking). This change addresses all of them.

The codebase currently has:
- `JdkClientHttpRequestFactory` using default HTTP/2 ALPN negotiation, which fails against DIAL Core (HTTP/1.1 only)
- `invokeWithStreaming(Duration timeout)` where the timeout is accepted but never applied
- `interruptRun()` that can lose cancellation signals due to async thread registration timing
- ~25 files touching TTFT/TTLT (DB columns, model, DTOs, mappers, filters, accumulator logic, tests)
- `Thread.sleep(1000/RPS)` as rate limiting placeholder
- Custom `resolveInt/Long/Double` methods duplicating `ObjectUtils.defaultIfNull`
- `requestBody(null)` hardcoded in `EvaluationWorker.buildResult()`
- No retry count or log details tracking in results

## Goals / Non-Goals

**Goals:**
- Fix all critical bugs (HTTP 505, race condition, unused timeout)
- Remove TTFT/TTLT complexity globally (DB migration, model, DTOs, filters, accumulator, tests)
- Add retry tracking (retryCount + logDetails) to eval results
- Store actual requestBody in eval results
- Replace naive rate limiting with Bucket4j
- Replace custom utility methods with Apache Commons ObjectUtils
- Clean up stale mock-request-body-builder spec

**Non-Goals:**
- Changing the retry policy logic itself (only adding tracking of attempts)
- Migrating existing analytics data (TTFT/TTLT columns are DROPped; old data loses those values)
- Adding per-request timeout to JDK HttpClient (remove the parameter instead — `RestClient` with `JdkClientHttpRequestFactory` doesn't support per-request timeout natively; the overall request timeout is handled at the worker level via `CompletableFuture.get(timeout)`)
- Implementing external rate limiting service (Bucket4j is in-process)

## Decisions

### D1: HTTP/1.1 version pinning
Pin `HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)` in `DialCoreDeploymentInvokerConfiguration`.

**Why not keep HTTP/2 with fallback?** DIAL Core explicitly does not support HTTP/2. The JDK client's ALPN negotiation causes 505 when the server doesn't understand the protocol. Pinning is deterministic and matches the target.

### D2: Remove timeout parameter from invokeWithStreaming
Remove the `Duration timeout` parameter from `invokeWithStreaming()` entirely rather than trying to apply it.

**Why remove instead of apply?** `RestClient` with `JdkClientHttpRequestFactory` does not expose per-request timeout configuration. The worker already enforces timeout via `CompletableFuture.get(remainingTimeout)` wrapping the streaming accumulation. Adding a second timeout mechanism would be redundant and confusing. The method signature becomes consistent with the non-streaming `invoke()` method.

**Alternative considered:** Set `HttpRequest.timeout()` on the underlying JDK request — rejected because `RestClient` abstracts away the raw request builder.

### D3: Fix interruptRun race condition
Register the cancellation signal **before** the method enters the async thread. The current `executeRunAsync()` is annotated with `@Async("testSuiteRunExecutor")`, so its **entire body** runs in the executor thread — including the signal registration on line 48-49. This means `interruptRun(runId)` can be called before the async thread starts, finding no signal in the map.

Current flow (broken):
```
caller thread:                    executor thread (@Async):
1. service calls                  (not started yet)
   executeRunAsync(runId)
   → Spring proxy submits
     to testSuiteRunExecutor
   → returns immediately
                                  2. signal = new AtomicBoolean(false)
                                  3. map.put(runId, signal)  // <-- TOO LATE
                                  4. ... execute ...

If interruptRun(runId) is called between step 1 and 3:
  → map.get(runId) returns null → cancellation LOST
```

Fixed flow — split into sync registration + async execution:
```
caller thread:
1. service calls registerAndExecute(runId, token)
2. signal = new AtomicBoolean(false)
3. map.put(runId, signal)         // <-- registered in CALLER thread
4. calls executeRunAsyncInternal(runId, token, signal)
   → @Async dispatches to executor

executor thread:
5. uses signal from parameter     // <-- always available
6. ... execute ...
7. finally { map.remove(runId) }
```

The public method `registerAndExecute(runId, token)` is synchronous and registers the signal. It then calls `executeRunAsyncInternal(runId, token, signal)` which is `@Async`. Note: for `@Async` to work via Spring proxy, the async method must be called on a different bean OR via self-injection — calling a `@Async` method on `this` bypasses the proxy. The cleanest approach is to have the caller (`TestSuiteRunService`) register the signal by calling a sync `registerCancellationSignal(runId)` method, then call the existing `@Async executeRunAsync(runId, token)` which receives the pre-registered signal from the map. **Edge case:** If an exception occurs between `registerCancellationSignal()` and `executeRunAsync()` (or if the executor rejects the task), the caller SHALL clean up the signal in a catch/finally block to prevent map leaks.

### D4: TTFT/TTLT removal strategy
Full removal across all layers:
1. **DB**: New Flyway migration `V1.4__RemoveStreamingTimingColumns.sql` — `ALTER TABLE test_case_run_results DROP COLUMN time_to_first_token_ms, DROP COLUMN time_to_last_token_ms`
2. **Model**: Remove `timeToFirstTokenMs`/`timeToLastTokenMs` from `TestCaseRunResult`
3. **DTOs**: Remove from `ExecutionInfoResponseDto`, `ExecutionInfoRequestDto` (batch write)
4. **RowMapper**: Remove column reads
5. **Repository**: Remove from INSERT SQL and batch parameter mapping
6. **Filter whitelist**: Remove two entries from `FilterWhitelists.ANALYTICS_RESULTS`
7. **Accumulator**: Remove TTFT/TTLT tracking from `StreamingResponseAccumulator`
8. **Worker**: Remove TTFT/TTLT population in `buildResult()`
9. **Tests**: Remove all TTFT/TTLT assertions and test scenarios
10. **Specs**: Remove TTFT/TTLT requirements and scenarios from main specs

**Breaking change**: API consumers lose `executionInfo.timeToFirstTokenMs` and `executionInfo.timeToLastTokenMs` from read responses and the filter whitelist. This is accepted.

### D5: Bucket4j for rate limiting
Replace `Thread.sleep(1000/RPS)` with Bucket4j's `Bucket` and `Bandwidth.builder().capacity(tokens).refillGreedy(tokens, Duration.ofSeconds(1)).build()` (Bucket4j 8.x API; `Bandwidth.simple()` was removed in 8.x).

**Integration point:** Create the bucket in `InProcessEvaluationExecutor` per run (each run gets its own bucket configured from `rateLimitRps`). Workers call `bucket.asBlocking().consume(1)` before each HTTP call (including retries).

**Dependency:** Add `bucket4j-core` to `build.gradle` (no Spring Boot starter needed — using core API directly).

**Why Bucket4j over alternatives?**
- Guava `RateLimiter`: Not in project dependencies (only Commons + Guava collections used); Bucket4j is purpose-built for rate limiting with better API
- Resilience4j: Heavier, designed for circuit-breaking; overkill for simple RPS limiting
- Custom token bucket: We prefer established libraries per project conventions

### D6: Retry tracking fields
Add `retryCount` (Integer, NOT NULL DEFAULT 0) and `logDetails` (JSONB, nullable) to `test_case_run_results`.

- `retryCount`: Always populated (0 for no retries, N for N retries before success/final failure)
- `logDetails`: Populated only when `retryCount > 0`. Contains structured log of retry attempts: `{"retryAttempts": [{"attemptIndex": 1, "statusCode": 429, "errorType": "HTTP_ERROR", "durationMs": 1234}, ...]}`. Null when retryCount is 0 (no noise for the common case). **DTO type:** `Object` in response DTO (deserialized from JSONB via Jackson — follows existing pattern for `testCaseData`/`extractedColumns`), `Object` in request DTO (any valid JSON structure accepted). Model type: `String` (raw JSON, serialized/deserialized via `ObjectMapper` in mapper layer).

**Migration:** `V1.4__DropTimingAddRetryColumns.sql` — combine TTFT/TTLT DROP and retry column ADD in a single migration for cleaner versioning.

### D7: Store actual requestBody
Change `EvaluationWorker.buildResult()` from `.requestBody(null)` to `.requestBody(serializeBody(resolvedRequest.getBody()))`. The `serializeBody()` method already exists in the worker. The resolved request body is available from `ResolvedRequestService.resolveRequest()`.

### D8: ObjectUtils replacement
Replace `TestSuiteEvaluationJob.resolveInt/Long/Double` with `ObjectUtils.defaultIfNull(value, default)`. Apache Commons Lang 3.20.0 is already a project dependency.

## Risks / Trade-offs

- **[TTFT/TTLT data loss]** Existing analytics rows lose timing data permanently. → Acceptable per user decision; data has low practical value.
- **[Breaking API change]** Consumers relying on TTFT/TTLT fields will break. → No known external consumers yet; acceptable for pre-GA.
- **[Bucket4j dependency]** New library added. → Well-maintained (7k+ GitHub stars), minimal footprint (core module only, no Spring integration needed).
- **[Combined migration]** Single migration handles both DROP and ADD. → Simpler version history; if rollback needed, must undo both. Acceptable since changes are logically related to the same cleanup effort.
- **[requestBody storage increases DB size]** Storing request bodies will increase analytics DB storage. → Already designed for this (column exists, nullable JSONB); the v1 `null` was a deliberate deferral, not a constraint.
