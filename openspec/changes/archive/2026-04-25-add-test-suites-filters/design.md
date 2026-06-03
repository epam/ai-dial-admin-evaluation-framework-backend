## Context

`GET /api/v1/test-suites` supports four filter fields (`name`, `suiteType`, `createdBy`, `createdAt`). Three new fields (`id`, `description`, `updatedAt`) follow the existing whitelist pattern — all target scalar columns that already exist in the `test_suites` table and are already included in the `SELECT_ALL_BASE_SQL` query.

## Goals / Non-Goals

**Goals:**
- Expose `id`, `description`, and `updatedAt` as filterable fields on the TestSuites list endpoint.
- Keep the change self-contained in `FilterWhitelists.TEST_SUITES` — no new infrastructure required.

**Non-Goals:**
- `deploymentName` cross-column JSONB filtering (deferred).
- Adding DB indexes for the new filter columns (separate performance concern).

## Decisions

All three new fields use existing `FilterFieldType` values and require no changes to `WhereBuilder`, `FilterFieldDefinition`, or any other infrastructure class.

| API field | DB column | Type | Operators |
|-----------|-----------|------|-----------|
| `id` | `id` | `STRING` | `EQ`, `IN` |
| `description` | `description` | `STRING` | `CO` |
| `updatedAt` | `updated_at_ms` | `LONG` | `GT`, `GTE`, `LT`, `LTE` |

`id` uses `STRING` (not `UUID`) because the filter is an exact string match and stored UUIDs are already consistently formatted VARCHAR(36). `EQ` on `STRING` performs a case-insensitive `lower()` comparison, which is harmless for UUID values.

## Risks / Trade-offs

| Risk | Mitigation |
|------|-----------|
| `ILIKE '%value%'` on `description` without a GIN index causes full table scan | Acceptable for V1 — suite counts are modest; index can be added separately if needed |
