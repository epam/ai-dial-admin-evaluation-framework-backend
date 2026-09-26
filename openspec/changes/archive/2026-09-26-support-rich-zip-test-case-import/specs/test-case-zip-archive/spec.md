## Purpose

Defines the ZIP archive format for dataset test cases and the behaviour of ZIP export, import and import preview. A dataset, including its multi-turn cases, its full field definitions and its FILE attachments, can be moved between datasets and environments and restored without loss.

## ADDED Requirements

### Requirement: ZIP archive layout
A test-case ZIP archive SHALL contain:
- `test-cases.csv` (required): header `testCaseName`, `turnIndex`, then data columns;
- `manifest.json` (see "ZIP manifest");
- `files/…` entries holding file bytes.

On import, the system SHALL:
- accept the archive entries either at the archive root or under a single top-level folder;
- ignore directory entries and anything under `__MACOSX/`;
- accept any `files/…` entry path, including paths written by earlier exports (`files/{row}/{field}/{filename}`) and hand-made layouts.

Status: **Implemented**

#### Scenario: Archive nested under one top-level folder
- **WHEN** a client imports a ZIP whose entries are all under `my-dataset/` (e.g. `my-dataset/test-cases.csv`, `my-dataset/files/1/a.pdf`)
- **THEN** the system SHALL import it as if the entries were at the archive root

#### Scenario: macOS metadata is ignored
- **WHEN** a ZIP contains `__MACOSX/` entries alongside `test-cases.csv`
- **THEN** the system SHALL ignore the `__MACOSX/` entries and SHALL NOT upload them

#### Scenario: Missing CSV
- **WHEN** a ZIP contains no `test-cases.csv` at the root or under a single top-level folder
- **THEN** the system SHALL return HTTP 400 and SHALL NOT upload any file

#### Scenario: Legacy file path layout still imports
- **WHEN** a ZIP's CSV references `files/1/document/report.pdf` and the archive contains that entry
- **THEN** the system SHALL upload that entry and store its dataset file reference in the cell

### Requirement: ZIP manifest
Every ZIP export SHALL include a `manifest.json` holding:
- a `formatVersion` (currently `1`);
- the dataset's full `testCaseSchema`: every field with its `name`, `type`, `perTurn`, `required`, `displayName` and `description`;
- a `files` list pairing each archive file path with the file reference it was exported from.

The manifest SHALL be written even when no file is copied into the archive. When export produces a ZIP rather than a CSV is defined by the `test-cases` capability and is unchanged.

On import the manifest SHALL be optional. An archive without a manifest SHALL import (see "ZIP import without a manifest").

Status: **Implemented**

#### Scenario: Export always writes the manifest
- **WHEN** a client exports a dataset as ZIP
- **THEN** the archive SHALL contain `manifest.json` with `formatVersion: 1`, a `testCaseSchema` equal to the dataset's current `testCaseSchema`, and one `files` entry per archive file naming its source reference

#### Scenario: Unsupported manifest version
- **WHEN** a client imports a ZIP whose `manifest.json` carries a `formatVersion` the system does not support
- **THEN** the system SHALL return HTTP 400 and SHALL NOT upload any file

#### Scenario: Malformed manifest
- **WHEN** a client imports a ZIP whose `manifest.json` is not valid JSON or does not match the manifest structure
- **THEN** the system SHALL return HTTP 400 and SHALL NOT upload any file

#### Scenario: Invalid field definition in the manifest
- **WHEN** a manifest's `testCaseSchema` is missing, contains a `null` entry, a field that violates the field-definition rules used by the schema API (e.g. a name with `:`, a missing `type`), or two fields with the same name; or its `files` list contains a `null` entry or an entry without a `path`
- **THEN** the system SHALL return HTTP 400 naming the offending field or entry, and SHALL NOT upload any file

### Requirement: ZIP export multiplies turns and writes each file once
ZIP export SHALL write `test-cases.csv` with the same row layout as CSV export:
- one row per turn for a multi-turn case, with `turnIndex` `0..N-1` and the shared data repeated on every turn row;
- one row with a blank `turnIndex` for a single-turn case.

Each distinct EF-owned file reference SHALL be written to the archive exactly once, at `files/{n}/{filename}`:
- `n` is a positive integer unique within the archive;
- `{filename}` is the file's stored filename.

Every cell holding that reference (in any row, turn, shared field or per-turn field) SHALL contain that same path.

Status: **Implemented**

#### Scenario: Multi-turn case exports one row per turn
- **WHEN** a dataset holding a 3-turn case is exported as ZIP
- **THEN** `test-cases.csv` SHALL contain 3 contiguous rows for that case with `turnIndex` `0`, `1`, `2`, each carrying that turn's per-turn values and the case's shared values

#### Scenario: Per-turn FILE values are exported
- **WHEN** a multi-turn case holds a different EF-owned file reference in a per-turn FILE field on each turn
- **THEN** each turn row SHALL reference its own archive path, and the archive SHALL contain the bytes of every one of those files

#### Scenario: Shared FILE value uses one path on every turn row
- **WHEN** a multi-turn case holds one EF-owned file reference in a shared FILE field
- **THEN** every turn row of that case SHALL carry the same `files/{n}/{filename}` path in that column

#### Scenario: One file used by many cases is written once
- **WHEN** several test cases reference the same EF-owned file
- **THEN** the archive SHALL contain one entry for that file, and every referencing cell SHALL carry its path

### Requirement: File reference handling on ZIP export
ZIP export SHALL treat FILE field values by kind:
- **Public reference (`public/…`):** written into the CSV exactly as stored. No archive entry SHALL be created and the file SHALL NOT be downloaded.
- **EF-owned reference** (the EF bucket alias: `…/datasets/…` of any dataset, or legacy `…/suites/…`): its bytes SHALL be downloaded into the archive, and the cell SHALL carry the archive path.
- **Blank or invalid value:** written unchanged.

Status: **Implemented**

#### Scenario: Public reference exported verbatim
- **WHEN** a test case's FILE field holds `public/shared/guide.pdf`
- **THEN** the exported CSV cell SHALL be `public/shared/guide.pdf` and the archive SHALL contain no entry for it

#### Scenario: Dataset file is materialized
- **WHEN** a test case's FILE field holds an EF-owned dataset file reference
- **THEN** the archive SHALL contain that file's bytes under `files/{n}/{filename}` and the cell SHALL carry that path

#### Scenario: Legacy suite-scoped file is materialized
- **WHEN** a test case's FILE field holds an EF-owned `…/suites/{suiteId}/…` reference
- **THEN** the archive SHALL contain that file's bytes and the cell SHALL carry its archive path

### Requirement: ZIP export fails when a file cannot be downloaded
If any EF-owned file referenced by the exported test cases cannot be downloaded, the ZIP export SHALL fail:
- the response SHALL carry the error status mapped from the storage failure (HTTP 502 for a missing or unreachable file, including transport errors) and a message naming the file reference;
- the system SHALL NOT send a partial or truncated archive;
- the error response SHALL NOT carry `Content-Type: application/zip`.

Status: **Implemented**

#### Scenario: Referenced file missing from storage
- **WHEN** a client exports a dataset as ZIP and one referenced dataset file no longer exists in DIAL storage
- **THEN** the system SHALL return an error response naming that reference, and no ZIP body

### Requirement: ZIP import schema from the manifest
When a ZIP carries a valid manifest, the dataset schema resulting from the import SHALL be:

| Import mode / dataset schema | Resulting schema |
|---|---|
| `OVERRIDE`, or any mode into a dataset with an empty `testCaseSchema` | Exactly the manifest's field definitions (type, `perTurn`, `required`, `displayName`, `description`), plus an inferred definition (as for CSV import) for any CSV data column the manifest does not list. Manifest fields without a CSV column SHALL be kept. |
| `MERGE`, non-empty schema | Existing fields unchanged. New CSV columns take their definition from the manifest when it lists them, otherwise it is inferred. |
| `APPEND`, non-empty schema | Existing schema unchanged; manifest ignored. |

Imported rows SHALL be validated and coerced against that resulting schema. A column whose type the manifest decides SHALL be parsed from its raw cell text with that type, without CSV import's numeric/boolean guessing, so a STRING value such as `007`, `1.50` or `TRUE` is stored verbatim; a manifest OBJECT/ARRAY column SHALL be stored as parsed JSON. Columns the manifest does not decide are parsed exactly as in CSV import. Multi-turn case assembly SHALL use the manifest's `perTurn` scopes. In MERGE, a new manifest-declared field SHALL be appended even when all of its cells are blank.

Status: **Implemented**

#### Scenario: OVERRIDE keeps FILE type and scope from the manifest
- **WHEN** a ZIP whose manifest declares `document` as `FILE`, `required: true`, shared, and `prompt` as `STRING`, `perTurn: true`, is imported with `importMode=OVERRIDE`
- **THEN** the persisted `testCaseSchema` SHALL declare `document` as `FILE`, `required: true`, shared, and `prompt` as `STRING`, `perTurn: true`

#### Scenario: Manifest keeps shared columns shared in a multi-turn import
- **WHEN** a ZIP containing a multi-turn case is imported into a dataset with an empty schema, and its manifest declares a column shared
- **THEN** that column SHALL be persisted as shared (not `perTurn: true`), its value SHALL be stored in the case's shared `data`, and no shared-column conflict SHALL be reported

#### Scenario: Manifest STRING column keeps its text
- **WHEN** a ZIP whose manifest declares `code` as `STRING` and whose `code` cells read `007` is imported with `importMode=OVERRIDE`
- **THEN** the stored `code` value SHALL be the string `007`

#### Scenario: MERGE takes new field definitions from the manifest
- **WHEN** a ZIP is imported with `importMode=MERGE` into a dataset whose schema lacks a column the manifest declares as `FILE`
- **THEN** the new field SHALL be appended to the schema as `FILE` with the manifest's other attributes, and existing fields SHALL be unchanged

#### Scenario: APPEND ignores the manifest
- **WHEN** a ZIP is imported with `importMode=APPEND` into a dataset with a non-empty schema
- **THEN** the dataset schema SHALL be unchanged regardless of the manifest's content

### Requirement: ZIP import without a manifest
When a ZIP carries no manifest, the import SHALL derive the schema as a CSV import would, with one addition: a data column in which at least one cell referenced an archive `files/…` path SHALL be typed `FILE` wherever the import derives a type for it.

Status: **Implemented**

#### Scenario: Legacy ZIP keeps the FILE type
- **WHEN** a ZIP without `manifest.json`, whose `document` column holds `files/1/document/report.pdf` paths, is imported with `importMode=OVERRIDE`
- **THEN** the persisted schema SHALL type `document` as `FILE`

### Requirement: ZIP import rewrites file paths cell by cell
The import SHALL parse `test-cases.csv` as CSV using the requested delimiter and standard CSV quoting. A cell SHALL be treated as an archive file path only when all of these hold:
- its entire value, trimmed, starts with `files/`;
- it is not in the `testCaseName` or `turnIndex` column (header names matched case-insensitively);
- its column's type, determined as below, is `FILE` or undetermined.

A column's type for this purpose SHALL be:
- `OVERRIDE`, or any mode into a dataset with an empty schema: the manifest's type, else undetermined. The dataset's current types SHALL NOT be used.
- `MERGE` into a non-empty schema: the dataset's type, else the manifest's type, else undetermined.
- `APPEND` into a non-empty schema: the dataset's type. A column the dataset schema does not declare is not imported, so its cells SHALL NOT be rewritten and no file SHALL be uploaded for them.

Each such cell is handled as follows:
- **Path present in the archive:** the cell SHALL be replaced by the dataset file reference of the uploaded file.
- **Path absent from the archive:** the value SHALL be stored blank (empty string), which validation treats as an absent value, and a warning SHALL be reported. The warning carries the row number, using the same convention as CSV import warnings (header = row 1), the column name and the message `File missing from archive: <path>`.

All other cells SHALL be imported unchanged. This includes text that contains `files/…` inside a longer value, and cells of columns declared with a non-FILE type.

Status: **Implemented**

#### Scenario: Semicolon-delimited archive
- **WHEN** a ZIP's CSV uses `;` as delimiter, is imported with `delimiter=;`, and a FILE cell references an archive file followed by further cells in the same row
- **THEN** the FILE cell SHALL hold the uploaded file's reference and every following cell SHALL keep its value

#### Scenario: Prompt text mentioning a path is untouched
- **WHEN** a STRING column's cell reads `Summarize files/1/doc/report.pdf please`
- **THEN** the imported value SHALL be exactly that text

#### Scenario: Missing archive file
- **WHEN** a FILE cell in the second data row (row 3, counting the header as row 1), column `document`, references `files/9/missing.pdf` and the archive has no such entry
- **THEN** the imported `document` value SHALL be blank and the result SHALL carry a warning with row number 3, column `document` and the message `File missing from archive: files/9/missing.pdf`

#### Scenario: APPEND does not upload files for undeclared columns
- **WHEN** a ZIP is imported with `importMode=APPEND` into a dataset whose non-empty schema lacks column `attachment`, and the CSV's `attachment` cells reference archive files
- **THEN** no file SHALL be uploaded or overwritten for those cells, and the `attachment` values SHALL NOT be stored

#### Scenario: OVERRIDE ignores the dataset's current column type
- **WHEN** a ZIP without a manifest, whose `document` cells reference archive files, is imported with `importMode=OVERRIDE` into a dataset that currently types `document` as `STRING`
- **THEN** the `document` cells SHALL be rewritten to uploaded file references and `document` SHALL be persisted as `FILE`

#### Scenario: Quoted value with a comma
- **WHEN** a FILE cell holds the quoted value `"files/1/report, final.pdf"` and the archive contains that entry
- **THEN** the cell SHALL hold the uploaded file's reference

### Requirement: ZIP import file placement and overwrite
Every archive entry that at least one CSV cell references SHALL be uploaded exactly once, into the target dataset's file storage (`{efBucket}/datasets/{datasetId}/{filename}`):
- `{filename}` is the entry's filename, sanitized to the dataset filename rules (a suffixed name, see below, is shortened before its extension so it still fits the 255-character limit);
- the uploaded file's content type SHALL be derived from its filename (`application/octet-stream` when unknown), also when it overwrites an existing file;
- every cell referencing the entry SHALL receive the same reference.

Naming and overwrite:
- If a file with that name already exists in the target dataset, it SHALL be overwritten, in every import mode. The content then takes effect for every test case that references it.
- If two different archive entries of one import would receive the same filename, one entry keeps it and the others SHALL receive a numeric suffix before the extension (`report_1.pdf`, `report_2.pdf`, …). The entry that keeps the name SHALL be the one whose manifest source reference is the target dataset's own file of that name, if any; otherwise the entry that comes first in archive order: ascending numeric `n` of a `files/{n}/…` path, then path text, with paths lacking a numeric segment last.
- A suffixed name SHALL be one that exists neither in the target dataset nor among the names already chosen in this import, so a suffixed file SHALL always be created, never overwrite. One import SHALL never overwrite a file it uploaded itself.
- An entry's name after sanitization counts as its own name, so it overwrites a same-name dataset file like an unsanitized name would.

Entries no cell references SHALL NOT be uploaded. Cells holding `public/…` or other non-`files/…` references SHALL be imported unchanged, and no public file SHALL be written.

Status: **Implemented**

#### Scenario: Import into a new dataset creates dataset files
- **WHEN** a ZIP exported from dataset A is imported into empty dataset B
- **THEN** every referenced archive file SHALL be uploaded under dataset B's storage, and B's cells SHALL reference B's files, never A's

#### Scenario: Re-import overwrites a same-name file
- **WHEN** dataset B already holds `report.pdf` and a ZIP containing an edited `report.pdf` is imported into B with `importMode=APPEND`
- **THEN** B's `report.pdf` SHALL hold the edited content, no `report_1.pdf` SHALL be created, and existing cases referencing `report.pdf` SHALL see the edited content

#### Scenario: Two distinct entries with the same name
- **WHEN** one import's CSV references `files/1/report.pdf` and `files/2/report.pdf` with different content
- **THEN** the system SHALL store them as `report.pdf` and `report_1.pdf`, and each cell SHALL reference the file uploaded from its own entry

#### Scenario: The dataset's own file keeps its name
- **WHEN** dataset B holds `report.pdf`, B's cases reference both B's `report.pdf` and a legacy suite file also named `report.pdf`, B is exported as ZIP with the suite file first in archive order, and the ZIP is imported back into B
- **THEN** B's `report.pdf` SHALL receive the content of B's own exported `report.pdf`, the suite file SHALL be stored under a new suffixed name, and each cell SHALL reference the file matching its original source

#### Scenario: Suffix skips names that already exist
- **WHEN** dataset B already holds `report_1.pdf` and one import's CSV references two different entries both named `report.pdf`
- **THEN** the second entry SHALL be stored as `report_2.pdf`, and B's existing `report_1.pdf` SHALL be unchanged

#### Scenario: Sanitized name overwrites a same-name file
- **WHEN** dataset B already holds `report_final.pdf` and a ZIP import references an archive file named `report#final.pdf`
- **THEN** B's `report_final.pdf` SHALL be overwritten with the archive file's content

#### Scenario: Public reference in a ZIP is kept
- **WHEN** a ZIP's FILE cell holds `public/shared/guide.pdf`
- **THEN** the imported value SHALL be `public/shared/guide.pdf` and no file SHALL be uploaded or written for it

#### Scenario: Unreferenced entry is not uploaded
- **WHEN** a ZIP contains `files/5/unused.txt` that no CSV cell references
- **THEN** no file named `unused.txt` SHALL be created in the dataset

#### Scenario: One entry referenced by many cases
- **WHEN** 10 CSV rows reference `files/1/policy.pdf`
- **THEN** exactly one file SHALL be uploaded and all 10 cells SHALL hold the same reference

### Requirement: ZIP import archive limits and entry validation
The import SHALL reject an archive with HTTP 400 before writing any file when any of these holds, judged from the archive's entry headers and the parsed CSV:
- **Entry count:** more than `csv.import.zip.max-entries` entries, counting every entry in the archive (directories and `__MACOSX/` included).
- **Not a ZIP:** content detected as ZIP that cannot be opened as a ZIP archive.
- **Total size:** total uncompressed size above `csv.import.zip.max-total-uncompressed-size`.
- **File entry size:** a referenced file entry above `dial.file-storage.max-file-size-bytes`.
- **CSV size:** `test-cases.csv`, before or after file paths are replaced by file references, above `csv.import.max-file-size`, or with more data rows than `csv.import.max-rows`.
- **Bad path:** an entry path that is absolute, contains a `..` segment, or contains a backslash.
- **Duplicates:** two entries with the same normalized path.
- **Capacity:** the dataset's existing files plus the files the import would *newly* create exceed `dial.file-storage.max-files-per-dataset`. Overwrites SHALL NOT count.

In addition, every entry's size SHALL be enforced on the bytes actually read, and the bytes actually read across all entries SHALL also count against `csv.import.zip.max-total-uncompressed-size` (`manifest.json` has no separate limit beyond that total), so an entry whose header understates its size SHALL be rejected with HTTP 400 as soon as it exceeds its limit. If that happens after files were already written, those writes SHALL be undone as described in "ZIP import leaves storage unchanged when it fails".

Status: **Implemented**

#### Scenario: Too many entries
- **WHEN** a ZIP holds more entries than `csv.import.zip.max-entries`
- **THEN** the system SHALL return HTTP 400 and SHALL NOT upload any file

#### Scenario: Highly compressed oversize entry
- **WHEN** a referenced entry declares a small size in its header but decompresses beyond `dial.file-storage.max-file-size-bytes`
- **THEN** the system SHALL return HTTP 400, and every file written by that import SHALL be deleted or restored

#### Scenario: Path traversal entry
- **WHEN** a ZIP contains an entry named `files/../../etc/passwd`
- **THEN** the system SHALL return HTTP 400 and SHALL NOT upload any file

#### Scenario: Rewritten CSV exceeds the size limit
- **WHEN** a ZIP's `test-cases.csv` is just under `csv.import.max-file-size` and replacing its file paths with file references pushes it over the limit
- **THEN** the system SHALL return HTTP 400 and SHALL NOT write any file

#### Scenario: Capacity counts only new files
- **WHEN** a dataset holds the maximum number of files and a ZIP import only overwrites same-name files
- **THEN** the import SHALL succeed

#### Scenario: Capacity exceeded by new files
- **WHEN** a ZIP import would create more new files than the dataset's remaining capacity
- **THEN** the system SHALL return HTTP 400 and SHALL NOT upload any file

### Requirement: ZIP import leaves storage unchanged when it fails
When a ZIP import fails for any reason (version conflict, name collision under `conflictStrategy=FAIL`, row limit, storage error or any other error), the system SHALL try to leave the dataset's files as they were before the import:
- files the import newly created SHALL be deleted;
- files the import overwrote SHALL be restored to their previous content.

A restored file SHALL keep its previous content type. If the previous content of a file cannot be saved before it would be overwritten, the import SHALL fail without overwriting that file. This restoration is best effort: a failure to restore or delete SHALL be logged and SHALL NOT replace the original error returned to the client. When an `If-Match` version is supplied and does not match the dataset's current version, the system SHALL return HTTP 409 without writing any file.

Status: **Implemented**

#### Scenario: Name collision after uploads
- **WHEN** a ZIP import with `importMode=APPEND` and `conflictStrategy=FAIL` uploads its files and then fails with HTTP 409 on a test-case name collision
- **THEN** the files it created SHALL no longer exist, files it overwrote SHALL hold their previous content, and the client SHALL receive the 409

#### Scenario: Stale If-Match writes nothing
- **WHEN** a ZIP import is sent with an `If-Match` version that does not match the dataset's current version
- **THEN** the system SHALL return HTTP 409 and no file in the dataset SHALL be created or changed

### Requirement: ZIP import preview matches import
ZIP import preview SHALL apply the same archive validation, manifest handling, schema resolution and cell rewriting as ZIP import, but SHALL NOT upload, overwrite or delete any file. For the same archive and parameters, preview SHALL report the outcome import would produce:
- FILE cells in sample rows SHALL show the dataset file reference import would store;
- missing-file warnings SHALL be reported with the same row and column;
- the reported schema, validity and warnings SHALL match what import would produce.

An archive that import would reject SHALL be rejected by preview with the same status. Preview takes no `If-Match` and so performs no version check.

Status: **Implemented**

#### Scenario: Preview shows future file references
- **WHEN** a client previews a ZIP whose FILE cell references `files/1/report.pdf`, for a dataset whose schema declares that column `FILE`
- **THEN** the sample row SHALL show `@ef/datasets/{datasetId}/report.pdf` (per the configured bucket alias), the sample SHALL carry no FILE format warning, and no file SHALL be written

#### Scenario: Preview reports missing files
- **WHEN** a client previews a ZIP whose CSV references a path absent from the archive
- **THEN** the preview SHALL report the same missing-file warning import would report

#### Scenario: Preview rejects what import rejects
- **WHEN** a client previews a ZIP that exceeds `csv.import.zip.max-entries`
- **THEN** the preview SHALL return HTTP 400

### Requirement: Repeatable ZIP round trip
Exporting a dataset as ZIP and importing the archive with `importMode=OVERRIDE`, whether into the same dataset or into a dataset with an empty schema, SHALL reproduce the items below. The guarantee covers datasets whose EF-owned references all point at the dataset's own files. A reference to a legacy suite file or to another dataset's file is copied into the target dataset on the first round trip; its stored value changes to the target's file and the file count grows by one. After that first round trip the guarantee applies. The items:
- the source dataset's `testCaseSchema` (every field's `type`, `perTurn`, `required`, `displayName`, `description`);
- each test case's `testCaseName`, shared `data` and `multiTurnData` (turn count and per-turn values);
- the content of every referenced EF-owned file;
- every `public/…` reference unchanged.

Repeating export → import SHALL yield the same result each time. Once every EF-owned reference points at the dataset's own files, round trips into the same dataset SHALL NOT increase its file count. A value the source case omitted MAY become blank at the destination (an empty string, or `{}` / `[]` for OBJECT / ARRAY fields), since a CSV cannot tell an absent value from a blank one.

Status: **Implemented**

#### Scenario: Double OVERRIDE round trip into the same dataset
- **WHEN** a dataset whose EF-owned references all point at its own files, with a multi-turn case, a shared FILE field and a per-turn FILE field, is exported as ZIP and imported back with `importMode=OVERRIDE`, and this is repeated a second time
- **THEN** after each import the schema SHALL equal the original, the case SHALL carry the original turns and values with FILE references pointing at files with the original content, and the dataset's file count SHALL equal the original

#### Scenario: First round trip copies a legacy suite reference
- **WHEN** a dataset whose case references a legacy `@ef/suites/{suiteId}/guide.pdf` is exported as ZIP and imported back with `importMode=OVERRIDE`, then exported and imported again
- **THEN** after the first import the case SHALL reference `@ef/datasets/{datasetId}/guide.pdf` with the same content and the file count SHALL have grown by one; the second round trip SHALL change neither the value nor the file count

#### Scenario: Round trip into another dataset
- **WHEN** a dataset is exported as ZIP and imported with `importMode=OVERRIDE` into a dataset with an empty schema
- **THEN** the destination's schema and test cases SHALL equal the source's, with EF-owned references pointing at the destination's own files of equal content

### Requirement: ZIP archives carry test-case data only
A ZIP archive SHALL carry test-case data and schema only. It SHALL NOT carry suite configuration (request templates, `additionalRequests`, bindings) or any per-request dimension. Test cases imported from a ZIP SHALL run under any suite bound to the dataset, including suites with `additionalRequests`, exactly as test cases created through the API.

Status: **Implemented**

#### Scenario: Imported cases run under a multi-request suite
- **WHEN** a dataset imported from a ZIP holds a 2-turn case with a shared FILE field and a per-turn field, and it is run by a suite with one additional request in which each request binds a different field
- **THEN** the run SHALL produce result rows for every `(request_index, turn_index)` pair the suite's bindings require, with FILE values resolved from the imported dataset files
