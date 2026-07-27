## MODIFIED Requirements

### Requirement: multiTurnData authoring field
The test-case request, response, and batch-put DTOs SHALL expose an optional `multiTurnData` (`List<Map<String,Object>>`); the model and `test_cases` table carry a nullable `multi_turn_data JSONB` column. The field is omitted (`@JsonInclude(NON_NULL)`) for single-turn cases. A test case MAY populate `data` **and** `multiTurnData` together: `data` carries the dataset's **shared** (`perTurn=false`) fields — test-case-level values constant across turns — while each `multiTurnData[i]` carries the **per-turn** (`perTurn=true`) fields. The two fields are NO LONGER mutually exclusive; there SHALL be no DB CHECK constraint or application 400 enforcing exclusivity. The multi-turn discriminator remains `multiTurnData != null` (independent of whether `data` is empty). A field placed in the wrong scope bucket — a per-turn field present in `data`, or a shared field present in any turn map — SHALL be rejected with HTTP 400 `VALIDATION_ERROR` at create/PUT/PATCH/batch (a structural placement error, distinct from content warnings).
Status: **Planned**

#### Scenario: Round-trip a multi-turn case
- **WHEN** a test case is created with a `multiTurnData` array and read back
- **THEN** the response includes `multiTurnData` with the same ordered turns and omits it for single-turn cases

#### Scenario: Shared and per-turn data coexist
- **WHEN** a case is created with `data` carrying the dataset's shared fields and a `multiTurnData` array carrying the per-turn fields
- **THEN** the write SHALL succeed, both are persisted, and the case is treated as multi-turn (`multiTurnData != null`)

#### Scenario: Misplaced field rejected
- **WHEN** a write places a per-turn field inside `data`, or a shared field inside a turn map
- **THEN** the request is rejected with HTTP 400 `VALIDATION_ERROR`

### Requirement: Per-turn validation against the dataset schema
Test-case validation SHALL be scope-aware. Shared fields SHALL be validated against the `data` map, and per-turn fields SHALL be validated against every element of `multiTurnData`, both using the dataset `test_case_schema`. A required shared field missing from `data`, or a required per-turn field missing from any turn, or a type mismatch in either bucket, SHALL be a content warning (not a 400) that sets `is_valid=false`. The case's `is_valid` is true iff no shared-field warning and every turn passes. Validation warnings aggregate across turns, each per-turn warning carrying the originating turn index. A multi-turn case whose per-turn maps are all empty (`{}`) SHALL be valid provided no required per-turn field is unmet — the turn count alone drives the conversation length.
Status: **Planned**

#### Scenario: One invalid turn invalidates the case
- **WHEN** any turn violates the schema for a per-turn field (missing required, type mismatch, unknown field)
- **THEN** the case is stored with `is_valid=false` and warnings tagged with the offending turn index

#### Scenario: Missing shared required field invalidates the case
- **WHEN** the `data` map omits a required shared field
- **THEN** the case is stored with `is_valid=false` and a warning against `data` (no turn index)

#### Scenario: Empty per-turn maps are valid
- **WHEN** a multi-turn case has all-shared schema fields and each `multiTurnData[i]` is `{}`, with all required shared fields present in `data`
- **THEN** the case is valid and runs one conversation per the turn count

## REMOVED Requirements

### Requirement: multiTurnData is PATCH-able with mutual exclusivity
**Reason**: `data` and `multiTurnData` now coexist (shared vs per-turn buckets), so a PATCH to one MUST NOT clear the other. Replaced by "data and multiTurnData are independently PATCH-able".
**Migration**: To convert a case between single- and multi-turn, explicitly set both fields in the PATCH (e.g. set `multiTurnData` and set `data` to `{}` if no shared fields), or set `multiTurnData: null` to revert to single-turn. Setting one field alone no longer implicitly clears the other.

## ADDED Requirements

### Requirement: data and multiTurnData are independently PATCH-able
Both `data` and `multiTurnData` SHALL be part of the merge-PATCH whitelist alongside `testCaseName`. Patching `data` SHALL update only the shared bucket, and patching `multiTurnData` SHALL update only the per-turn bucket; neither SHALL implicitly clear the other. Setting `multiTurnData: null` SHALL revert the case to single-turn. Placement and per-scope validation SHALL run after the merge (misplaced field → 400; content issues → invalidating warnings).
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
