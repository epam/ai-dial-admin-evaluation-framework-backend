## 1. Data access

- [x] 1.1 Add model `RunPassCounts(runId, passedTrue, passedFalse, unscored, failed)` in the analytics data model package (`data/db/analytics/model`). Done: record compiles, documented per field.
- [x] 1.2 Add `countByRunAndComputation(Map<UUID, UUID> runToComputation)` to `TestCaseEvalScoreRepository` and implement it in `PostgresTestCaseEvalScoreRepository` with `count(*) FILTER (WHERE …)` aggregates over `(test_suite_run_id, computation_id)` row values, grouped by run, ANDed with `RunDeletionFilter.notDeleted`. Empty input returns an empty map. Done: Checkstyle clean.
- [x] 1.3 Add a run-selection method to the analytics layer (on `EvalSummaryRepository` + `PostgresEvalSummaryRepository`, or a dedicated read repository) returning `(suiteId, runId, rank)` for up to two runs per suite: group `test_case_eval_summaries` by `(test_suite_id, test_suite_run_id)` with `max(created_at_ms)`, require existence of a `test_case_eval_scores` row, apply `RunDeletionFilter.notDeleted`, rank with `row_number() OVER (PARTITION BY test_suite_id ORDER BY max_created_at DESC, test_suite_run_id DESC)`, keep rank ≤ 2. Done: unit/functional coverage in group 5.
- [x] 1.4 Add `findThresholdsByRunIds(Collection<UUID>)` to `TestSuiteRunRepository` + Postgres impl, projecting only `id` and `suite_snapshot->>'overallScoreThreshold'` (no heavy columns), returning `Map<UUID, Double>` (absent when no snapshot or no threshold). Expose it through a new `TestSuiteRunService` method (`@Transactional("metaTransactionManager", readOnly = true)`). Done: Checkstyle clean.

## 2. Service logic

- [x] 2.1 Add `PassRateCalculator` (`@Component`, `@LogExecution`) in `service.domain.analytics`: `rate(RunPassCounts, boolean hasThreshold)` (threshold: passedTrue / total; no threshold: (passedTrue + passedFalse + unscored) / total; null when total is 0) and `mean(Collection<Double>)` (null when empty). Done: pure, no I/O.
- [x] 2.2 Add request/response DTOs in `service.domain.dto.analytics`: `SuitePassRateRequestDto { List<UUID> suiteIds }` and `SuitePassRateSummaryDto { Double passRate, Double previousPassRate, int suiteCount, int previousSuiteCount }` with `@Schema` examples. Done: DTOs compile.
- [x] 2.3 Add constant `MAX_BATCH_SUITE_IDS = 100` to `ValidationConstants`. Done: single definition, no duplicates.
- [x] 2.4 Add `SuitePassRateService` (`@Service`, `@LogExecution`, explicit read-only `TransactionTemplate` on `analyticsTransactionManager`): validate and dedupe `suiteIds` (inline `ValidationException` for null/empty/over cap); step 1 in the analytics transaction select runs (each with its latest computation) and counts; step 2 read thresholds via `TestSuiteRunService` outside that transaction; step 3 compute per-run rates with `PassRateCalculator`, group by suite (rank 1 = latest, rank 2 = previous), average, build the DTO. Done: no meta query inside the analytics transaction.

## 3. Web layer

- [x] 3.1 Add `SuitePassRateController` in `web.controller` (`@RestController @LogExecution @Validated`, `@Tag`, `POST /api/v1/analytics/test-suites/pass-rate`, `@Operation`/`@ApiResponse` including 400) delegating to the service. Done: endpoint registered, `LayeredArchitectureTest` passes.
- [x] 3.2 Add OpenAPI examples under `src/main/resources/openapi/examples/`: `api-v1-analytics-test-suites-pass-rate-POST-request-minimal.json`, `…-request-full.json`, `…-POST-response-200-minimal.json`, `…-response-200-full.json`. Done: files match the registered path key.

## 4. Unit tests

- [x] 4.1 `PassRateCalculatorTest`: threshold set with mixed outcomes (expects 0.25), no threshold (2/3), FAILED rows, total 0, mean of values and of an empty collection. Done: `./gradlew :test --tests "*PassRateCalculatorTest"` passes.
- [x] 4.2 `SuitePassRateServiceTest` (mocked repositories and services): two suites with one previous run, no eligible runs (null rates, zero counts), duplicates and unknown ids, suite without threshold, validation (null, empty, over the cap). Done: `./gradlew :test --tests "*SuitePassRateServiceTest"` passes.
- [x] 4.3 `SuitePassRateControllerTest` (web slice): 200 with body shape, 400 for empty and over-cap lists. Done: test passes.

## 5. Functional tests

- [x] 5.1 Repository functional tests (next to `PostgresTestCaseEvalScoreRepositoryFunctionalTests` and the eval-summary repository tests): counts per run and computation for passed true/false, unscored, FAILED; deleted run excluded; run selection ranks two runs per suite, skips runs without score rows, breaks ties by run id. Done: nested `PostgresFunctionalTests` class passes.
- [x] 5.2 Endpoint functional test (`PostgresFunctionalTests` nested class, `MetaTestDataHelper` / `AnalyticsTestDataHelper`; extract a shared `scoreRow` helper instead of copying): two suites with several runs and a re-computation, a suite with one run, a threshold run with unscored rows, a no-threshold run, a deleted run; assert exact `passRate`, `previousPassRate` and counts. Done: test passes and the context boots.
- [x] 5.3 Test that the request and response examples appear for `POST /api/v1/analytics/test-suites/pass-rate` in `/v3/api-docs`. Done: test passes.

## 6. Docs, specs and verification

- [x] 6.1 Sync the delta spec into `openspec/specs/suite-pass-rate-summary/spec.md` (sync or archive step) and add the entry to `openspec/specs/README.md`. Done: `openspec validate` passes for the new spec and the index lists it.
- [x] 6.2 Check `docs/key-packages.md` and `AGENTS.md` per their maintenance guidelines; update only if a new package or pattern appears (expected no change). Done: decision recorded.
- [x] 6.3 Run `./gradlew spotlessApply checkstyleMain checkstyleTest`, the new unit and functional tests, `LayeredArchitectureTest` and `LoggingConventionTest`. Done: all green.
- [x] 6.4 After restarting the app on :8096, call the endpoint with suites `64ab7f1f-4f93-4aa5-a035-01ded741a6aa` and `b5167912-1414-4056-815b-491e20111c64`. Done: `passRate` 0.75 with `suiteCount` 2 and `previousPassRate` 0.0 with `previousSuiteCount` 1.
