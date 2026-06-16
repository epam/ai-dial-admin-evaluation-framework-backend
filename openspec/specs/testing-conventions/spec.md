### Requirement: Test fixtures via test data helpers, not raw SQL

Functional tests SHALL use dedicated test data helper classes (`MetaTestDataHelper`, `AnalyticsTestDataHelper`) to create, manipulate, and query test fixtures. Test methods and `@BeforeEach` blocks MUST NOT inject `JdbcTemplate`, `NamedParameterJdbcTemplate`, or `DSLContext` directly, and MUST NOT contain raw SQL `INSERT`, `UPDATE`, or `SELECT` statements (text-block or DSL-built).

Helper classes SHALL live in the `functional.helper` package (test source) and be registered as Spring beans in `PostgresFunctionalTestConfiguration`. Helpers SHALL build their queries via the typed jOOQ DSL using injected `@Qualifier("metaDsl") DSLContext` / `@Qualifier("analyticsDsl") DSLContext`. Helpers MUST NOT inject `NamedParameterJdbcTemplate` or `JdbcTemplate`.

Status: **Implemented**

#### Scenario: Fixture creation uses repository, not SQL

- **WHEN** a functional test needs a `TestSuite` or `TestSuiteRun` prerequisite
- **THEN** the test calls `metaTestDataHelper.createTestSuite(name)` or `metaTestDataHelper.createTestSuiteRun(suiteId)`, which delegates to the production repository

#### Scenario: Test class has no jdbcTemplate or DSLContext field

- **WHEN** a functional test class is reviewed
- **THEN** it contains no `@Qualifier("metaJdbcTemplate")`, `@Qualifier("analyticsJdbcTemplate")`, `@Qualifier("metaDsl")`, or `@Qualifier("analyticsDsl")` field injections in test methods or `@BeforeEach` blocks

#### Scenario: New test needing meta fixtures

- **WHEN** a developer writes a new functional test that needs meta entity prerequisites
- **THEN** they inject `MetaTestDataHelper` and call its factory methods; they do not write `INSERT INTO` SQL or `dsl.insertInto(...)` calls in the test class

### Requirement: Analytics cleanup centralised in AnalyticsTestDataHelper

Analytics cleanup (clearing `test_case_run_results` between tests) SHALL be performed via `AnalyticsTestDataHelper.cleanupResults()`. Test classes MUST NOT call `analyticsJdbcTemplate.update("DELETE FROM …")` directly.

The analytics repository is append-only by design; `AnalyticsTestDataHelper` is the single permitted location for cleanup SQL because no production `deleteAll()` exists.

#### Scenario: Analytics test setup

- **WHEN** an analytics functional test's `@BeforeEach` needs to clear results
- **THEN** it calls `analyticsTestDataHelper.cleanupResults()` — not a raw DELETE

### Requirement: Back-door state manipulation via named helper methods

Test-only state transitions that cannot be triggered through the production API (e.g., forcing a test suite into an invalid state) SHALL be expressed as named methods on `MetaTestDataHelper` or `AnalyticsTestDataHelper`. The method name MUST communicate intent, not mechanism. The underlying SQL or repository call lives exclusively inside the helper.

Back-door state operations that touch a table owned by a production repository SHOULD be implemented via a repository method, not via inline SQL in the helper.

#### Scenario: Forcing invalid state for negative-path test

- **WHEN** a test needs a `TestSuite` with `is_valid = false`
- **THEN** it calls `metaTestDataHelper.forceSuiteInvalid(suiteId)`, which internally calls `testSuiteRepository.updateIsValid(suiteId, false)`

#### Scenario: Named method communicates intent

- **WHEN** a developer reads the test
- **THEN** `forceSuiteInvalid(id)` is immediately understandable without inspecting the helper; no raw SQL is visible in the test method

### Requirement: Assertions use repositories, not raw SELECT

Functional test assertions that verify persisted state SHALL use production repositories (e.g., `repository.findById(id)`, `repository.count()`) rather than inline `SELECT` queries. Raw SELECT is permitted in `AnalyticsTestDataHelper` only for queries that cannot be expressed through the cursor-paginated analytics repository API (e.g., fetching an arbitrary result ID).

#### Scenario: Verifying a persisted field

- **WHEN** a test asserts that a saved entity has an expected field value
- **THEN** it fetches the entity via `repository.findById(id).orElseThrow()` and asserts on the Java field — it does not issue a `SELECT column FROM table WHERE id = :id` query

#### Scenario: Counting persisted records

- **WHEN** a test asserts a specific record count
- **THEN** it calls `repository.count(…)` — it does not issue `SELECT COUNT(*) FROM table`

**Exception**: When the repository `count()` method requires mandatory filter parameters that cannot be satisfied for an unconditional aggregate (as is the case with `TestCaseRunResultRepository`), raw `SELECT COUNT(*)` SQL is permitted — but it MUST live exclusively in `AnalyticsTestDataHelper.countAll()`, not in individual test methods.

### Requirement: ArchUnit fence forbids raw JdbcTemplate outside allowed packages in production code

A LayeredArchitectureTest rule SHALL fail when any production class outside the two allowed packages imports `org.springframework.jdbc.core.JdbcTemplate` or `org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate`. The two allowed packages are `com.epam.aidial.evaluation.configuration.datasource` (datasource bean wiring and Flyway configuration) and `com.epam.aidial.evaluation.service.infrastructure.health` (`DatabaseHealthIndicator` and `AnalyticsDatabaseHealthIndicator`, which probe each datasource via JdbcTemplate). The rule applies to production sources (`src/main/java`, `src/main/java-generated`) only — test infrastructure classes such as `PostgresTestPersistenceService` (schema reset DDL) are excluded by `ImportOption.DoNotIncludeTests`.

Status: **Implemented**

#### Scenario: Regression caught in production code

- **WHEN** a developer adds an import of `NamedParameterJdbcTemplate` to any class under `com.epam.aidial.evaluation.data.db`, `.service`, `.web`, or `.client`
- **THEN** the ArchUnit rule SHALL fail with a message naming the offending class and the two allowed locations

#### Scenario: Health indicator carve-out permitted

- **WHEN** `DatabaseHealthIndicator` or `AnalyticsDatabaseHealthIndicator` in `service.infrastructure.health` injects `JdbcTemplate` to issue its liveness probe
- **THEN** the ArchUnit rule SHALL NOT flag these classes

### Requirement: Test data helpers expose typed DSLContext only via constructor injection

`MetaTestDataHelper` and `AnalyticsTestDataHelper` SHALL receive their `DSLContext` dependencies through constructor injection annotated with the correct `@Qualifier`. The DSLContext field SHALL be private and SHALL NOT be exposed to test classes.

Status: **Implemented**

#### Scenario: Helper builds query via DSL

- **WHEN** `MetaTestDataHelper.createTestSuiteRun(...)` is invoked
- **THEN** the helper SHALL build the insert via `metaDsl.insertInto(TEST_SUITE_RUNS)...`
- **AND THEN** the helper SHALL NOT use `String` SQL or `MapSqlParameterSource`
