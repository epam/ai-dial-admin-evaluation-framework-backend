## ADDED Requirements

### Requirement: Schema-change revalidation auto-coerces test case data
The system SHALL run a value-coercion pass over each test case's `data` map BEFORE validation when `RevalidationService` re-validates existing test cases after a TestSuite schema change. Coercion MUST use the strict schema-change conversion table below (narrower than the CSV-import table used by `SchemaTypeCoercer`) and SHALL be implemented by a separate `SchemaChangeCoercer` (`service.domain.csv` package). For each `(value, targetType)` pair where the target type is the field's current schema type, the coercer SHALL return either a converted value (if the conversion is in the safe set) or the input value unchanged (if not). When at least one cell in a row is converted, the new `data` JSONB SHALL be persisted in a guarded UPDATE (precondition: `updated_at = :seenAt`) BEFORE validation runs against the post-coercion data. Validation results (`is_valid`, `validation_warnings`) SHALL be persisted in a SECOND guarded UPDATE. If either guarded UPDATE affects 0 rows (concurrent edit), the row SHALL be skipped entirely (no further writes). *Status: Planned.*

**Strict schema-change conversion table** (rows = source Java type, columns = target `SchemaFieldType`; "skip" = return input unchanged):

| Source ↓ \ Target →     | STRING                | INTEGER             | NUMBER                | BOOLEAN                  | FILE         | OBJECT     | ARRAY      |
|-------------------------|-----------------------|---------------------|-----------------------|--------------------------|--------------|------------|------------|
| String                  | identity              | `Long.parseLong`    | `Double.parseDouble`  | `"true"`/`"false"` only  | identity     | skip       | skip       |
| Integer/Long            | `String.valueOf`      | identity            | `doubleValue`         | skip                     | skip         | skip       | skip       |
| Double                  | `String.valueOf`      | only if `% 1 == 0`  | identity              | skip                     | skip         | skip       | skip       |
| Boolean                 | `"true"`/`"false"`    | skip                | skip                  | identity                 | skip         | skip       | skip       |
| Object (Map)            | skip                  | skip                | skip                  | skip                     | skip         | identity   | skip       |
| Array (List)            | skip                  | skip                | skip                  | skip                     | skip         | skip       | identity   |
| `null`                  | identity              | identity            | identity              | identity                 | identity     | identity   | identity   |

This table is intentionally **stricter** than `SchemaTypeCoercer` used by CSV import: `Integer/Long → BOOLEAN`, `Double → BOOLEAN`, `Boolean → INTEGER/NUMBER`, `Object/Array → STRING`, and any non-String → FILE are all **excluded** to avoid silently reinterpreting typed JSON in ways the user did not request.

#### Scenario: Boolean field type changed to STRING coerces existing values
- **WHEN** a TestSuite schema field's type is changed from `BOOLEAN` to `STRING` and revalidation runs
- **AND** an existing test case has `data: {"flag": true}`
- **THEN** the system SHALL persist `data: {"flag": "true"}` (Java String) before validation
- **AND** validation SHALL set `isValid=true` (no TYPE warning) for that field
- **AND** the revalidation task `coercedCellCount` SHALL be incremented by 1

#### Scenario: Integer field type changed to STRING coerces existing values
- **WHEN** a schema field's type is changed from `INTEGER` to `STRING` and revalidation runs
- **AND** an existing test case has `data: {"year": 1865}` (Long)
- **THEN** the system SHALL persist `data: {"year": "1865"}` and `isValid=true`

#### Scenario: Number field type changed to STRING coerces existing values
- **WHEN** a schema field's type is changed from `NUMBER` to `STRING` and revalidation runs
- **AND** an existing test case has `data: {"score": 3.14}` (Double)
- **THEN** the system SHALL persist `data: {"score": "3.14"}` and `isValid=true`

#### Scenario: Integer field type changed to NUMBER coerces existing values
- **WHEN** a schema field's type is changed from `INTEGER` to `NUMBER` and revalidation runs
- **AND** an existing test case has `data: {"n": 42}` (Long)
- **THEN** the system SHALL persist `data: {"n": 42.0}` (Double) and `isValid=true`

#### Scenario: String field type changed to BOOLEAN coerces "true"/"false" only
- **WHEN** a schema field's type is changed from `STRING` to `BOOLEAN` and revalidation runs
- **AND** an existing test case has `data: {"flag": "true"}`
- **THEN** the system SHALL persist `data: {"flag": true}` (Boolean) and `isValid=true`
- **AND** when another test case has `data: {"flag": "yes"}`, the value SHALL be left unchanged and the row SHALL be marked invalid with a TYPE warning

#### Scenario: String field type changed to INTEGER coerces parseable values only
- **WHEN** a schema field's type is changed from `STRING` to `INTEGER` and revalidation runs
- **AND** an existing test case has `data: {"n": "42"}`
- **THEN** the system SHALL persist `data: {"n": 42}` (Long) and `isValid=true`
- **AND** when another test case has `data: {"n": "hello"}`, the value SHALL be left unchanged and the row SHALL be marked invalid with a TYPE warning

#### Scenario: Whole-number Double field type changed to INTEGER coerces successfully
- **WHEN** a schema field's type is changed from `NUMBER` to `INTEGER` and revalidation runs
- **AND** an existing test case has `data: {"n": 7.0}` (Double, whole number)
- **THEN** the system SHALL persist `data: {"n": 7}` (Long) and `isValid=true`

#### Scenario: Fractional Double field type changed to INTEGER does not truncate
- **WHEN** a schema field's type is changed from `NUMBER` to `INTEGER` and revalidation runs
- **AND** an existing test case has `data: {"n": 3.14}` (Double, fractional)
- **THEN** the system SHALL leave the value unchanged and the row SHALL be marked invalid with a TYPE warning
- **NOTE**: Truncating `3.14` → `3` would lose data; only whole-number Doubles are coerced.

#### Scenario: Integer-to-BOOLEAN is NOT auto-coerced (stricter than CSV import)
- **WHEN** a schema field's type is changed from `INTEGER` to `BOOLEAN` and revalidation runs
- **AND** an existing test case has `data: {"flag": 1}` (Long)
- **THEN** the system SHALL leave the value unchanged and the row SHALL be marked invalid with a TYPE warning
- **NOTE**: CSV import would coerce `1` → `true`, but on the revalidation path the user did not request that interpretation; the row stays invalid until the user explicitly fixes the data.

#### Scenario: Boolean-to-INTEGER is NOT auto-coerced (stricter than CSV import)
- **WHEN** a schema field's type is changed from `BOOLEAN` to `INTEGER` and revalidation runs
- **AND** an existing test case has `data: {"n": true}` (Boolean)
- **THEN** the system SHALL leave the value unchanged and the row SHALL be marked invalid with a TYPE warning

#### Scenario: Non-String value targeting FILE is NOT auto-coerced
- **WHEN** a schema field's type is changed to `FILE` and revalidation runs
- **AND** an existing test case has a non-String value (Boolean, Number, Object, Array) for that field
- **THEN** the system SHALL leave the value unchanged and the row SHALL be marked invalid with a TYPE warning
- **NOTE**: The validator emits TYPE (not FILE-format) because the value is the wrong Java type and never reaches the FILE-format check. A FILE value must be a valid DIAL file reference; coercing a Boolean/Number to a String would produce text that is not a file path and would still fail downstream validation.

#### Scenario: String value targeting FILE is preserved as-is
- **WHEN** a schema field's type is changed from `STRING` to `FILE` and revalidation runs
- **AND** an existing test case has `data: {"f": "@ef/suites/abc/foo.png"}`
- **THEN** the system SHALL leave the value unchanged (identity coercion)
- **AND** validation SHALL apply the standard FILE format/prefix check (passing for valid `@ef/...` references, warning otherwise)

#### Scenario: Object-to-STRING is NOT auto-coerced
- **WHEN** a schema field's type is changed to `STRING` and revalidation runs
- **AND** an existing test case has `data: {"obj": {"a": 1}}` (Map)
- **THEN** the system SHALL leave the value unchanged and the row SHALL be marked invalid with a TYPE warning
- **NOTE**: `String.valueOf(Map)` produces Java's debug form (e.g. `{a=1}`), which is rarely the user's intent; treat as unconvertible.

#### Scenario: Array-to-STRING is NOT auto-coerced
- **WHEN** a schema field's type is changed to `STRING` and revalidation runs
- **AND** an existing test case has `data: {"arr": [1, 2, 3]}` (List)
- **THEN** the system SHALL leave the value unchanged and the row SHALL be marked invalid with a TYPE warning

#### Scenario: Null values are never coerced
- **WHEN** any test case has `data: {"f": null}` and revalidation runs against any target type
- **THEN** the system SHALL leave the null unchanged
- **AND** the row SHALL be marked invalid only if the field is required (existing REQUIRED check)

#### Scenario: Already-matching types yield zero coerced cells
- **WHEN** revalidation runs and every cell already matches its current schema type (e.g. revalidation re-run after a prior coerced run)
- **THEN** no `data` UPDATE SHALL be issued for that row
- **AND** only the validation UPDATE SHALL fire
- **AND** the task `coercedCellCount` SHALL remain 0 if no prior coercion occurred in this run

#### Scenario: Per-row data update is guarded by updated_at
- **WHEN** revalidation reads a test case row with `updated_at = T0`, computes coerced data, and another caller PATCHes the row to `updated_at = T1` before the data UPDATE runs
- **THEN** the guarded UPDATE SHALL affect 0 rows
- **AND** revalidation SHALL skip both the data write and the validation write for that row
- **AND** the user's edit at T1 SHALL remain intact
- **AND** the row SHALL count toward `processedCases` but SHALL NOT increment `validCount` or `invalidCount`

#### Scenario: Per-row validation update is guarded by updated_at
- **WHEN** revalidation successfully writes coerced data and then attempts the validation UPDATE, but a concurrent edit changes `updated_at` between the two writes
- **THEN** the second guarded UPDATE SHALL affect 0 rows
- **AND** revalidation SHALL skip the validation write; subsequent revalidations will reconcile
- **AND** the row SHALL count toward `processedCases` but SHALL NOT increment `validCount` or `invalidCount`

#### Scenario: Coerced cell counter accumulates across rows
- **WHEN** a revalidation task processes 100 test cases, each with 3 BOOLEAN→STRING fields successfully coerced
- **THEN** the task's `coercedCellCount` SHALL be 300 at completion
- **AND** the task response SHALL include `coercedCellCount` as a Long

### Requirement: RevalidationTask exposes coercedCellCount
The `revalidation_tasks` table SHALL include a `coerced_cell_count BIGINT NOT NULL DEFAULT 0` column, and the `RevalidationTaskDto` returned by the revalidation status endpoints SHALL include a `coercedCellCount` field of type `Long`. The counter SHALL be incremented by the number of cells (one per (row, field) pair) successfully coerced during the run, and SHALL NOT count rows whose data was unchanged. *Status: Planned.*

#### Scenario: Get revalidation task includes coercedCellCount
- **WHEN** client calls `GET /api/v1/test-suites/{id}/revalidation-tasks/{taskId}`
- **THEN** the response body SHALL include `"coercedCellCount": <number>` (default 0 when no coercion occurred)

#### Scenario: List revalidation tasks includes coercedCellCount
- **WHEN** client calls `GET /api/v1/test-suites/{id}/revalidation-tasks`
- **THEN** every entry in `content[]` SHALL include `coercedCellCount`

#### Scenario: Start revalidation HTTP 202 response includes coercedCellCount
- **WHEN** client triggers revalidation (e.g. via suite update with schema change, or `POST .../revalidation-tasks`)
- **THEN** the HTTP 202 RevalidationTaskDto body SHALL include `coercedCellCount: 0` (initial value)

#### Scenario: Pre-existing tasks expose 0 coercedCellCount
- **WHEN** the migration runs against a database with existing `revalidation_tasks` rows
- **THEN** every existing row SHALL have `coerced_cell_count = 0` (column default)
- **AND** subsequent reads via the API SHALL surface `coercedCellCount: 0` for those tasks

### Requirement: Direct API writes do NOT auto-coerce
The auto-coercion behaviour described above SHALL apply ONLY to the schema-change revalidation path (`RevalidationService`). Direct test case writes (`POST/PUT/PATCH /api/v1/test-suites/{id}/test-cases[/{tcId}]`) SHALL continue to validate the supplied `data` as-is and emit TYPE warnings on type mismatch. CSV import SHALL continue to use the existing `SchemaTypeCoercer` with its own permissive table, unaffected by this change. *Status: Planned.*

#### Scenario: POST test case with Boolean for STRING field still produces TYPE warning
- **WHEN** schema declares field `f` as `STRING`
- **AND** client calls `POST .../test-cases` with body `{"data": {"f": true}}`
- **THEN** the system SHALL save the test case with `isValid=false` and a TYPE warning for field `f`
- **NOTE**: Direct API writes preserve the user's literal intent; auto-coercion is reserved for cases where the user changed the schema and the system must reinterpret existing rows.

#### Scenario: PATCH test case data does not coerce values
- **WHEN** client PATCHes a test case with new `data` containing a type mismatch against the current schema
- **THEN** validation SHALL emit a TYPE warning (existing behaviour); no coercion runs

#### Scenario: CSV import retains its own (permissive) coercion rules
- **WHEN** client imports CSV containing `"1"` for a BOOLEAN-typed field
- **THEN** the existing `SchemaTypeCoercer` SHALL coerce `"1"` → `true` (Long → Boolean rule), unchanged from current behaviour
- **NOTE**: `SchemaChangeCoercer` is stricter than `SchemaTypeCoercer`; the two are sibling components in the `service.domain.csv` package.

## MODIFIED Requirements

### Requirement: Validate TestCases against schema, template, and bindings (Soft Validation)
The service SHALL validate `data` against `testCaseSchema` and validate template variable requirements against effective bindings. Validation uses the effective template (`requestTemplateOverride ?? suite.requestTemplate`) and effective bindings (`inputBindingsOverride ?? suite.inputBindings`). Validation failures produce warnings (not rejection). `valid=false` when any validation fails.

In addition to existing checks (REQUIRED, ADDITIONAL, UNKNOWN, FILE format), the system SHALL check that each data field's value type is compatible with the declared schema field type. When a mismatch is detected, the system SHALL emit a `TYPE` validation warning — EXCEPT on the schema-change revalidation path, where `SchemaChangeCoercer` first attempts to convert the value (per the strict table in the ADDED Requirements above); when coercion succeeds, no TYPE warning is emitted for that cell.

This MODIFICATION qualifies the type-mismatch scenarios below to clarify which path they apply to. Direct API writes (`POST/PUT/PATCH /test-cases`) and CSV import paths retain their existing behavior. The schema-change revalidation path is governed by the ADDED `Schema-change revalidation auto-coerces test case data` requirement.

#### Scenario: Type mismatch warning for STRING field with boolean value
- **WHEN** a test case data field contains a Boolean value and the schema declares the field type as `STRING`
- **AND** the value is reaching the validator via a direct API write (POST/PUT/PATCH) or CSV import
- **THEN** system SHALL emit a `TYPE` validation warning
- **NOTE**: On the schema-change revalidation path, `Boolean → STRING` is auto-coerced by `SchemaChangeCoercer` (`true` → `"true"`, `false` → `"false"`) BEFORE validation runs, and no TYPE warning is emitted.

#### Scenario: Type mismatch warning for BOOLEAN field with non-boolean value
- **WHEN** a test case data field contains a String, Double, Integer, or Long value and the schema declares the field type as `BOOLEAN`
- **THEN** system SHALL emit a `TYPE` validation warning
- **Note:** On the CSV import path, `BOOLEAN ← Long` is coerced successfully before validation runs, so Long values in BOOLEAN columns will not produce a TYPE warning after CSV import. On the API path (POST/PUT/PATCH), no coercion runs — Integer, Long, and Double values in BOOLEAN columns will produce TYPE warnings. On the schema-change revalidation path, `String → BOOLEAN` is coerced ONLY for the literal values `"true"` / `"false"` (other strings, and Integer/Long/Double, remain unchanged and produce TYPE warnings).

#### Scenario: Track re-validation task status
- **WHEN** client calls `GET /api/v1/test-suites/{id}/revalidation-tasks/{taskId}`
- **THEN** system SHALL return task status (PENDING, RUNNING, COMPLETED, FAILED, TIMED_OUT) and progress (`processedCases`, `validCount`, `invalidCount`, `coercedCellCount`)
- **AND** `coercedCellCount` SHALL default to `0` for tasks created before this feature shipped (per the migration's `DEFAULT 0`) and for tasks where no cells required coercion

## Implementation notes

- New component: `com.epam.aidial.evaluation.service.domain.csv.SchemaChangeCoercer` (`@Component`, `@LogExecution`).
- Modified service: `com.epam.aidial.evaluation.service.domain.RevalidationService.runRevalidationAsync` — coerce → guarded data update → validate → guarded validation update; accumulate `coercedCellCount` and `skippedCount`. Skipped rows count toward `processedCases` but NOT toward `validCount` / `invalidCount`.
- Modified repository: `com.epam.aidial.evaluation.data.db.repository.TestCaseRepository` (and `PostgresTestCaseRepository`) — new methods `updateDataIfUnchanged(...)` and `updateValidationIfUnchanged(...)` returning `int` rows-affected.
- Modified model: `com.epam.aidial.evaluation.data.db.model.RevalidationTask` — new `Long coercedCellCount` field.
- Flyway migration: `db/migration/meta/POSTGRES/V1.21__AddCoercedCellCountToRevalidationTasks.sql` — adds `coerced_cell_count BIGINT NOT NULL DEFAULT 0`.
- DTO: `com.epam.aidial.evaluation.service.domain.dto.RevalidationTaskDto.coercedCellCount` (Long).
- Tests: `SchemaChangeCoercerTest` (unit, covers every cell of the conversion table); `PostgresFunctionalTests$RevalidationTests` extends with happy-path coercion, FILE carve-out, Object/Array carve-out, concurrent-edit guard miss, and `coercedCellCount` accumulation.
