## MODIFIED Requirements

### Requirement: multiTurnData authoring field
The test-case request, response, and batch-put DTOs SHALL expose an optional `multiTurnData` (`List<Map<String,Object>>`); the model and `test_cases` table carry a nullable `multi_turn_data JSONB` column. The field is omitted (`@JsonInclude(NON_NULL)`) for single-turn cases. A test case MAY populate `data` **and** `multiTurnData` together: `data` carries the dataset's **shared** (`perTurn=false`) fields — test-case-level values constant across turns — while each `multiTurnData[i]` carries the **per-turn** (`perTurn=true`) fields. The two fields are NO LONGER mutually exclusive; there SHALL be no DB CHECK constraint or application 400 enforcing exclusivity. The multi-turn discriminator remains `multiTurnData != null` (independent of whether `data` is empty).

On a write carrying `multiTurnData`, a field **declared in the dataset schema** and placed in the wrong scope bucket — a per-turn field present in `data`, or a shared field present in any turn map — SHALL be rejected with HTTP 400 `VALIDATION_ERROR` at create/PUT/PATCH/batch (a structural placement error, distinct from content warnings). Scope placement SHALL NOT be checked when `multiTurnData` is absent: a single-turn case has no turn bucket, so no placement can be violated. A key that is **not declared** in the dataset schema SHALL NOT be rejected: it has no scope to violate, and is reported as a content warning that invalidates the case (see *Per-turn validation against the dataset schema*).
Status: **Planned**

#### Scenario: Round-trip a multi-turn case
- **WHEN** a test case is created with a `multiTurnData` array and read back
- **THEN** the response includes `multiTurnData` with the same ordered turns and omits it for single-turn cases

#### Scenario: Shared and per-turn data coexist
- **WHEN** a case is created with `data` carrying the dataset's shared fields and a `multiTurnData` array carrying the per-turn fields
- **THEN** the write SHALL succeed, both are persisted, and the case is treated as multi-turn (`multiTurnData != null`)

#### Scenario: Misplaced field rejected
- **WHEN** a write carrying `multiTurnData` places a per-turn field inside `data`, or a shared field inside a turn map, and that field is declared in the dataset schema
- **THEN** the request is rejected with HTTP 400 `VALIDATION_ERROR`

#### Scenario: Single-turn write is not scope-checked
- **WHEN** a write omits `multiTurnData` (single-turn case)
- **THEN** no scope-placement rejection SHALL occur, whatever the declared scope of the fields present in `data`

#### Scenario: Undeclared field is warned about, not rejected
- **WHEN** a write places a key that no dataset schema field declares into `data` or into a turn map
- **THEN** the write SHALL succeed and the case SHALL be stored with `is_valid=false` and an unknown-field warning whose `path` identifies the bucket the key was found in

### Requirement: Per-turn validation against the dataset schema
Test-case validation SHALL be scope-aware. Shared fields SHALL be validated against the `data` map, and per-turn fields SHALL be validated against every element of `multiTurnData`, both using the dataset `test_case_schema`. A required shared field missing from `data`, or a required per-turn field missing from any turn, or a type mismatch in either bucket, SHALL be a content warning (not a 400) that sets `is_valid=false`. The case's `is_valid` is true iff no shared-field warning and every turn passes. Validation warnings aggregate across turns, each per-turn warning carrying the originating turn index. A multi-turn case whose per-turn maps are all empty (`{}`) SHALL be valid provided no required per-turn field is unmet — the turn count alone determines the number of turns run.

A declared field found in the wrong scope bucket SHALL produce a dedicated **scope-misplacement** warning that names the field, the bucket it belongs in, and the bucket it was found in — never a generic unknown-field warning. The `ValidationWarningCode` enum SHALL include a value `INVALID_SCOPE` denoting "this value is stored in the wrong scope bucket", so clients can distinguish it from "this key matches no schema field" (`ADDITIONAL`) and from a genuinely absent value (`REQUIRED`). The warning's wording SHALL match the corresponding write-path rejection message so the two surfaces read identically.

A misplaced field SHALL yield exactly one warning per occurrence — one per offending turn when it sits in turn maps, one when it sits in `data` — and SHALL NOT additionally be reported as unknown or type-mismatched in the bucket it was found in, nor as missing from the bucket it belongs in. Suppression is symmetric in both directions: a shared field found in turns SHALL NOT also be reported as missing from `data`, and a per-turn field found in `data` SHALL NOT also be reported as missing from any turn.

A scope-misplacement warning SHALL be derived from the case's stored data and the dataset's current schema on every validation pass; it SHALL NOT be carried forward from previously stored warnings, so correcting either the data or the schema clears it.

Each validation warning SHALL identify its bucket in its `path`: warnings against the shared map use `$.data.<field>`, and warnings against turn *i* use `$.multiTurnData[<i>].<field>`.
Status: **Planned**

#### Scenario: One invalid turn invalidates the case
- **WHEN** any turn violates the schema for a per-turn field (missing required, type mismatch, unknown field)
- **THEN** the case is stored with `is_valid=false` and warnings tagged with the offending turn index

#### Scenario: Missing shared required field invalidates the case
- **WHEN** the `data` map omits a required shared field
- **THEN** the case is stored with `is_valid=false` and a warning against `data` (no turn index)

#### Scenario: Empty per-turn maps are valid
- **WHEN** a multi-turn case has all-shared schema fields and each `multiTurnData[i]` is `{}`, with all required shared fields present in `data`
- **THEN** the case is valid and runs one test-case run per the turn count

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

#### Scenario: Undeclared key stays an unknown-field warning
- **WHEN** validation processes a case holding a key that no dataset schema field declares
- **THEN** the warning SHALL remain an unknown-field warning naming the key, not a scope-misplacement warning
- **AND** its `path` SHALL identify the bucket the key was found in

#### Scenario: Fixing the schema clears the misplacement warning
- **WHEN** a case carries a scope-misplacement warning and the dataset schema is then changed so the field's declared scope matches where the value is stored
- **THEN** the next validation pass SHALL NOT report that warning, and the case SHALL become valid if nothing else invalidates it

### Requirement: data and multiTurnData are independently PATCH-able
Both `data` and `multiTurnData` SHALL be part of the merge-PATCH whitelist alongside `testCaseName`. Patching `data` SHALL update only the shared bucket, and patching `multiTurnData` SHALL update only the per-turn bucket; neither SHALL implicitly clear the other. Setting `multiTurnData: null` SHALL revert the case to single-turn. Placement and per-scope validation SHALL run after the merge (misplaced **declared** field → 400; content issues, including a misplaced undeclared key → invalidating warnings).
Status: **Planned**

#### Scenario: PATCH updates shared data without clearing turns
- **WHEN** a multi-turn case is PATCHed with a `data` object updating a shared field
- **THEN** the shared field is merged into `data`, `multiTurnData` is unchanged, and the case stays multi-turn

#### Scenario: PATCH updates per-turn data without clearing shared
- **WHEN** a multi-turn case is PATCHed with a `multiTurnData` array
- **THEN** the turns are replaced and the existing shared `data` is unchanged

#### Scenario: PATCH reverts to single-turn
- **WHEN** a multi-turn case is PATCHed with `multiTurnData: null`
- **THEN** the case becomes single-turn and its `data` is retained as the single-turn map

## Implementation Notes

- Placement rule (both the rejecting and the warning form) is owned by a single injectable component in `service.domain`, consumed by the write-path structural validator and by `TestCaseValidationService.validateMultiTurn`.
- Warning codes live in `evaluation-runner-core`'s `runner.dto.ValidationWarningCode`; `INVALID_SCOPE` is additive.
- Recomputation callers (dataset revalidation, CSV import fixup) reach the warning form through `validateMultiTurn`, so no call-site signature changes.
