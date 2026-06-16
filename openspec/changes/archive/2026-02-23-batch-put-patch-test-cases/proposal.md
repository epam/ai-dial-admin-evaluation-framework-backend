## Why

The FE test cases grid allows users to edit multiple rows simultaneously (e.g., rename, toggle enabled, change data values). Currently each change requires a separate PUT or PATCH call, causing N round-trips and poor UX for bulk edits. A batch endpoint lets the client save all grid changes in a single atomic request.

## What Changes

- Add **batch PUT** on `PUT /api/v1/test-suites/{suiteId}/test-cases` — accepts an array of test case full-update items, each identified by `id`
- Add **batch PATCH** on `PATCH /api/v1/test-suites/{suiteId}/test-cases` — accepts an array of RFC 7396 merge-patch items, each identified by `id`
- Both operations are **all-or-nothing** (single transaction, full rollback on any error)
- Both operations are **update-only** (no upsert/create — all IDs must exist)
- **Configurable batch size limit** (default 256) via application properties
- Response is an ordered list of `TestCaseResponseDto` matching input order
- `?includeWarnings` query param supported on both endpoints

## Capabilities

### New Capabilities
_(none — batch operations are additions to the existing test-cases capability)_

### Modified Capabilities
- `test-cases`: Add batch PUT and PATCH requirements — atomic multi-item updates with configurable size limits, final-state uniqueness enforcement across batch items, and error handling

## Impact

- **Controller**: `TestCaseController` — two new endpoint methods (batch PUT, batch PATCH)
- **Service**: `TestCaseService` — new `batchUpdate` and `batchPatch` methods
- **Repository**: `TestCaseRepository` — may need batch-optimized update method or reuse existing `update`
- **DTOs**: New `TestCaseBatchPutItemDto` for typed batch PUT items; batch PATCH uses `List<Map<String, Object>>` (consistent with single PATCH merge-patch approach)
- **Configuration**: New `test-case.batch.max-items` property (default 256)
- **Validation**: Batch-level validation (size limit, duplicate ID check) + per-item validation (same as single PUT/PATCH)
- **OpenAPI**: New endpoint documentation + request/response examples
- **Tests**: Functional tests for both batch endpoints covering happy path, error cases, atomicity
