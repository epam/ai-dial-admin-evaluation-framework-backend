# Design: Add Unique Indexes for Meta Names

## Context

The backend stores TestSuites and TestCases in PostgreSQL (JDBC only). Today there is no uniqueness enforced at the DB level for TestSuite `name` or for the pair `(test_suite_id, test_case_name)`. The entity-relationship model treats these as business keys; duplicate names lead to ambiguous identities and make future features (e.g. lookup by name, cross-run matching) harder. The proposal adds unique indexes, maps violations to HTTP 409, and allows pruning duplicates in a single Flyway migration (early-stage project).

## Goals / Non-Goals

**Goals:**

- Enforce unique `name` on `test_suites` and unique `(test_suite_id, test_case_name)` on `test_cases` via DB indexes (case-insensitive).
- Map PostgreSQL unique violations to a deterministic API response (HTTP 409 Conflict) with a consistent error body.
- One Flyway migration that adds indexes; prune existing duplicates in the same migration with in-file comments where pruning occurs.
- Functional tests that assert uniqueness on create and update for both entities.
- Update `docs/database-schema.md` with the new indexes.

**Non-Goals:**

- No new endpoints or request/response schema changes.
- No proactive "check before insert" in the service layer as a requirement (DB index is source of truth; optional check is acceptable for clearer messages).
- No backward compatibility for clients that rely on duplicate names (early stage).

## Decisions

1. **Use LOWER() functional unique index**
   Use functional indexes with `LOWER()` for case-insensitive uniqueness (no extension required):
   - `CREATE UNIQUE INDEX uq_test_suites_name ON test_suites(LOWER(name))`
   - `CREATE UNIQUE INDEX uq_test_cases_suite_name ON test_cases(test_suite_id, LOWER(test_case_name))`

2. **Case-insensitive uniqueness**
   Names are case-insensitive. `"MyTest"` and `"mytest"` are considered duplicates. This is more user-friendly and prevents accidental near-duplicates. Performance overhead is negligible (microseconds per operation).

3. **Map violation to HTTP 409 Conflict with `UNIQUE_CONSTRAINT_VIOLATION`**
   Duplicate name is a conflict with existing state; 409 is standard and distinct from 400 (validation) and 404 (not found). Use error code `UNIQUE_CONSTRAINT_VIOLATION` in the existing `ErrorView` shape. Error message SHALL include the duplicated name(s).

4. **Create domain exception following existing pattern**
   Create `UniqueConstraintViolationException` in `service.domain.exception` following the existing `VersionConflictException` pattern. Repositories or services catch `DataIntegrityViolationException`, detect SQLSTATE 23505 (unique_violation), and throw the domain exception with the duplicated name. Handler maps it to 409. This keeps the handler clean and consistent with existing patterns.

5. **Migration: prune then add index**
   For `test_suites`: delete duplicates, keeping the row with `MIN(created_at_ms)` per `LOWER(name)` (oldest wins). For `test_cases`: delete duplicates, keeping the row with `MIN(created_at_ms)` per `(test_suite_id, LOWER(test_case_name))`. Then add the unique indexes. Order: prune `test_cases` first, then `test_suites`, then add indexes. In-file comments document where pruning occurs.

6. **Replace non-unique index on `test_suites(name)`**
   Current migration has `CREATE INDEX idx_test_suites_name ON test_suites(name)`. The new migration will drop this index and add a unique functional index on `LOWER(name)`. Index name: `uq_test_suites_name`. For test_cases: `uq_test_cases_suite_name`.

7. **CSV import fails entirely on duplicate within CSV**
   CSV import uses **replace-all mode** (deletes all existing test cases in the suite before importing). Therefore, conflicts with existing data do not occur. However, if a CSV contains duplicate `testCaseName` values (case-insensitive) within the file itself, the import SHALL fail with HTTP 409 and message listing the duplicated name(s). No partial import.

## Risks / Trade-offs

- **Risk:** Pruning deletes data; if duplicates were intentional, that data is lost.
  **Mitigation:** Early-stage project; proposal explicitly allows pruning. Deterministic criterion (oldest wins by `MIN(created_at_ms)`) ensures reproducibility. Comment in migration documents what is pruned.

- **Risk:** Domain exception pattern requires detecting SQLSTATE from exception chain.
  **Mitigation:** Encapsulate detection in repository/service layer. Pattern is consistent with existing `VersionConflictException`.

- **Trade-off:** CSV import fails entirely on any duplicate.
  **Mitigation:** Clear error message lists all duplicated names so user can fix CSV and retry. No partial state to clean up.

- **Trade-off:** LOWER() functional index requires using `LOWER()` in queries to leverage the index.
  **Mitigation:** For uniqueness enforcement, PostgreSQL uses the index automatically on insert/update. For lookups by name (if added later), queries should use `WHERE LOWER(name) = LOWER(:input)`.

## Migration Plan

1. **Flyway migration (single script)**
   - Comment: "Early-stage project; duplicate rows are pruned below."
   - Prune duplicate `test_cases`: keep one row per `(test_suite_id, LOWER(test_case_name))` by `MIN(created_at_ms)`, delete the rest; add in-file comment where pruning occurs.
   - Prune duplicate `test_suites`: keep one row per `LOWER(name)` by `MIN(created_at_ms)`, delete the rest; add in-file comment.
   - Drop non-unique index `idx_test_suites_name` if it exists.
   - Add unique functional index: `CREATE UNIQUE INDEX uq_test_suites_name ON test_suites(LOWER(name))`.
   - Add unique functional index: `CREATE UNIQUE INDEX uq_test_cases_suite_name ON test_cases(test_suite_id, LOWER(test_case_name))`.

2. **Deploy**
   Run application with Flyway; migration runs on startup. No separate data migration step.

3. **Rollback**
   No automatic rollback. If needed, a new migration would drop the unique indexes and optionally recreate the non-unique index on `name`. Document in migration comment that rollback is manual.

## Open Questions

- None; scope is clear from the proposal.
