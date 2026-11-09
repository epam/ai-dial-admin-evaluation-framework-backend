# Test-Case Revalidation

## Purpose

Strict schema-change coercion, guarded persistence/counters and preservation of shared/per-turn shape. [datasets](../datasets/spec.md) owns synchronous task triggering and task API; [test-case-validation](../test-case-validation/spec.md) owns content warnings and durable source conflicts; [test-case-csv-transfer](../test-case-csv-transfer/spec.md) owns permissive CSV processing before schema persistence.

Status: **Implemented**

## Requirements

### Requirement: Strict schema-change coercion and guarded counters

Status: **Implemented**

The system SHALL run a value-coercion pass over each test case's `data` map BEFORE validation when the dataset-rooted `RevalidationTask` (Phase 1) re-validates existing test cases after a **Dataset** schema change (dataset PUT mutating `testCaseSchema`). Coercion MUST use the strict schema-change conversion table below (narrower than the CSV-import table used by `SchemaTypeCoercer`) and SHALL be implemented by a separate `SchemaChangeCoercer` (`service.domain.csv` package). For each `(value, targetType)` pair where the target type is the field's current dataset schema type, the coercer SHALL return either a converted value (if the conversion is in the safe set) or the input value unchanged (if not). When at least one cell in shared data or a turn is converted, the updated `data` and, for multi-turn cases, `multi_turn_data` SHALL be persisted in a guarded UPDATE (precondition: `updated_at = :seenAt`) BEFORE validation runs against the post-coercion data. Validation results (`is_valid`, `validation_warnings`) SHALL be persisted in a SECOND guarded UPDATE. If either guarded UPDATE affects 0 rows (concurrent edit), the row SHALL be skipped entirely (no further writes).

**Strict schema-change conversion table** (rows = source Java type, columns = target `SchemaFieldType`; "skip" = return input unchanged):

| Source ↓ \ Target →     | STRING                | INTEGER             | NUMBER                | BOOLEAN                  | FILE         | SKILL        | OBJECT     | ARRAY      |
|-------------------------|-----------------------|---------------------|-----------------------|--------------------------|--------------|--------------|------------|------------|
| String                  | identity              | `Long.parseLong`    | `Double.parseDouble`  | `"true"`/`"false"` only (case-sensitive) | identity     | identity     | skip       | skip       |
| Integer/Long            | `String.valueOf`      | Integer → Long; Long identity | `doubleValue`         | skip                     | skip         | skip         | skip       | skip       |
| Double                  | `String.valueOf`      | only if `% 1 == 0`  | identity              | skip                     | skip         | skip         | skip       | skip       |
| Boolean                 | `"true"`/`"false"`    | skip                | skip                  | identity                 | skip         | skip         | skip       | skip       |
| Object (Map)            | skip                  | skip                | skip                  | skip                     | skip         | skip         | identity   | skip       |
| Array (List)            | skip                  | skip                | skip                  | skip                     | skip         | skip         | skip       | identity   |
| `null`                  | identity              | identity            | identity              | identity                 | identity     | identity     | identity   | identity   |

This table is intentionally **stricter** than `SchemaTypeCoercer` used by CSV import: `Integer/Long → BOOLEAN`, `Double → BOOLEAN`, `Boolean → INTEGER/NUMBER`, `Object/Array → STRING`, and any non-String → FILE or SKILL are all **excluded** to avoid silently reinterpreting typed JSON in ways the user did not request.

`coercedCellCount` SHALL accumulate changed cells in shared data and turn maps only for rows whose guarded processing, including the validation write, completes. A row skipped after either guard miss SHALL contribute zero, even if the data conversion was persisted before the validation guard miss. Unchanged cells SHALL not count. Task trigger, creation descriptor and list/get wire shape are owned by [datasets](../datasets/spec.md).

Permissive coercion (`SchemaTypeCoercer`, CSV import) SHALL coerce any value to `SKILL` as it does to `STRING`. Strict coercion on schema-type change (`SchemaChangeCoercer`) SHALL treat a `SKILL` target like a `FILE` target: only a `String` value is kept (identity); any other value SHALL NOT be auto-coerced and SHALL be reported by revalidation. After a type change to `SKILL`, revalidation SHALL apply the skill format warning.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| BOOLEAN → STRING | Schema changes BOOLEAN→STRING; stored flag is true | Persist string `"true"` before validation, no field TYPE warning; successful guarded processing adds one coerced cell |
| INTEGER → STRING | Schema changes INTEGER→STRING; stored Long year is 1865 | Persist `"1865"`; field is valid |
| NUMBER → STRING | Schema changes NUMBER→STRING; stored Double score is 3.14 | Persist `"3.14"`; field is valid |
| INTEGER → NUMBER | Schema changes INTEGER→NUMBER; stored Long n is 42 | Persist Double `42.0`; field is valid |
| STRING → BOOLEAN | Schema changes STRING→BOOLEAN | Literal `"true"`/`"false"` converts case-sensitively; `"yes"` stays unchanged and yields invalid TYPE |
| STRING → INTEGER | Schema changes STRING→INTEGER | `"42"` becomes Long 42 and valid; `"hello"` stays unchanged and yields invalid TYPE |
| Whole NUMBER → INTEGER | Schema changes NUMBER→INTEGER; stored Double n is 7.0 | Persist Long 7; field is valid |
| Fractional NUMBER → INTEGER refused | Schema changes NUMBER→INTEGER; stored Double n is 3.14 | Keep 3.14 and report invalid TYPE; never truncate to 3 |
| INTEGER → BOOLEAN refused | Schema changes INTEGER→BOOLEAN; stored Long flag is 1 | Keep 1 and report invalid TYPE; unlike permissive CSV coercion |
| BOOLEAN → INTEGER refused | Schema changes BOOLEAN→INTEGER; stored n is true | Keep true and report invalid TYPE |
| Non-string → FILE refused | Target becomes FILE; value is Boolean/Number/Map/List | Keep it and report invalid TYPE; FILE also checks its string representation and may emit a separate format TYPE warning |
| STRING → FILE identity | Schema changes STRING→FILE; stored ref is `@ef/suites/abc/foo.png` | Keep the string and validate FILE format/prefix; valid refs pass, invalid ones warn |
| OBJECT → STRING refused | Target becomes STRING; stored value is Map `{"a":1}` | Keep the Map and report invalid TYPE; never stringify to Java debug form |
| ARRAY → STRING refused | Target becomes STRING; stored value is List `[1,2,3]` | Keep the List and report invalid TYPE |
| Null identity | Any target type; stored value is null | Keep null; invalid only when required |
| Already-matching types yield zero coerced cells | revalidation runs and every cell already matches its current schema type (e.g. revalidation re-run after a prior coerced run) | no `data` UPDATE SHALL be issued for that row; only the validation UPDATE SHALL fire; the task `coercedCellCount` SHALL remain 0 if no prior coercion occurred in this run |
| Per-row data update is guarded by updated_at | revalidation reads a test case row with `updated_at = T0`, computes coerced data, and another caller PATCHes the row to `updated_at = T1` before the data UPDATE runs | the guarded UPDATE SHALL affect 0 rows; revalidation SHALL skip both the data write and the validation write for that row; the user's edit at T1 SHALL remain intact; the row SHALL count toward `processedCases` but SHALL NOT increment `validCount`, `invalidCount`, or `coercedCellCount` |
| Per-row validation update is guarded by updated_at | revalidation successfully writes coerced data and then attempts the validation UPDATE, but a concurrent edit changes `updated_at` between the two writes | the second guarded UPDATE SHALL affect 0 rows; revalidation SHALL skip the validation write; subsequent revalidations will reconcile; the row SHALL count toward `processedCases` but SHALL NOT increment `validCount`, `invalidCount`, or `coercedCellCount` |
| Coerced cell counter accumulates across rows | a revalidation task processes 100 test cases, each with 3 BOOLEAN→STRING fields successfully coerced | the task's `coercedCellCount` SHALL be 300 at completion; the task response SHALL include `coercedCellCount` as a Long |
| String retyped to SKILL keeps its value | a field is changed from `STRING` to `SKILL` and a test case holds `"skills/b/x"` | the value SHALL be unchanged and no coerced-cell count SHALL be added for it |
| Non-string value retyped to SKILL is not auto-coerced | a field is changed from `INTEGER` to `SKILL` and a test case holds `42` | the value SHALL NOT be auto-coerced and revalidation SHALL report a type mismatch |
| Retype to SKILL revalidates format | a field is changed from `STRING` to `SKILL` and a test case holds `"not-a-skill"` | the value SHALL be kept and a `SKILL field` `TYPE` warning SHALL be produced |

### Requirement: Dataset revalidation preserves multi-turn shape

Status: **Implemented**

Dataset-rooted revalidation (Phase 1) re-coerces and re-validates the test cases of a dataset. It runs when dataset update changes the stored schema field list, including an import that makes such a change. Import schema persistence alone does not guarantee a task: an empty-to-empty schema update increments the dataset version but creates no revalidation task (see `test-case-csv-transfer`).

It SHALL treat a case carrying a turn array as multi-turn: it SHALL coerce the values inside each turn as well as the shared `data`, and SHALL compute validity and warnings from the shared data against the schema's shared fields and each turn against the schema's per-turn fields — never from the shared `data` against the whole schema. The updated turn array SHALL be persisted together with the shared data under the same concurrent-edit guard that protects the shared data today.

Revalidation SHALL NOT relocate values between the shared `data` map and the turn maps. When a schema change re-scopes a field, the stored values stay where they are and the resulting misplacement SHALL be reported as a scope-misplacement warning that names the field, the bucket it now belongs in, and the bucket it was found in — never as a generic unknown-field warning. The same applies to the CSV-import fixup pass, which re-validates stored cases against a final derived schema.

If a case's stored turn array is present but unreadable, revalidation SHALL leave that case untouched and log a warning, writing neither its data nor its validity — rewriting it would convert the case to single-turn and destroy every turn.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Per-turn values are validated | revalidation processes a multi-turn case whose turn holds a value that does not match its per-turn field's schema type | the case SHALL be marked invalid with the corresponding type warning, rather than valid because its shared `data` alone is consistent |
| Per-turn values are coerced | revalidation runs after a schema type change and a multi-turn case holds a coercible value inside a turn for that field | the value inside the turn SHALL be coerced by the same rules applied to shared `data`, and the updated turn array SHALL be persisted together with the shared data under the same concurrent-edit guard |
| Concurrent edit still wins | a multi-turn case is edited by another caller between revalidation reading it and writing the coerced result | the write SHALL affect no rows and revalidation SHALL skip that case, exactly as for a single-turn case today |
| Unreadable turn array is skipped, never overwritten | revalidation encounters a case whose stored turn array is present but cannot be read | the case SHALL be left unchanged in the database and a warning SHALL be logged; the case SHALL NOT be rewritten as a single-turn case; the row SHALL count toward the task's processed cases but SHALL NOT increment its valid or invalid counts, consistent with the existing concurrent-edit skip |
| Removing a per-turn field prunes it from stored turns | a field is removed from a dataset's `testCaseSchema` and the system prunes that field from stored test case data | the field SHALL be removed from each turn of a multi-turn case as well as from the shared `data`; the subsequent revalidation SHALL NOT report the removed field as an unknown field on any turn |
| Per-turn field re-scoped to shared reports a misplacement | a dataset schema field declared `perTurn: true` is changed to shared while stored multi-turn cases still hold that field's values inside their turn maps | revalidation SHALL mark each affected case invalid with a scope-misplacement warning per offending turn, stating that the field is shared (test-case-level) but its values are specified on turn level, and directing the author to re-create the column; the warning SHALL carry the offending turn index and SHALL NOT be the generic unknown-field warning; if the field is required, it SHALL NOT additionally be reported as missing from `data`; the stored values SHALL NOT be moved into the shared `data` map by revalidation |
| Shared field re-scoped to per-turn reports a misplacement | a dataset schema field declared shared is changed to `perTurn: true` while stored cases still hold that field's value in their shared `data` map | revalidation SHALL mark each affected case invalid with a scope-misplacement warning stating that the field is per-turn but is currently specified on a test-case level, and directing the author to re-create the column; the field SHALL NOT additionally be reported as a required field missing from any turn |
| Misplacement clears once the schema or the data is fixed | a case carrying a scope-misplacement warning is followed by a change that reconciles scope and storage — the schema field is re-scoped back, or the value is moved to the correct bucket — and the case is revalidated | the misplacement warning SHALL be absent from the case's stored warnings; the case SHALL become valid if nothing else invalidates it |
| CSV import fixup reports the same misplacement | a CSV import persists a schema whose field scope disagrees with where a stored case holds that field's values, and the post-persist fixup re-validates the case | the fixup SHALL report the same scope-misplacement warning shape as revalidation, not a generic unknown-field warning |
| Single-turn revalidation is unchanged | revalidation processes a case with no turn array | it SHALL coerce and validate exactly as before, with the same guarded-update and skip behavior |

## Implementation notes

- [SchemaChangeCoercer.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/csv/SchemaChangeCoercer.java): strict conversion table.
- [RevalidationService.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/RevalidationService.java), [PostgresTestCaseRepository.java](../../../src/main/java/com/epam/aidial/evaluation/data/db/repository/PostgresTestCaseRepository.java): separate guarded data/validation writes, counters and turn-shape preservation.
