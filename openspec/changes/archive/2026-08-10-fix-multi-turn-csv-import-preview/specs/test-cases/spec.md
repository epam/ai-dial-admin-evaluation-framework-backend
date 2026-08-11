## MODIFIED Requirements

### Requirement: Import preview (CSV or ZIP)
The import preview endpoint SHALL support both CSV and ZIP formats with the same detection logic as the import endpoint.

The preview response SHALL report both `totalRows` — the number of CSV data rows parsed — and `totalTestCases` — the number of test cases those rows assemble into. The two differ only when the CSV contains multi-turn cases, whose turn rows assemble into one case each; for a single-turn CSV they are equal. Both describe the CSV as submitted and SHALL NOT be reduced by rows a conflict strategy would skip.

`sampleRows` SHALL contain assembled test cases (bounded by the sample limit), not raw CSV rows. A sample for a multi-turn case SHALL carry its `multiTurnData` turn array and its shared `data`; a sample for a single-turn case SHALL carry a flat `data` with no turn array.

Status: **Implemented**

#### Scenario: Preview CSV file
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import/preview` with a CSV file
- **THEN** system SHALL return the preview (current behavior)

#### Scenario: Preview ZIP archive
- **WHEN** client sends `POST /api/v1/datasets/{datasetId}/test-cases/import/preview` with a ZIP file
- **THEN** system SHALL extract and preview the `test-cases.csv` within the archive
- **AND** FILE columns SHALL show the relative paths from the CSV (not DIAL file paths, since files are not yet uploaded during preview)

#### Scenario: Preview reports test case count alongside row count
- **WHEN** client previews a CSV whose rows include a multi-turn case of N turns
- **THEN** `totalRows` SHALL count every CSV data row and `totalTestCases` SHALL count the N turn rows as one test case

#### Scenario: Multi-turn sample carries the validity import would produce
- **WHEN** client previews a CSV containing a multi-turn case
- **THEN** the sample's validity and warnings SHALL be those the import would compute for the assembled case — schema validation of its shared and per-turn data, merged with any multi-turn conflict — not a default or a per-row verdict

#### Scenario: Single-turn CSV preview is unchanged apart from the new count
- **WHEN** client previews a CSV containing no `turnIndex` values
- **THEN** `totalTestCases` SHALL equal `totalRows`, each sample row SHALL carry a flat `data` with no turn array, and no other previously reported field SHALL change value

### Requirement: CSV conflict strategy parameter
The CSV import and import preview endpoints SHALL accept an optional `conflictStrategy` query parameter of type `CsvConflictStrategy` enum with values `FAIL`, `SKIP`, and `OVERRIDE`. When omitted, the system SHALL default to `FAIL`. The conflict strategy governs behavior when a `testCaseName` collision occurs — either a CSV row name matching an existing test case in the suite (case-insensitive), or a duplicate name within the CSV itself. The parameter applies to all import modes, including `OVERRIDE` (where cross-import collisions are impossible after deleteAll, but within-CSV duplicates are still subject to the strategy). Within-CSV duplicates are handled identically to cross-import collisions under the chosen strategy: `FAIL` rejects the import with HTTP 409 on the first duplicate, `SKIP` silently skips duplicate rows (first wins), `OVERRIDE` replaces the earlier row with the later one (last wins via upsert).

Collision and duplicate detection SHALL key on the **assembled test case**, not the raw CSV row. Consecutive rows sharing a `testCaseName` and carrying a `turnIndex` that parses as an integer assemble into one multi-turn test case and SHALL count as a single name occurrence — turn rows of one case are never duplicates of each other. Consecutive rows sharing a `testCaseName` with a blank `turnIndex` remain separate test cases and SHALL each count as an occurrence, so same-named single-turn rows collide exactly as before.

Status: **Implemented**

#### Scenario: FAIL strategy rejects import on name collision
- **WHEN** client calls import with any `importMode` and `conflictStrategy=FAIL` (or omitted) and a `testCaseName` collision occurs (a CSV row name matching an existing test case in APPEND/MERGE modes, or a within-CSV duplicate in any mode)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message identifying the first colliding name; no rows SHALL be imported (transaction rolled back by DB constraint)

#### Scenario: SKIP strategy skips colliding rows
- **WHEN** client calls import with any `importMode` and `conflictStrategy=SKIP` and `testCaseName` collisions occur (cross-import collisions in APPEND/MERGE, or within-CSV duplicates in any mode)
- **THEN** system SHALL skip those rows (using `INSERT ... ON CONFLICT DO NOTHING`), import the remaining rows, and return result with `skippedCount` set to the total number of skipped rows

#### Scenario: OVERRIDE strategy replaces colliding rows
- **WHEN** client calls import with any `importMode` and `conflictStrategy=OVERRIDE` and `testCaseName` collisions occur (cross-import collisions in APPEND/MERGE, or within-CSV duplicates in any mode)
- **THEN** system SHALL replace the matching rows with the CSV rows (using `INSERT ... ON CONFLICT DO UPDATE`); result SHALL include `overriddenCount` set to the number of replaced rows
- **Note:** Replacement is a full data substitution — the existing row's `data` is entirely replaced with the CSV row's data. Fields defined in the schema but absent from the CSV row are NOT preserved. Field-level merge of existing and imported row data is out of scope.

#### Scenario: Conflict strategy applies in OVERRIDE import mode for within-CSV duplicates
- **WHEN** client calls import with `importMode=OVERRIDE` (which deletes all existing test cases first) and the CSV file contains two or more rows with the same `testCaseName` (case-insensitive)
- **THEN** system SHALL handle those within-CSV duplicates according to `conflictStrategy`: FAIL → HTTP 409 on the second occurrence; SKIP → first occurrence wins, `skippedCount` is set; OVERRIDE → last occurrence wins via upsert, `overriddenCount` is set

#### Scenario: OVERRIDE import mode + SKIP strategy returns skippedCount for within-CSV duplicates
- **WHEN** client calls import with `importMode=OVERRIDE` and `conflictStrategy=SKIP` and the CSV contains rows with duplicate `testCaseName` values
- **THEN** system SHALL import the first occurrence of each duplicate name, skip subsequent duplicates, and return `skippedCount` equal to the number of skipped within-CSV duplicate rows; `overriddenCount` SHALL be null

#### Scenario: OVERRIDE import mode + OVERRIDE strategy returns overriddenCount for within-CSV duplicates
- **WHEN** client calls import with `importMode=OVERRIDE` and `conflictStrategy=OVERRIDE` and the CSV contains rows with duplicate `testCaseName` values
- **THEN** system SHALL store the last occurrence of each duplicate name (via upsert) and return `overriddenCount` equal to the number of replaced within-CSV duplicate rows; `skippedCount` SHALL be null

#### Scenario: Default conflict strategy
- **WHEN** client calls import without `conflictStrategy` parameter
- **THEN** system SHALL behave as `FAIL`

#### Scenario: Preview shows conflict-specific context
- **WHEN** client calls preview with `importMode=APPEND` (or `MERGE`) and CSV row names collide with existing test case names
- **THEN** system SHALL include warnings indicating which names collide and what action the current `conflictStrategy` would take (skip / override / fail)

#### Scenario: Within-CSV duplicates follow conflictStrategy
- **WHEN** client calls import with any `importMode` and the CSV contains multiple rows with the same `testCaseName` (case-insensitive)
- **THEN** system SHALL handle them per `conflictStrategy`: FAIL → HTTP 409 on the first within-CSV duplicate encountered; SKIP → first occurrence is imported, subsequent duplicates are silently skipped (skippedCount incremented); OVERRIDE → last occurrence wins via upsert (overriddenCount incremented for each replacement)

#### Scenario: Preview annotates within-CSV duplicates with strategy-appropriate warnings
- **WHEN** client calls the preview endpoint with a CSV that contains duplicate `testCaseName` values
- **THEN** preview response SHALL annotate duplicate rows with strategy-appropriate warnings (FAIL: "would cause import failure"; SKIP: "would be skipped"; OVERRIDE: "would replace earlier row"); no HTTP 409 is returned from the preview endpoint itself

#### Scenario: Turn rows of one case are not a name collision
- **WHEN** client imports or previews a CSV whose consecutive rows share a `testCaseName` and carry distinct non-blank `turnIndex` values
- **THEN** the system SHALL treat them as one test case name occurrence — no duplicate warning on import or preview, no HTTP 409 under `FAIL`, and no `skippedCount`/`overriddenCount` increment under `SKIP`/`OVERRIDE`

#### Scenario: Same-named single-turn rows still collide
- **WHEN** client imports or previews a CSV with two adjacent rows carrying the same `testCaseName` and a blank `turnIndex`
- **THEN** the second row SHALL be treated as a within-CSV duplicate exactly as before, per the chosen `conflictStrategy`

### Requirement: OVERRIDE mode schema handling
In OVERRIDE mode, the system SHALL always auto-detect the schema from the CSV and persist it to the suite, replacing any existing schema. This applies whether the suite schema is empty or not.

Replacement covers field **membership and types** only. Each field's `perTurn` scope SHALL be carried forward from the dataset's current schema by field name, because a CSV expresses values and never scope — see the `multi-turn-test-case` capability, requirement *CSV schema rebuild preserves per-field scope*. A CSV column with no same-named field in the current schema is a new field and SHALL be persisted with `perTurn` absent.

Status: **Implemented**

#### Scenario: OVERRIDE replaces existing schema
- **WHEN** client calls import with `importMode=OVERRIDE` and the suite has an existing `testCaseSchema`
- **THEN** system SHALL replace the schema with the auto-detected schema from CSV, persist it, and bump the suite version

#### Scenario: OVERRIDE with empty schema auto-detects
- **WHEN** client calls import with `importMode=OVERRIDE` and the suite's `testCaseSchema` is empty
- **THEN** system SHALL auto-detect schema from CSV, persist, and bump suite version (same as when schema exists)

#### Scenario: OVERRIDE preview shows replacement schema
- **WHEN** client calls preview with `importMode=OVERRIDE`
- **THEN** the preview response SHALL include `autoDetectedSchema` regardless of whether the suite already has a schema

#### Scenario: OVERRIDE replacement keeps field scope
- **WHEN** client calls import with `importMode=OVERRIDE` and an existing schema field is marked `perTurn: true`
- **THEN** the replacement schema SHALL still mark that field `perTurn: true`, while its type is re-derived from the CSV as usual

## Implementation notes
- Preview and import: `service/domain/CsvImportService.java`; preview response DTO `service/domain/dto/csv/CsvImportPreviewDto.java`.
- Endpoints: `web/controller/TestCaseController.java` (`import`, `import/preview`).
- OpenAPI examples: `src/main/resources/openapi/examples/api-v1-datasets-datasetId-test-cases-import-preview-POST-response-200-{minimal,full}.json`.
- Functional coverage: `CsvImportModeFunctionalTests` (single-turn contract, must stay green unchanged) and `MultiTurnCsvFunctionalTests` (multi-turn preview and round trip).
