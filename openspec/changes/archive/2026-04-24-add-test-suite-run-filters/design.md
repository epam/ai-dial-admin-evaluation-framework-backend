## Context

`GET /api/v1/test-suite-runs` is backed by `PostgresTestSuiteRunRepository.findAll`, which delegates filter parsing to `WhereBuilder` using `FilterWhitelists.TEST_SUITE_RUNS`. The whitelist is a static map from API field name → `FilterFieldDefinition` (column name, type, allowed operators). Adding a new filterable field requires only a new map entry — no SQL changes, no new classes.

The `id` column is `VARCHAR(36)` in the DB, exposed as UUID in the API. The `started_at_ms` and `completed_at_ms` columns are `BIGINT` (epoch ms), nullable (null for runs that haven't started/completed yet). All three are already in the `SortWhitelists.TEST_SUITE_RUNS` map, confirming they exist and are already projected in `SELECT_LIST_COLUMNS`.

## Goals / Non-Goals

**Goals:**
- Expose `id`, `startedAt`, and `completedAt` as filterable fields on the runs list endpoint.

**Non-Goals:**
- No new sort fields (already supported).
- No changes to DB schema, Flyway migrations, DTOs, response structure, or pagination logic.
- No changes to `OpenApiQueryParamCustomizer` (auto-regenerates from the whitelist).

## Decisions

**Single-file change in `FilterWhitelists.java`**

Three new entries added to `TEST_SUITE_RUNS`:

| API field    | DB column        | Type | Operators          |
|--------------|------------------|------|--------------------|
| `id`         | `id`             | UUID | `eq`, `in`         |
| `startedAt`  | `started_at_ms`  | LONG | `gt`, `gte`, `lt`, `lte` |
| `completedAt`| `completed_at_ms`| LONG | `gt`, `gte`, `lt`, `lte` |

`id` follows the same UUID pattern as `testSuiteId` (equality and set membership). `startedAt`/`completedAt` follow the same LONG range pattern as `createdAt`.

**NULL semantics for `startedAt`/`completedAt`**: Rows with `NULL` in these columns are naturally excluded when a range filter is applied — `NULL <op> value` evaluates to `UNKNOWN` in SQL, which is equivalent to `false` in a WHERE clause. This is the correct behavior: a range filter on "runs that started after T" should not return pending runs.

## Risks / Trade-offs

- `startedAt:eq` and `startedAt:in` are not exposed (LONG type doesn't allow EQ/IN). Exact-match on epoch ms is rarely useful and would produce confusing results for nullable timestamps.
