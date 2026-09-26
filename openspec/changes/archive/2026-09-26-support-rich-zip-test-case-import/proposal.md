## Why

ZIP import/export of test cases has fallen behind CSV import/export. A ZIP round trip silently turns multi-turn cases into single-turn ones. It also retypes FILE fields as STRING, so the dataset's file handling is lost after one re-import. The import path itself is unsafe: it rewrites raw CSV text, uploads files without the normal checks, and leaves files behind (or already-overwritten files changed) when an import fails. Users now build multi-turn, multi-request suites with FILE inputs, and ZIP is the only format that carries those files, so the gaps block real datasets from being moved or restored.

## What Changes

**Current state (Implemented)**:
- `ZipImportService` passes the CSV to `CsvImportService`, so a hand-made ZIP with `turnIndex` already imports multi-turn cases.
- Everything else below is **Planned**.

**Archive format**
- `test-cases.csv` stays the canonical file: `testCaseName,turnIndex,<fields…>`.
- New versioned **`manifest.json`** carrying the full dataset `testCaseSchema` (type, `perTurn`, `required`, `displayName`, `description`).
  - **Export: always written.** Every ZIP export includes it, even when no file ends up in the archive. It also lists each archive file with the ref it came from, so import can keep the target dataset's own filenames. When a ZIP (rather than a CSV) is produced is unchanged: only for datasets with FILE fields and `materializeFiles` not `false`.
  - **Import: used when present.** It decides the schema for OVERRIDE / empty-schema imports. MERGE takes only new fields from it, and APPEND ignores it.
  - **Import without a manifest** (ZIPs exported before this change, hand-made ZIPs): types are guessed from column values as today, except a column whose cells were archive file paths is typed `FILE` instead of `STRING`. A manifest is not required, because that would break both kinds of ZIP.
- **One archive entry per distinct file ref.** Path `files/{n}/{filename}`, where `n` numbers each distinct ref in export order. Every cell holding that ref (other rows, other turns, shared or per-turn fields) points at the same path. This removes duplicate uploads and shared-field conflicts across turns. Paths in the old `files/{row}/{field}/{name}` layout (and any other `files/...` path) still import.
- Defined behaviour for bad entries:
  - missing file: warning with the real row and field; the value is stored empty, which validation treats as absent
  - duplicate or invalid entry path: 400
  - unreferenced entry: ignored and not uploaded
  - single top-level folder: accepted
  - `__MACOSX/`: ignored

**File reference rule (the same for export and import)**
- **Public files (`public/...`) are shared and never copied.** Export writes the ref into the CSV exactly as stored, with no archive entry. Import leaves it as-is: no upload, never overwritten, always reused. Today export downloads public files into the archive, and import turns them into private dataset copies.
- **Files owned by EF (`@ef/datasets/...`, legacy `@ef/suites/...`) are always copied and re-uploaded.** Export puts the file bytes into the archive. Import uploads each one into the target dataset (`@ef/datasets/{targetId}/{filename}`), whether that dataset is new or already exists. **The filename decides: a file with the same name in the target dataset is overwritten.** If the content did not change in transit, nothing changes. If someone edits a file and re-imports, the new content takes effect for every case that references it. Each archive entry is uploaded once and all cells pointing at it get the same ref. When two *different* archive entries have the same filename (e.g. a dataset file and a legacy suite file both named `report.pdf`), the file that already belongs to the target dataset keeps the name and the other gets a suffix. The suffix is always a name that doesn't exist yet in the dataset, so one import never overwrites its own uploads or an unrelated file.

**Export**
- ZIP export writes one row per turn with `turnIndex`, exactly like CSV export. The row-building logic moves into one component shared by both exports.
- Each distinct file ref is written to the archive once, however many cells use it (currently one copy per row).
- Export processes test cases page by page instead of loading them all into memory.
- Export now **fails** (behaviour change) if a referenced file cannot be downloaded from DIAL. Today it only logs a warning and produces an incomplete ZIP.

**Import**
- The CSV is parsed and rewritten with Apache Commons CSV using the chosen delimiter. Only whole cell values in FILE columns are rewritten. The raw text `String.replace` and the comma-only regex are removed.
- Archives are read from a temp file, one entry at a time. New configurable limits: entry count and total unpacked size. Each file entry is also capped by the existing dataset file size limit, and the CSV entry by the existing CSV import size limit.
- Uploads go through a new `FileService` method for archive entries, so they get the same checks as a normal upload: size limit, filename validation and `max-files-per-dataset` (only new names count). Unlike the normal upload endpoint, a same-name file is overwritten (see the file reference rule).
- Filenames are predictable: the sanitized original name. An existing dataset file with the same name is overwritten on purpose (above). **Behaviour change**: `public/...` refs keep pointing at the public file instead of becoming private copies.
- If the import fails, files it overwrote are restored from a backup taken just before the overwrite, and files it created are deleted (both best effort). The If-Match version is checked before any upload.
- ZIP uploads are also recognised by their first bytes, not only by filename or MIME type.
- Preview runs the same parsing and rewrite without uploading anything. Its schema, validity and warnings (including missing files) then match what the import would produce.

**Multi-request boundary**
- `additionalRequests` is part of the suite configuration, not test-case data. The dataset ZIP carries no `requestIndex` and no request chain. Imported cases are shown by tests to run correctly under a multi-request suite.

**Non-goals**
- Importing or exporting suite configuration (request chains, `additionalRequests`). That would need a separate suite-bundle API.
- Making ZIPs self-contained by copying `public/...` files into them. Public refs only resolve in a target environment that has the same public files.
- Comparing content before overwriting. A same-name file is simply overwritten (the result is the same when the content matches).
- Deleting dataset files left unreferenced after an OVERRIDE import. Such files may have been uploaded on purpose through the dataset files API.
- Fixing `DatasetCloneService`, which drops `multiTurnData`. That is a separate multi-turn data-loss defect for its own change.
- Changing plain CSV import/export behaviour, except for the row builder shared with ZIP export. This includes producing a ZIP (with a manifest) for datasets without FILE fields: the round-trip gaps of plain CSV export are out of scope.

## Capabilities

### New Capabilities
- `test-case-zip-archive`: the ZIP archive format and ZIP import/export behaviour: `test-cases.csv` + versioned `manifest.json` (always written on export, optional on import) + `files/` layout, one archive entry per distinct file ref, the file reference rule (public refs kept as-is, EF-owned files re-uploaded into the target dataset and overwritten when the name matches), archive limits and entry validation, cell-level path rewriting, upload safety (checks, restore and cleanup after failure), matching preview, and export failing on missing files. Supersedes the ZIP parts of `test-cases`.

### Modified Capabilities
- `test-cases`: the ZIP parts of "Export test cases (CSV or ZIP)", "Import test cases (CSV or ZIP)" and "Import preview (CSV or ZIP)" change. The outdated suite-scoped storage, `{rowIndex}_{fieldName}_` naming and "preview shows raw paths" scenarios are replaced with pointers to `test-case-zip-archive`. Format detection gains a check of the file's first bytes.
- `multi-turn-test-case`: "Flat CSV import/export multiplication" also covers ZIP export/import. "CSV schema rebuild preserves per-field scope" gains the rule that the manifest decides scope on OVERRIDE.
- `dataset-file-storage`: dataset file limits (size, `max-files-per-dataset`, filename rules) also apply to files created by a ZIP import.

## Impact

- **Code**:
  - `ZipImportService` (rewritten)
  - `ZipExportService`, `CsvExportService` (shared row builder)
  - `CsvImportService` / `CsvSchemaFieldBuilder` (schema from the manifest and FILE type hints)
  - `FileService` (new method for uploading archive entries)
  - `TestCaseController` (ZIP detection)
- **New classes** (likely, all in `service.domain`, final names in design):
  - a shared export row builder
  - a ZIP archive reader (limits, entry checks)
  - a CSV file-path rewriter
  - the manifest DTO and its (de)serializer
  - an upload journal that restores overwritten files and deletes created ones after a failed import
- **API**: same endpoints and parameters.
  - ZIP export now always includes the `turnIndex` column and `manifest.json`, and returns an error when a file can't be downloaded.
  - ZIP import returns 400 for archive limit or entry errors.
  - Preview FILE cells show the future `@ef/datasets/{id}/…` refs instead of raw paths.
  - OpenAPI descriptions and examples will be updated.
- **Config**: new properties `csv.import.zip.max-entries` and `csv.import.zip.max-total-uncompressed-size`, with defaults in `application.yml` and rows in `docs/configuration.md`. Per-entry size reuses `dial.file-storage.max-file-size-bytes`; the CSV entry reuses `csv.import.max-file-size`.
- **DB**: none. No Flyway migration.
- **Security**: removes the zip-bomb memory risk and the path through which ZIP uploads skipped the upload checks. Archive paths are checked but never written to the local filesystem.
- **Risks**:
  - Export failing on a missing file may surface failures that were previously silent → the error names the ref.
  - Old ZIPs have no manifest → the FILE type-hint fallback plus tests covering them.
- **Rollout**: backend only, backward compatible for importing existing ZIPs. The frontend may show the new preview refs. Needs an FE handoff note.
- **Test plan**:
  - unit tests for the archive reader, path rewriter, row builder and manifest handling
  - functional tests:
    - multi-turn ZIP round trip, re-imported twice with OVERRIDE, keeping `FILE`, `perTurn` and `required`
    - shared and per-turn FILE fields
    - an old ZIP without a manifest
    - preview/import parity
    - `;` delimiter, quoted values, and `files/…` inside prompt text
    - missing, duplicate, oversized and unreferenced entries
    - a same-name file overwritten with new content on APPEND re-import, and restored when the import fails
    - newly created files deleted after a failed import
    - export failing when a download fails
    - an imported multi-turn + FILE case run through a two-request suite, with correct `(requestIndex, turnIndex)` rows
