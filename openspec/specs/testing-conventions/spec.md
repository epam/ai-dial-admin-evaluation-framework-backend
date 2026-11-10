# Testing Conventions

## Purpose

Define the current functional-test fixture boundary and production JDBC fence: private, datasource-specific helpers own back-door queries while test classes use helper intent methods and repositories.

Status: **Implemented**

## Requirements

### Requirement: Test fixtures via test data helpers, not raw SQL

Functional tests SHALL use dedicated test data helper classes (`MetaTestDataHelper`, `AnalyticsTestDataHelper`) to create, manipulate, and query test fixtures. Test methods and `@BeforeEach` blocks MUST NOT inject `JdbcTemplate`, `NamedParameterJdbcTemplate`, or `DSLContext` directly, and MUST NOT contain raw SQL `INSERT`, `UPDATE`, or `SELECT` statements (text-block or DSL-built).

Helper classes SHALL live in the `functional.helper` package (test source) and be registered as Spring beans in `PostgresFunctionalTestConfiguration`. Their datasource-specific DSL contexts SHALL be selected by `@Qualifier("metaDsl")` / `@Qualifier("analyticsDsl")` on the configuration factory parameters and passed to helper constructors. Helpers SHALL use repositories or typed jOOQ for ordinary table access; catalog, migration-script, and constraint-bypass operations may use helper-local SQL. Helpers MUST NOT inject `NamedParameterJdbcTemplate` or `JdbcTemplate`.

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
Status: **Implemented**

Analytics cleanup (clearing `test_case_run_results` between tests) SHALL be performed via `AnalyticsTestDataHelper.cleanupResults()`. Test classes MUST NOT call `analyticsJdbcTemplate.update("DELETE FROM …")` directly.

The analytics repository is append-only by design; `AnalyticsTestDataHelper` is the single permitted location for cleanup SQL because no production `deleteAll()` exists.

#### Scenario: Analytics test setup

- **WHEN** an analytics functional test's `@BeforeEach` needs to clear results
- **THEN** it calls `analyticsTestDataHelper.cleanupResults()` — not a raw DELETE

### Requirement: Back-door state manipulation via named helper methods
Status: **Implemented**

Test-only state transitions that cannot be triggered through the production API (e.g., forcing a test suite into an invalid state) SHALL be expressed as named methods on `MetaTestDataHelper` or `AnalyticsTestDataHelper`. The method name MUST communicate intent, not mechanism. The underlying SQL or repository call lives exclusively inside the helper.

Back-door state operations that touch a table owned by a production repository SHOULD be implemented via a repository method, not via inline SQL in the helper.

#### Scenario: Forcing invalid state for negative-path test

- **WHEN** a test needs a `TestSuite` with `is_valid = false`
- **THEN** it calls `metaTestDataHelper.forceSuiteInvalid(suiteId)`, which internally calls `testSuiteRepository.updateIsValid(suiteId, false)`

#### Scenario: Named method communicates intent

- **WHEN** a developer reads the test
- **THEN** `forceSuiteInvalid(id)` is immediately understandable without inspecting the helper; no raw SQL is visible in the test method

### Requirement: Assertions use repositories, not raw SELECT
Status: **Implemented**

Functional test assertions that verify persisted state SHALL use production repositories (e.g., `repository.findById(id)`, `repository.count()`) rather than inline `SELECT` queries. Raw SELECT is permitted in `AnalyticsTestDataHelper` only for queries that cannot be expressed through the cursor-paginated analytics repository API (e.g., fetching an arbitrary result ID).

#### Scenario: Verifying a persisted field

- **WHEN** a test asserts that a saved entity has an expected field value
- **THEN** it fetches the entity via `repository.findById(id).orElseThrow()` and asserts on the Java field — it does not issue a `SELECT column FROM table WHERE id = :id` query

#### Scenario: Counting persisted records

- **WHEN** a test asserts a specific record count
- **THEN** it calls `repository.count(…)` — it does not issue `SELECT COUNT(*) FROM table`

**Exception**: When the repository `count()` method requires mandatory filter parameters that cannot be satisfied for an unconditional aggregate (as is the case with `TestCaseRunResultRepository`), raw `SELECT COUNT(*)` SQL is permitted — but it MUST live exclusively in `AnalyticsTestDataHelper.countAll()`, not in individual test methods.

### Requirement: ArchUnit fence forbids raw JdbcTemplate outside allowed packages in production code

`JdbcTemplateFenceTest` SHALL fail when any imported production class outside the two allowed packages (`configuration.datasource` and `service.infrastructure.health`) depends on `org.springframework.jdbc.core.JdbcTemplate` or `org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate`. Test infrastructure classes such as `PostgresTestPersistenceService` (schema reset DDL) are excluded by `ImportOption.DoNotIncludeTests`. The full persistence contract is owned by the `typed-sql-dsl` capability.

Status: **Implemented**

#### Scenario: Regression caught in production code

- **WHEN** a developer adds a dependency on `NamedParameterJdbcTemplate` to a production class outside the two allowed package trees
- **THEN** the ArchUnit rule SHALL fail with a message naming the offending class and the two allowed locations

### Requirement: Test data helpers expose typed DSLContext only via constructor injection

`MetaTestDataHelper` and `AnalyticsTestDataHelper` SHALL receive their `DSLContext` dependencies through constructor injection, with datasource qualifiers applied at their Spring configuration factory boundary. The DSLContext field SHALL be private and SHALL NOT be exposed to test classes.

Status: **Implemented**

#### Scenario: Helper builds query via DSL

- **WHEN** `MetaTestDataHelper.createTestSuiteRun(...)` is invoked
- **THEN** the helper SHALL construct a `TestSuiteRun` model and persist it through `TestSuiteRunRepository.save(...)`
- **AND THEN** the helper SHALL NOT use `String` SQL or `MapSqlParameterSource`

## Implementation notes

- [MetaTestDataHelper](../../../src/test/java/com/epam/aidial/evaluation/functional/helper/MetaTestDataHelper.java) and [AnalyticsTestDataHelper](../../../src/test/java/com/epam/aidial/evaluation/functional/helper/AnalyticsTestDataHelper.java) — repository-backed factories, private DSLs, intentional back doors and analytics cleanup.
- [PostgresFunctionalTestConfiguration](../../../src/test/java/com/epam/aidial/evaluation/functional/config/PostgresFunctionalTestConfiguration.java) — qualified helper factory parameters.
- [JdbcTemplateFenceTest](../../../src/test/java/com/epam/aidial/evaluation/architectural/JdbcTemplateFenceTest.java) — production-only dependency fence; it does not enforce the test-helper convention.
