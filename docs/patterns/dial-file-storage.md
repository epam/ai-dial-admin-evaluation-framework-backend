# DIAL Core File Storage (DialFileClient + DialFileRefResolver)

Binary file storage uses DIAL Core file storage API, accessed via a dedicated EF service API key. `DialFileClient` (`client.dialcore`) handles HTTP operations (upload/download/delete/list/metadata). `DialFileRefResolver` (`service.domain`) translates client-facing file references (with `@ef` alias) to real DIAL API paths.

**Bucket discovery**: Lazy initialization via `GET /v1/bucket`. Cached in `AtomicReference`; no failure caching (retries on next call). `DialFileStorageHealthIndicator` registered in `readiness` group only.

**Two-folder convention** — EF-managed files split by owner:

| Owner | Short ref (client-facing) | Real path (DialFileClient) | DIAL data ref (request payloads) |
|---|---|---|---|
| Dataset (test-case `data`) | `@ef/datasets/{datasetId}/{filename}` | `{realBucket}/datasets/{datasetId}/{filename}` | `files/{realBucket}/datasets/{datasetId}/{filename}` |
| Suite (`requestTemplate`, `argumentTemplate`, FormPartDto, `\|file` constant bindings) | `@ef/suites/{suiteId}/{filename}` | `{realBucket}/suites/{suiteId}/{filename}` | `files/{realBucket}/suites/{suiteId}/{filename}` |
| Public | `public/{path}/{filename}` | `public/{path}/{filename}` (unchanged) | `files/public/{path}/{filename}` |

`DialFileRefResolver` exposes `buildEfRef(suiteId, filename)` and `buildDatasetEfRef(datasetId, filename)` for constructing short refs; `resolveToRealPath` / `resolveToDialRef` are segment-agnostic and pass any whitelisted prefix through unchanged. Prefix whitelist: `@ef`, `public`. Inner `@ef/{segment}/` whitelist is enforced by `FileRefValidator` and limited to `suites/` and `datasets/`.

**REST endpoint families**:
- `POST/GET /api/v1/test-suites/{suiteId}/files` (+ `/{filename}` GET/DELETE) — suite-scoped uploads
- `POST/GET /api/v1/datasets/{datasetId}/files` (+ `/{filename}` GET/DELETE) — dataset-scoped uploads (use this for files referenced from test-case `data`)

**Ownership validation** (`FileRefValidator`):
- `validateDatasetOwnership(ref, datasetId)` — strict on `@ef/datasets/...`; pass-through on `@ef/suites/...` (legacy tolerance: test-case `data` may still carry suite-shaped refs from before the dataset split).
- `validateSuiteOwnership(ref, suiteId)` — strict on `@ef/suites/...`. Dataset-shaped refs in suite-level fields trigger a wrong-scope warning via `isDatasetShapedRef`.

**Lifecycle**:
- `FileController` / `DatasetFileController` → `FileService` → `DialFileClient` (proxy pattern).
- Suite delete: DB-first via `TransactionTemplate`, then post-commit best-effort DIAL cleanup via `FileService.deleteAllBySuiteId`. If the suite was bound to a PRIVATE dataset that is cascade-deleted in the same transaction, `deleteAllByDatasetId` also runs post-commit.
- Dataset delete (PUBLIC explicit / PRIVATE explicit): same post-commit pattern via `FileService.deleteAllByDatasetId`. PUBLIC datasets with dependents are blocked by FK RESTRICT, so files are never cleaned while a dataset is still referenced.

**Copy operations** (used by suite clone): both run **before** the DB transaction (DIAL I/O is non-transactional) and are best-effort — an inaccessible source file is logged and skipped, never failing the clone.
- `FileService.copyFilesBetweenSuites(sourceSuiteId, targetSuiteId)` — copies suite-scoped files for every clone.
- `FileService.copyFilesBetweenDatasets(sourceDatasetId, targetDatasetId)` — copies dataset-scoped files; invoked (via `DatasetCloneService.copyDatasetFiles`) only when a PRIVATE-dataset suite is cloned and its dataset is cloned too. On clone failure, the partially-copied target folders are cleaned best-effort in the `finally` (`deleteAllBySuiteId` + `deleteAllByDatasetId` when auto-cloning).

**Filename validation**: alphanumeric, `-`, `_`, `.`, ` `, `(`, `)`. Max 255 chars. Per-owner uniqueness and per-owner file-count cap (`max-files-per-suite`, `max-files-per-dataset`) enforced at upload time (check-then-upload — not strictly race-free; accepted for v1).

**ZIP I/O**:
- `ZipExportService` supports `materializeFiles` param (true: embed file bytes; false: raw DIAL paths in CSV).
- `ZipImportService` uploads imported files under `{efBucket}/datasets/{datasetId}/...` and rewrites CSV cells to the matching `@ef/datasets/...` short refs.

**Configuration**: `dial.file-storage.*` (api-key, bucket-alias, max-file-size-bytes, max-files-per-suite, max-files-per-dataset, timeouts).
