## MODIFIED Requirements

### Requirement: Flat CSV import/export multiplication
CSV import/export SHALL remain flat: a multi-turn case is represented as one row per turn. A reserved `turnIndex` header groups and orders turns; it and `testCaseName` are excluded from `data` and from schema auto-detection. Per-turn columns vary per row. Shared columns SHALL be repeated on every turn row of a case; on import the shared columns of a case's rows MUST be identical, and a mismatch SHALL be reported as a conflict warning that invalidates the case. Single-turn cases export one row with a blank `turnIndex`.

The round trip SHALL be **repeatable**: importing an exported CSV back into a dataset, and then importing an export of the result again, SHALL yield the same test cases each time — the second and every subsequent import SHALL produce the same `data` and `multiTurnData` as the first.

This guarantee holds under two conditions. First, the dataset's `testCaseSchema` declares every field the case carries — export derives its column set from the schema, so a key held by a case but absent from the schema is omitted from the CSV and cannot survive any round trip. Second, re-importing the same names is a defined write: `importMode=OVERRIDE` with any `conflictStrategy`, or `APPEND`/`MERGE` with `conflictStrategy=OVERRIDE`. `APPEND`/`MERGE` with `FAIL` (HTTP 409) or `SKIP` (nothing written) is correct collision handling, not a round-trip defect, and is excluded.

Status: **Implemented**

#### Scenario: Export multiplies turns to rows
- **WHEN** a multi-turn case with N turns is exported
- **THEN** it produces N contiguous rows sharing `testCaseName`, with `turnIndex` `0..N-1` in order; shared columns carry the same value on every row; single-turn cases export one row with a blank `turnIndex`

#### Scenario: Import assembles a contiguous run into one case
- **WHEN** consecutive import rows share a `testCaseName` and carry non-blank `turnIndex` values
- **THEN** they are assembled into one multi-turn test case whose `multiTurnData` is the per-turn columns sorted by `turnIndex`, and whose shared `data` is taken from the (identical) shared columns

#### Scenario: Conflicting shared columns are a conflict
- **WHEN** two turn rows of the same case carry different values for a shared column
- **THEN** a conflict warning is reported and the case is invalidated

#### Scenario: Non-contiguous name is a conflict
- **WHEN** a `testCaseName` reappears non-contiguously, or a `turnIndex` is duplicated within a run
- **THEN** a row/conflict error is reported

#### Scenario: Re-importing an exported CSV twice is stable
- **WHEN** a dataset containing a multi-turn case is exported, that CSV is imported back into the dataset with `importMode=OVERRIDE`, the result is exported again, and that second CSV is imported with `importMode=OVERRIDE`
- **THEN** after both imports the case SHALL carry the same number of turns with the same per-turn values as the original, and its shared `data` SHALL be unchanged
- **AND** no per-turn value SHALL be promoted into the shared `data` map, and no turn map SHALL become empty

## ADDED Requirements

### Requirement: CSV schema rebuild preserves per-field scope
When a CSV import rebuilds or updates the dataset's `testCaseSchema` (any mode that persists a schema), the system SHALL preserve each field's `perTurn` scope from the dataset's current schema, matched by field name. A CSV expresses only values, never scope, so scope SHALL NOT be re-derived from the CSV. This preservation SHALL apply consistently everywhere a schema is derived from CSV columns during import or preview: the schema imported rows are validated against, the schema persisted to the dataset, the schema used to re-validate rows after persistence, and the `autoDetectedSchema` reported by preview.

A CSV column with no same-named field in the dataset's current schema is a new field and SHALL keep the existing auto-detection defaults, with `perTurn` absent (shared).

Deriving a schema from CSV columns SHALL NOT mutate the dataset's current schema field definitions.

Status: **Implemented**

#### Scenario: OVERRIDE import preserves perTurn on the persisted schema
- **WHEN** a dataset has a schema field marked `perTurn: true` and a CSV containing that column is imported with `importMode=OVERRIDE`
- **THEN** the persisted `testCaseSchema` SHALL still mark that field `perTurn: true`

#### Scenario: Preview reports perTurn in autoDetectedSchema
- **WHEN** a client previews a CSV with `importMode=OVERRIDE` against a dataset whose schema marks a field `perTurn: true`
- **THEN** the `autoDetectedSchema` in the preview response SHALL mark that field `perTurn: true`, so a client writing it back does not strip the scope

#### Scenario: New CSV column defaults to shared
- **WHEN** a CSV contains a data column with no same-named field in the dataset's current schema
- **THEN** the derived field definition SHALL omit `perTurn` (shared scope)

#### Scenario: Empty dataset schema has nothing to preserve
- **WHEN** a CSV is imported into a dataset whose `testCaseSchema` is empty
- **THEN** the persisted schema SHALL omit `perTurn` on every field, so every field is shared
- **AND** the CSV's columns SHALL all be treated as shared, which for a CSV carrying turn rows means the ordinary shared-column rules apply — turn rows that differ on a column are a conflict and invalidate the case, exactly as for any all-shared schema

### Requirement: CSV import preview assembles multi-turn cases
CSV import preview SHALL group CSV rows into test cases using the same rules as import, and report per assembled test case rather than per CSV row. Turn rows of one multi-turn case SHALL NOT be reported as duplicate names of each other.

A run is a maximal group of consecutive rows sharing a `testCaseName`. A run whose rows carry a non-blank `turnIndex` assembles into **one** multi-turn test case. A run of K rows with a blank `turnIndex` remains **K separate** test cases. Duplicate-name and existing-name collision detection SHALL therefore key once per multi-turn run and once per row of a single-turn run.

Preview SHALL report the multi-turn conflicts import would report for the same CSV — duplicate `turnIndex` within a case, a multi-turn `testCaseName` reappearing non-contiguously, and shared columns differing across a case's turn rows — anchored to the run's first CSV row number, matching import.

Status: **Implemented**

#### Scenario: Turn rows are not flagged as duplicate names
- **WHEN** a client previews a CSV whose rows are the turns of one multi-turn case (same `testCaseName`, distinct `turnIndex` values)
- **THEN** the preview SHALL NOT emit a within-CSV duplicate-name warning for any of those rows

#### Scenario: Multi-turn sample rows carry their turns
- **WHEN** a client previews a CSV containing a multi-turn case
- **THEN** the corresponding sample row SHALL be the assembled case, carrying its `multiTurnData` turn array and its shared `data`, not one sample per turn row

#### Scenario: Adjacent same-named single-turn rows stay separate cases
- **WHEN** a client previews a CSV with two adjacent rows carrying the same `testCaseName` and a blank `turnIndex`
- **THEN** the preview SHALL still emit a within-CSV duplicate-name warning for the second row, and SHALL count them as two test cases

#### Scenario: Preview predicts multi-turn conflicts
- **WHEN** a client previews a CSV in which one case's turn rows repeat a `turnIndex`, or disagree on a shared column value, or a multi-turn `testCaseName` reappears after another case
- **THEN** the preview SHALL report the same conflict import would report, anchored to the first CSV row number of the affected run

### Requirement: Post-persist CSV fixup preserves multi-turn shape
After a CSV import persists a schema whose field types were newly determined, the system re-reads the dataset's test cases to coerce stored values to the new types. This pass SHALL treat a multi-turn case as multi-turn: it SHALL coerce per-turn values as well as shared `data`, SHALL re-validate the case against the shared and per-turn scopes of the schema rather than as a single-turn case, and SHALL persist the updated turn array.

If a case's stored turn array is present but unreadable, the system SHALL leave that case untouched and log a warning; it SHALL NOT write the case back, because writing an unreadable turn array back would convert the case to single-turn and destroy its turns.

Status: **Implemented**

#### Scenario: Per-turn values are coerced to the new type
- **WHEN** an import determines a per-turn field's type and a stored multi-turn case holds values for that field needing coercion
- **THEN** the values inside each turn SHALL be coerced to the new type and the updated turn array SHALL be persisted

#### Scenario: Multi-turn case is re-validated as multi-turn
- **WHEN** the fixup pass re-validates a case that carries a turn array
- **THEN** validity and warnings SHALL be computed from the case's shared data against the schema's shared fields and each turn against the schema's per-turn fields, not from the shared data against the whole schema

#### Scenario: Unreadable turn array is skipped, never overwritten
- **WHEN** the fixup pass encounters a case whose stored turn array is present but cannot be read
- **THEN** the case SHALL be left unchanged in the database and a warning SHALL be logged
- **AND** the case SHALL NOT be rewritten as a single-turn case

#### Scenario: Single-turn cases are unaffected
- **WHEN** the fixup pass processes a case with no turn array
- **THEN** it SHALL behave exactly as before — coercing and re-validating the shared `data` only

#### Scenario: Fixup does not erase an import conflict
- **WHEN** the fixup pass re-validates a case that carries an import-derived conflict warning
- **THEN** that warning SHALL be carried forward and the case SHALL stay invalid, per the durable-conflict-warning requirement

### Requirement: Import-derived conflict warnings are durable
A CSV import detects conflicts that describe the submitted **rows** rather than the resulting test case: a `turnIndex` repeated within a case, and turn rows disagreeing on a shared column. The assembled case is well-formed, so these findings cannot be re-derived from stored data by any later pass.

(The third multi-turn conflict, a `testCaseName` reappearing non-contiguously, is not covered by this requirement: it describes a second run of a name that already exists, and no conflict strategy leaves a conflicted case behind — the request is rejected, the second run is skipped, or it deliberately replaces the earlier case — so it remains a request-scoped import warning as today.)

Such warnings SHALL be persisted with a distinguishing code that marks them as derived from the import source. Any pass that **recomputes** a test case's validity from its stored state — the post-import coercion fixup and dataset revalidation alike — SHALL carry forward the stored source-derived warnings and SHALL report the case invalid while any remain, in addition to whatever it computes itself.

A direct API write of a test case (`PUT` or `PATCH`) is not a recomputation: the caller supplies new content, so these warnings SHALL be cleared as they are today. A source-derived warning therefore persists until the case is edited or re-imported.

Status: **Implemented**

#### Scenario: Import conflict survives the import request
- **WHEN** a CSV whose turn rows repeat a `turnIndex` is imported, the import persists a schema, and the coercion fixup and revalidation both run before the response is returned
- **THEN** the persisted case SHALL still be marked invalid and SHALL still carry the conflict warning reported in the import response

#### Scenario: Import conflict survives a later schema change
- **WHEN** a case carrying an import-derived conflict warning is revalidated after a subsequent dataset schema change
- **THEN** the warning SHALL still be present and the case SHALL still be invalid, alongside any new warnings the revalidation computes

#### Scenario: Import conflict survives value coercion
- **WHEN** the post-import fixup coerces a conflicted case's values to a newly determined schema type and re-validates it
- **THEN** the recomputed result SHALL be unioned with the stored conflict warning and the case SHALL stay invalid, rather than being reported valid because the assembled case is well-formed

#### Scenario: Editing the case clears the conflict
- **WHEN** a client `PUT`s or `PATCH`es a case that carries an import-derived conflict warning
- **THEN** validity and warnings SHALL be computed from the submitted content alone and the conflict warning SHALL NOT be carried forward

#### Scenario: Cases without source conflicts are unaffected
- **WHEN** a recomputation pass processes a case carrying no source-derived warning
- **THEN** it SHALL write exactly the result it computes, with no change from current behavior

### Requirement: Dataset revalidation preserves multi-turn shape
Dataset-rooted revalidation (Phase 1) re-coerces and re-validates the test cases of a dataset. It runs on a dataset schema change and also as part of any CSV import that persists a schema.

It SHALL treat a case carrying a turn array as multi-turn: it SHALL coerce the values inside each turn as well as the shared `data`, and SHALL compute validity and warnings from the shared data against the schema's shared fields and each turn against the schema's per-turn fields — never from the shared `data` against the whole schema. The updated turn array SHALL be persisted together with the shared data under the same concurrent-edit guard that protects the shared data today.

If a case's stored turn array is present but unreadable, revalidation SHALL leave that case untouched and log a warning, writing neither its data nor its validity — rewriting it would convert the case to single-turn and destroy every turn.

Status: **Implemented**

#### Scenario: Per-turn values are validated
- **WHEN** revalidation processes a multi-turn case whose turn holds a value that does not match its per-turn field's schema type
- **THEN** the case SHALL be marked invalid with the corresponding type warning, rather than valid because its shared `data` alone is consistent

#### Scenario: Per-turn values are coerced
- **WHEN** revalidation runs after a schema type change and a multi-turn case holds a coercible value inside a turn for that field
- **THEN** the value inside the turn SHALL be coerced by the same rules applied to shared `data`, and the updated turn array SHALL be persisted together with the shared data under the same concurrent-edit guard

#### Scenario: Concurrent edit still wins
- **WHEN** a multi-turn case is edited by another caller between revalidation reading it and writing the coerced result
- **THEN** the write SHALL affect no rows and revalidation SHALL skip that case, exactly as for a single-turn case today

#### Scenario: Unreadable turn array is skipped, never overwritten
- **WHEN** revalidation encounters a case whose stored turn array is present but cannot be read
- **THEN** the case SHALL be left unchanged in the database and a warning SHALL be logged
- **AND** the case SHALL NOT be rewritten as a single-turn case
- **AND** the row SHALL count toward the task's processed cases but SHALL NOT increment its valid or invalid counts, consistent with the existing concurrent-edit skip

#### Scenario: Removing a per-turn field prunes it from stored turns
- **WHEN** a field is removed from a dataset's `testCaseSchema` and the system prunes that field from stored test case data
- **THEN** the field SHALL be removed from each turn of a multi-turn case as well as from the shared `data`
- **AND** the subsequent revalidation SHALL NOT report the removed field as an unknown field on any turn

#### Scenario: Single-turn revalidation is unchanged
- **WHEN** revalidation processes a case with no turn array
- **THEN** it SHALL coerce and validate exactly as before, with the same guarded-update and skip behavior

## Implementation notes
- CSV import and preview: `service/domain/CsvImportService.java`; schema derivation, run grouping and multi-turn assembly live in injectable components under `service/domain/csv/`.
- Field scope partitioning: `service/domain/TestCaseFieldScopeResolver.java`.
- Export reference behavior (unchanged): `service/domain/CsvExportService.java` — its column set comes from the dataset schema.
- Dataset revalidation Phase 1: `service/domain/RevalidationService.java`; the guarded write of `data` + `multi_turn_data` lives in `data/db/repository/TestCaseRepository.java` and its Postgres implementation.
- Functional coverage: `src/test/java/com/epam/aidial/evaluation/functional/tests/MultiTurnCsvFunctionalTests.java`.
