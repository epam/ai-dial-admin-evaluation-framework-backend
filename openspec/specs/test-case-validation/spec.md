# Test-Case Validation

## Purpose

Dataset-scoped content validation, NUMBER representation normalization, scope warnings and durable import-conflict warnings. Authoring/CRUD is owned by [test-cases](../test-cases/spec.md); CSV preprocessing by [test-case-csv-transfer](../test-case-csv-transfer/spec.md); strict revalidation by [test-case-revalidation](../test-case-revalidation/spec.md). Suite configuration is independent of stored test-case validity.

Status: **Implemented**

## Requirements

### Requirement: Normalize whole NUMBER values before API persistence

Status: **Implemented**

Test-case API writes that perform per-row validation SHALL normalize actual numeric values in schema-declared `NUMBER` fields to decimal JSON with at least one fractional digit before persistence. This applies to shared `data` and each `multiTurnData` map. Adding the fractional digit SHALL NOT round integral values through binary floating-point conversion. Fractional values, `INTEGER` fields, unknown fields, nested values, strings, booleans, nulls, and missing fields SHALL remain unchanged by normalization.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Frontend-style update submits a whole-number value | a test case stores `{"numeric":1.0}` for a `NUMBER` field; a PUT update submits `{"numeric":1}` | the persisted data and PUT response SHALL contain `{"numeric":1.0}` |
| Normalize shared and per-turn numeric fields | a test-case API write submits whole numeric values in shared and per-turn `NUMBER` fields | each submitted numeric value SHALL be persisted with a fractional digit, preserving turn order |
| Retain non-numeric input for validation | a `NUMBER` field contains a string or boolean | normalization SHALL NOT coerce that value to a number, and existing validation SHALL evaluate the original value |

### Requirement: Per-turn validation against the dataset schema

Status: **Implemented**

Shared fields SHALL be checked against `data`; per-turn fields against every `multiTurnData[i]`, using the dataset schema. Any warning—including case-level warnings—makes `valid=false`. Missing required values and type mismatches are content warnings, not structural HTTP 400 errors. Warnings aggregate across turns and identify the originating turn. Empty turn maps are valid when at least one per-turn field is declared and no required value is unmet; execution turn counts remain independent of validation.

| Finding | Required warning behavior |
|---|---|
| Turns present but dataset declares no per-turn fields | Exactly one case-level ADDITIONAL warning at `$.multiTurnData`, including turn count, without fieldName/turn index; ordered before field warnings so the configured warning cap cannot discard it |
| Declared shared field found in a turn | INVALID_SCOPE naming the field, expected shared bucket and offending turn; one warning per occurrence |
| Declared per-turn field found in shared data | INVALID_SCOPE naming the field and expected per-turn bucket; one warning for the shared occurrence |
| Undeclared field | ADDITIONAL unknown-field warning, not a placement rejection |

Scope detection (both INVALID_SCOPE rows) runs only for multi-turn cases, i.e. cases carrying a `multiTurnData` array. A single-turn case is validated against the whole schema, so a per-turn field held in its `data` produces no INVALID_SCOPE warning.

Misplacement wording SHALL match the write-path rejection, naming the expected/actual buckets and directing the author to re-create the column. A misplaced field SHALL NOT also produce unknown/type warnings in the wrong bucket or required-missing warnings in its proper bucket; suppression applies symmetrically, including all turns for a per-turn field found in shared data. Independent findings coexist: no-per-turn-columns, over-cap and each misplaced field retain their distinct warnings.

These warnings are derived from current stored content/schema on every validating write, revalidation and CSV row/fixup validation (only rows fixup rewrites), not carried forward. Declaring a per-turn field or clearing turns removes the no-per-turn-columns warning; fixing data/schema removes misplacement. Suite bindings SHALL NOT affect these findings. Shared paths are `$.data.<field>`, turn paths `$.multiTurnData[i].<field>`, whole-array paths `$.multiTurnData`.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| One invalid turn invalidates the case | any turn violates the schema for a per-turn field (missing required, type mismatch, unknown field) | the case is stored with `is_valid=false` and warnings tagged with the offending turn index |
| Missing shared required field invalidates the case | the `data` map omits a required shared field | the case is stored with `is_valid=false` and a warning against `data` (no turn index) |
| Empty per-turn maps are valid | a multi-turn case's dataset schema declares at least one `perTurn: true` field, each `multiTurnData[i]` is `{}`, no per-turn field is required, and all required shared fields are present in `data` | the case SHALL be valid and SHALL NOT carry the no-per-turn-columns warning |
| Multi-turn case in an all-shared schema is invalidated | a case carries a non-empty `multiTurnData` and its dataset schema declares no `perTurn: true` field | the case SHALL be stored with `is_valid=false` and one warning at path `$.multiTurnData` with code `ADDITIONAL`, no field name and no turn index, reporting the case's turn count and stating that the dataset declares no per-turn columns; the case SHALL be excluded from runnable test-case selection like any other invalid case |
| Single-turn case in an all-shared schema stays valid | a case has no `multiTurnData` and its dataset schema declares no `perTurn: true` field | no no-per-turn-columns warning SHALL be emitted and validity SHALL be unchanged from the single-turn behavior |
| Declaring a per-turn column clears the warning | a case carries the no-per-turn-columns warning and the dataset schema is then changed to declare a `perTurn: true` field | the next validation pass SHALL NOT report that warning, and the case SHALL become valid if nothing else invalidates it |
| Shared field found inside a turn is reported as misplaced | validation processes a case whose turn *i* holds a value for a field the dataset schema declares shared | the case SHALL be marked invalid with an `INVALID_SCOPE` warning stating that the field is shared (test-case-level) but its values are specified on turn level, and directing the author to re-create the column; the warning SHALL carry turn index *i* and path `$.multiTurnData[<i>].<field>`; no unknown-field warning SHALL be emitted for that field in that turn; if the field is required, no "required field missing" warning SHALL be emitted against `data` for it |
| Per-turn field found in shared data is reported as misplaced | validation processes a multi-turn case whose `data` map holds a value for a field the dataset schema declares per-turn | the case SHALL be marked invalid with an `INVALID_SCOPE` warning stating that the field is per-turn but is currently specified on a test-case level, and directing the author to re-create the column; the warning SHALL carry no turn index and path `$.data.<field>`; no unknown-field warning SHALL be emitted for that field against `data`; if the field is required, no "required field missing" warning SHALL be emitted against any turn for it |
| Misplacement in both directions is reported once each | a case simultaneously holds a per-turn field in `data` and a shared field in one of its turns | both misplacements SHALL be reported, each as its own `INVALID_SCOPE` warning at its own path; neither field SHALL produce an unknown-field or required-missing warning |
| Misplacement and the no-per-turn-columns warning coexist | a case carries turns holding values for fields the dataset schema declares shared, and that schema declares no `perTurn: true` field at all | the case SHALL carry one `INVALID_SCOPE` warning per misplaced occurrence **and** the case-level no-per-turn-columns warning at `$.multiTurnData`; the case-level warning SHALL survive when the per-case warning maximum truncates the field-level ones |
| Undeclared key stays an unknown-field warning | validation processes a case holding a key that no dataset schema field declares | the warning SHALL remain an unknown-field warning naming the key, not a scope-misplacement warning; its `path` SHALL identify the bucket the key was found in |
| Fixing the schema clears the misplacement warning | a case carries a scope-misplacement warning and the dataset schema is then changed so the field's declared scope matches where the value is stored | the next validation pass SHALL NOT report that warning, and the case SHALL become valid if nothing else invalidates it |

### Requirement: Validation entry points and structured warnings

Status: **Implemented**

TestCase content validation SHALL use only the owning dataset's schema, independently of suite templates/bindings/endpoints and test-case presence. Required, unknown-field, type, FILE and SKILL checks SHALL produce structured warnings rather than HTTP 400; any warning makes `valid=false`. Structural write errors follow [test-cases](../test-cases/spec.md). FILE format/ownership and SKILL format remain owned by [file-ref-validation](../file-ref-validation/spec.md) and [dial-skill-ref](../dial-skill-ref/spec.md).

| Entry point | Preparation before content validation |
|---|---|
| Validating API create/PUT/PATCH, array batches and relevant composite items | Normalize actual schema-declared NUMBER values only; no general type coercion |
| CSV import/preview | Permissive [CSV coercion](../test-case-csv-transfer/spec.md); failed conversion preserves the parsed value |
| Dataset schema-change revalidation | [Strict coercion](../test-case-revalidation/spec.md) before validation |
| Selector-scoped composite patches | No validation/normalization; stored verdicts may remain stale |

Type-mismatch rules apply to values after this preparation. Null values and unknown schema types have no type-mismatch warning; successful coercion removes the corresponding mismatch. Scope-aware checks are specified separately below.

API warnings SHALL be returned when `includeWarnings=true`. Each has `path`, `message`, optional `code`, `fieldName` only for field-specific warnings, and a 0-based `turnIndex` only for warnings raised against a turn (per-turn field warnings and shared-field-in-turn INVALID_SCOPE); case-level warnings omit `fieldName`, and case-level and shared-data warnings omit `turnIndex`. Paths use `$.data.<field>`, `$.multiTurnData[i].<field>`, or `$.multiTurnData` for the array itself. Import streaming/provisional verdicts belong to [CSV transfer](../test-case-csv-transfer/spec.md); task trigger/status belong to [datasets](../datasets/spec.md).

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Missing required field in data | a `testCaseSchema` field has `required: true` and the corresponding key is missing or null in `data`, or, for a required `FILE`/`SKILL` field, holds a blank (empty or whitespace-only) string | SHALL set valid=false and add a validation warning with `fieldName` |
| Type mismatch in data | a `data` value does not match the `testCaseSchema` field's declared `type` | SHALL set valid=false and add a validation warning with `fieldName` and expected type |
| Type mismatch warning for STRING field with numeric value | a test case data field contains a numeric value (Integer, Long, or Double) and the schema declares the field type as `STRING` | SHALL emit a `TYPE` validation warning with message indicating the expected and actual types; the test case SHALL still be saved with `isValid=false` |
| Type mismatch warning for STRING field with boolean value | a test case data field contains a Boolean value and the schema declares the field type as `STRING`; the value is submitted through a direct API write (POST/PUT/PATCH) | SHALL emit a `TYPE` validation warning; CSV import and schema-change revalidation SHALL convert Boolean values to strings before validation, so those converted values SHALL NOT produce TYPE warnings for this field |
| Type mismatch warning for INTEGER field with string value | a test case data field contains a String value and the schema declares the field type as `INTEGER` | SHALL emit a `TYPE` validation warning |
| Type mismatch warning for BOOLEAN field with non-boolean value | a test case data field contains a String, Double, Integer, or Long value and the schema declares the field type as `BOOLEAN` | SHALL emit a `TYPE` validation warning (On the CSV import path, `BOOLEAN ← Long` is coerced successfully before validation runs, so Long values in BOOLEAN columns will not produce a TYPE warning after CSV import. On the API path (POST/PUT/PATCH), no coercion runs — Integer, Long, and Double values in BOOLEAN columns will produce TYPE warnings. On the schema-change revalidation path, `String → BOOLEAN` is coerced ONLY for the literal values `"true"` / `"false"` (other strings, and Integer/Long/Double, remain unchanged and produce TYPE warnings).) |
| NUMBER field accepts integer values without warning | a test case data field contains an Integer or Long value and the schema declares the field type as `NUMBER` | SHALL NOT emit a TYPE warning (integers are valid numbers) |
| No type warning for null values | a test case data field is null and the schema declares any type | SHALL NOT emit a TYPE warning for null (nulls are handled by the existing REQUIRED check) |
| No type warning when schema type is unknown | a data field has no corresponding schema field definition | SHALL NOT emit a TYPE warning (the existing ADDITIONAL/UNKNOWN check handles this) |
| API create with type mismatch produces warning | a client creates a test case via POST API with `{"data": {"answer": 1865}}` and schema declares `answer` as `STRING` | SHALL save the test case with `isValid=false` and a TYPE warning for field `answer` |
| Suite configuration does not affect stored test-case validity | a test case's content satisfies its dataset schema with no validation warnings, but a referencing suite has a missing template or incomplete input bindings | the test case SHALL remain valid and SHALL NOT acquire suite template or binding warnings; suite configuration warnings SHALL be reported on the suite independently |
| Unknown fields in data (not in schema) | `data` contains keys not present in `testCaseSchema` | SHALL add a validation warning (e.g., "Unknown data field 'x'") |
| Get validation warnings on request | `GET .../test-cases/{id}?includeWarnings=true` for invalid case | SHALL include validationWarnings in response; without param, omit them |
| POST test case with Boolean for STRING field produces TYPE warning | schema declares field `f` as `STRING`; `POST .../test-cases` with body `{"data": {"f": true}}` | SHALL save the test case with `isValid=false` and a TYPE warning for field `f` (Direct API writes preserve the user's literal intent; auto-coercion is reserved for cases where the user changed the schema and the system must reinterpret existing rows.) |
| PATCH test case data does not coerce values | client PATCHes a test case with new `data` containing a type mismatch against the current schema | validation SHALL emit a TYPE warning without converting the mismatched value; schema-declared numeric NUMBER values SHALL still receive representation normalization |
| Create returns structured warnings when invalid | client creates a TestCase that fails validation with `includeWarnings=true` | response SHALL include structured `validationWarnings` with `path`, `message`, optional `code`, `fieldName` for field-specific warnings, and `turnIndex` for turn-level warnings; case-level warnings SHALL omit the field name |
| Warnings use fieldName | a TestCase has validation warnings | each field-specific warning object SHALL use `fieldName` to identify the schema field, whether its value is shared or per-turn |

### Requirement: Import-derived conflict warnings are durable

Status: **Implemented**

A CSV import detects conflicts that describe the submitted **rows** rather than the resulting test case: a `turnIndex` repeated within a case, and turn rows disagreeing on a shared column. The assembled case is well-formed, so these findings cannot be re-derived from stored data by any later pass.

(The third multi-turn conflict, a `testCaseName` reappearing non-contiguously, is not covered by this requirement: it describes a second run of a name that already exists, and no conflict strategy leaves a conflicted case behind — the request is rejected, the second run is skipped, or it deliberately replaces the earlier case — so it remains a request-scoped import warning as today.)

Such warnings SHALL be persisted with a distinguishing code that marks them as derived from the import source. Any pass that **recomputes** a test case's validity from its stored state — the post-import coercion fixup and dataset revalidation alike — SHALL carry forward the stored source-derived warnings and SHALL report the case invalid while any remain, in addition to whatever it computes itself.

A single-case or array-batch `PUT`/`PATCH` SHALL recompute validation without carrying source-derived warnings forward, including a name-only PATCH. Composite `PATCH :bulk` has different validation scope: selector operations do not recompute validity or warnings; item operations recompute when the patch contains any of `data`, `multiTurnData`, or `testCaseName`, even if its submitted value is ignored. A name-only composite item patch SHALL recompute validation without carrying source-derived warnings forward. Re-import behavior follows the selected conflict strategy; skipped cases are not rewritten.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Import conflict survives the import request | a CSV whose turn rows repeat a `turnIndex` is imported, the import persists a schema, and the coercion fixup and revalidation both run before the response is returned | the persisted case SHALL still be marked invalid and SHALL still carry the conflict warning reported in the import response |
| Import conflict survives a later schema change | a case carrying an import-derived conflict warning is revalidated after a subsequent dataset schema change | the warning SHALL still be present and the case SHALL still be invalid, alongside any new warnings the revalidation computes |
| Import conflict survives value coercion | the post-import fixup coerces a conflicted case's values to a newly determined schema type and re-validates it | the recomputed result SHALL be unioned with the stored conflict warning and the case SHALL stay invalid, rather than being reported valid because the assembled case is well-formed |
| Editing the case clears the conflict | a client uses single-case or array-batch `PUT`/`PATCH` on a case that carries an import-derived conflict warning | validity and warnings SHALL be computed from the submitted content alone and the conflict warning SHALL NOT be carried forward |
| Name-only composite item patch clears source conflicts | a composite `PATCH :bulk` item contains only `testCaseName` on a case carrying an import-derived conflict warning | validation SHALL be recomputed without carrying the source-derived warning forward |
| Composite item validation clears source conflicts | a composite `PATCH :bulk` item contains `data`, `multiTurnData`, or `testCaseName` on a case carrying an import-derived conflict warning | validation SHALL be recomputed without carrying source-derived warnings forward, even when the submitted value is ignored by patch merging |
| Cases without source conflicts are unaffected | a recomputation pass processes a case carrying no source-derived warning | it SHALL write exactly the result it computes, with no change from current behavior |

### Requirement: Turn-count bounds

Status: **Implemented**

The maximum number of turns SHALL be configurable via `test-case.multi-turn.max-turns` (default 30). An over-cap case SHALL be persisted with `valid=false` and an invalidating warning, not rejected, and excluded from runnable selection. The structural non-empty-array rule is owned by [test-cases](../test-cases/spec.md).

#### Scenario: Over-cap case is invalidated, not rejected
- **WHEN** a case is stored with more turns than the configured maximum
- **THEN** it is persisted with `is_valid=false` and a warning, and it is excluded from runnable selection

## Implementation notes

- [TestCaseValidationService.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/TestCaseValidationService.java), [TestCaseFieldScopeResolver.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/TestCaseFieldScopeResolver.java): schema/scope checks and warning construction.
- [TestCaseService.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/TestCaseService.java): validating-write NUMBER normalization; selectors intentionally bypass it.
- [TestCaseMapper.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/mapper/TestCaseMapper.java): includeWarnings response shaping.
