## 1. Dependencies & Build

- [x] 1.1 Add Bucket4j core dependency to `build.gradle`

## 2. Database Migration

- [x] 2.1 Create `V1.4__DropTimingAddRetryColumns.sql` in `db/migration/analytics/POSTGRES/` — DROP `time_to_first_token_ms`, DROP `time_to_last_token_ms`, ADD `retry_count` (INTEGER NOT NULL DEFAULT 0), ADD `log_details` (JSONB nullable)

## 3. HTTP Client Fix (505 Bug)

- [x] 3.1 Pin `HttpClient.Version.HTTP_1_1` in `DialCoreDeploymentInvokerConfiguration` when building the `JdkClientHttpRequestFactory`

## 4. Remove Unused Timeout Parameter

- [x] 4.1 Remove `Duration timeout` parameter from `DialCoreDeploymentInvoker.invokeWithStreaming()` method signature and all callers

## 5. Fix interruptRun Race Condition

- [x] 5.1 Add synchronous `registerCancellationSignal(UUID runId)` method to `TestSuiteEvaluationJob` that creates `AtomicBoolean` and puts it in `activeCancellationSignals` map; update `executeRunAsync()` to read the pre-registered signal from the map instead of creating it (current code uses `@Async` annotation — entire method body runs in async thread, so signal must be registered by the caller before `@Async` dispatch)
- [x] 5.2 Update the caller (`TestSuiteRunService`) to call `registerCancellationSignal(runId)` before `executeRunAsync(runId, token)`; wrap in try-catch so signal is cleaned up if `executeRunAsync` dispatch fails (e.g., executor rejection)
- [x] 5.3 Ensure signal cleanup happens in `finally` block of `executeRunAsync()`

## 6. Replace Utility Methods with ObjectUtils

- [x] 6.1 Replace `resolveInt`, `resolveLong`, `resolveDouble` static methods in `TestSuiteEvaluationJob` with `ObjectUtils.defaultIfNull` calls; remove the static methods

## 7. TTFT/TTLT Removal — Data Layer

- [x] 7.1 Remove `timeToFirstTokenMs` and `timeToLastTokenMs` from `TestCaseRunResult` model
- [x] 7.2 Remove TTFT/TTLT column reads from `TestCaseRunResultRowMapper`
- [x] 7.3 Remove TTFT/TTLT from INSERT SQL and batch parameter mapping in `PostgresTestCaseRunResultRepository`
- [x] 7.4 Remove TTFT/TTLT filter entries from `FilterWhitelists.ANALYTICS_RESULTS`

## 8. TTFT/TTLT Removal — Service & DTO Layer

- [x] 8.1 Remove `timeToFirstTokenMs` and `timeToLastTokenMs` from `ExecutionInfoResponseDto` and `ExecutionInfoRequestDto`
- [x] 8.2 Update `TestCaseRunResultMapper` (MapStruct) to remove TTFT/TTLT mappings
- [x] 8.3 Remove TTFT/TTLT capture logic from `StreamingResponseAccumulator` (fields, timing capture at content delta, getters)
- [x] 8.4 Remove TTFT/TTLT population from `EvaluationWorker.buildResult()` (both streaming and non-streaming paths)

## 9. Add Retry Tracking — Data Layer

- [x] 9.1 Add `retryCount` (Integer) and `logDetails` (String) fields to `TestCaseRunResult` model
- [x] 9.2 Add `retry_count` and `log_details` column reads to `TestCaseRunResultRowMapper`
- [x] 9.3 Add `retry_count` and `log_details` to INSERT SQL and batch parameter mapping in `PostgresTestCaseRunResultRepository`
- [x] 9.4 Add `retryCount` (LONG) filter entry to `FilterWhitelists.ANALYTICS_RESULTS` (no `FilterFieldType.INTEGER` exists; LONG works for DB INTEGER columns)

## 10. Add Retry Tracking — Service & DTO Layer

- [x] 10.1 Add `retryCount` (Integer) and `logDetails` (Object) fields to `ExecutionInfoResponseDto` and `ExecutionInfoRequestDto` (batch write). `logDetails` uses `Object` type in DTOs (follows existing JSONB pattern like `testCaseData`/`extractedColumns`); model stores as `String` (raw JSON)
- [x] 10.2 Update `TestCaseRunResultMapper` to map retry fields
- [x] 10.3 Update `EvaluationWorker.invokeWithRetries()` to track attempt count and build logDetails structure (only when retryCount > 0)
- [x] 10.4 Update `EvaluationWorker.buildResult()` to accept and set `retryCount` and `logDetails`

## 11. Store Request Body in Results

- [x] 11.1 Change `EvaluationWorker.buildResult()` from `.requestBody(null)` to `.requestBody(serializeBody(resolvedRequest.getBody()))` — pass the resolved request body through

## 12. Bucket4j Rate Limiting

- [x] 12.1 Replace `Thread.sleep(1000/RPS)` in `InProcessEvaluationExecutor` with Bucket4j `Bucket` creation (`Bandwidth.builder().capacity(tokens).refillGreedy(tokens, Duration.ofSeconds(1)).build()` — Bucket4j 8.x API)
- [x] 12.2 Workers call `bucket.asBlocking().consume(1)` before each HTTP call (including retries). Pass bucket (or null if no rate limit) through `EvaluationContext`.

## 13. Spec Cleanup

- [x] 13.1 Delete `openspec/specs/mock-request-body-builder/` directory
- [x] 13.2 Update `openspec/specs/README.md` to remove mock-request-body-builder entry (N/A — no entry existed)

## 14. OpenAPI & Documentation Updates

- [x] 14.1 Update OpenAPI example JSON files under `openapi/examples/` for analytics results endpoints to reflect removed TTFT/TTLT and added retry fields
- [x] 14.2 Update `@Schema` annotations on modified DTOs (`ExecutionInfoResponseDto`, `ExecutionInfoRequestDto`)
- [x] 14.3 Update `docs/database-schema.md` to reflect column changes (DROP timing, ADD retry)

## 15. Tests

- [x] 15.1 Remove TTFT/TTLT assertions from analytics results functional tests
- [x] 15.2 Add functional tests for retry tracking fields in batch write and read APIs
- [x] 15.3 Add/update functional tests for requestBody being stored in results
- [x] 15.4 Update unit tests for `StreamingResponseAccumulator` (remove TTFT/TTLT assertions)
- [x] 15.5 Add unit test for Bucket4j rate limiting in `InProcessEvaluationExecutor`
- [x] 15.6 Update unit tests for `EvaluationWorker` (retryCount, logDetails, requestBody population)
- [x] 15.7 Verify build passes: `./gradlew clean build` (compileJava, compileTestJava, checkstyleMain, checkstyleTest, test)
