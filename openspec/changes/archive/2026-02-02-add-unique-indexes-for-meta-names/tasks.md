# Tasks: Add Unique Indexes for Meta Names

## 1. Database migration

- [x] 1.1 Add Flyway migration `V1.4__AddUniqueIndexesForMetaNames.sql` in `src/main/resources/db/migration/POSTGRES/`: prune duplicate rows using `MIN(created_at_ms)` on `LOWER(name)` / `LOWER(test_case_name)` (oldest wins) with in-file comments where pruning occurs, drop `idx_test_suites_name`, then add unique functional index `uq_test_suites_name` on `test_suites(LOWER(name))` and unique functional index `uq_test_cases_suite_name` on `test_cases(test_suite_id, LOWER(test_case_name))`
- [x] 1.2 Run migration locally and verify indexes enforce case-insensitive uniqueness (e.g. "Test" and "test" rejected as duplicates)

## 2. Exception handling

- [x] 2.1 Create `UniqueConstraintViolationException` in `service.domain.exception` (following `VersionConflictException` pattern) with field(s) for duplicated name(s)
- [x] 2.2 Add `UNIQUE_CONSTRAINT_VIOLATION` to `ErrorCode` enum
- [x] 2.3 In `DefaultExceptionHandler`, add handler for `UniqueConstraintViolationException`: return HTTP 409 with error code `UNIQUE_CONSTRAINT_VIOLATION` and message including the duplicated name(s)
- [x] 2.4 In repository or service layer, catch `DataIntegrityViolationException` with SQLSTATE 23505, extract constraint details, and throw `UniqueConstraintViolationException` with the duplicated name

## 3. Functional tests (failure cases)

- [x] 3.1 In `TestSuiteFunctionalTests`, add test: create two suites with same name; second request returns 409 with `UNIQUE_CONSTRAINT_VIOLATION` and body includes the duplicated name
- [x] 3.2 In `TestSuiteFunctionalTests`, add test: create suite with name that differs only by case from existing suite; request returns 409
- [x] 3.3 In `TestSuiteFunctionalTests`, add test: update existing suite to a name that another suite already has (case-insensitive); request returns 409
- [x] 3.4 In `TestCaseFunctionalTests`, add test: create two test cases in same suite with same `testCaseName`; second request returns 409
- [x] 3.5 In `TestCaseFunctionalTests`, add test: create test case with name that differs only by case from existing case in same suite; request returns 409
- [x] 3.6 In `TestCaseFunctionalTests`, add test: update a test case to a name that another case in the same suite already has (case-insensitive); request returns 409
- [x] 3.7 In `TestCaseFunctionalTests`, add test: PATCH a test case to a name that already exists in the same suite (case-insensitive); request returns 409
- [x] 3.8 In `TestCaseFunctionalTests`, add test: CSV import with duplicate names (case-insensitive) within CSV fails with 409 and lists duplicated names
- [x] ~~3.9 In `TestCaseFunctionalTests`, add test: CSV import with name conflicting (case-insensitive) with existing data fails with 409~~ (N/A: CSV import uses replace-all mode—existing data is deleted before import, so conflicts with existing data do not occur)

## 4. Functional tests (success cases)

- [x] 4.1 In `TestSuiteFunctionalTests`, add test: create suites with different names succeeds
- [x] 4.2 In `TestSuiteFunctionalTests`, add test: update suite to its own current name (or case variation) succeeds (no false-positive 409)
- [x] 4.3 In `TestCaseFunctionalTests`, add test: create test cases with same name in different suites succeeds

## 5. Documentation and quality

- [x] 5.1 Update `docs/database-schema.md`: document unique functional index `uq_test_suites_name` on `test_suites(LOWER(name))` and unique functional index `uq_test_cases_suite_name` on `test_cases(test_suite_id, LOWER(test_case_name))`; note case-insensitive uniqueness
- [x] 5.2 Run `checkstyleMain` and `checkstyleTest` and fix any issues
