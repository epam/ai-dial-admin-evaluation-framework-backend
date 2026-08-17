## MODIFIED Requirements

### Requirement: Per-turn validation against the dataset schema
Test-case validation SHALL be scope-aware. Shared fields SHALL be validated against the `data` map, and per-turn fields SHALL be validated against every element of `multiTurnData`, both using the dataset `test_case_schema`. A required shared field missing from `data`, or a required per-turn field missing from any turn, or a type mismatch in either bucket, SHALL be a content warning (not a 400) that sets `is_valid=false`. The case's `is_valid` is true iff validation produces no warning at all — including case-level warnings that belong to neither the shared bucket nor any single turn (the over-max-turns warning and the no-per-turn-columns warning below). Validation warnings aggregate across turns, each per-turn warning carrying the originating turn index.

A multi-turn case whose per-turn maps are all empty (`{}`) SHALL be valid provided the dataset schema declares at least one `perTurn: true` field and no required per-turn field is unmet. The number of turns actually executed is not decided by validation — it follows the turn-count rule in `multi-turn-test-case`.

A multi-turn case (`multiTurnData` non-empty) whose dataset schema declares **no** `perTurn: true` field SHALL be marked `is_valid=false` with exactly one such warning — stating that the case carries turns while the dataset declares no per-turn columns, so the turn data cannot be attached. The warning SHALL report the case's turn count, SHALL use code `ADDITIONAL`, SHALL carry no field name and no turn index, and SHALL use path `$.multiTurnData` — it describes the case, not any one turn or field. It is independent of the over-max-turns warning, which shares that path and code: a case that is both over-cap and without per-turn columns SHALL carry both. The condition SHALL depend solely on the dataset schema's declared field scopes, never on any suite's input bindings, since validity is dataset-scoped. The warning SHALL be recomputed from stored state on every pass that recomputes the case's validity — the write paths, dataset revalidation, and CSV-import row validation, plus the post-import fixup pass on the cases it actually rewrites — so declaring a per-turn column, or clearing the case's `multiTurnData`, clears it. It SHALL NOT be carried forward from previously stored warnings.

Because warnings are truncated to a configured per-case maximum, this warning SHALL be ordered ahead of the shared-bucket and per-turn warnings, so a case with many field-level warnings cannot lose the case-level explanation to truncation.

A declared field found in the wrong scope bucket SHALL produce a dedicated **scope-misplacement** warning that names the field, the bucket it belongs in, and the bucket it was found in — never a generic unknown-field warning. The `ValidationWarningCode` enum SHALL include a value `INVALID_SCOPE` denoting "this value is stored in the wrong scope bucket", so clients can distinguish it from "this key matches no schema field" (`ADDITIONAL`) and from a genuinely absent value (`REQUIRED`). The warning's wording SHALL match the corresponding write-path rejection message so the two surfaces read identically.

A misplaced field SHALL yield exactly one warning per occurrence — one per offending turn when it sits in turn maps, one when it sits in `data` — and SHALL NOT additionally be reported as unknown or type-mismatched in the bucket it was found in, nor as missing from the bucket it belongs in. Suppression is symmetric in both directions: a shared field found in turns SHALL NOT also be reported as missing from `data`, and a per-turn field found in `data` SHALL NOT also be reported as missing from any turn.

The no-per-turn-columns warning and scope-misplacement warnings are independent and SHALL both be reported when both apply: the misplacement warnings name each offending field, while the case-level warning names the root cause the per-field warnings do not express.

A scope-misplacement warning SHALL be derived from the case's stored data and the dataset's current schema on every validation pass; it SHALL NOT be carried forward from previously stored warnings, so correcting either the data or the schema clears it.

Each validation warning SHALL identify its bucket in its `path`: warnings against the shared map use `$.data.<field>`, and warnings against turn *i* use `$.multiTurnData[<i>].<field>`. A warning that describes the turn array as a whole rather than one turn uses `$.multiTurnData`.
Status: **Planned**

#### Scenario: One invalid turn invalidates the case
- **WHEN** any turn violates the schema for a per-turn field (missing required, type mismatch, unknown field)
- **THEN** the case is stored with `is_valid=false` and warnings tagged with the offending turn index

#### Scenario: Missing shared required field invalidates the case
- **WHEN** the `data` map omits a required shared field
- **THEN** the case is stored with `is_valid=false` and a warning against `data` (no turn index)

#### Scenario: Empty per-turn maps are valid
- **WHEN** a multi-turn case's dataset schema declares at least one `perTurn: true` field, each `multiTurnData[i]` is `{}`, no per-turn field is required, and all required shared fields are present in `data`
- **THEN** the case SHALL be valid and SHALL NOT carry the no-per-turn-columns warning

#### Scenario: Multi-turn case in an all-shared schema is invalidated
- **WHEN** a case carries a non-empty `multiTurnData` and its dataset schema declares no `perTurn: true` field
- **THEN** the case SHALL be stored with `is_valid=false` and one warning at path `$.multiTurnData` with code `ADDITIONAL`, no field name and no turn index, reporting the case's turn count and stating that the dataset declares no per-turn columns
- **AND** the case SHALL be excluded from runnable test-case selection like any other invalid case

#### Scenario: Single-turn case in an all-shared schema stays valid
- **WHEN** a case has no `multiTurnData` and its dataset schema declares no `perTurn: true` field
- **THEN** no no-per-turn-columns warning SHALL be emitted and validity SHALL be unchanged from the single-turn behavior

#### Scenario: Declaring a per-turn column clears the warning
- **WHEN** a case carries the no-per-turn-columns warning and the dataset schema is then changed to declare a `perTurn: true` field
- **THEN** the next validation pass SHALL NOT report that warning, and the case SHALL become valid if nothing else invalidates it

#### Scenario: Shared field found inside a turn is reported as misplaced
- **WHEN** validation processes a case whose turn *i* holds a value for a field the dataset schema declares shared
- **THEN** the case SHALL be marked invalid with an `INVALID_SCOPE` warning stating that the field is shared (test-case-level) but its values are specified on turn level, and directing the author to re-create the column
- **AND** the warning SHALL carry turn index *i* and path `$.multiTurnData[<i>].<field>`
- **AND** no unknown-field warning SHALL be emitted for that field in that turn
- **AND** if the field is required, no "required field missing" warning SHALL be emitted against `data` for it

#### Scenario: Per-turn field found in shared data is reported as misplaced
- **WHEN** validation processes a case whose `data` map holds a value for a field the dataset schema declares per-turn
- **THEN** the case SHALL be marked invalid with an `INVALID_SCOPE` warning stating that the field is per-turn but is currently specified on a test-case level, and directing the author to re-create the column
- **AND** the warning SHALL carry no turn index and path `$.data.<field>`
- **AND** no unknown-field warning SHALL be emitted for that field against `data`
- **AND** if the field is required, no "required field missing" warning SHALL be emitted against any turn for it

#### Scenario: Misplacement in both directions is reported once each
- **WHEN** a case simultaneously holds a per-turn field in `data` and a shared field in one of its turns
- **THEN** both misplacements SHALL be reported, each as its own `INVALID_SCOPE` warning at its own path
- **AND** neither field SHALL produce an unknown-field or required-missing warning

#### Scenario: Misplacement and the no-per-turn-columns warning coexist
- **WHEN** a case carries turns holding values for fields the dataset schema declares shared, and that schema declares no `perTurn: true` field at all
- **THEN** the case SHALL carry one `INVALID_SCOPE` warning per misplaced occurrence **and** the case-level no-per-turn-columns warning at `$.multiTurnData`
- **AND** the case-level warning SHALL survive when the per-case warning maximum truncates the field-level ones

#### Scenario: Undeclared key stays an unknown-field warning
- **WHEN** validation processes a case holding a key that no dataset schema field declares
- **THEN** the warning SHALL remain an unknown-field warning naming the key, not a scope-misplacement warning
- **AND** its `path` SHALL identify the bucket the key was found in

#### Scenario: Fixing the schema clears the misplacement warning
- **WHEN** a case carries a scope-misplacement warning and the dataset schema is then changed so the field's declared scope matches where the value is stored
- **THEN** the next validation pass SHALL NOT report that warning, and the case SHALL become valid if nothing else invalidates it

## Implementation Notes

- Trigger and warning construction: `TestCaseValidationService.validateMultiTurn` (`src/main/java/com/epam/aidial/evaluation/service/domain/TestCaseValidationService.java`), immediately after the max-turns cap check, using the per-turn sub-schema already produced by `TestCaseFieldScopeResolver.splitSchema`. The warning is prepended to the warning list so the `validation.max-warnings-per-case` truncation at the end of the method cannot drop it.
- Inherited by every validation entry point without further wiring: `TestCaseService` (write paths), `RevalidationService` (dataset revalidation), `CsvImportService` (row validation and post-persist fixup — the latter recomputes only for cases it coerces and rewrites).
- Runnable exclusion is the existing `is_valid` filter in `RunnableTestCaseSelector`; no change there.
- Run-time turn count remains `PerTurnBindingDetector` + `TurnLoopExecutor` (`evaluation-runner-core`); this change does not touch execution.
