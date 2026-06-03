## 1. CsvCellParser Fix

- [x] 1.1 Remove `"1"`/`"0"` from `CsvCellParser.isBoolean()` — only literal `"true"`/`"false"` (case-insensitive) are boolean. Switch `Integer.parseInt()` to `Long.parseLong()` in `parseCell()` — aligns with Type System Reference (`INTEGER → Long`) and fixes silent overflow for values exceeding `Integer.MAX_VALUE`. `"1"` and `"0"` fall through to the integer pattern and are parsed as `Long(1)` / `Long(0)`. Also clean up `parseBoolean()` to remove the `"1"` true-check since `1`/`0` no longer reach this method (done: `isBoolean`, `parseBoolean`, and `parseCell` updated, `parseCell("1")` returns `Long(1)`, `parseCell("3000000000")` returns `Long(3000000000)`)
- [x] 1.2 Update `CsvCellParser` unit tests: `parseCell("1")` → `Long(1)` (not `Boolean(true)`), `parseCell("0")` → `Long(0)` (not `Boolean(false)`), `parseCell("42")` → `Long(42)`, `parseCell("3000000000")` → `Long(3000000000)`, `parseCell("true")` → `Boolean(true)` unchanged, `parseCell("false")` → `Boolean(false)` unchanged. Update `inferTypeName` tests: `"1"` → `INTEGER`, `"0"` → `INTEGER`, `"true"` → `BOOLEAN`, `"false"` → `BOOLEAN` (done: tests pass)

## 2. SchemaTypeCoercer Component

- [x] 2.1 Create `SchemaTypeCoercer` class in `service.domain.csv` package — `@Component`, `@LogExecution`, with `public Object coerce(Object value, SchemaFieldType schemaType)` method implementing the full coercion matrix from design.md. Uses `Long.parseLong()` for STRING→INTEGER coercion, accepts both `Integer` and `Long` inputs via `Number.longValue()`, always outputs `Long` for INTEGER schema type. No `rawValue` parameter needed — `String.valueOf()` is lossless after CsvCellParser fix (done: class exists, compiles, has `@LogExecution`)
- [x] 2.2 Write unit tests for `SchemaTypeCoercer` covering all type combinations: STRING←Long, STRING←Integer, STRING←Double, STRING←Boolean, INTEGER←String (`Long.parseLong`), INTEGER←String large (`"3000000000"`→`Long(3000000000)`), INTEGER←Double whole (`3.0`→`Long(3)`), INTEGER←Double fractional (`3.14`→ coercion failure, returns Double unchanged), INTEGER←Boolean (`true→1L`, `false→0L`), INTEGER←Integer (widen to Long), BOOLEAN←Long (`!=0→true`, `0→false`), BOOLEAN←Integer (`!=0→true`, `0→false`), BOOLEAN←String (`"true"`→`true`, `"false"`→`false`), BOOLEAN←Double (coercion failure, returns Double unchanged), NUMBER←String, NUMBER←Long, NUMBER←Integer, NUMBER←Boolean (`true→1.0`, `false→0.0`), no-op cases (STRING←String, INTEGER←Long, etc.), null schema type passthrough, FILE←Long/Boolean (coerce to string), FILE←String (no-op), coercion failure fallback (e.g. `"hello"`→INTEGER), empty string coercion failure for non-STRING types (`""`→INTEGER/NUMBER/BOOLEAN returns `""` unchanged) (done: all scenarios from spec covered, tests pass)

## 3. CsvImportService Integration — Inline Coercion

- [x] 3.1 Inject `SchemaTypeCoercer` into `CsvImportService` and call `coerce(value, type)` in `parseRow()` after `csvCellParser.parseCell()` for scalar types (non-OBJECT/ARRAY) when schema type is known (done: coercion applied before `data.put()`, existing OBJECT/ARRAY handling unchanged)
- [x] 3.2 Verify existing CSV import unit tests still pass; update any assertions affected by the CsvCellParser `1`/`0` and `Integer→Long` changes or coercion. Update `CsvImportServiceSchemaTest.setUp()` constructor call to include the new `SchemaTypeCoercer` dependency (the test uses a direct constructor call at line ~67 that will fail to compile after injection) (done: `./gradlew test` passes)

## 4. Post-Persist Fixup Pass

- [x] 4.1 Implement the fixup pass in `CsvImportService.importCsv()` after `persistSchema()`: compute `changedColumns` (column names whose schema type was newly determined — all columns for OVERRIDE/empty-schema, only new columns for MERGE); if non-empty, read all suite test cases in batches, for each test case coerce changed columns via `SchemaTypeCoercer`, re-validate via `TestCaseValidationService`, and batch-update rows whose data changed (done: fixup runs after schema persist, processes in batches, within same transaction)
- [x] 4.2 Write unit/integration tests for the fixup pass: import CSV with empty schema where column has mixed types (e.g., integers then strings → schema widens to STRING), assert all stored test cases have correct string values after fixup; import in APPEND mode with existing schema, assert no fixup runs (done: tests pass)

## 5. Type Mismatch Validation in TestCaseValidationService

- [x] 5.1 Add type-checking logic to `TestCaseValidationService.validateTestCase()`: for each schema field with a known type (STRING/INTEGER/NUMBER/BOOLEAN/OBJECT/ARRAY/FILE), check if the data value's JSON type is compatible; emit `ValidationWarningCode.TYPE` warning on mismatch (done: logic added, NUMBER accepts integers, nulls skipped)
- [x] 5.2 Write unit tests for type validation covering all spec scenarios: STRING+Long → TYPE warning, STRING+Integer → TYPE warning, STRING+Boolean → TYPE warning, INTEGER+String → TYPE warning, BOOLEAN+String → TYPE warning, BOOLEAN+Long → TYPE warning (API path — no coercion), BOOLEAN+Integer → TYPE warning, BOOLEAN+Double → TYPE warning, NUMBER+Integer → no warning, NUMBER+Long → no warning, OBJECT+non-Map → TYPE warning, ARRAY+non-List → TYPE warning, null value → no warning, no schema field → no warning, matching types → no warning (STRING+String, INTEGER+Integer, INTEGER+Long, NUMBER+Double, BOOLEAN+Boolean, OBJECT+Map, ARRAY+List, FILE+String) (done: tests pass)

## 6. Functional Tests

- [x] 6.1 Add or update CSV import functional test(s) to verify type coercion: import CSV with numeric value in STRING-typed column (existing schema), assert stored JSONB contains JSON string not JSON number (done: functional test asserts correct JSON type in DB)
- [x] 6.2 Add functional test for boolean coercion: import CSV with `true`/`false` in STRING-typed column, assert stored as JSON string (done: test passes)
- [x] 6.3 Add functional test for API-path type warning: create test case via POST with integer value in STRING-typed column, assert test case saved with `isValid=false` and TYPE validation warning (done: test passes)
- [x] 6.4 Add functional test for post-persist fixup: import CSV into suite with empty schema where a column has `42, 99, "hello"` (widens to STRING), assert ALL rows have string values in JSONB after import (done: test passes)
- [x] 6.5 Add functional test for `1`/`0` CsvCellParser fix: import CSV with `1`, `0` values into empty schema, assert stored as integers (not booleans), auto-detected schema type is INTEGER (done: test passes)
- [x] 6.6 Add functional test for large integer values: import CSV with value `3000000000` (exceeds `Integer.MAX_VALUE`) into suite with INTEGER-typed schema, assert stored as Long and no type warning (done: test passes)
- [x] 6.7 Add functional test for coercion failure with type warning: import CSV with `"hello"` into suite with INTEGER-typed schema, assert value stored as string and test case has `isValid=false` with TYPE warning (done: test passes)
- [x] 6.8 Add functional test for CSV export-import round-trip: export test cases with mixed types (INTEGER, NUMBER, BOOLEAN, STRING), re-import into same suite with OVERRIDE, assert data values are type-equivalent after round-trip (done: test passes)

## 7. Verification

- [x] 7.1 Run full build: `./gradlew clean build` (includes checkstyleMain, checkstyleTest, and all tests) (done: build succeeds with no warnings)

## 8. Spec Sync

- [x] 8.1 Sync the delta spec for test-cases to the main spec via `/opsx:sync`
