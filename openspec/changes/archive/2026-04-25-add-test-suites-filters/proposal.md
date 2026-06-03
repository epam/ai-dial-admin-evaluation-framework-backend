## Why

The `GET /api/v1/test-suites` list endpoint has a sparse filter whitelist (`name`, `suiteType`, `createdBy`, `createdAt`), making it impossible for clients to look up a suite by id, search by description, or filter by modification time — all common UI use cases.

## What Changes

- Add `id` filter (`eq`, `in`) to the TestSuites filter whitelist.
- Add `description` filter (`co`) to the TestSuites filter whitelist.
- Add `updatedAt` filter (`gt`, `gte`, `lt`, `lte`) to the TestSuites filter whitelist.

## Capabilities

### New Capabilities

_(none)_

### Modified Capabilities

- `test-suites`: filter whitelist gains `id`, `description`, and `updatedAt` fields.

## Impact

- **`FilterWhitelists`** (`.data.db.repository.sql`): `TEST_SUITES` gains three entries.
- No DB migrations required (`id`, `description`, `updated_at_ms` columns already exist).
- No changes to DTOs, mappers, controllers, or services.
