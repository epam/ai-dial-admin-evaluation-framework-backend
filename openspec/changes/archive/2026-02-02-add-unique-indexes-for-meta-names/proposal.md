# Proposal: Add Unique Indexes for Meta Names

## Why

The database currently allows duplicate TestSuite names and duplicate TestCase names within the same suite. The design (entity-relationship-model) treats suite name and (suite, case name) as business keys; enforcing uniqueness at the DB level prevents inconsistent data and aligns API behavior with these rules. Adding unique constraints now avoids technical debt before more features depend on these identities.

## What Changes

- **test_suites**: Add a unique index on `LOWER(name)`. Names are **case-insensitive** (e.g. "MyTest" and "mytest" are duplicates). Creating or updating a suite with a name that already exists (ignoring case) will fail at the DB; the API will map this to HTTP 409 Conflict with a clear message that includes the duplicated name.
- **test_cases**: Add a unique composite index on `(test_suite_id, LOWER(test_case_name))`. Names are **case-insensitive**. Creating or updating a test case with a duplicate name (ignoring case) within the same suite will fail at the DB; the API will map this to HTTP 409 with a clear message that includes the duplicated name. CSV import (which uses replace-all mode) SHALL fail the entire import if the CSV file itself contains duplicate names (case-insensitive).
- **Migration**: One Flyway migration (POSTGRES) that adds the unique index/constraint for `test_suites(name)` and the unique composite index/constraint for `test_cases(test_suite_id, test_case_name)`. Because the project is in an early stage, duplicate data may be pruned during migration (e.g. keep one row per unique key, drop the rest); the migration script SHALL leave an in-file comment where data is pruned so future readers know.
- **Tests**: Add functional tests that verify uniqueness: create/update attempts that would violate the constraint receive the appropriate HTTP status and error payload; successful create/update still works when names are unique.
- **Docs**: Update `docs/database-schema.md` to document the new unique constraints/indexes.

No new API endpoints or request/response shape changes; only enforcement and error handling for existing create/update operations.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- **test-suites**: Add requirement that TestSuite `name` is unique across all suites (case-insensitive); create and update SHALL reject duplicate names with HTTP 409 and error body containing `UNIQUE_CONSTRAINT_VIOLATION` code and the duplicated name.
- **test-cases**: Add requirement that `testCaseName` is unique within a TestSuite (case-insensitive); create, update, and CSV import SHALL reject duplicates with HTTP 409 and error body containing `UNIQUE_CONSTRAINT_VIOLATION` code and the duplicated name(s).

## Impact

- **Code**: Create a domain exception (e.g. `UniqueConstraintViolationException`) following the existing `VersionConflictException` pattern. Exception handler maps it to HTTP 409 with `UNIQUE_CONSTRAINT_VIOLATION` error code and message including the duplicated name(s). DB constraint remains the source of truth.
- **Database**: One new Flyway migration; duplicate rows may be pruned in the migration (comment in migration where pruning occurs).
- **API**: No new endpoints; existing `POST`/`PUT` for test-suites and test-cases will return 409 (or equivalent) when the unique constraint is violated.
- **Docs**: `docs/database-schema.md` — add unique constraint/index entries for `test_suites.name` and `test_cases(test_suite_id, test_case_name)`.
- **Tests**: New or extended functional tests (e.g. in `TestSuiteFunctionalTests`, `TestCaseFunctionalTests`) that assert uniqueness behavior for create and update.
