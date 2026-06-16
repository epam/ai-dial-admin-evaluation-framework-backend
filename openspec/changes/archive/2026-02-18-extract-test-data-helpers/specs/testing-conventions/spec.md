## ADDED Requirements

### Requirement: Test fixtures via test data helpers, not raw SQL

Functional tests SHALL use dedicated test data helper classes (`MetaTestDataHelper`, `AnalyticsTestDataHelper`) to create, manipulate, and query test fixtures. Test methods and `@BeforeEach` blocks MUST NOT inject `JdbcTemplate` or `NamedParameterJdbcTemplate` directly, and MUST NOT contain raw SQL `INSERT`, `UPDATE`, or `SELECT` statements.

Helper classes SHALL live in the `functional.helper` package (test source) and be registered as Spring beans in `PostgresFunctionalTestConfiguration`.

#### Scenario: Fixture creation uses repository, not SQL

- **WHEN** a functional test needs a `TestSuite` or `TestSuiteRun` prerequisite
- **THEN** the test calls `metaTestDataHelper.createTestSuite(name)` or `metaTestDataHelper.createTestSuiteRun(suiteId)`, which delegates to the production repository

#### Scenario: Test class has no jdbcTemplate field

- **WHEN** a functional test class is reviewed
- **THEN** it contains no `@Qualifier("metaJdbcTemplate")` or `@Qualifier("analyticsJdbcTemplate")` field injections in test methods or `@BeforeEach` blocks

#### Scenario: New test needing meta fixtures

- **WHEN** a developer writes a new functional test that needs meta entity prerequisites
- **THEN** they inject `MetaTestDataHelper` and call its factory methods; they do not write `INSERT INTO` SQL in the test class

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
