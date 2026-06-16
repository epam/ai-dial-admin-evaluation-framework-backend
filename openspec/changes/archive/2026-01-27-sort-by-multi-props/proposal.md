## Why

Today our list APIs (and the repository pagination helpers behind them) do not define a consistent way to sort by more than one property. UI and automation use cases commonly require stable ordering (e.g., sort by `status` then by `updatedAt`), and implementing this per-entity risks inconsistent parameter formats and unsafe SQL construction.

## What Changes

- Add a **standard API sorting contract** that supports **multiple sort keys** (ordered) and direction per key.
- Implement **multi-column sorting** in the shared paging/sorting infrastructure so it can be reused by any entity repository.
- Apply the sorting contract to existing list endpoints (starting with TestSuites) and ensure deterministic ordering when multiple rows share the same primary sort key.
- Ensure sorting remains **SQL-injection safe** via strict column whitelisting and validation.

## Capabilities

### New Capabilities
- `sorting`: Define request parameter format and backend behavior for safe multi-column sorting across list endpoints (including validation rules and whitelisting requirements).

### Modified Capabilities
- `test-suites`: Extend the list endpoint requirements to accept multi-column sorting parameters in addition to pagination.
- `database-and-migrations`: Extend the “pagination and safe sorting” requirements to explicitly cover multi-column sorting (ordered list of sort keys) and the expected whitelisting/validation behavior.

## Impact

- **API**: List endpoints will accept additional sorting query parameters (backwards compatible if current clients omit them).
- **Data access layer**: Shared pagination/sorting utilities and Postgres repository implementations will support building `ORDER BY` clauses from multiple validated sort fields.
- **Security**: Sorting inputs must be validated and mapped only to allowed columns; rejection behavior (e.g., 400) must be consistent.
- **Testing**: Add/extend functional tests to cover multi-key sorting order, tie-breaking behavior, and invalid/unknown sort fields.
