## Why

CSV import ignores the declared test case schema type when storing cell values. `CsvCellParser.parseCell()` applies heuristic type inference (integer/number/boolean patterns), so a value like `1865` in a `STRING`-typed column is stored as a JSON number instead of a JSON string. This propagates through metric input binding resolution (which does no type coercion) and causes downstream metric providers to reject the value (e.g., Pydantic returns `422: Input should be a valid string, input: 1865`).

The same issue affects boolean detection: CSV values `"true"`, `"false"` in STRING columns are stored as JSON booleans. Additionally, `CsvCellParser` treats `"1"` and `"0"` as boolean literals, so these are stored as JSON booleans instead of numbers.

## What Changes

- **Fix `CsvCellParser`**: Remove `"1"`/`"0"` from boolean detection — only literal `"true"`/`"false"` (case-insensitive) are parsed as booleans. Switch from `Integer.parseInt()` to `Long.parseLong()` — aligns with the baseline spec's Type System Reference (`INTEGER → Long`) and fixes silent overflow for values exceeding `Integer.MAX_VALUE`. `"1"` and `"0"` now fall through to the integer pattern and are parsed as `Long(1)` and `Long(0)`. This eliminates lossy coercion (no more `1` → `Boolean(true)` → `"true"`) and makes `String.valueOf()` lossless for all `CsvCellParser` outputs. Schema auto-detection will infer columns with only `1`/`0` values as `INTEGER` instead of `BOOLEAN` — this is more correct; users can adjust the schema type if they need `BOOLEAN`.
- Add schema-aware type coercion in `CsvImportService.parseRow()` after `CsvCellParser.parseCell()`. When the declared schema type is known, coerce the parsed value to match:
  - Schema `STRING` + parsed Long/Double/Boolean → `String.valueOf(value)` (lossless after CsvCellParser fix)
  - Schema `INTEGER` + parsed String that is numeric → `Long.parseLong()`; Double with fractional part (e.g., `3.14`) is NOT truncated — treated as coercion failure to avoid data loss
  - Schema `NUMBER` + parsed String that is numeric → `Double.parseDouble()`
  - Schema `BOOLEAN` + parsed String → boolean parse; Long → `!= 0`
- **Post-persist fixup pass**: When schema is auto-detected or changed during import (OVERRIDE, APPEND with empty schema, MERGE with new columns), the final schema types are not known until all rows are processed. After schema persistence, a fixup pass re-reads all suite test cases in batches, coerces values for columns whose schema type was newly determined, re-validates, and batch-updates any changed rows. This ensures type correctness even when schema is undefined at import start.
- CSV preview (`/preview` endpoint) sample rows will also reflect coerced values when a schema type is known, since `parseRow()` is shared between preview and import. This is the desired behavior — preview should accurately show what will be stored. Schema type auto-detection (inference) in preview is unaffected, as that code path is separate from per-cell coercion.
- Add type mismatch validation in `TestCaseValidationService`: when a data field's JSON type does not match the declared schema type, emit a `TYPE` validation warning. This covers **all ingestion paths** (API create/update/patch, CSV import) as a safety net. No coercion at this level — just soft validation (test case is still saved with `isValid=false`). Note: the baseline spec already defines a "Type mismatch in data" scenario that was never implemented — this change implements it with detailed type compatibility rules.

## Capabilities

### New Capabilities

_(none)_

### Modified Capabilities

- `test-cases`: CSV import must coerce parsed cell values to match the declared schema field type before storage. This is a new requirement on the import path — currently the spec only describes OBJECT/ARRAY JSON parsing, not scalar type coercion. Additionally, `TestCaseValidationService` must emit a `TYPE` warning when a data field's value type does not match the schema's declared type.

## Impact

- **Code**: `CsvCellParser` — remove `1`/`0` from boolean detection, switch `Integer.parseInt()` to `Long.parseLong()`. `CsvImportService.parseRow()` — add coercion logic after `csvCellParser.parseCell()`. `CsvImportService.importCsv()` — add post-persist fixup pass. Extract a `SchemaTypeCoercer` component for testability. Add type-checking logic to `TestCaseValidationService.validateTestCase()`.
- **Existing data**: Already-stored test cases with wrong types are **not** retroactively fixed by this change alone. However, re-importing CSV or re-validating existing test cases will now surface TYPE warnings for mismatched data. The post-persist fixup only applies to the current import operation (all suite test cases at the time of import).
- **APIs**: No API contract changes. No new endpoints or DTOs. New `TYPE` validation warning may appear for test cases with type mismatches.
- **Tests**: Unit tests for coercion logic and type validation; update existing CSV import functional tests to verify type correctness; add functional tests for API-path type warnings.
- **No DB migration needed.**
- **No config changes needed.**

## Rollout

This is a bug fix with no API contract changes. Only new CSV imports are affected — stored types will now match the declared schema. Existing data from previous imports is not retroactively modified. No feature flags or phased rollout needed.
