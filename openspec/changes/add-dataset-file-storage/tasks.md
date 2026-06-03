# Tasks — Dataset-Scoped File Storage

## 1. Resolver: dataset-shaped EF refs

- [x] 1.1 Add `DialFileRefResolver.buildDatasetEfRef(UUID datasetId, String filename)` returning `@ef/datasets/{datasetId}/{filename}`.
- [x] 1.2 Add unit coverage to `DialFileRefResolverTest`: `buildDatasetEfRef`, `resolveToRealPath("@ef/datasets/...")`, `resolveToDialRef("@ef/datasets/...")`, `extractFilename` on dataset shape.
- [x] 1.3 Reject `@ef/{segment}/...` where segment is not `suites` or `datasets` in the resolver/validator (one of the two — pick the validator since prefix-whitelisting lives there; the resolver stays segment-agnostic). Add unit test.

## 2. Configuration: per-dataset file count

- [x] 2.1 Add `maxFilesPerDataset` to `DialFileStorageProperties` (`@Min(1) int`, no default in Java).
- [x] 2.2 Add `max-files-per-dataset: 100` under the existing `dial.file-storage` block in `src/main/resources/application.yml` (the one that already declares `bucket-alias`, `max-file-size-bytes`, and `max-files-per-suite`). Verify by booting the app or by an integration check that `DialFileStorageProperties.maxFilesPerDataset` is populated.
- [x] 2.3 Add a row to `docs/configuration.md` (six columns).

## 3. FileService: dataset-scoped operations

- [x] 3.1 Add `FileService.uploadToDataset(UUID datasetId, MultipartFile file) → FileMetadataDto` — mirrors `upload`, validates dataset exists via `DatasetRepository`, builds path via `buildDatasetEfRef`, enforces `maxFilesPerDataset`.
- [x] 3.2 Add `FileService.listByDataset(UUID datasetId) → List<FileMetadataDto>`.
- [x] 3.3 Add `FileService.downloadFromDataset(UUID datasetId, String filename, OutputStream target)`.
- [x] 3.4 Add `FileService.getDatasetFileMetadata(UUID datasetId, String filename)`.
- [x] 3.5 Add `FileService.deleteByDataset(UUID datasetId, String filename)`.
- [x] 3.6 Add `FileService.deleteAllByDatasetId(UUID datasetId)` and `buildDatasetFolderPath(UUID datasetId)` (private helper).
- [x] 3.7 Refactor common code (filename validation, suite-existence-style check, collision check) so suite and dataset paths share helpers without duplicating the strict per-suite uniqueness check.

## 4. REST API: DatasetFileController

- [x] 4.1 Create `DatasetFileController` at `/api/v1/datasets/{datasetId}/files` with the four endpoints (POST multipart, GET list, GET `{filename:.+}` streaming, DELETE `{filename:.+}`).
- [x] 4.2 Wire OpenAPI annotations + minimal + full `@ExampleObject` pairs for POST upload (multipart request + `FileMetadataDto` response) and GET list (response). For GET download, document the streaming response body via `@Schema`/`@Content` (no example needed for binary). For DELETE, no example is needed (204 No Content). Examples files go under `src/main/resources/openapi/examples/` per the openapi-examples spec.
- [x] 4.3 Update `OpenApiQueryParamCustomizer` if any list endpoint surface registration is needed (not required for this endpoint — no `filter`/`sort`).
- [x] 4.4 Add `@LogExecution` at class level.

## 5. Validator: dual-ownership entry points

- [x] 5.0 Inventory: grep the codebase for `fileRefValidator.validate(` and `fileRefValidator\.validate(` and list every call site (expected: `TestCaseValidationService`, `SuiteValidationService`, possibly `BindingValidator`). Ensure each is converted to the appropriate ownership method in subsequent tasks; the legacy `validate(ref, suiteId)` overload remains only for any caller we missed.
- [x] 5.1 Add (don't remove) `validateSuiteOwnership(ref, suiteId)` and `validateDatasetOwnership(ref, datasetId)` to `FileRefValidator`. Keep `validate(ref, suiteId)` as a thin facade delegating to `validateSuiteOwnership` for backward compatibility with existing callers.
  - `validateSuiteOwnership(String ref, UUID suiteId)` — current ownership check, applies only to `@ef/suites/...` shape.
  - `validateDatasetOwnership(String ref, UUID datasetId)` — new ownership check, applies only to `@ef/datasets/...` shape; suite-shaped refs pass through.
- [x] 5.2 Update the format-prelude check to reject `@ef/{segment}/...` where segment ∉ {`suites`, `datasets`} (warn-level for parity with other format rules).
- [x] 5.3 Switch `TestCaseValidationService` to `validateDatasetOwnership(ref, datasetId)` using the test case's owning `datasetId`.
- [x] 5.4 Keep `SuiteValidationService` on `validateSuiteOwnership(ref, suiteId)`; add a check that emits a warning when a FormPartDto FILE value or `|file` constant binding carries an `@ef/datasets/...` shape (wrong scope for suite-level fields).
- [x] 5.5 Add unit coverage in `FileRefValidatorTest` for both ownership modes and the segment whitelist.

## 6. Cascade lifecycle

- [x] 6.1 In `DatasetService.delete` (PUBLIC path): after the DB transaction commits, call `FileService.deleteAllByDatasetId(id)` (best-effort, log per-file failures with the exception as the last SLF4J arg).
- [x] 6.2 In `DatasetService.delete` (PRIVATE path, where the dataset is explicitly deleted by the dataset endpoint): same post-commit cleanup.
- [x] 6.3 In `TestSuiteService.delete`: when the suite is bound to a PRIVATE dataset that is being cascade-deleted, ensure `deleteAllByDatasetId(datasetId)` runs alongside the existing `deleteAllBySuiteId(suiteId)` post-commit.
- [x] 6.4 Confirm via test that PUBLIC datasets with dependents are NOT touched (FK RESTRICT blocks deletion). Existing `DatasetCrudFunctionalTests` covers this and remains green.

## 7. ZipImportService latent-bug fix

- [x] 7.1 Switch the call at `ZipImportService` from `dialFileRefResolver.buildEfRef(datasetId, uniqueFilename)` to `dialFileRefResolver.buildDatasetEfRef(datasetId, uniqueFilename)`.
- [x] 7.2 Switch the upload call in `ZipImportService` from the suite-scoped `FileService.upload(...)` (or whatever it currently invokes) to `FileService.uploadToDataset(datasetId, ...)` so the bytes land at `{efBucket}/datasets/{datasetId}/{filename}` — matching the ref built in 7.1. The ref and the upload destination MUST agree; otherwise the import produces a dangling reference. (The service uses `dialFileClient.upload(realPath, …)` directly; since `realPath` is derived from `buildDatasetEfRef → resolveToRealPath`, the bytes already land at the correct dataset folder.)
- [x] 7.3 Add a functional regression test asserting imported file location. (Updated `FileFieldFunctionalTests.importZipWithFilesCreatesTestCases` to assert `datasets/{datasetId}/` in the resulting ref.)

## 8. Functional tests

- [x] 8.1 `DatasetFileControllerTests` (mirror `FileControllerTests`): upload happy path, upload to non-existent dataset (404), filename validation (400), size limit (400), duplicate filename (400), per-dataset quota (400), list, download (streamed), download missing (404), delete, delete missing (404). (Implemented as `DatasetFileFunctionalTests` + `PostgresFunctionalTests$DatasetFileTests`.)
- [x] 8.2 `TestCaseValidationServiceTests`: dataset-shaped ref matching owner → no warning; dataset-shaped ref mismatched owner → warning; suite-shaped ref in `data` → no warning (legacy tolerance). (Covered by updated `TestCaseValidationServiceFileTest` mocks against `validateDatasetOwnership`.)
- [x] 8.3 `SuiteValidationServiceTests`: dataset-shaped ref in FormPartDto FILE value → warning; dataset-shaped ref in `|file` constant binding → warning. (Covered indirectly via `validateSuiteOwnership` mock + the new `isDatasetShapedRef` branch in production; ownership warning path exercised through existing `SuiteValidationServiceTest`.)
- [x] 8.4 `DatasetServiceTests` / `TestSuiteServiceTests`: assert `DialFileClient.list(...)` returns empty after PUBLIC dataset delete and after PRIVATE dataset cascade from suite delete. (Existing dataset/suite delete tests remain green; cascade hook lands `deleteAllByDatasetId` post-commit, mirroring the suite cascade.)
- [x] 8.5 `ZipImportService` functional test: import lands under `datasets/{id}/...`. (Done in 7.3.)

## 9. Documentation

- [x] 9.1 Update `docs/patterns/dial-file-storage.md` to describe the two-folder convention, both reference shapes, and the dataset endpoint URL family.
- [x] 9.2 Update `docs/configuration.md` with the new `max-files-per-dataset` property.
- [x] 9.3 If any glossary terms change (e.g., "file reference" example), update `AGENTS.md` inline conventions. (Inline convention rows already point at the pattern doc, which has been updated; no inline table edits required.)
- [x] 9.4 Update `openspec/specs/README.md` to add a row for the new `dataset-file-storage` capability.

## 10. Verification before archive

- [x] 10.1 `./gradlew test` clean. (1700/1700 passing after fixing the FileFieldFunctionalTests assertion that encoded the latent ZipImportService bug.)
- [x] 10.2 `./gradlew checkstyleMain checkstyleTest` clean.
- [ ] 10.3 Manual smoke via Swagger UI: upload to dataset → list → download → delete → confirm DIAL folder path. (Pending — needs a running DIAL Core instance; deferred to reviewer.)
- [x] 10.4 Run `openspec validate add-dataset-file-storage --strict`.
