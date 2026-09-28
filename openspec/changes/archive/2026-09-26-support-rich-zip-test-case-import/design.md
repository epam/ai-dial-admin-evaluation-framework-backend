## Context

See proposal.md (Why / What Changes) for the motivation. This section keeps only the current state the design depends on.

**Import today** (`ZipImportService`, not transactional):
1. Unpacks the whole archive into memory: `ZipInputStream` → `Map<String, byte[]>`.
2. Uploads **every** `files/*` entry with `DialFileClient.upload`, a PUT that overwrites. Name uniqueness is checked only within the archive (`generateUniqueFilename`).
3. Rewrites the CSV **text** with `String.replace` plus the comma-only regex `files/\d+/[^,\n\r"]+`.
4. Calls `CsvImportService.importCsv`, which is `@Transactional("metaTransactionManager")`.

Uploads therefore happen outside the DB transaction and before it starts, and nothing is cleaned up when the import fails. Preview skips the rewrite, so FILE cells fail `FileRefValidator.validateFormat` (the `files` prefix is not allowed).

**Export today** (`ZipExportService.exportZip`):
- Collects **all** rows in memory (`collectRows`).
- Writes one row per case with no `turnIndex` and ignores `multiTurnData`.
- Copies every ref that passes `validateFormat` into the ZIP, `public/...` included, as `files/{row}/{field}/{name}`, once per row.
- Streams straight to `HttpServletResponse`. A download failure is logged and skipped, since by then the HTTP status is already sent.
- `CsvExportService` already handles multi-turn correctly (one row per turn, shared data merged in).

**Schema on import**:
- OVERRIDE (and an empty schema) rebuilds the schema through `CsvSchemaFieldBuilder.buildFromBindings`, using inferred types. `inferCellType` never returns `FILE`, and every field is `required=false`.
- When the CSV has any multi-turn case, every undeclared column becomes `perTurn=true` (`CsvImportService.java:335`, `sawMultiTurnCase ? allDataFieldNames`).
- So the CSV alone cannot reproduce a FILE type or which fields are shared.

**Constraints**:
- `FileService.uploadInternal` enforces the size limit (`dial.file-storage.max-file-size-bytes`), filename pattern, existence check and `max-files-per-dataset`. It takes only a `MultipartFile`.
- The `DialFileMetadataDto` returned by DIAL has only `contentLength`, with no hash or ETag.
- `DialCoreClientException` is already mapped to an HTTP status by `DefaultExceptionHandler`.
- `additionalRequests` is suite configuration (`TestSuiteRequestDto.java:71`). Test-case data has no request dimension.

## Goals / Non-Goals

**Goals:**
- Export → import round trips keep the case shape (single-turn and multi-turn) and the full field definitions, FILE type included.
- The import pipeline runs identically for preview and import, minus the uploads.
- Memory use is bounded no matter how large the archive is.
- Every upload runs the same checks as a normal dataset file upload.

**Non-Goals (design level, in addition to the proposal):**
- No Flyway migration and no new tables. Uploaded files are tracked only for the duration of the request.
- No new endpoints or request parameters; `materializeFiles`, `importMode`, `conflictStrategy`, `delimiter` and `If-Match` keep their meaning.
- No change to `CsvImportService`'s streaming, grouping, coercion or fixup logic beyond the new schema-hint entry point.
- No protection against two ZIP imports into the same dataset at the same moment (see Risks).

## Decisions

### D1. New package `service.domain.zip` with small injectable components

`ZipImportService` and `ZipExportService` stay as the entry points and become coordinators. New `@Component`s, each `@LogExecution` and each unit-testable on its own:

| Component | Responsibility |
|---|---|
| `ZipArchiveReader` | Opens a staged ZIP file as `ZipFile`. Normalises entry names: strips a single top-level folder and ignores `__MACOSX/` and directory entries. Rejects absolute paths, paths containing `..` or backslashes, duplicate names, and archives over the limits. Exposes the `test-cases.csv` bytes, the optional `manifest.json`, and file entries as `path → lazily opened stream`. |
| `ZipManifest` (record) + `ZipManifestSerializer` | `{"formatVersion":1,"testCaseSchema":[FieldDefinitionDto…],"files":[{"path":"files/1/report.pdf","sourceRef":"@ef/datasets/{id}/report.pdf"}]}`. `files` is optional on read. Serialization failures throw. On read, invalid JSON or an unsupported `formatVersion` → `ValidationException` (400). |
| `ZipCsvFileRefRewriter` | Parses the CSV with Commons CSV using the chosen delimiter and writes it back with `CSVPrinter`, applying a `path → ref` resolution to FILE cells only (D5). Returns the rewritten bytes, per-cell warnings, the set of columns that held archive paths, and the set of archive paths the CSV references. |
| `ZipFileImportPlanner` | Builds an `ImportFilePlan`: referenced archive path → target filename → future `@ef/datasets/{id}/{name}` ref. Uses one `FileService.listByDataset` call to mark each target as *new* or *overwrite* (D6). Makes no writes, so preview reuses it. |
| `TestCaseExportRowProjector` (in `service.domain.csv`, not `zip`) | Turns a `TestCase` into ordered `(turnIndex, Map data)` rows: shared data merged into every turn, a blank `turnIndex` for single-turn cases. Takes the logic out of `CsvExportService` and is shared by both exporters. It lives in the CSV package because `CsvExportService` uses it and the CSV package must not depend on the ZIP package. |
| `ZipImportColumnTypeResolver` | Decides, once per import, the type of each CSV data column for rewriting purposes (D5), using the import mode, the dataset schema and the manifest. It feeds both the rewriter and the `CsvImportSchemaHints`, so the rewrite and the stored schema never disagree. |
| `ZipImportUploadJournal` | Per-request (not a bean; created by `ZipImportService`). Records each DIAL write as *created* or *overwritten + temp backup*; `rollback()` restores backups and deletes created files, best effort; `close()` deletes backups. |
| `ZipExportFileCollector` | Classifies each FILE value during export (D3), numbers each distinct EF-owned ref, and returns the archive path for a cell. |

`FileService` gains `putDatasetFile(UUID datasetId, String filename, InputStream content, String contentType)`. It creates or overwrites the file. It wraps `content` in a limiting stream that throws once `max-file-size-bytes` is exceeded, before the PUT is sent (`DialFileClient.upload` reads the whole stream into memory, so the cap must apply to that read). It shares the size and filename checks in `uploadInternal`, which is refactored to take a filename, a stream and a size instead of a `MultipartFile`. The existing "already exists" check stays on the normal upload endpoint only. There is also `checkDatasetFileCapacity(UUID datasetId, int newFiles)`, where only *new* names count. The CSV and ZIP packages stay separate: `ZipImportService` depends on `CsvImportService`, never the other way round.

*Alternative:* keep everything inside the two services. Rejected: that is how the text-replace defects were introduced, and it breaks the AGENTS.md rule that conversion and validation logic go into injectable components.

### D2. Archive format v1

```
test-cases.csv       required; header: testCaseName,turnIndex,<fields…>
manifest.json        always written by export; optional on import; formatVersion + testCaseSchema + files[{path, sourceRef}]
files/{n}/{filename} one entry per distinct EF-owned ref, n = 1..N in first-seen export order
```

- `files/{n}/` keeps the original filename (what the user sees) and gives each ref its own folder, so two files named `report.pdf` from different sources can't clash.
- The path has no row, turn or field in it. A shared FILE field that appears on every turn row, or one file used by many cases, points at the same path. That fixes the shared-field conflict on multi-turn import and removes duplicate uploads.
- Import accepts **any** `files/...` path, so ZIPs in the old `files/{row}/{field}/{name}` layout and hand-made ZIPs keep working.

*Alternative:* the turn-aware `files/{case}/t{turn}/{field}/{name}`. Rejected: it still writes one copy per case for a shared ref and needs special handling for shared fields.

### D3. File reference rule

| Stored value | Export | Import |
|---|---|---|
| `public/...` | CSV cell = the ref as stored, no archive entry | Kept as is: no upload, never overwritten |
| `@ef/datasets/...` (any dataset), `@ef/suites/...` (legacy) | Bytes written to `files/{n}/{filename}`, cell = that path | Uploaded to `@ef/datasets/{targetId}/{filename}`; **a same-name file is overwritten** (the file name is the file's identity within a dataset, so re-importing an edited file makes the change take effect) |
| Blank, or an invalid format | Cell value unchanged (today's behaviour) | Unchanged; the validator reports it |

- Classification uses `DialFileRefResolver` prefixes (the bucket alias vs `public`), never string guesses.
- The import side needs no classification: only `files/...` cells are touched. Anything else is a ref the user or the export chose to keep.

### D4. Schema from the manifest, with a FILE-hint fallback

**Export always writes `manifest.json`** (D8), so every ZIP exported after this change carries its full schema. Import treats the manifest as optional only to keep two kinds of ZIP working: those exported before this change and hand-made ones (CSV + `files/`). The second column below applies only to them. Requiring the manifest was rejected: it would make both kinds fail with a 400.

`CsvImportService` gains an overload of `preview`/`importCsv` that takes `CsvImportSchemaHints { List<FieldDefinitionDto> declaredSchema; Set<String> fileColumns; }`. The existing signatures pass empty hints, so plain CSV behaviour is unchanged.

| Mode / dataset schema state | With a manifest | Without a manifest |
|---|---|---|
| OVERRIDE, or any mode with an empty schema | Schema = manifest fields as written (type, `perTurn`, `required`, `displayName`, `description`), plus any CSV column missing from the manifest, inferred as today. These definitions are used both for validation and when saving the schema. | Inferred as today, except columns in `fileColumns` are typed `FILE` |
| MERGE, non-empty schema | Fields already in the dataset win; new fields come from the manifest when listed there, otherwise they are inferred | New fields inferred; `fileColumns` → `FILE` |
| APPEND, non-empty schema | Manifest ignored | Hints ignored (the existing schema already decides types) |

- The hints plug into `buildValidationSchema`, `persistSchema`, `buildFinalSchema`/`buildAutoDetectedSchema` and `CsvSchemaFieldBuilder` (through a "declared" tier that takes priority over inference).
- Coercion keeps using `SchemaTypeCoercer` with the resolved types.
- Manifest fields that no CSV column mentions are still saved, so a field that is blank in every row is not dropped.

*Alternatives:*
- Type hints only: `perTurn`, `required` and `displayName` would still be lost.
- A schema header row inside the CSV: breaks every CSV tool.
- Inferring `FILE` from `@ef/`-prefixed values in plain CSVs: tempting, but it changes plain CSV behaviour, so it is out of scope.

### D5. Rewriting cell by cell

`ZipImportColumnTypeResolver` decides each data column's type for rewriting purposes, per import mode:

| Mode / dataset schema | Column type source | Columns never rewritten |
|---|---|---|
| OVERRIDE, or any mode with an empty schema | manifest type; otherwise untyped. The dataset's current types are **ignored**, because the schema is about to be replaced. | — |
| MERGE, non-empty schema | dataset type; then manifest type; otherwise untyped | — |
| APPEND, non-empty schema | dataset type only | columns missing from the dataset schema: `CsvImportService.parseRow` drops them (`CsvImportService.java:1261`), so their files must not be uploaded |

A cell is rewritten only when **all** of these hold:
- its whole value, trimmed, starts with `files/`;
- its column's resolved type is `FILE` or untyped, and the column is not excluded above;
- the column is not `testCaseName` or `turnIndex`.

Results:
- **Path in the archive:** the cell becomes the planned `@ef/datasets/{id}/{name}` ref.
- **Path not in the archive:** the cell becomes blank and a warning is added with the row number, the header name, and the message `File missing from archive: <path>`. `CsvCellParser.parseCell` stores a blank cell as `""` (never null), and validation treats a blank FILE value as absent (`TestCaseValidationService.java:152,370`). Row numbers follow the CSV import convention (header = row 1, first data row = row 2), so rewriter and import warnings line up.
- **Any other cell, including text that merely contains `files/...` in a STRING column:** left byte-for-byte as it is.

The rewritten CSV is kept in memory. Rewritten refs are longer than the `files/…` paths they replace, so the rewritten CSV can exceed `csv.import.max-file-size` even when the original didn't. The rewritten size and the data row count (against `csv.import.max-rows`) are therefore checked right after the rewrite, before any DIAL write (D6 step 6).

*Alternative:* a regex anchored to cell boundaries. Rejected: correct quoting and delimiter handling needs a real parser, and Commons CSV is already used.

### D6. Upload naming, overwrite, limits and restore (import flow and transaction boundaries)

```
controller ─ transferTo(temp file) ─> ZipImportService.importZip
  1. ZipArchiveReader.open(temp)            → limits, entry checks            (400 on error)
  2. manifest = serializer.read(...)        → optional                        (400 on bad JSON/version)
  3. rewriter.scan(csv)                     → referenced paths, fileColumns
  3a. typeResolver.resolve(mode, datasetSchema, manifest) → column types for the rewrite and the hints
  4. planner.plan(datasetId, referenced, manifest.files) → one listByDataset; final names; new vs overwrite; capacity check on new (400)
  5. rewriter.rewrite(csv, plan)            → rewritten CSV + cell warnings
  6. pre-write checks: rewritten CSV size ≤ csv.import.max-file-size, row count ≤ csv.import.max-rows (400),
     If-Match against the dataset version read via the dataset domain service (409) — before any DIAL write
  7. for each planned entry:
       overwrite → download the current file (with its content type from the listing) to a temp backup;
                   if the backup fails, abort here (nothing is overwritten); then fileService.putDatasetFile(...)
       new       → fileService.putDatasetFile(...)
     record each write (created | overwritten + backup + content type) in a ZipImportUploadJournal
  8. csvImportService.importCsv(..., hints) → @Transactional(meta): its own tx, commits on return
  9. on RuntimeException in 7 or 8: journal.rollback() — restore each backup with its original content type,
     delete each created file (best effort, log.warn(..., e)), rethrow
  finally: close ZipFile, delete temp file and backups
```

- **Names:** the sanitized original filename (`sanitizeFilename` kept). **A name already in the target dataset is overwritten** (D3).
- **Same name, different entries:** when two archive entries of one import end up with the same name, one keeps it and the others get a suffix. Priority for keeping the name:
  1. the entry whose manifest `sourceRef` is the target dataset's own file with that name (`@ef/datasets/{targetId}/{name}`);
  2. otherwise the entry with the lowest `n` (archive order).

  This stops an old suite file or another dataset's file from taking over, and overwriting, the dataset's own file just because it came first in the export. Without a manifest `files` list, archive order decides.
- **Suffixes never collide:** a suffix `_1`, `_2`, … is the first one that is neither in the dataset listing nor already planned in this import. A suffixed name therefore always creates a new file. Only an entry's own sanitized original name can overwrite.
- **Cleaned-up names can overwrite:** a name produced by cleanup (`report#1.pdf` → `report_1.pdf`) is treated as the entry's original name and overwrites a same-name file, per D3. This is covered by the spec and a test.
- **Final names decide:** the planner marks *new* vs *overwrite* and counts capacity using final names (after suffixing), never pre-suffix names.
- **Repeatability:** priority and suffix choice depend only on the archive, the manifest and the dataset listing, so repeated round trips of the same archive into the same dataset produce the same names.
- **Limits:**
  - capacity = listing size + planned *new* names ≤ `max-files-per-dataset`, checked once in step 4 before any upload (overwrites don't add files);
  - two stages for sizes:
    1. **Before any write** (step 1): the entry count, the sum of entry-header sizes against `max-total-uncompressed-size`, the header size of each *referenced* entry against `max-file-size-bytes`, and the CSV's header size. A violation returns 400 with nothing written.
    2. **While reading:** every entry stream (CSV, manifest, files) is wrapped in a limiting stream that counts real bytes against the same caps and a running total. A file entry that lies in its header fails during step 7 and goes through the journal rollback. The CSV and manifest are read fully in steps 1–2, before any write.
  - Unreferenced entries are never read, so only their header sizes count toward the total.
- **Unreferenced entries** are never uploaded.
- **Why upload before the transaction:** saved test cases must never point at files that don't exist. The opposite order (import, then upload) would commit refs to missing files whenever an upload fails, and a DB transaction must not stay open across DIAL HTTP calls.
- **Why back up before overwriting:** a failed import (e.g. 409 from `conflictStrategy=FAIL`, a row limit, validation) must not leave existing cases pointing at replaced content while their DB rows roll back. A backup is only taken for names being overwritten, is bounded by `max-file-size-bytes` each, and lives in a temp file for the request only. *Alternative:* accept the changed content on failure. Rejected: it makes a rejected import partly applied. *Alternative:* compare content and skip unchanged files. Rejected in favour of simple overwrite: the result is the same when the content matches, and DIAL gives no hash, so every compare would need a download anyway.
- **If-Match pre-check (step 6):** the version check inside `importCsv` stays authoritative. The early check only avoids DIAL writes for the most common failure.
- **Uploads run one after another:** the per-dataset file limit is small (100), and ordering keeps cleanup simple.

### D7. Preview matches import

`previewZip` runs steps 1–6 without the If-Match check (preview takes no `If-Match`; step 4 makes one read-only DIAL listing), then `csvImportService.preview(..., hints)` on the rewritten CSV, and merges the rewriter's warnings.

- FILE cells in `sampleRows` show the future `@ef/datasets/{id}/{name}` refs, so validation (format, ownership, required) matches the import.
- Preview doesn't say which files would be overwritten. Refs are the same either way, and the import result stays the source of truth. If the FE wants to show it later, the plan already has that flag.

### D8. Export builds a temp file, then streams it

`ZipExportService.exportZip(datasetId, filter, delimiter, OutputStream)` is replaced by `buildZip(datasetId, filter, delimiter)`, which returns an `AutoCloseable` handle to a finished temp ZIP (`transferTo(OutputStream)`, `close()` deletes it). The controller sets the ZIP headers and streams the handle only after `buildZip` returns:
1. Writes the ZIP to a temp file.
   - Pages through test cases (`csvExportProperties.pageSize`).
   - For each case, `TestCaseExportRowProjector` produces rows and `ZipExportFileCollector` maps each FILE cell.
   - The first time an EF-owned ref appears, its bytes are streamed into `files/{n}/{filename}` right away with `dialFileClient.downloadTo`.
   - CSV rows go to a second temp file.
   - `test-cases.csv` and `manifest.json` are added last. The manifest is **always** written, from `datasetSchemaProvider.getSchema`, whether or not any file was copied, and its `files` list pairs each `files/{n}/{filename}` with the ref it was downloaded from. `ZipFile` reads the central directory, so entry order doesn't matter.
   - Memory holds only the `ref → n` map.
2. Only after the ZIP is complete does the controller set headers and copy it to the response stream. Temp files are deleted when the handle closes (try-with-resources in the controller) or when `buildZip` fails.

**A download failure throws.** A `DialCoreClientException` goes through `DefaultExceptionHandler` (for example, a 404 in DIAL becomes the mapped status). A message naming the ref is added. Because nothing has been written to the response yet, the client gets a real error status instead of a truncated ZIP. Because headers are set only after `buildZip` returns, an error response isn't sent with `Content-Type: application/zip`.

`CsvExportService` uses the same projector. Its output is unchanged, and existing CSV export tests guard that.

*Alternatives:*
- Check every file's metadata before streaming: still fails in the window between the check and the download, and doubles DIAL calls.
- Keep streaming directly and put an error marker inside the ZIP: clients can't detect it.

### D9. ZIP detection

`TestCaseController.isZipFile` returns true if the extension is `.zip`, the MIME type is `application/zip` or `application/x-zip-compressed`, **or** the first 4 bytes are `PK\3\4`. The bytes are read with `ZipImportService.isZipArchive` over a `BufferedInputStream` from `file.getInputStream()`.

### D10. Configuration

The only new properties (defaults in `application.yml`, rows in `docs/configuration.md`, class `CsvImportProperties` gains a nested `zip` block with `@Validated` and `@Min(1)`):

| Property | Env | Default |
|---|---|---|
| `csv.import.zip.max-entries` | `CSV_IMPORT_ZIP_MAX_ENTRIES` | `1000` |
| `csv.import.zip.max-total-uncompressed-size` | `CSV_IMPORT_ZIP_MAX_TOTAL_UNCOMPRESSED_SIZE` | `1GB` |

The per-entry size reuses `dial.file-storage.max-file-size-bytes`, and the CSV entry reuses `csv.import.max-file-size`. No duplicate limits.

### D11. Error handling summary

| Condition | Result |
|---|---|
| Missing `test-cases.csv`; invalid entry path; duplicate entry; over entry-count or header-size limit; bad manifest; not enough file capacity; rewritten CSV over size or row limit | 400 `ValidationException`, nothing uploaded |
| A file entry's real size exceeds the cap while it is read (its header lied) | 400; journal rollback of earlier writes |
| Backup of a file about to be overwritten fails | Original error rethrown; journal rollback; that file is not overwritten |
| Referenced file missing from the archive | Cell stored blank (`""`) + warning (row, column); import continues |
| If-Match mismatch detected before uploads | 409, no DIAL writes |
| DIAL upload fails; import 409 (conflict strategy / If-Match race); any other import exception | Journal rollback: overwritten files restored from backup, created files deleted (best effort), original exception rethrown |
| Export download fails | Mapped DIAL error status, no ZIP sent |

Every `catch` passes the exception as the last SLF4J argument; specific exception types are caught (`IOException`, `DialCoreClientException`, `RuntimeException` only at the cleanup boundary, where it is always rethrown).

### D12. Data model and API contract

- **Data model:** none. No Flyway migration, no table or column changes, and `docs/database-schema.md` is not touched. Stored FILE values keep the existing ref formats (`@ef/datasets/{id}/{name}`, `public/...`).
- **Endpoints:** unchanged. `POST /api/v1/datasets/{datasetId}/test-cases/import`, `…/import/preview` and `GET …/test-cases/export.csv` keep their paths, parameters and response DTOs (`CsvImportResultDto`, `CsvImportPreviewDto`).
- **Contract changes:**
  - ZIP export (still produced only for datasets with FILE fields when `materializeFiles` is not `false`): always has a `turnIndex` column, `manifest.json` (including the `files` source list) and one `files/{n}/{filename}` entry per distinct EF-owned ref. `public/...` refs appear as-is in the CSV. A failed file download now returns the mapped DIAL error status instead of a partial ZIP.
  - ZIP import: 400 for archive limit, entry and manifest errors; missing-file warnings carry `rowNumber` (CSV import convention: header = row 1) and `columnName`. A same-name dataset file is overwritten.
  - ZIP preview: FILE cells in `sampleRows` show the future `@ef/datasets/{id}/{name}` refs instead of `files/...` paths.
- **OpenAPI:** the operation descriptions for import, preview and export are updated. Examples are added under `src/main/resources/openapi/examples/` for the import result with a missing-file warning.

### D13. Multi-request suites

No change to the archive or the code. Tests prove that a ZIP-imported dataset with shared and per-turn FILE fields runs under a suite with `additionalRequests`, where each request binds a different per-turn field, and yields the expected `(request_index, turn_index)` rows. See proposal Non-goals for importing suite configuration.

## Risks / Trade-offs

- **[Same-name overwrite replaces a file that other cases still use]** → intended: the file name decides, so an edit applies to every case referencing it (APPEND/MERGE included). Documented in the spec and in the OpenAPI description of the import endpoint.
- **[Two imports into the same dataset at once overwrite each other's files]** → last write wins, same as the DB side. The dataset version/If-Match discourages concurrent imports. Accepted.
- **[Rollback fails (restore or delete)]** → `log.warn` with the ref and the exception. A file may be left with new content or orphaned. It stays visible in `GET …/datasets/{id}/files`.
- **[A failed import after a restore still had changed content for a short time]** → runs that start during the import may see it. Accepted: an import changing a dataset during a run has the same effect on the DB rows.
- **[Temp disk use on export: up to about the number of dataset files × `max-file-size-bytes`]** → bounded by the existing file limits (100 × 50MB). Temp files are deleted in `finally`.
- **[Export now fails where it used to silently produce a partial ZIP]** → deliberate (proposal); the error names the ref.
- **[Old ZIPs without a manifest]** → FILE hints keep the FILE type. `perTurn`/`required` fall back to today's inference, which is a documented limitation covered by a test.
- **[Repeated OVERRIDE round trips into the same dataset]** → same names are overwritten, so the file count stays stable once every EF-owned ref points at the target dataset. The first round trip of an old suite ref or another dataset's ref copies it into the target and adds a file. Files no longer referenced after an OVERRIDE are kept (proposal Non-goal).
- **[Filtered export + APPEND import into the same dataset overwrites a file that excluded cases also reference]** → intended under D3: the file name identifies the file within the dataset, so the change applies to every case that references it.
- **[Cleaned-up name overwrites an unrelated existing file]** (`report#1.pdf` → `report_1.pdf` when `report_1.pdf` exists) → accepted under D3; documented in the spec. Suffixes never overwrite.
- **[Memory per upload up to `max-file-size-bytes`]** because `DialFileClient.upload` reads the whole stream → bounded by the limiting stream and by uploads running one after another.
- **[Deleting uploads after a failure is a compensation, not a real transaction]** → acceptable: each file is referenced only by rows that did not commit.

## Migration Plan

- Backend only, no DB migration. Deploy normally.
- ZIPs exported before this change import through the old-path and no-manifest fallbacks.
- ZIPs exported after this change import on older backends only partially: the old regex handles `files/{n}/…` paths, but `turnIndex` and the manifest are ignored. Acceptable, since backends are not downgraded in practice.
- Rollback = revert. Nothing is stored in a new format.
- The FE handoff notes three contract changes:
  - preview FILE cells show `@ef/datasets/...` refs;
  - ZIP export can now return an error status;
  - ZIP export always has a `turnIndex` column and `manifest.json`.

## Open Questions

- Default for `csv.import.zip.max-total-uncompressed-size` (1GB proposed). It can be tuned in config without changing the spec or the tasks.
