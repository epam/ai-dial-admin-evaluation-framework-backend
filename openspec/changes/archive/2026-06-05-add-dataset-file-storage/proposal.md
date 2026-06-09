## Why

DIAL file storage currently scopes every uploaded file under `{bucket}/suites/{suiteId}/{filename}`, and short references are written as `@ef/suites/{suiteId}/{filename}`. After the Dataset entity migration (V1.22), test cases are owned by datasets, not suites, while suite-level concerns (request/argument templates) remain suite-bound. The current single-folder convention therefore conflates two different ownership models and forces dataset-scoped content to live in a suite folder it does not belong to.

In addition, `ZipImportService` already passes a `datasetId` into `DialFileRefResolver.buildEfRef(...)`, but that method hardcodes `"/suites/"`, so test-case imports today produce refs of the form `@ef/suites/{datasetUuid}/{filename}` — a dataset UUID nested under a `suites/` segment. This is a latent inconsistency that this change resolves alongside the broader split.

## What Changes

- Introduce a dataset-scoped file reference shape `@ef/datasets/{datasetId}/{filename}` alongside the existing `@ef/suites/{suiteId}/{filename}`. Files referenced from test-case `data` SHALL use the dataset shape; files referenced from suite-level fields (FormPartDto, request/argument template constant bindings) SHALL keep the suite shape.
- Add a new REST API for dataset-scoped file management:
  - `POST   /api/v1/datasets/{datasetId}/files`
  - `GET    /api/v1/datasets/{datasetId}/files`
  - `GET    /api/v1/datasets/{datasetId}/files/{filename}`
  - `DELETE /api/v1/datasets/{datasetId}/files/{filename}`
- Extend `DialFileRefResolver` with `buildDatasetEfRef(datasetId, filename)`. `resolveToRealPath` and `resolveToDialRef` keep their current behavior — they already pass through any path after a whitelisted prefix.
- Extend `FileService` with dataset-scoped operations (`uploadToDataset`, `listByDataset`, `downloadFromDataset`, `deleteByDataset`, `deleteAllByDatasetId`). Suite-scoped operations remain unchanged.
- Extend `FileRefValidator` so test-case `data` accepts both the dataset-shaped ref (validated against the test case's owning `datasetId`) and the legacy suite-shaped ref (no rewrite, no migration). Suite-level fields keep their existing suite-ownership rule.
- Fix the latent `ZipImportService` bug: switch the call from `buildEfRef(datasetId, ...)` to `buildDatasetEfRef(datasetId, ...)` so imported files land under `datasets/{datasetId}/`.
- Hook `FileService.deleteAllByDatasetId` into dataset delete (both PUBLIC explicit delete and the PRIVATE-dataset cascade triggered by suite delete) as a best-effort, post-commit step — mirroring the existing suite cascade.
- No data migration. Existing test-case `data` keeps its `@ef/suites/{suiteId}/...` refs and both shapes remain valid indefinitely. Existing files stay where they are.

## Capabilities

### New Capabilities
- `dataset-file-storage`: REST API and service-layer operations for uploading, listing, downloading, and deleting files scoped to a dataset, plus the post-commit cascade that removes dataset files when a dataset is deleted (PUBLIC explicit delete or PRIVATE delete triggered by suite cascade).

### Modified Capabilities
- `dial-file-ref`: add `buildDatasetEfRef(datasetId, filename)` and document the dataset-shaped reference (`@ef/datasets/{datasetId}/{filename}`) as a supported source alongside `@ef/suites/{suiteId}/...` and `public/...`.
- `file-ref-validation`: test-case `data` accepts both dataset-shaped refs (validated against the test case's owning `datasetId`) and legacy suite-shaped refs (no ownership enforcement on legacy shape — these refs predate the split). Suite-level field validation (FormPartDto, typed constant bindings) is unchanged.

## Impact

- **Code**: new `DatasetFileController` (`web.controller`); new methods on `FileService` and `DialFileRefResolver` (`service.domain`); `FileRefValidator` updated to dispatch on ref shape; `ZipImportService` switched to the new builder; `DatasetService.delete` and `TestSuiteService.delete` post-commit blocks extended to call `FileService.deleteAllByDatasetId` when a dataset is removed.
- **API**: four new endpoints under `/api/v1/datasets/{datasetId}/files` (mirror the existing suite endpoints). No breaking changes. OpenAPI updated.
- **DB**: no schema change. No new migrations.
- **Config**: dataset-scoped quotas reuse `dial.file-storage.max-file-size-bytes` and a new `dial.file-storage.max-files-per-dataset` (parallel to `max-files-per-suite`); document the new property in `docs/configuration.md`.
- **Docs**: update `docs/patterns/dial-file-storage.md` to describe both folder conventions and the two reference shapes.
- **Latent bug fix**: `ZipImportService` produces correctly-scoped refs after the change.
- **Migration**: none — both ref shapes remain valid; existing data untouched.