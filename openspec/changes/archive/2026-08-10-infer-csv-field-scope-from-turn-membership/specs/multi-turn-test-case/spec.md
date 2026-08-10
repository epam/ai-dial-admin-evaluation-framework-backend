## MODIFIED Requirements

### Requirement: CSV schema rebuild preserves per-field scope
When a CSV import rebuilds or updates the dataset's `testCaseSchema` (any mode that persists a schema), the system SHALL resolve each derived field's `perTurn` scope in this precedence order:

1. **Declared** — the field name exists in the dataset's current schema: its `perTurn` value is preserved verbatim, including an absent/false value (declared shared). The declared test is the field's *presence* in the current schema, not the presence of a non-null `perTurn` value — a declared field whose `perTurn` is absent is declared shared and SHALL NOT be re-scoped by inference.
2. **Inferred from the file's turn structure** — the field is undeclared and the CSV contains at least one multi-turn case (a contiguous same-`testCaseName` run where some row's `turnIndex` parses to an integer): the derived field SHALL carry `perTurn: true`. The gate is file-level: one multi-turn case makes every undeclared column per-turn.
3. **Default shared** — the field is undeclared and the CSV contains no multi-turn case: `perTurn` SHALL be absent (shared).

This resolution SHALL apply consistently everywhere a schema is derived from CSV columns during import or preview: the schema imported rows are validated against, the schema persisted to the dataset, the schema used to re-validate rows after persistence, and the `autoDetectedSchema` reported by preview. Preview SHALL report exactly the scope import would persist for the same CSV, and the shared/per-turn partitioning of an imported multi-turn case's values SHALL agree with the persisted scope.

Scope inference SHALL require no additional read of the CSV: a single streaming pass over the rows SHALL suffice for both scope resolution and case assembly.

Deriving a schema from CSV columns SHALL NOT mutate the dataset's current schema field definitions, and export SHALL NOT change: shared values remain repeated on every turn row, so an export → import → export round trip through a schema-less dataset yields byte-identical CSV content.

Status: **Implemented**

#### Scenario: OVERRIDE import preserves perTurn on the persisted schema
- **WHEN** a dataset has a schema field marked `perTurn: true` and a CSV containing that column is imported with `importMode=OVERRIDE`
- **THEN** the persisted `testCaseSchema` SHALL still mark that field `perTurn: true`

#### Scenario: Preview reports perTurn in autoDetectedSchema
- **WHEN** a client previews a CSV with `importMode=OVERRIDE` against a dataset whose schema marks a field `perTurn: true`
- **THEN** the `autoDetectedSchema` in the preview response SHALL mark that field `perTurn: true`, so a client writing it back does not strip the scope

#### Scenario: Declared-shared field is never re-scoped by inference
- **WHEN** a dataset's current schema declares a field with `perTurn` absent (shared) and a multi-turn CSV carries that column with values differing across a case's turn rows
- **THEN** the derived schema SHALL keep the field shared, and the turn-row disagreement SHALL be reported as a shared-column conflict that invalidates the case

#### Scenario: New CSV column defaults to shared
- **WHEN** a CSV containing no multi-turn case carries a data column with no same-named field in the dataset's current schema
- **THEN** the derived field definition SHALL omit `perTurn` (shared scope)

#### Scenario: Empty dataset schema has nothing to preserve
- **WHEN** a CSV with no multi-turn case is imported into a dataset whose `testCaseSchema` is empty
- **THEN** no field is declared, the file-level gate does not fire, and the persisted schema SHALL omit `perTurn` on every field — identical to the pre-inference behavior

#### Scenario: Multi-turn CSV into an empty dataset infers per-turn scope
- **WHEN** a CSV containing a multi-turn case is imported into a dataset whose `testCaseSchema` is empty
- **THEN** every data column SHALL be persisted with `perTurn: true`, the case SHALL retain all its turns in `multiTurnData`, and no shared-column conflict SHALL be reported

#### Scenario: Mixed CSV marks every undeclared column per-turn
- **WHEN** a CSV containing both multi-turn and single-turn cases is imported into a dataset whose `testCaseSchema` is empty
- **THEN** the persisted schema SHALL mark every field `perTurn: true`, the multi-turn cases SHALL retain their turns, and the single-turn cases SHALL keep their values in `data` and remain valid

#### Scenario: Preview reports the scope import would persist
- **WHEN** a client previews a multi-turn CSV against a dataset whose `testCaseSchema` is empty
- **THEN** the `autoDetectedSchema` SHALL mark every data column `perTurn: true`, the multi-turn sample rows SHALL carry those columns in their `multiTurnData` turn maps, and no shared-column conflict SHALL be reported

### Requirement: Flat CSV import/export multiplication
CSV import/export SHALL remain flat: a multi-turn case is represented as one row per turn. A reserved `turnIndex` header groups and orders turns; it and `testCaseName` are excluded from `data` and from schema auto-detection. Per-turn columns vary per row. Shared columns SHALL be repeated on every turn row of a case; on import the columns whose **resolved scope is shared** (declared shared in the dataset's current schema, or defaulted shared by scope resolution) MUST be identical across a case's turn rows, and a mismatch SHALL be reported as a conflict warning that invalidates the case. Columns whose resolved scope is per-turn — declared or inferred from the file's turn structure — are NOT subject to this identity rule. Single-turn cases export one row with a blank `turnIndex`.

The round trip SHALL be **repeatable**: importing an exported CSV back into a dataset, and then importing an export of the result again, SHALL yield the same test cases each time — the second and every subsequent import SHALL produce the same `data` and `multiTurnData` as the first.

This guarantee holds under two conditions. First, the dataset's `testCaseSchema` declares every field the case carries — export derives its column set from the schema, so a key held by a case but absent from the schema is omitted from the CSV and cannot survive any round trip. Second, re-importing the same names is a defined write: `importMode=OVERRIDE` with any `conflictStrategy`, or `APPEND`/`MERGE` with `conflictStrategy=OVERRIDE`. `APPEND`/`MERGE` with `FAIL` (HTTP 409) or `SKIP` (nothing written) is correct collision handling, not a round-trip defect, and is excluded.

Additionally, importing an exported multi-turn CSV into a **different dataset whose `testCaseSchema` is empty** SHALL reproduce the source's turn structure: every turn survives with its per-turn values, and re-exporting the destination SHALL yield CSV content identical to the source export. The destination schema MAY differ from the source schema in scope (columns the source declared shared are inferred per-turn at the destination), and a value the source case omitted MAY materialize as an empty string at the destination (a CSV cannot distinguish an absent value from a blank one); each turn's merged effective view SHALL otherwise equal the source case's.

Status: **Implemented**

#### Scenario: Export multiplies turns to rows
- **WHEN** a multi-turn case with N turns is exported
- **THEN** it produces N contiguous rows sharing `testCaseName`, with `turnIndex` `0..N-1` in order; shared columns carry the same value on every row; single-turn cases export one row with a blank `turnIndex`

#### Scenario: Import assembles a contiguous run into one case
- **WHEN** consecutive import rows share a `testCaseName` and carry non-blank `turnIndex` values
- **THEN** they are assembled into one multi-turn test case whose `multiTurnData` is the per-turn columns sorted by `turnIndex`, and whose shared `data` is taken from the (identical) shared columns

#### Scenario: Conflicting shared columns are a conflict
- **WHEN** two turn rows of the same case carry different values for a column whose resolved scope is shared
- **THEN** a conflict warning is reported and the case is invalidated

#### Scenario: Non-contiguous name is a conflict
- **WHEN** a `testCaseName` reappears non-contiguously, or a `turnIndex` is duplicated within a run
- **THEN** a row/conflict error is reported

#### Scenario: Re-importing an exported CSV twice is stable
- **WHEN** a dataset containing a multi-turn case is exported, that CSV is imported back into the dataset with `importMode=OVERRIDE`, the result is exported again, and that second CSV is imported with `importMode=OVERRIDE`
- **THEN** after both imports the case SHALL carry the same number of turns with the same per-turn values as the original, and its shared `data` SHALL be unchanged
- **AND** no per-turn value SHALL be promoted into the shared `data` map, and no turn map SHALL become empty

#### Scenario: Exported multi-turn CSV round-trips into a schema-less dataset
- **WHEN** a multi-turn dataset is exported, the CSV is imported into a second dataset whose `testCaseSchema` is empty, and the second dataset is exported
- **THEN** the second dataset's cases SHALL carry the same turn counts as the source and per-turn merged effective views equal to the source's up to blank materialization, every imported case SHALL be valid with no shared-column conflict, and the two exported CSVs SHALL be identical
