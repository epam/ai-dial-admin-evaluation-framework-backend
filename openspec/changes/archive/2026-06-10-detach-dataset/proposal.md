## Why

A test suite bound to a PUBLIC dataset shares that dataset with any other suite that may bind to it. When a suite owner wants to iterate on test cases independently without affecting other consumers, there is currently no way to fork the dataset into a private, exclusive copy in a single operation. This "detach" operation fills that gap, reusing the existing clone infrastructure while also refactoring it to accept an explicit name — making it a generic primitive ready for a future `POST /datasets/{id}/clone` endpoint.

## What Changes

- **Refactor** `DatasetCloneService.cloneRowAndTestCases()` to accept an explicit `name` parameter (inserted before `createdBy`), removing the implicit name derivation inside the method. Callers that previously relied on the implicit name now pass `datasetCloneService.deriveCloneName(source.getName())` explicitly. No behavior change for existing callers.
- **New endpoint** `POST /test-suites/{id}/detach-dataset` — forks the suite's bound PUBLIC dataset into a new PRIVATE clone, rebinds the suite to the clone, and returns the updated `TestSuiteResponseDto`.
- **New DTO** `DatasetDetachRequestDto` with an optional `name` field. When omitted, the clone name is derived via `deriveCloneName`.
- **New service method** `TestSuiteService.detachDataset()` orchestrating: pre-TX file copy → in-TX row + test-case clone + `disabledTestCaseIds` remap + suite rebind.

## Capabilities

### New Capabilities

- `detach-dataset`: `POST /test-suites/{id}/detach-dataset` endpoint that forks a suite's bound PUBLIC dataset into a new PRIVATE clone for exclusive use by that suite. Covers the refactor of `DatasetCloneService.cloneRowAndTestCases()`, the new DTO, service method, controller endpoint, and functional tests.

### Modified Capabilities

- `test-suites`: New sub-operation added to the test-suite API surface — `detach-dataset` action endpoint.
- `datasets`: `DatasetCloneService.cloneRowAndTestCases()` signature change (added `name` parameter); no change to existing dataset API contracts.

## Impact

- **`DatasetCloneService`** (`service.domain`): signature change to `cloneRowAndTestCases` — one extra `String name` parameter before `createdBy`.
- **`TestSuiteCloneService`** (`service.domain`): updated call site to pass `deriveCloneName(source.getName())` explicitly.
- **`TestSuiteService`** (`service.domain`): new `detachDataset(UUID suiteId, DatasetDetachRequestDto dto, Jwt jwt)` method.
- **`TestSuiteController`** (`web.controller`): new `POST /api/v1/test-suites/{id}/detach-dataset` endpoint.
- **`DatasetDetachRequestDto`** (`service.domain.dto`): new class.
- No Flyway migrations required — no schema changes.
- No configuration changes required.
- No new packages introduced.
