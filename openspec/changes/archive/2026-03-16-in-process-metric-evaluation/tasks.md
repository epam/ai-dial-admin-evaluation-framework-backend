## 1. Configuration & Properties

- [x] 1.1 Create `MetricEvaluationProperties` in `configuration.properties` with nested `Retry` class. Fields: `defaultConcurrencyPerProvider`, `batchSize`, `cancellationGracePeriodMs`, `retry.maxRetries`, `retry.retryDelayMs`, `retry.retryBackoffMultiplier`, `retry.maxRetryDelayMs`. Use `@Validated`, `@NotNull`, `@Min` — no field initializers. (done: class compiles, checkstyle passes)
- [x] 1.2 Add `metric-evaluation.*` defaults to `application.yml` (done: defaults defined for all properties per spec)
- [x] 1.3 Update `docs/configuration.md` with new `metric-evaluation.*` properties (done: docs reflect all new properties)

## 2. Client Layer — MetricProviderClient /evaluate

- [x] 2.1 Create `EvaluationRequestDto` in `client.metricprovider.dto` with fields: `metricName` (String), `config` (Map<String, Object>), `input` (Map<String, Object>). Use `@JsonProperty("metric_name")` for snake_case serialization. (done: DTO compiles)
- [x] 2.2 Create `EvaluationResponseDto` in `client.metricprovider.dto` with fields: `metricName` (String, `@JsonProperty("metric_name")`), `output` (Map<String, MetricOutputDto>). Create `MetricOutputDto` with Jackson `@JsonTypeInfo` discriminated by `type` field, subtypes: `MetricOutputFieldDto` (value: BigDecimal, details: Map<String, Object>) and `MetricErrorDto` (message: String). (done: DTOs compile, deserialization handles both value and error types)
- [x] 2.3 Add `evaluate(String providerId, EvaluationRequestDto request)` method to `MetricProviderClient`. Use existing `MetricProviderRestClientFactory.getRestClient(providerId)`, POST to `/evaluate`, return `EvaluationResponseDto`. (done: method compiles, follows existing `getMetrics` pattern)

## 3. Data Layer — Repository Extension

- [x] 3.1 Add `findAllAggregatedByTestSuiteId(UUID testSuiteId)` to `TestSuiteMetricDefinitionRepository` interface returning `List<AggregatedMetricDefinition>`. (done: interface updated)
- [x] 3.2 Implement `findAllAggregatedByTestSuiteId` in `PostgresTestSuiteMetricDefinitionRepository`. Reuse the 3-table JOIN SQL from `findAggregatedByIdAndTestSuiteId` without `WHERE md.id = :id`. Reuse `AggregatedMetricDefinitionRowMapper`. (done: query returns correct results, uses existing row mapper)

## 4. Service Layer — Binding Resolution

- [x] 4.1 Create `BindingResolver` as `@Component` in `service.domain.job`. Method: `resolveBindings(List<MetricParameterBindingDto> bindings, Map<String, Object> testCaseData, Map<String, Object> extractedColumns)` → `Map<String, Object>`. Handles TestCase, Response, Constant sources. Missing columns resolve to null. Inject `ObjectMapper` for JSON parsing of testCaseData/extractedColumns strings. (done: compiles, @LogExecution annotation present)
- [x] 4.2 Unit tests for `BindingResolver`: all three source types, missing column → null, empty bindings → empty map, multiple bindings merged correctly. (done: all test cases pass)

## 5. Service Layer — Output Mapping

- [x] 5.1 Create `MetricOutputMapper` as `@Component` in `service.domain.job`. Methods: `buildMetricValues(Map<String, Object> tsmdResults)` → `ObjectNode`, `buildMetricInfos(Map<String, Object> tsmdResults)` → `ObjectNode` (nullable). Input map values are either `EvaluationResponseDto` (success) or `Exception` (transport failure). Returns `ObjectNode` (not String) so the batch write client can pass it directly to `EvalSummaryBatchWriteItemDto.metricValues` (JsonNode) without String→JsonNode roundtrip. Uses Jackson `ObjectNode` for JSON construction. Preserves explicit null values via `putNull()` for error entries. Omits empty details. (done: compiles, @LogExecution annotation present)
- [x] 5.2 Unit tests for `MetricOutputMapper`: value without details, value with details, error output (null in values + error in infos), transport failure (exception → error entries), multiple TSMDs merged, all-empty details → null metricInfos. (done: all test cases pass)

## 6. Service Layer — Metric Evaluation Worker

- [x] 6.1 Create `MetricEvaluationContext` in `service.domain.job`. Immutable carrier (Lombok @Builder): `computationId` (UUID), `computedAtMs` (Long), `testSuiteRunId` (UUID), `testSuiteId` (UUID), `createdAtMs` (Long), `aggregatedTsmds` (List<AggregatedMetricDefinition>), `tsmdsByProvider` (Map<String, List<AggregatedMetricDefinition>>), `providerSemaphores` (Map<String, Semaphore>), `cancellationSignal` (AtomicBoolean), `retryConfig` (retry properties), `batchSize` (int), `cancellationGracePeriodMs` (long). (done: compiles)
- [x] 6.2 Create `MetricEvaluationWorker` as `@Component` in `service.domain.job`. Method: `evaluate(AggregatedMetricDefinition tsmd, TestCaseRunResult result, Semaphore providerSemaphore, MetricEvaluationContext context)` → `EvaluationResponseDto`. Acquires semaphore, resolves bindings via `BindingResolver`, builds `EvaluationRequestDto`, calls `MetricProviderClient.evaluate()`, releases semaphore in finally. Implements retry with exponential backoff (respects cancellation signal during backoff sleep). Throws exception on transport failure (after retries exhausted) — executor catches per-future. (done: compiles, @LogExecution annotation present)
- [x] 6.3 Unit tests for `MetricEvaluationWorker`: successful evaluation, retry on 5xx, non-retryable 4xx throws immediately, all retries exhausted throws, cancellation during backoff throws. (done: all test cases pass)

## 7. Service Layer — Batch Write Clients

- [x] 7.1 Create `EvalSummaryBatchWriteClient` as `@Component` in `service.domain.job`. Converts internal EvalSummary models to `EvalSummaryBatchWriteRequestDto` (envelope + items) and delegates to `EvalSummaryService.batchCreate()`. Chunks items to respect the existing `analytics.eval-summaries.batch.max-items` limit. Inject `EvalSummaryService` and `EvalSummaryProperties` (for max-items). (done: compiles, @LogExecution annotation present)
- [x] 7.2 Create `RunMetricSnapshotBatchWriteClient` as `@Component` in `service.domain.job`. Converts internal RunMetricSnapshot models to `RunMetricSnapshotBatchWriteRequestDto` (envelope + items) and delegates to `RunMetricSnapshotService.batchCreate()`. Inject `RunMetricSnapshotService`. (done: compiles, @LogExecution annotation present)

## 8. Service Layer — Metric Evaluation Executor

- [x] 8.1 Create `MetricEvaluationExecutor` interface in `service.domain.job` (analogous to `EvaluationExecutor` for deployment evaluation). Create `InProcessMetricEvaluationExecutor` as `@Component` implementing the interface. Orchestrates: captures RunMetricSnapshots before evaluation, iterates TestCaseRunResults (cursor-paginated by runId). Within each page, dispatches TSMD evaluations for ALL SUCCESS results concurrently (cross-result parallelism): each result gets a composed CompletableFuture that dispatches per-TSMD futures, collects results (including per-TSMD exceptions via handle()), merges via MetricOutputMapper, and builds EvalSummary DTO. Non-SUCCESS results get propagated EvalSummary directly. Single `allOf().get(gracePeriod)` per page. Buffers results and flushes via `EvalSummaryBatchWriteClient` at `metric-evaluation.batch-size` threshold. Handles cancellation signal and empty TSMDs (returns early). Final flush on completion. (done: compiles, @LogExecution annotation present)

## 9. Job Layer — Chain Metric Evaluation in TestSuiteEvaluationJob

- [x] 9.1 Modify `TestSuiteEvaluationJob.executeRunAsync()` to chain metric evaluation after deployment evaluation following a consistent pattern: `buildMetricEvaluationContext()` → `metricEvaluationExecutor.execute(context)` (mirroring Phase 1's `buildContext()` → `evaluationExecutor.execute(context)`). Context building loads aggregated TSMDs, generates computationId + computedAtMs (via injected Clock), builds provider semaphores. RunMetricSnapshot writing is handled by the executor, not the job. (done: both phases chained with consistent pattern, cancelled runs skip Phase 2)
- [x] 9.2 Add `findAllAggregatedByTestSuiteId(UUID testSuiteId)` method to `TestSuiteMetricDefinitionService` that delegates to the repository. Uses `@Transactional(value = "metaTransactionManager", readOnly = true)`. (done: service method compiles)

## 10. Testing

- [x] 10.1 Create `MetricEvaluationTestHelper` in test helpers package. Encapsulates mock setup for `DialCoreDeploymentInvoker` (configurable per-test-case: SUCCESS with response body, or TIMEOUT/FAILED) and `MetricProviderClient.evaluate()` (returns configured `EvaluationResponseDto` per metric name). Keeps test class readable. (done: helper compiles, provides fluent setup API)
- [x] 10.2 E2e functional test in `TestSuiteRunFunctionalTests`: single test method covering full two-phase lifecycle. Setup: suite with 2-3 test cases (one configured to fail at deployment), TSMDs with bindings, mocked deployment + metric provider. Assertions: run COMPLETED, 1:1 TestCaseRunResult ↔ EvalSummary, SUCCESS results have populated metricValues/metricInfos, non-SUCCESS result has propagated status + empty metrics, RunMetricSnapshots exist for each TSMD with correct computationId and snapshot fields. (done: test passes)
- [x] 10.3 Functional test: no TSMDs configured — verify run completes without EvalSummary records. (done: test passes)
- [x] 10.4 Add assertion helpers to `AnalyticsTestDataHelper` if needed (e.g., `findEvalSummariesByRunId`, `findRunMetricSnapshotsByRunId`) for test assertions. (done: helpers available)

## 11. Cross-Cutting

- [x] 11.1 Run `./gradlew checkstyleMain checkstyleTest` — fix any violations (done: clean checkstyle)
- [x] 11.2 Run `./gradlew test` — all tests pass (done: green build)
- [x] 11.3 Update `openspec/specs/README.md` per Spec Index Maintenance Policy — add `metric-evaluation` spec entry under Analytics section (done: index reflects new spec)
