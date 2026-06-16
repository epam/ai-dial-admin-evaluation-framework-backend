## Why

Datasets start as `PRIVATE` and bound to a single suite. When a user is ready to share a dataset with the catalogue, they want to promote it to `PUBLIC` and give it a proper display name and description in a single action. A dedicated `POST /datasets/{id}/publish` endpoint covers this "promote to catalogue" use case atomically and with clear intent.

## What Changes

- New endpoint: `POST /api/v1/datasets/{id}/publish` — transitions a dataset to `PUBLIC` and optionally updates `name`/`description` in the same transaction.
- New DTO: `DatasetPublishRequestDto` with optional `name` (`max 263`) and `description` (`max 2000`) fields. Both are null-safe — if omitted, the current values are preserved.
- New repository method `updateVisibilityAndMetadata` — atomically updates `visibility`, `name`, `description`, bumps `version`, and sets `updatedAt`. Called under the existing `FOR UPDATE` row lock.
- New service method `DatasetService.publish()` — acquires row lock, computes effective name/description, short-circuits on true no-op, applies update, catches `DataIntegrityViolationException` for name conflicts (409).
- No DB migration needed — no schema changes.

## Capabilities

### New Capabilities

_(none — this change adds a scenario to an existing capability)_

### Modified Capabilities

- `datasets`: Add `POST /api/v1/datasets/{id}/publish` endpoint and its scenarios (publish with metadata, no-op when already PUBLIC, name-conflict 409, not-found 404).

## Impact

- **`DatasetController`** — new `@PostMapping("/{id}/publish")` handler
- **`DatasetService`** — new `publish(UUID, DatasetPublishRequestDto)` method
- **`DatasetRepository`** interface + `PostgresDatasetRepository` — new `updateVisibilityAndMetadata` method
- **New DTO** `DatasetPublishRequestDto` in `service.domain.dto`
- **Tests** — new scenarios in `DatasetVisibilityFunctionalTests`
- No Flyway migration, no config properties, no new packages
