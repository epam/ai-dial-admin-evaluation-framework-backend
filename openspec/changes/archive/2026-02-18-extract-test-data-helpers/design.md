## Context

Functional tests currently inject `NamedParameterJdbcTemplate` directly and write raw SQL. `createTestSuite()` and `createTestSuiteRun()` fixture helpers are copy-pasted across four analytics test files. The state back-door (`UPDATE test_suites SET is_valid = false`) is also inlined in test methods. The project already uses production repositories (`TestSuiteRepository`, `TestSuiteRunRepository`, `TestCaseRunResultRepository`) that own all SQL for their tables — tests should delegate to them rather than duplicate their concerns.

## Goals / Non-Goals

**Goals:**
- Eliminate raw SQL `INSERT`/`UPDATE`/`SELECT` from test methods.
- Centralise fixture creation in two focused helpers — one per datasource.
- Replace duplicated `createTestSuite()` / `createTestSuiteRun()` with a single shared implementation.
- Replace direct SELECT assertions with repository calls where the repository API allows it.
- Add `updateIsValid(UUID, boolean)` to `TestSuiteRepository` so the back-door state change is owned by the repository layer.
- Document the pattern in `testing-conventions` spec and `AGENTS.md`.

**Non-Goals:**
- Switching fixture creation to REST API calls (decided against — slower, more brittle for prerequisite setup).
- Removing cleanup SQL (`DELETE FROM test_case_run_results`) — analytics repository is append-only by design; a plain DELETE is unavoidable and acceptable when centralised.
- Changing any production API behaviour.

## Decisions

### Decision 1 — Two separate helpers, not one

`MetaTestDataHelper` covers meta fixtures; `AnalyticsTestDataHelper` covers analytics cleanup and query helpers. Keeping them separate mirrors the dual-datasource split, prevents one class accruing both datasource qualifiers, and makes the dependency explicit per test class.

Alternatives considered:
- Single `TestDataHelper` — rejected: mixes `metaJdbcTemplate` and `analyticsJdbcTemplate` in one class, harder to reason about transaction boundaries.

### Decision 2 — Repository-based fixture creation, not raw SQL

`MetaTestDataHelper.createTestSuite()` calls `TestSuiteRepository.save()`. When the `test_suites` schema changes, only the repository needs updating; the helper inherits the fix automatically.

`MetaTestDataHelper` exposes the following methods (all delegating to repositories):
- `createTestSuite(String name)` — saves a `TestSuite` with sensible defaults; returns `TestSuite`
- `createTestSuiteRun(UUID suiteId)` — saves a `TestSuiteRun` in `COMPLETED` status (default for analytics test prerequisites); returns `TestSuiteRun`
- `createPendingRun(UUID suiteId, String testRunName)` — saves a `TestSuiteRun` in `PENDING` status with a given name; used by concurrency-limit tests in `TestSuiteRunFunctionalTests`
- `createRunningRun(UUID suiteId, String testRunName)` — saves a `TestSuiteRun` in `RUNNING` status with a given name; used by cancel and delete-non-terminal tests that need a run already in RUNNING state; returns `TestSuiteRun`
- `findRun(UUID id)` — delegates to `TestSuiteRunRepository.findById`; used to fetch timestamps for assertions without injecting the repository directly in tests
- `deleteRun(UUID id)` — delegates to `TestSuiteRunRepository.deleteById`; used by targeted cleanup in `finally` blocks in `TestSuiteRunFunctionalTests`
- `forceSuiteInvalid(UUID suiteId)` — calls `testSuiteRepository.updateIsValid(suiteId, false)`

Three separate run-creation methods are required: analytics tests need `COMPLETED` runs (only completed runs are valid batch-write targets); concurrency-limit tests need `PENDING` runs with controlled names to fill concurrent-run slots; cancel and delete-non-terminal tests need `RUNNING` runs that bypass the normal job lifecycle.

Note: `TestSuiteRunFunctionalTests.createTestSuite()` already uses the REST API — it is **not** raw SQL and should **not** be touched by this refactoring. The SQL methods in that file are: `insertRunDirectly(UUID, String, String)` called with both `PENDING` and `RUNNING` status (replaced by `createPendingRun` and `createRunningRun` respectively), and the `DELETE FROM test_suite_runs WHERE id = :id` in `finally` blocks (replaced by `metaTestDataHelper.deleteRun(id)`).

Alternatives considered:
- Keep SQL in helper — partially solves duplication but preserves schema coupling (column names, `::jsonb` casts leak into test source).
- Single `createTestSuiteRun(UUID suiteId, String status)` — workable, but named methods (`createPendingRun`, `createRunningRun`) are more expressive and prevent accidental status typos.

### Decision 3 — `updateIsValid` belongs on `TestSuiteRepository`

`TestSuiteRepository` already owns all SQL for `test_suites`. Adding `updateIsValid(UUID, boolean)` keeps SQL ownership consistent. The method is used from `MetaTestDataHelper.forceSuiteInvalid()` — a semantically named wrapper that signals intent over mechanism.

Alternatives considered:
- Raw SQL in `MetaTestDataHelper` directly — still SQL in the wrong place; breaks the principle that repositories own all DML for their table.
- Expose via REST API — no product intent for this; overkill.

### Decision 4 — Analytics cleanup stays as SQL, but in `AnalyticsTestDataHelper`

`TestCaseRunResultRepository` has no `deleteAll()` — the production analytics layer is append-only by design. The single `DELETE FROM test_case_run_results` is unavoidable SQL, but placing it in `AnalyticsTestDataHelper.cleanupResults()` means it lives in exactly one place.

`AnalyticsTestDataHelper` exposes the following methods:
- `cleanupResults()` — annotated `@Transactional("analyticsTransactionManager")`; executes `DELETE FROM test_case_run_results`; used in `@BeforeEach`
- `countAll()` — `SELECT COUNT(*) FROM test_case_run_results`; replaces inline `SELECT COUNT(*)` assertions in idempotency and deduplication tests; `TestCaseRunResultRepository.count(filters, runCreatedAtMs)` requires filter parameters and cannot count all rows without setup, so raw SQL here is unavoidable
- `findAnyResultId()` — `SELECT id FROM test_case_run_results LIMIT 1`; used to fetch an ID for subsequent REST GET assertions
- `findAnyResultCreatedAt()` — `SELECT created_at_ms FROM test_case_run_results LIMIT 1`; used in `shouldBatchCreateResults` to assert that result timestamps match the run's `created_at_ms`; a direct SQL query avoids coupling the batch-write test to the GET endpoint

### Decision 5 — Plain classes registered as explicit `@Bean`, not `@Component`

New package `com.epam.aidial.evaluation.functional.helper` (test source). Separates helpers from config classes in `functional.config`. Helpers are **plain classes** (no `@Component`) instantiated as explicit `@Bean` factory methods in `PostgresFunctionalTestConfiguration`. This follows the established pattern for test infrastructure beans (see `TestPersistenceService`) and avoids a duplicate-bean conflict: in a `@SpringBootTest` context the production `@SpringBootApplication` component scan covers the `com.epam.aidial.evaluation` package tree, which includes `functional.helper`; adding `@Component` there alongside an explicit `@Bean` declaration would register the same bean twice.

`AnalyticsTestDataHelper` requires `@Qualifier("analyticsJdbcTemplate")`; this qualifier is applied to the `@Bean` factory method's parameter in `PostgresFunctionalTestConfiguration`, consistent with how `testPersistenceService` already handles qualified template injection.

### Decision 6 — `TransactionTimestampContext` is available in helpers

`TransactionTimestampAspect` uses the pointcut `@Before("@annotation(transactional)")` with no `within` clause. It fires for any Spring-managed `@Transactional("metaTransactionManager")` method regardless of layer — including test helper components. The `isMetaTransaction` guard skips analytics transactions only; it does not restrict to the service package.

Therefore, `MetaTestDataHelper` write methods annotated `@Transactional("metaTransactionManager")` have the timestamp context initialised by the aspect before the method body runs. Production repositories (`PostgresTestSuiteRepository.save()`, etc.) can be called directly without pre-filling timestamps on the fixture entity.

## Risks / Trade-offs

- [Risk] `updateIsValid` on `TestSuiteRepository` is only called from tests today → Mitigation: it is a legitimate data operation on an existing table; if production logic ever needs it the method is already there. Mark with a comment noting current callers are tests.
- [Trade-off] `AnalyticsTestDataHelper.findAnyResultId()` and `findAnyResultCreatedAt()` still use raw SQL (cursor pagination makes `findAll` impractical for simple point queries) → Accepted: SQL is in one place, named helpers communicate intent.

## Migration Plan

Pure refactoring — no schema changes, no API changes.

1. Add `updateIsValid` to `TestSuiteRepository` interface and `PostgresTestSuiteRepository`.
2. Create `MetaTestDataHelper` and `AnalyticsTestDataHelper` in `functional.helper`.
3. Register helpers in `PostgresFunctionalTestConfiguration`.
4. Refactor test classes one by one: remove jdbcTemplate injections, remove duplicated fixture methods, replace with helper calls.
5. Verify: `./gradlew test` passes; `./gradlew checkstyleTest` passes.
6. Update `AGENTS.md` testing DO/DON'T section.
