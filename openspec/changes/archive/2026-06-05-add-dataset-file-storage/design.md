# Design — Dataset-Scoped File Storage

## Context

Files in DIAL today live under a single per-suite folder `{efBucket}/suites/{suiteId}/{filename}`, and short refs are written as `@ef/suites/{suiteId}/{filename}`. The Dataset entity (V1.22) moved test-case ownership off the suite. Files referenced from `test_cases.data` belong to the dataset; files referenced from suite-level fields (FormPartDto, typed `|file` constant bindings in request/argument templates) belong to the suite. Continuing to write all files into the suite folder conflates the two ownership models and breaks two scenarios:

- **PUBLIC datasets bound to many suites.** A dataset can be referenced by multiple suites simultaneously. Its test-case files cannot live under any single suite's folder without arbitrary choice.
- **`ZipImportService` is already inconsistent.** It calls `dialFileRefResolver.buildEfRef(datasetId, filename)`, but `buildEfRef` hardcodes `"/suites/"`, so imported files land at `@ef/suites/{datasetUuid}/{filename}` — a dataset UUID nested under a `suites/` segment. This is a latent bug.

This change introduces a parallel dataset folder layout, a small additive API to manage files at that scope, and a validator that recognises both shapes.

## Goals

- Files referenced from test-case `data` live under `{efBucket}/datasets/{datasetId}/{filename}` and are referenced as `@ef/datasets/{datasetId}/{filename}`.
- Files referenced from suite-level fields keep their current location and shape.
- Dataset delete (PUBLIC explicit + PRIVATE cascade from suite delete) cleans up the dataset folder, mirroring the existing suite cascade.
- New code is additive — the existing suite API and existing suite-shaped refs continue to work unchanged.
- The `ZipImportService` bug is fixed in this change.

## Non-goals

- No backfill of existing refs or files. Test cases that still hold `@ef/suites/{suiteId}/{filename}` refs continue to work.
- No deprecation of the suite file API.
- No change to the snapshot phase, ZIP export materialisation, multipart serializer, or MCP request resolver — they all go through `DialFileRefResolver`, which is unchanged at the call-site level.
- No change to the suite clone behaviour. The clone still copies the suite's own files; the shared dataset's files do not need copying.

## Architecture

### Reference shapes (recap)

| Shape | Stored where | Owner |
|---|---|---|
| `@ef/datasets/{datasetId}/{filename}` (new) | `test_cases.data` JSONB | Dataset |
| `@ef/suites/{suiteId}/{filename}` (existing) | Suite-level fields (FormPartDto, typed constant bindings); also legacy values in `test_cases.data` | Suite (or legacy in `data`) |
| `public/...` (unchanged) | Anywhere a file ref is valid | DIAL public bucket |

### Component diagram (delta)

```
                  ┌──────────────────────────────┐
                  │      DialFileRefResolver     │
                  │  + buildDatasetEfRef(id,fn)  │
                  └──────────────┬───────────────┘
                                 │
            ┌────────────────────┴────────────────────┐
            ▼                                         ▼
   ┌──────────────────┐                       ┌──────────────────┐
   │ FileController   │                       │  DatasetFile     │
   │ (suite paths)    │                       │  Controller (new)│
   └────────┬─────────┘                       └─────────┬────────┘
            │                                            │
            ▼                                            ▼
   ┌────────────────────────────────────────────────────────────┐
   │                       FileService                          │
   │  - uploadToSuite / listBySuite / deleteAllBySuiteId        │
   │  - uploadToDataset / listByDataset / deleteAllByDatasetId  │
   │                  (new dataset-scoped ops)                  │
   └────────────────────────────┬───────────────────────────────┘
                                │
                                ▼
                       ┌──────────────────┐
                       │  DialFileClient  │  (unchanged)
                       └──────────────────┘
```

`DialFileClient` and `DialFileRefResolver.resolveToRealPath/resolveToDialRef` need no behavior change — they already pass through any path after a whitelisted prefix. Everything downstream of the resolver (ResolvedRequestService, MultipartFormDataRequestBodySerializer, McpRequestResolver, ZipExportService, SuiteSnapshotBuilder) keeps its current code.

### Validator dispatch

`FileRefValidator` gains two ownership entry points:

- `validateSuiteOwnership(ref, suiteId)` — strict on suite-shaped refs against `suiteId`; pass-through on dataset-shaped and `public` refs.
- `validateDatasetOwnership(ref, datasetId)` — strict on dataset-shaped refs against `datasetId`; pass-through on suite-shaped (legacy tolerance) and `public` refs.

Call sites:

- `TestCaseValidationService` → `validateDatasetOwnership(ref, datasetId)` (where `datasetId` is the test case's owning dataset id).
- `SuiteValidationService` (FormPartDto + typed constant bindings) → `validateSuiteOwnership(ref, suiteId)`. Suite-level fields that carry a dataset-shaped ref produce a warning indicating the wrong scope.

Format rules and placeholder skipping are unchanged.

### Cascade lifecycle

| Event | DB delete | File cleanup (post-commit, best-effort) |
|---|---|---|
| Suite delete (no PRIVATE dataset bound) | Suite + cascaded test cases | `FileService.deleteAllBySuiteId(suiteId)` (existing) |
| Suite delete (PRIVATE dataset bound) | Suite + unbind + dataset row | `deleteAllBySuiteId(suiteId)` **and** `deleteAllByDatasetId(datasetId)` |
| Dataset explicit delete — PUBLIC (no dependents) | Dataset + cascaded test cases | `deleteAllByDatasetId(datasetId)` |
| Dataset explicit delete — PRIVATE | Unbind suite + dataset row | `deleteAllByDatasetId(datasetId)` (the bound suite is not being deleted, so suite files are untouched) |

All file cleanup runs **outside** the transactional scope using the existing `TransactionTemplate` post-commit pattern. Per-file failures are logged at WARN with the exception object passed as the last SLF4J argument; nothing is rolled back.

## Decisions

- **No backfill.** Migrating existing refs and files is risky (PUBLIC datasets bound to multiple suites would need ambiguous source-folder choices) and not necessary for correctness — both shapes resolve correctly through the existing resolver. Cost is permanent tolerance in `FileRefValidator` for the suite shape in `data`, which is a few lines.
- **Per-dataset quota uses its own property.** Adding `dial.file-storage.max-files-per-dataset` (default 100) keeps the suite cap intact and lets ops tune dataset volumes independently. Shared `max-file-size-bytes` is reused.
- **Two controllers vs one.** A separate `DatasetFileController` (mirroring `FileController`) is cleaner than overloading the suite controller. The two are siblings — same DTO (`FileMetadataDto`), same validation, same `FileService` shared backend.
- **`FileRefValidator` API split.** Replacing the single `validate(ref, suiteId)` with two ownership methods makes call-site intent explicit and prevents accidental over-restriction. The format-only path (`validate(ref)`) remains as the shared prelude.
- **Latent bug fix lives in this change.** `ZipImportService` switches to `buildDatasetEfRef`. Doing this separately would create a confusing intermediate state where dataset imports land under the new folder but new uploads still use the old endpoint.
- **No new spec-level concept introduced.** This is additive to `dial-file-storage`. The dataset side gets its own spec (`dataset-file-storage`) so that capability sits next to its peer, but the resolver and validator deltas live in their existing capability specs.

## Risks & mitigations

- **PUBLIC dataset shared across suites lifecycle.** If a PUBLIC dataset is referenced by two suites and one suite is deleted, the dataset and its files must not be removed (FK RESTRICT on PUBLIC blocks this at the DB layer — already in place). The only path that cleans dataset files is dataset-row deletion, which RESTRICT prevents. Safe by construction.
- **Orphaned files on partial DIAL failures.** Already accepted for suites; same behavior here. No regression.
- **Validator regression on legacy refs.** Suite-shaped refs in `data` predate the split. The validator's ownership pass-through for that shape is intentional and tested.
- **Concurrent upload collisions.** Same check-then-upload race as suites today. Acceptable for v1.

## Test plan

- **Unit**
  - `DialFileRefResolverTest`: `buildDatasetEfRef`, plus `resolveToRealPath`/`resolveToDialRef` for the dataset shape.
  - `FileRefValidatorTest`: ownership pass-through for cross-shape refs; format rules unchanged.
- **Functional (`@PostgresFunctionalTests`)**
  - `DatasetFileControllerTests` — upload, list, download (streamed), delete, 404 on non-existent dataset/file, quota, filename validation, duplicate filename, size limit, dataset-scope path correctness in DIAL.
  - Test-case validation: dataset-shaped ref with mismatched dataset id → warning; suite-shaped ref in `data` → no warning.
  - Suite-level field validation: dataset-shaped ref in FormPartDto or `|file` constant → warning.
  - Dataset delete cleans files (PUBLIC explicit; PRIVATE cascade from suite delete) — assert via `DialFileClient.list()` after commit.
  - ZIP import (`POST /api/v1/datasets/{id}/test-cases/import`) writes files to `datasets/{id}/...` (latent bug regression test).
- **Verification**
  - `./gradlew test` clean.
  - `./gradlew checkstyleMain checkstyleTest` clean.
  - Manual: hit the four new endpoints via Swagger UI; confirm DIAL folder layout.

## Documentation

- `docs/configuration.md`: add `dial.file-storage.max-files-per-dataset` row (six columns per project rule).
- `docs/patterns/dial-file-storage.md`: extend to describe the two-folder convention and both reference shapes; update the example refs.
- OpenAPI: `@Schema` examples on `FileMetadataDto` updated; `@ExampleObject` files added for the new endpoints (request multipart + response).
- AGENTS.md Unique Patterns: the `Dataset Entity` and `DIAL Core File Storage` rows already point to pattern docs; no inline table change needed if the linked docs are updated.
