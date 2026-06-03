## 1. Configuration

- [x] 1.1 Add `resultFailureProbability` field (`@NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double`) to `TestSuiteRunProperties.MockJob`
- [x] 1.2 Add `test-suite-run.mock-job.result-failure-probability: 0.10` default to `application.yml`
- [x] 1.3 Verify application starts and `TestSuiteRunProperties` binds without errors

## 2. MockRequestBodyBuilder

- [x] 2.1 Create `MockRequestBodyBuilder` in `.service.domain.job` — `@Component @LogExecution @RequiredArgsConstructor @Slf4j`; inject `ObjectMapper`
- [x] 2.2 Implement `build(TestSuite suite, TestCase testCase): String` — resolves effective template and bindings (case override > suite default); parses `template.body` as `JsonNode`; builds resolution map from bindings (`dataField` → look up in parsed `testCase.data`, `constantValue` → `TextNode`); walks the JSON tree recursively — for whole-placeholder text nodes (`${{var}}`), replaces the node with the resolved `JsonNode` (type-preserving: arrays/objects stay as-is); for mixed-text nodes, does scalar string substitution; serializes result to JSON string
- [x] 2.3 Implement fallback: return `testCase.getData()` as-is when template is null, `template.body` absent, or any exception occurs; log WARN in each fallback path
- [x] 2.4 Write unit tests for `MockRequestBodyBuilder`: happy-path with dataField binding, constantValue binding, default value used, no template fallback, parsing-failure fallback

## 3. MockResponseBodyBuilder

- [x] 3.1 Create `MockResponseBodyBuilder` in `.service.domain.job` — `@Component @LogExecution @RequiredArgsConstructor @Slf4j`; inject `ObjectMapper`
- [x] 3.2 Implement `buildResponseBody(ExecutionStatus status): String` returning status-specific JSON: SUCCESS → chat-completions envelope; ERROR → `{error:{code:MOCK_INTERNAL_ERROR,...}}`; FAILED → `{error:{code:MOCK_EVAL_FAILED,...}}`; TIMEOUT → `{error:{code:MOCK_TIMEOUT,...}}`
- [x] 3.3 Implement `resolveHttpStatus(ExecutionStatus status): int` — SUCCESS → 200, FAILED → 422, ERROR → 500, TIMEOUT → 504
- [x] 3.4 Write unit tests for `MockResponseBodyBuilder`: verify each status maps to the correct HTTP code and that response body is valid JSON for each status

## 4. MockResultsBatchWriter

- [x] 4.1 Create `MockResultsBatchWriter` in `.service.domain.job` — `@Component @LogExecution @RequiredArgsConstructor`; inject `TestCaseRunResultRepository` (no qualifier — there is only one bean of this type, already wired to the analytics datasource internally)
- [x] 4.2 Implement `save(List<TestCaseRunResult> batch): @Transactional("analyticsTransactionManager") void` — delegates to `resultRepository.saveAll(batch)`

## 5. TestCaseRepository — enabled+valid query

- [x] 5.1 Add `List<TestCase> findEnabledValidByTestSuiteId(UUID testSuiteId, int offset, int limit)` to `TestCaseRepository` interface
- [x] 5.2 Implement in `PostgresTestCaseRepository`: `SELECT <columns> FROM test_cases WHERE test_suite_id = :testSuiteId AND is_enabled = true AND is_valid = true ORDER BY created_at_ms ASC, id ASC LIMIT :limit OFFSET :offset`

## 6. MockResultsGenerator

- [x] 6.1 Create `MockResultsGenerator` in `.service.domain.job` — `@Component @LogExecution @RequiredArgsConstructor @Slf4j`; inject `TestSuiteRunRepository`, `TestSuiteRepository`, `TestCaseRepository`, `MockResultsBatchWriter`, `MockRequestBodyBuilder`, `MockResponseBodyBuilder`, `ObjectMapper`, `TestSuiteRunProperties`
- [x] 6.2 Implement `generateAndSave(UUID runId): void` — load run + suite from meta; parse `numberOfRuns` from `runConfig` JSON (default 1 on parse failure with WARN); paginate through enabled+valid test cases via `testCaseRepository.findEnabledValidByTestSuiteId(suiteId, offset, PAGE_SIZE)` at page size 100; for each page × runIndex build `TestCaseRunResult` list and call `mockResultsBatchWriter.save(pageBatch)`
- [x] 6.3 Implement `buildResult(TestCase tc, TestSuite suite, TestSuiteRun run, int runIndex): TestCaseRunResult` — populates all fields: `id` (new `UUID.randomUUID()`), `testSuiteRunId` (from run), `testSuiteId` (from suite), `testCaseId` (from tc), `testCaseName` (from tc), `runIndex`, `testCaseData` (`tc.getData()`), `requestBody` (via `MockRequestBodyBuilder`), `responseBody` (via `MockResponseBodyBuilder`), `responseStatusCode` (via `MockResponseBodyBuilder.resolveHttpStatus`), `executionStatus` (ERROR with `resultFailureProbability`, else SUCCESS), `execStartedAtMs`/`execCompletedAtMs` (current time ± small random offset), `execDurationMs` (`execCompletedAtMs - execStartedAtMs`), `traceId` (null — no tracing in mock), `createdAtMs` (`run.getCreatedAt()`)

## 7. TestSuiteEvaluationJob Integration

- [x] 7.1 Inject `MockResultsGenerator` into `TestSuiteEvaluationJob` (constructor injection)
- [x] 7.2 In the success path (before `repository.updateToCompleted`), call `mockResultsGenerator.generateAndSave(runId)` wrapped in try/catch — on exception: log WARN with runId and exception, then continue. After the try/catch block, refresh `now = System.currentTimeMillis()` so `completedAt` reflects actual completion time, then call `repository.updateToCompleted(runId, now, now)`

## 8. Tests

- [x] 8.1 Write unit test for `MockResultsGenerator.generateAndSave` with mocked dependencies: verify `MockResultsBatchWriter.save()` is called with correct number of results for a 2-test-case suite with `numberOfRuns=2`
- [x] 8.2 Write functional test: create test suite + 2 enabled+valid test cases + 1 disabled test case, trigger a run, wait for COMPLETED status, then assert analytics results count = 2 × numberOfRuns and disabled test case has no results
- [x] 8.3 Verify `checkstyleMain` and `checkstyleTest` pass (`./gradlew checkstyleMain checkstyleTest`)
- [x] 8.4 Verify full test suite passes (`./gradlew test`)
