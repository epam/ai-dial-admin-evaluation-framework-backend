## 1. Repository — add updateIsValid to TestSuiteRepository

- [x] 1.1 Add `void updateIsValid(UUID id, boolean isValid)` to `TestSuiteRepository` interface
- [x] 1.2 Implement `updateIsValid` in `PostgresTestSuiteRepository` using `UPDATE test_suites SET is_valid = :isValid WHERE id = :id`

## 2. Test helpers — create MetaTestDataHelper

- [x] 2.1 Create `MetaTestDataHelper` in `src/test/java/…/functional/helper/MetaTestDataHelper.java`
  - Plain class (no `@Component`), `@RequiredArgsConstructor`; instantiated as a `@Bean` in `PostgresFunctionalTestConfiguration`
  - Inject `TestSuiteRepository`, `TestSuiteRunRepository` via constructor (both are meta beans)
  - `createTestSuite(String name)` — builds and saves a `TestSuite` with the following required defaults; timestamps are managed by `TransactionTimestampContext` (the aspect fires for `@Transactional("metaTransactionManager")` helpers); returns `TestSuite`. Required model fields: `name = name`, `createdBy = "test-user"`, `testCaseSchema = "[]"` (NOT NULL JSONB — `PostgresTestSuiteRepository.create()` explicitly inserts this column; passing null causes a NOT NULL constraint violation), `inputBindings = "[]"` (same — NOT NULL JSONB), `validationWarnings = "[]"` (same — NOT NULL JSONB), `valid = true` (primitive `boolean` defaults to `false` in Java, which would create an invalid suite and potentially break analytics tests that call the batch-write endpoint)
  - `createTestSuiteRun(UUID suiteId)` — builds and saves a `TestSuiteRun` in **`COMPLETED`** status; used by analytics tests where only completed runs are valid batch-write targets; returns `TestSuiteRun`. Required model fields: `testSuiteId = suiteId`, `testRunName = "run-" + UUID.randomUUID()` (unique per call — using `suiteId` would collide when creating multiple runs for the same suite), `status = RunStatus.COMPLETED.name()`, `runConfig = "{\"numberOfRuns\":1}"` (NOT NULL JSONB — must not be null or the INSERT will fail), `numberOfTestCases = 0`; `createdAt`/`updatedAt` are set by `TransactionTimestampContext` inside `save()`
  - `createPendingRun(UUID suiteId, String testRunName)` — builds and saves a `TestSuiteRun` in **`PENDING`** status with the given name; used by concurrency-limit tests that need to fill PENDING run slots; returns `TestSuiteRun`. Required model fields: `testSuiteId = suiteId`, `testRunName = testRunName`, `status = RunStatus.PENDING.name()`, `runConfig = "{\"numberOfRuns\":1}"`, `numberOfTestCases = 0`
  - `createRunningRun(UUID suiteId, String testRunName)` — builds and saves a `TestSuiteRun` in **`RUNNING`** status with the given name; used by cancel and delete-non-terminal tests that need a run already in RUNNING state; returns `TestSuiteRun`. Required model fields: `testSuiteId = suiteId`, `testRunName = testRunName`, `status = RunStatus.RUNNING.name()`, `runConfig = "{\"numberOfRuns\":1}"`, `numberOfTestCases = 0`
  - `findRun(UUID id)` — delegates to `testSuiteRunRepository.findById(id)`; returns `Optional<TestSuiteRun>`; used for timestamp assertions without directly injecting the repository in tests
  - `deleteRun(UUID id)` — delegates to `testSuiteRunRepository.deleteById(id)`; used for targeted run cleanup in `finally` blocks
  - `forceSuiteInvalid(UUID suiteId)` — calls `testSuiteRepository.updateIsValid(suiteId, false)`
  - Wrap write methods in `@Transactional("metaTransactionManager")`

## 3. Test helpers — create AnalyticsTestDataHelper

- [x] 3.1 Create `AnalyticsTestDataHelper` in `src/test/java/…/functional/helper/AnalyticsTestDataHelper.java`
  - Plain class (no `@Component`), `@RequiredArgsConstructor`; instantiated as a `@Bean` in `PostgresFunctionalTestConfiguration`
  - Inject `NamedParameterJdbcTemplate` via constructor (the analytics template; the `@Bean` factory method parameter carries `@Qualifier("analyticsJdbcTemplate")`)
  - `cleanupResults()` — annotated `@Transactional("analyticsTransactionManager")`; executes `DELETE FROM test_case_run_results`
  - `countAll()` — returns `long` via `SELECT COUNT(*) FROM test_case_run_results`; replaces inline count assertions in idempotency and deduplication tests (`TestCaseRunResultRepository.count` requires filter params and cannot count unconditionally)
  - `findAnyResultId()` — returns `Optional<UUID>` via `SELECT id FROM test_case_run_results LIMIT 1`
  - `findAnyResultCreatedAt()` — returns `Optional<Long>` via `SELECT created_at_ms FROM test_case_run_results LIMIT 1`; used in `shouldBatchCreateResults` to verify result timestamps match the run's `created_at_ms`

## 4. Test configuration — register helpers

- [x] 4.1 Add `@Bean` factory methods for `MetaTestDataHelper` and `AnalyticsTestDataHelper` to `PostgresFunctionalTestConfiguration`
  - `MetaTestDataHelper` factory receives `TestSuiteRepository` and `TestSuiteRunRepository` as parameters
  - `AnalyticsTestDataHelper` factory receives `@Qualifier("analyticsJdbcTemplate") NamedParameterJdbcTemplate` as a parameter

## 5. Refactor — TestSuiteRunFunctionalTests

Note: `createTestSuite(String name)` in this file already uses the REST API — do **not** change it.
`insertRunDirectly(UUID, String, String)` is called with both `PENDING` and `RUNNING` status; replace each call with the corresponding helper method.

- [x] 5.1 Remove `@Qualifier("metaJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate` field injection
- [x] 5.2 Inject `MetaTestDataHelper metaTestDataHelper`
- [x] 5.3 Replace all `insertRunDirectly` calls with the corresponding helper:
  - `insertRunDirectly(suiteId, name, RunStatus.PENDING.name())` → `metaTestDataHelper.createPendingRun(suiteId, name).getId()`
  - `insertRunDirectly(suiteId, name, RunStatus.RUNNING.name())` → `metaTestDataHelper.createRunningRun(suiteId, name).getId()`
- [x] 5.4 Replace `UPDATE test_suites SET is_valid = false WHERE id = :id` with `metaTestDataHelper.forceSuiteInvalid(suite.getId())`
- [x] 5.5 Replace `jdbcTemplate.update("DELETE FROM test_suite_runs WHERE id = :id", …)` in `finally` blocks with `metaTestDataHelper.deleteRun(id)`
- [x] 5.6 Delete the now-unused `insertRunDirectly(UUID, String, String)` private method

## 6. Refactor — AnalyticsResultBatchWriteFunctionalTests

- [x] 6.1 Remove direct `metaJdbcTemplate` and `analyticsJdbcTemplate` field injections
- [x] 6.2 Inject `MetaTestDataHelper` and `AnalyticsTestDataHelper`
- [x] 6.3 Replace `createTestSuite()` helper method with `metaTestDataHelper.createTestSuite(name)`
- [x] 6.4 Replace `createTestSuiteRun(UUID)` helper method with `metaTestDataHelper.createTestSuiteRun(suiteId)`
- [x] 6.5 Replace `analyticsJdbcTemplate.update("DELETE FROM …")` in `@BeforeEach` with `analyticsTestDataHelper.cleanupResults()`
- [x] 6.6 Replace the two-query timestamp assertion in `shouldBatchCreateResults`:
  - Replace `SELECT created_at_ms FROM test_suite_runs WHERE id = :id` with `metaTestDataHelper.findRun(testSuiteRunId).orElseThrow().getCreatedAt()`
  - Replace `SELECT created_at_ms FROM test_case_run_results LIMIT 1` with `analyticsTestDataHelper.findAnyResultCreatedAt().orElseThrow()`
  - Assert the two values are equal
- [x] 6.7 Replace `SELECT COUNT(*) FROM test_case_run_results` in `shouldHandleIdempotentRetry` and `shouldSkipIntraBatchDuplicates` with `analyticsTestDataHelper.countAll()`
- [x] 6.8 Replace `SELECT id FROM test_case_run_results LIMIT 1` in `shouldWriteResultsWithOptionalFields` with `analyticsTestDataHelper.findAnyResultId().orElseThrow().toString()`

## 7. Refactor — AnalyticsResultCountFunctionalTests

- [x] 7.1 Remove direct jdbcTemplate field injections
- [x] 7.2 Inject `MetaTestDataHelper` and `AnalyticsTestDataHelper`
- [x] 7.3 Replace `createTestSuite()` / `createTestSuiteRun()` helper methods with `metaTestDataHelper` calls
- [x] 7.4 Replace `DELETE FROM test_case_run_results` in `@BeforeEach` with `analyticsTestDataHelper.cleanupResults()`

## 8. Refactor — AnalyticsResultListFunctionalTests

- [x] 8.1 Remove direct jdbcTemplate field injections
- [x] 8.2 Inject `MetaTestDataHelper` and `AnalyticsTestDataHelper`
- [x] 8.3 Replace `createTestSuite()` / `createTestSuiteRun()` helper methods with `metaTestDataHelper` calls
- [x] 8.4 Replace `DELETE FROM test_case_run_results` in `@BeforeEach` with `analyticsTestDataHelper.cleanupResults()`

## 9. Refactor — AnalyticsResultGetByIdFunctionalTests

- [x] 9.1 Remove direct jdbcTemplate field injections
- [x] 9.2 Inject `MetaTestDataHelper` and `AnalyticsTestDataHelper`
- [x] 9.3 Replace `createTestSuite()` / `createTestSuiteRun()` helper methods with `metaTestDataHelper` calls
- [x] 9.4 Replace `DELETE FROM test_case_run_results` in `@BeforeEach` with `analyticsTestDataHelper.cleanupResults()`
- [x] 9.5 Replace `SELECT id FROM test_case_run_results LIMIT 1` with `analyticsTestDataHelper.findAnyResultId()`

## 10. Documentation

- [x] 10.1 Update `AGENTS.md` testing DO/DON'T section with rules from the `testing-conventions` spec:
  - DO: Use `MetaTestDataHelper` / `AnalyticsTestDataHelper` for fixture creation and cleanup
  - DO: Use repositories for assertions (`findById`, `count`) instead of raw SELECT
  - DO: Name back-door helpers after intent, not mechanism
  - DON'T: Inject `jdbcTemplate` directly into test methods
  - DON'T: Write raw INSERT/UPDATE/SELECT in test methods or duplicate fixture logic across test classes
  - DON'T: Add test-only methods to production repository interfaces (except when the operation is a real data concern owned by that repository)
- [x] 10.2 Update `openspec/specs/README.md` to add `testing-conventions` under a new "Testing" section

## 11. Verification

- [x] 11.1 Run `./gradlew test` — all tests pass
- [x] 11.2 Run `./gradlew checkstyleTest` — no violations
