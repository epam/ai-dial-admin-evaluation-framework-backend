## MODIFIED Requirements

### Requirement: Dataset revalidation preserves multi-turn shape
Dataset-rooted revalidation (Phase 1) re-coerces and re-validates the test cases of a dataset. It runs on a dataset schema change and also as part of any CSV import that persists a schema.

It SHALL treat a case carrying a turn array as multi-turn: it SHALL coerce the values inside each turn as well as the shared `data`, and SHALL compute validity and warnings from the shared data against the schema's shared fields and each turn against the schema's per-turn fields — never from the shared `data` against the whole schema. The updated turn array SHALL be persisted together with the shared data under the same concurrent-edit guard that protects the shared data today.

Revalidation SHALL NOT relocate values between the shared `data` map and the turn maps. When a schema change re-scopes a field, the stored values stay where they are and the resulting misplacement SHALL be reported as a scope-misplacement warning that names the field, the bucket it now belongs in, and the bucket it was found in — never as a generic unknown-field warning. The same applies to the CSV-import fixup pass, which re-validates stored cases against a newly persisted schema.

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

#### Scenario: Per-turn field re-scoped to shared reports a misplacement
- **WHEN** a dataset schema field declared `perTurn: true` is changed to shared while stored multi-turn cases still hold that field's values inside their turn maps
- **THEN** revalidation SHALL mark each affected case invalid with a scope-misplacement warning per offending turn, stating that the field is shared (test-case-level) and must be provided in `data`, not a turn
- **AND** the warning SHALL carry the offending turn index and SHALL NOT be the generic unknown-field warning
- **AND** if the field is required, it SHALL NOT additionally be reported as missing from `data`
- **AND** the stored values SHALL NOT be moved into the shared `data` map by revalidation

#### Scenario: Shared field re-scoped to per-turn reports a misplacement
- **WHEN** a dataset schema field declared shared is changed to `perTurn: true` while stored cases still hold that field's value in their shared `data` map
- **THEN** revalidation SHALL mark each affected case invalid with a scope-misplacement warning stating that the field is per-turn and must be provided in each `multiTurnData` turn, not in `data`
- **AND** the field SHALL NOT additionally be reported as a required field missing from any turn

#### Scenario: Misplacement clears once the schema or the data is fixed
- **WHEN** a case carrying a scope-misplacement warning is followed by a change that reconciles scope and storage — the schema field is re-scoped back, or the value is moved to the correct bucket — and the case is revalidated
- **THEN** the misplacement warning SHALL be absent from the case's stored warnings
- **AND** the case SHALL become valid if nothing else invalidates it

#### Scenario: CSV import fixup reports the same misplacement
- **WHEN** a CSV import persists a schema whose field scope disagrees with where a stored case holds that field's values, and the post-persist fixup re-validates the case
- **THEN** the fixup SHALL report the same scope-misplacement warning shape as revalidation, not a generic unknown-field warning

#### Scenario: Single-turn revalidation is unchanged
- **WHEN** revalidation processes a case with no turn array
- **THEN** it SHALL coerce and validate exactly as before, with the same guarded-update and skip behavior

## Implementation Notes

- Revalidation Phase 1 reaches the scope-misplacement warning through the shared multi-turn validation entry point; it gains no placement logic of its own.
- The CSV-import fixup pass uses the same entry point and therefore reports the same warning shape.
- Scope-misplacement warnings are recomputed from stored data on every pass and are deliberately excluded from the durable (`INVALID_INPUT`) carry-forward set, so fixing the data or the schema clears them.
