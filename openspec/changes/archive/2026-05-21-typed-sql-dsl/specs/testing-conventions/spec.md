## MODIFIED Requirements

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

## ADDED Requirements

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
