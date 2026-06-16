## Context

Datasets have a `visibility` field (`PUBLIC` | `PRIVATE`). The existing `PATCH /datasets/{id}/visibility` handles generic visibility transitions but is limited to changing visibility only. Publishing a PRIVATE dataset to the catalogue is a distinct user intent that also involves setting a display name and description. This change adds a dedicated `POST /datasets/{id}/publish` endpoint that performs the transition and metadata update atomically.

Current state: `DatasetService.transitionVisibility()` acquires a `FOR UPDATE` row lock, validates binding count for `PUBLIC→PRIVATE`, and calls `datasetRepository.updateVisibility()` which only updates `visibility`, `version`, and `updatedAt`. There is no way to atomically update name/description alongside visibility today.

No DB schema changes are needed — `name` and `description` columns already exist on the `datasets` table.

## Goals / Non-Goals

**Goals:**
- New `POST /datasets/{id}/publish` endpoint that transitions a dataset to `PUBLIC` and optionally updates `name` and `description` in one atomic write.
- Idempotent: calling publish on an already-PUBLIC dataset with unchanged metadata is a no-op (returns 200, no version bump).
- Consistent with existing locking discipline: acquires `SELECT ... FOR UPDATE` before reads and writes.

**Non-Goals:**
- Not a replacement for `PATCH /datasets/{id}/visibility` — the generic transition endpoint is unchanged.
- Not changing `PRIVATE→PUBLIC` transition rules (always allowed regardless of binding count).
- Not adding name/description fields to `PATCH /datasets/{id}/visibility`.

## Decisions

### Decision 1: Dedicated `POST /{id}/publish` endpoint over extending `PATCH /{id}/visibility`

`PATCH /visibility` has a narrow contract (only changes visibility). Extending it with optional metadata fields would blur its purpose and require callers to infer publish semantics. A dedicated `POST /publish` endpoint makes the intent explicit in the URL, keeps `PATCH /visibility` simple, and aligns with the REST convention of named actions for intent-specific operations.

Alternative considered: extend `PATCH /visibility` with optional `name`/`description` — rejected because it conflates generic transition logic with publish-specific UX.

### Decision 2: New repository method `updateVisibilityAndMetadata` over calling `updateVisibility` + `update` separately

A single jOOQ `UPDATE` touching `visibility`, `name`, `description`, `version`, and `updated_at_ms` is atomic by definition and avoids the need for two writes in one transaction. The existing `updateVisibility` is kept unchanged for the `PATCH /visibility` path.

### Decision 3: No-op detection in the service layer before issuing the write

Before calling the repository, the service computes `effectiveName` and `effectiveDesc` (provided value if non-null, else current) and checks whether `visibility`, `name`, and `description` all remain unchanged. If so, return the current dataset without touching the DB (no version bump). This matches the no-op behaviour of `transitionVisibility`.

### Decision 4: Name conflict surfaced as 409

`uq_datasets_name` is a unique index on `LOWER(name)`. If the provided name collides, the jOOQ `UPDATE` throws `DataIntegrityViolationException`. The service catches it and throws an `InvalidOperationException` with a clear message — the same pattern used in `DatasetService.create()`.

### Component interaction

```
DatasetController.publish(id, DatasetPublishRequestDto)
  └─ DatasetService.publish(id, dto)          @Transactional("metaTransactionManager")
       ├─ datasetRepository.findByIdForUpdate(id)      SELECT ... FOR UPDATE
       ├─ no-op check
       ├─ datasetRepository.updateVisibilityAndMetadata(id, PUBLIC, name, desc, now)
       │    UPDATE datasets SET visibility=?, name=?, description=?, version=version+1, updated_at_ms=? WHERE id=?
       └─ datasetRepository.findById(id)               re-fetch for response
```

New classes introduced:
- `DatasetPublishRequestDto` — `service.domain.dto`, Lombok `@Data @Builder`, optional `name` + `description` with `@Size` constraints referencing `ValidationConstants`.

## Risks / Trade-offs

- **Name collision window**: Between the no-op check (name unchanged) and the `UPDATE`, another transaction could insert a dataset with the same name. This is handled correctly — the `UPDATE` will throw `DataIntegrityViolationException` and the service surfaces it as 409. No extra guard needed.
- **Version bump on name/description-only change**: If a dataset is already `PUBLIC` but the caller supplies a new name, the version will be bumped. This is correct and expected — the resource changed.
