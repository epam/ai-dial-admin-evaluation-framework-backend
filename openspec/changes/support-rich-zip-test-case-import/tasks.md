## 1. Foundations: config, manifest, shared row builder, FileService

- [ ] 1.1 Add a nested `zip` block to `CsvImportProperties` (`maxEntries` `@Min(1)`, `maxTotalUncompressedSize` `DataSize` `@NotNull`) with defaults only in `application.yml` (`csv.import.zip.max-entries: ${CSV_IMPORT_ZIP_MAX_ENTRIES:1000}`, `csv.import.zip.max-total-uncompressed-size: ${CSV_IMPORT_ZIP_MAX_TOTAL_UNCOMPRESSED_SIZE:1GB}`), and add both rows to `docs/configuration.md` with all six columns (done: a properties binding test asserts both defaults; the docs rows exist)
- [ ] 1.2 Create `service.domain.zip.ZipManifest` (record: `formatVersion`, `testCaseSchema`, optional `files` list of `{path, sourceRef}`) and `ZipManifestSerializer` (`@Component`, `@LogExecution`; write throws on failure; read → `ValidationException` for invalid JSON, wrong structure or unsupported `formatVersion`) (done: `ZipManifestSerializerTest` covers round trip, bad JSON, unsupported version, and keeps `perTurn`/`required`/`displayName`/`description`)
- [ ] 1.3 Move the per-turn row expansion out of `CsvExportService` into `service.domain.csv.TestCaseExportRowProjector` (`@Component`; shared data merged into every turn, blank `turnIndex` for single-turn) and switch `CsvExportService` to it (done: `TestCaseExportRowProjectorTest` passes, and the existing CSV export functional tests (`MultiTurnCsvFunctionalTests`, CSV export in `FileFieldFunctionalTests`) pass unchanged)
- [ ] 1.4 Refactor `FileService.uploadInternal` to take `(filename, InputStream, size, contentType)`. Add `putDatasetFile(datasetId, filename, InputStream, contentType)`, which creates or overwrites, validates the filename, and enforces `max-file-size-bytes` on the bytes actually read, through a limiting stream that throws before the PUT is sent (`DialFileClient.upload` reads the whole stream). Add `checkDatasetFileCapacity(datasetId, newFiles)`. Keep the duplicate-name 400 on the existing upload endpoint only (done: `FileServiceTest` covers overwrite allowed via `putDatasetFile`, an oversize stream rejected, capacity counting only new files, and the existing upload endpoint still rejecting duplicates)

## 2. Archive reading, column types, rewriting, planning, journal

- [ ] 2.1 Create `service.domain.zip.ZipArchiveReader` (`@Component`), which opens a staged temp file as `ZipFile`:
  - strips a single top-level folder; ignores directories and `__MACOSX/`;
  - before any write, rejects (400) absolute paths, `..` segments, backslashes, duplicate normalized names, more than `max-entries`, entry-header sizes summing above `max-total-uncompressed-size`, a referenced entry's header size above `dial.file-storage.max-file-size-bytes`, and a CSV above `csv.import.max-file-size`;
  - wraps every entry stream in a limiting stream that enforces the same caps on the bytes actually read;
  - exposes the CSV bytes, the optional manifest, and lazily opened file entries.

  (done: `ZipArchiveReaderTest` covers each header-based rejection, an entry whose header understates its size failing while read, nested-folder archives and `__MACOSX` archives)
- [ ] 2.2 Create `service.domain.zip.ZipImportColumnTypeResolver` (`@Component`). It resolves each CSV data column's type for rewriting per design D5:
  - OVERRIDE/empty schema: manifest type else undetermined, ignoring the dataset's current types;
  - MERGE: dataset type, then manifest type;
  - APPEND non-empty: dataset type only, with undeclared columns excluded.

  The same result feeds the rewriter and `CsvImportSchemaHints` (done: `ZipImportColumnTypeResolverTest` covers each mode, including an APPEND undeclared column excluded and an OVERRIDE of a currently STRING column treated as undetermined)
- [ ] 2.3 Create `service.domain.zip.ZipCsvFileRefRewriter` (`@Component`):
  - `scan(csv, delimiter, columnTypes)` returns the referenced `files/…` paths and the columns holding them;
  - `rewrite(csv, delimiter, columnTypes, pathToRef)` uses Commons CSV parsing and `CSVPrinter`. It rewrites only whole-cell trimmed `files/…` values in columns resolved as FILE or undetermined (never excluded columns, never `testCaseName`/`turnIndex`). A missing path becomes a blank cell plus a warning with the row number (header = row 1, as in CSV import) and the column name.

  (done: `ZipCsvFileRefRewriterTest` covers the `;` delimiter, quoted values with commas, prompt text containing `files/…` left untouched, STRING-typed and excluded columns left untouched, and missing-file warnings with the right row numbers)
- [ ] 2.4 Create `service.domain.zip.ZipFileImportPlanner` (`@Component`):
  - maps each referenced path to its sanitized filename (reuse `sanitizeFilename`);
  - resolves same-name clashes between different entries by giving the name to the entry whose manifest `sourceRef` is the target dataset's own file, otherwise to the lowest `n`;
  - picks suffixes (`_1`, `_2`, …) that are neither in the single `FileService.listByDataset` result nor already planned;
  - marks *new* vs *overwrite* and calls `checkDatasetFileCapacity` using final names only;
  - builds the future `@ef/datasets/{id}/{name}` ref via `DialFileRefResolver`.

  (done: `ZipFileImportPlannerTest` covers: a same-name overwrite; the dataset's own file keeping its name when a suite file comes first; a suffix skipping an existing `report_1.pdf`; a sanitized name overwriting; a full dataset with only overwrites allowed; and a full dataset with new files rejected)
- [ ] 2.5 Create `service.domain.zip.ZipImportUploadJournal`, a per-request object that is not a bean. It records *created* and *overwritten + temp backup + original content type*. `rollback()` restores backups with their original content type and deletes created files, best effort, with `log.warn(…, e)` as the last argument. `close()` deletes the backups (done: `ZipImportUploadJournalTest` covers restore with the content type kept, delete, a rollback that continues after one failure, and backups deleted on close)

## 3. CSV import schema hints (manifest + FILE hint)

- [ ] 3.1 Add `service.domain.csv.CsvImportSchemaHints` (`declaredSchema`, `fileColumns`) and `preview`/`importCsv` overloads on `CsvImportService` that accept it. The existing signatures delegate with empty hints (done: existing `CsvImportService*Test` and CSV import functional tests pass unchanged)
- [ ] 3.2 Apply `declaredSchema` per the mode table in design D4 across `buildValidationSchema`, `persistSchema`, `buildFinalSchema`/`buildAutoDetectedSchema` and `CsvSchemaFieldBuilder`, via a declared tier that takes priority over inference:
  - OVERRIDE/empty: manifest verbatim, plus inferred definitions for unlisted CSV columns; manifest fields without a CSV column are kept;
  - MERGE: new fields come from the manifest;
  - APPEND: the manifest is ignored.

  (done: unit tests assert the persisted schema for each mode, including `required`, `displayName`, `description` and a FILE type)
- [ ] 3.3 Make multi-turn assembly and the tier-0 scope rule use the manifest's `perTurn`, so the file-level gate no longer marks a manifest-declared shared column per-turn (done: a unit test imports a multi-turn CSV with a manifest into an empty schema, and a shared column stays shared with no conflict)
- [ ] 3.4 When there is no manifest, apply `fileColumns` → `FILE` wherever import derives a type (OVERRIDE/empty rebuild, MERGE delta, preview `autoDetectedSchema`) (done: a unit test shows a legacy-style CSV column becoming `FILE`; a column with a declared type is unchanged)

## 4. ZIP import and preview orchestration

- [ ] 4.1 Rewrite `ZipImportService.importZip` to the design D6 flow:
  1. stage the upload to a temp file;
  2. read the archive, the manifest and the CSV;
  3. resolve column types, scan and plan, then rewrite;
  4. before any write: check the rewritten CSV against `csv.import.max-file-size` and `csv.import.max-rows` (400), and If-Match against the dataset version read via the dataset domain service, not a repository injected into `ZipImportService` (409);
  5. for each entry: if it will be overwritten, back it up first (a failed backup aborts before that overwrite), then `putDatasetFile` and record it in the journal;
  6. call `importCsv` with hints;
  7. on `RuntimeException`, roll back the journal and rethrow;
  8. in `finally`, clean up.

  Delete the old `String.replace`/regex rewrite and the in-memory `Map<String, byte[]>` (done: `ZipImportServiceTest` with mocks covers rollback after a 409, rollback after an upload failure, a backup failure leaving the file un-overwritten, no writes on a stale If-Match or an oversized rewritten CSV, and cleanup of the temp file and backups)
- [ ] 4.2 Rewrite `ZipImportService.previewZip` to run steps 1–4 (without If-Match) and no DIAL write and merge the rewriter warnings into `csvImportService.preview(…, hints)` (done: a unit test verifies zero `putDatasetFile`/`delete` calls and that sample FILE cells hold the future refs)
- [ ] 4.3 In `TestCaseController`:
  - pass the staged file to the ZIP service;
  - detect ZIP by extension, MIME type or `PK\x03\x04` signature (`isZipArchive` over a `BufferedInputStream`);
  - update the OpenAPI operation descriptions for import and preview (same-name overwrite, suffix rule, manifest, 400 archive errors).

  (done: a web/unit test detects a ZIP named `export.bin` with `application/octet-stream`)
- [ ] 4.4 Add a ZIP import functional test class (`@PostgresFunctionalTests` nested, `MetaTestDataHelper` fixtures, repository assertions) covering:
  - a `;` delimiter, quoted values, and prompt text containing `files/…`;
  - a missing file giving a blank value plus a warning with row and column;
  - APPEND not uploading files for a column the schema lacks; OVERRIDE rewriting a column the dataset currently types STRING;
  - the existing `FileFieldFunctionalTests` ZIP import tests updated where the contract changed, and passing;
  - the legacy layout, a nested folder, `__MACOSX`, and unreferenced entries not uploaded;
  - `public/…` kept with nothing written;
  - two different entries with the same name getting a suffix.

  (done: `./gradlew test --tests "…PostgresFunctionalTests\$<ZipImportTests>"` passes)
- [ ] 4.5 Extend the ZIP import functional tests for writes and failures:
  - APPEND re-import overwrites a same-name file, and existing cases see the new content;
  - the dataset's own file keeps its name when a same-name suite file comes first; a suffix skips an existing name; a sanitized name overwrites;
  - a rewritten CSV over the size limit gives 400 with no writes; an entry whose header understates its size gives 400 and rolls back earlier writes;
  - entry-count, oversize, traversal and capacity limits give 400 with no files written;
  - `conflictStrategy=FAIL` 409 deletes created files and restores overwritten ones;
  - a stale If-Match gives 409 with no writes;
  - preview/import parity with no writes during preview.

  (done: the functional test class passes)

## 5. ZIP export

- [ ] 5.1 Create `service.domain.zip.ZipExportFileCollector`:
  - classifies FILE values via `DialFileRefResolver` prefixes: `public/…` verbatim; EF-owned `datasets`/`suites` materialized; blank/invalid unchanged;
  - numbers distinct EF-owned refs `n = 1..N` in first-seen order;
  - returns `files/{n}/{filename}`.

  (done: `ZipExportFileCollectorTest` covers classification and dedup across rows, turns and fields)
- [ ] 5.2 Replace `ZipExportService.exportZip(…, OutputStream)` with `buildZip(datasetId, filter, delimiter)`. It returns an `AutoCloseable` handle to a finished temp ZIP and:
  - pages through test cases via `csvExportProperties.pageSize`;
  - builds rows with `TestCaseExportRowProjector`;
  - downloads each EF-owned ref once into `files/{n}/{filename}` the first time it is seen;
  - writes the CSV to a temp file;
  - always writes `manifest.json` from `datasetSchemaProvider.getSchema`, with the `files` list pairing each path with its source ref;
  - throws, naming the ref, when a download fails.

  Delete `collectRows` (done: `ZipExportServiceTest` covers multi-turn rows, the manifest always present, dedup, and a download failure throwing and deleting the temp file)
- [ ] 5.3 In `TestCaseController`, call `buildZip` first, then set the ZIP headers and stream the handle with try-with-resources, so an error response never carries `application/zip`. Update the export OpenAPI description (turn rows, manifest, `public/` kept verbatim, error on download failure) and add an `@ApiResponse` for the download-failure error status (done: controller/functional test for the error path)
- [ ] 5.4 Add ZIP export functional tests:
  - multi-turn one row per turn;
  - a shared FILE field keeps one path across turns, and per-turn files are exported;
  - one entry for a file used by many cases;
  - a `public/…` ref verbatim with no entry;
  - a legacy `@ef/suites/…` ref materialized;
  - `manifest.json` always present;
  - a missing DIAL file gives an error status and no ZIP;
  - the existing `FileFieldFunctionalTests` ZIP export tests updated where the contract changed, and passing.

  (done: the functional tests pass)

## 6. End-to-end round trip and multi-request

- [ ] 6.1 Functional test: a dataset whose EF-owned refs all point at its own files, with a multi-turn case, a shared FILE field, a per-turn FILE field and a `public/…` ref is exported as ZIP and imported back with OVERRIDE, twice. After each round the schema (type, `perTurn`, `required`, `displayName`, `description`) equals the original, the turns, values and file contents match, the `public/…` ref is unchanged, and the dataset file count is stable (done: the test passes)
- [ ] 6.2 Functional tests: a round trip into a different, empty-schema dataset, where refs point at the destination's own files with equal content; a legacy ZIP without a manifest imported with OVERRIDE keeps `FILE`; and a legacy `@ef/suites/…` ref copied into the dataset on the first round trip (file count +1) and stable on the second (done: the tests pass)
- [ ] 6.3 Functional test: a ZIP-imported 2-turn case with a shared FILE field and a per-turn field runs under a suite with one `additionalRequests` entry, each request binding a different field, and produces the expected `(request_index, turn_index)` result rows with FILE values resolved (done: the test passes)

## 7. Docs, specs and quality gates

- [ ] 7.1 Add an OpenAPI example for the import result with a missing-file warning under `src/main/resources/openapi/examples/`, with the filename matching the registered path key, and a test asserting it appears in `/v3/api-docs` (done: the test passes)
- [ ] 7.2 Update AGENTS.md per the AGENTS.md Maintenance guidelines (done: relevant sections reflect the change):
  - add `service.domain.zip` to `docs/key-packages.md`;
  - add `docs/patterns/zip-archive-round-trip.md`, covering the file reference rule, same-name overwrite, the upload journal with backup/restore, and manifest precedence;
  - add a Unique Patterns row.
- [ ] 7.3 Update openspec/specs/README.md per the Spec Index Maintenance Policy (done: the index lists `test-case-zip-archive` and the `test-cases`/`dataset-file-storage` summaries mention ZIP behaviour). Main specs are updated via `/opsx:sync` at archive time.
- [ ] 7.4 Write the FE handoff note with the `fe-api-handoff` skill (done: file produced) covering:
  - preview FILE cells showing `@ef/datasets/…` refs;
  - the export error status;
  - `turnIndex` and `manifest.json` in ZIP exports;
  - same-name overwrite on import.
- [ ] 7.5 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` and then a full `./gradlew build`, including `LayeredArchitectureTest`, `LoggingConventionTest` and `SecretHandlingLoggingTest` (done: the build is green)
