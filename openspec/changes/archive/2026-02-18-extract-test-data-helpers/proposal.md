## Why

Functional tests directly inject `NamedParameterJdbcTemplate` and write raw SQL `INSERT`/`UPDATE`/`SELECT` statements. Fixture creation logic (`createTestSuite`, `createTestSuiteRun`) is duplicated across four test files, so any schema change (new NOT NULL column, rename, JSONB structure change) requires hunting down and fixing each copy. Tests also encode column names and PostgreSQL-specific casts (`::jsonb`), coupling them to implementation details rather than the domain model.

## What Changes

- Introduce `MetaTestDataHelper` — a test-source Spring `@Component` that creates meta fixtures (`TestSuite`, `TestSuiteRun`) via production repositories, eliminating all raw `INSERT` SQL from test methods.
- Introduce `AnalyticsTestDataHelper` — a test-source Spring `@Component` that centralises analytics cleanup (`DELETE FROM test_case_run_results`) and narrow query helpers needed for assertions (e.g. `findAnyResultId()`).
- Add `updateIsValid(UUID id, boolean isValid)` to `TestSuiteRepository` and its Postgres implementation — this back-door state transition (used to test invalid-suite behaviour) moves from scattered raw SQL into the repository that already owns all SQL for that table.
- Refactor all five affected functional test classes to use the helpers: remove direct `jdbcTemplate` injections, raw `INSERT`/`UPDATE`/`SELECT` statements, and duplicated fixture methods.
- Register helpers in `PostgresFunctionalTestConfiguration`.
- Add a `testing-conventions` spec documenting the rule: repositories/helpers for fixtures, SQL only in centralised helpers for operations with no repository equivalent.
- Update `AGENTS.md` with the new DO/DON'T rules for test setup.

## Capabilities

### New Capabilities

- `testing-conventions`: Rules and patterns for functional test setup — use `MetaTestDataHelper`/`AnalyticsTestDataHelper` for fixtures and assertions; no raw SQL in test methods; back-door state via named helper methods only.

### Modified Capabilities

*(none — no existing spec requirements change)*

## Impact

- **Test source only** (except the two new repository method): no production behaviour changes.
- Files touched:
  - `src/test/…/functional/helper/MetaTestDataHelper.java` (new)
  - `src/test/…/functional/helper/AnalyticsTestDataHelper.java` (new)
  - `src/test/…/functional/config/PostgresFunctionalTestConfiguration.java` (register helpers)
  - `src/main/…/data/db/repository/TestSuiteRepository.java` (add `updateIsValid`)
  - `src/main/…/data/db/repository/PostgresTestSuiteRepository.java` (implement `updateIsValid`)
  - `src/test/…/functional/tests/TestSuiteRunFunctionalTests.java` (refactor)
  - `src/test/…/functional/tests/AnalyticsResultBatchWriteFunctionalTests.java` (refactor)
  - `src/test/…/functional/tests/AnalyticsResultCountFunctionalTests.java` (refactor)
  - `src/test/…/functional/tests/AnalyticsResultListFunctionalTests.java` (refactor)
  - `src/test/…/functional/tests/AnalyticsResultGetByIdFunctionalTests.java` (refactor)
  - `openspec/specs/testing-conventions/spec.md` (new)
  - `AGENTS.md` (update testing DO/DON'T section)
