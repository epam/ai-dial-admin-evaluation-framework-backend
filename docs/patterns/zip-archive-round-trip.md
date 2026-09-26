# ZIP Archive Round Trip (test-case import/export)

Dataset test cases with FILE fields export as a ZIP and import back without losing turns, field definitions or files. Code: `service.domain.zip` (+ `ZipImportService` / `ZipExportService` coordinators). Spec: [test-case-zip-archive](../../openspec/specs/test-case-zip-archive/spec.md).

**Archive v1**

```
test-cases.csv        testCaseName,turnIndex,<fields…>  (one row per turn, same as CSV export)
manifest.json         {formatVersion:1, testCaseSchema:[FieldDefinitionDto…], files:[{path, sourceRef, contentType?}]}
files/{n}/{filename}  one entry per distinct EF-owned ref, n = first-seen order
```

Export always writes the manifest; import treats it as optional (legacy / hand-made ZIPs) and falls back to typing columns that held `files/…` paths as `FILE`.

**File reference rule** (same both ways):

| Stored value | Export | Import |
|---|---|---|
| `public/...` | CSV cell verbatim, no entry | kept verbatim, never uploaded/overwritten |
| `@ef/datasets/*`, legacy `@ef/suites/*` | bytes → `files/{n}/{name}` | uploaded to `@ef/datasets/{targetId}/{name}`; **same name overwrites** |

- Filename = identity within a dataset. Suffixes (`_1`, …) only when two *different* entries of one import share a name; a suffix never collides with the listing or another planned name. The entry whose manifest `sourceRef` is the target's own file keeps the name, else lowest `n`.
- Only whole-cell trimmed `files/…` values in FILE/untyped columns are rewritten (Commons CSV in the shared `CsvFormats.forImport` dialect, never text replace). Column types come from `ZipImportColumnTypeResolver`, a thin adapter over `ResolvedSchemaHints` (OVERRIDE/empty: manifest; MERGE: dataset then manifest; APPEND: dataset only, undeclared columns excluded). Missing entry → blank cell + warning (header = row 1).

**Manifest precedence** — one owner: `ResolvedSchemaHints.resolve(mode, datasetSchema, CsvImportSchemaHints)` computes the *driving* manifest fields once per import; `CsvImportService` (parse types, raw-text parsing, persist gate), `CsvSchemaFieldBuilder` tier 0 and the ZIP rewrite resolver all read it, never the mode table directly: OVERRIDE/empty schema uses manifest fields verbatim (type, `perTurn`, `required`, `displayName`, `description`) plus inferred unlisted columns; MERGE takes only new fields from it; APPEND ignores it. Post-persist fixup coerces to the *persisted* types, not the inferred ones.

**Import flow & safety** (`ZipImportService.importZip`):
1. The controller stages the upload to a temp file it owns (`StagedUpload`, try-with-resources) → `ZipArchiveReader` (`ZipFile`, strip one top folder, ignore `__MACOSX/`, reject bad/duplicate paths, entry count, header sizes) — 400, nothing written.
2. scan → resolve types → `validateReferencedEntries` → `ZipFileImportPlanner` (one `listByDataset`, capacity on new names) → rewrite → rewritten CSV size/rows + If-Match pre-check.
3. Uploads **before** the meta transaction (never hold a tx across DIAL HTTP): overwrite → backup first (failed backup aborts), then `FileService.putDatasetFile` (limiting stream, same rules as the upload endpoint except overwrite). Content type: the manifest entry's `contentType` when it parses as a media type, else guessed from the filename.
4. `importCsv(…, hints)`; on `RuntimeException` → `ZipImportUploadJournal.rollback()` (restore backups with content type, delete created; best effort) and rethrow. `finally` deletes backup temp files; the staged upload is the controller's to delete.

Every stream is capped on real bytes (`LimitingInputStream`) plus a running total, since entry headers can lie.

Preview runs the same pipeline without writes or If-Match, so FILE cells show the future `@ef/datasets/…` refs.

**Export**: `ZipExportService.buildZip` builds a temp ZIP (paged, `TestCaseExportRowProjector` shared with CSV export, each ref downloaded once and its response `Content-Type` recorded in the manifest) and returns an `AutoCloseable` handle; the controller sets `application/zip` headers only after it succeeds. A download failure throws (mapped DIAL error status), never a partial ZIP.

**Out of scope**: suite config (`additionalRequests`) is not in the archive; unreferenced files are not deleted after OVERRIDE; no content-hash compare.
