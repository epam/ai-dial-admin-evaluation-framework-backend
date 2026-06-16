## 1. Build validation schema helper

- [x] 1.1 Add private method `buildValidationSchema(CsvImportMode mode, boolean schemaEmpty, List<ColumnBinding> bindings, List<FieldDefinitionDto> testCaseSchema)` to `CsvImportService` that returns `List<FieldDefinitionDto>` based on the mode matrix: OVERRIDE → all data columns from bindings (`required=false`, `type=STRING`); MERGE + non-empty → existing schema + new data columns; APPEND/MERGE + empty → all data columns; APPEND + non-empty → existing `testCaseSchema` unchanged
- [x] 1.2 Add unit test for `buildValidationSchema` covering all mode/schema-state combinations (OVERRIDE+empty, OVERRIDE+non-empty, MERGE+empty, MERGE+non-empty-with-new-columns, MERGE+non-empty-no-new-columns, APPEND+empty, APPEND+non-empty)

## 2. Fix importCsv() to use validation schema

- [x] 2.1 In `CsvImportService.importCsv()`, call `buildValidationSchema()` after `resolveColumnBindings()` and store result as `validationSchema`; pass `validationSchema` instead of `testCaseSchema` to `processBatch()` and through to `testCaseValidationService.validateTestCase()`
- [x] 2.2 In `CsvImportService.preview()`, call `buildValidationSchema()` after `resolveColumnBindings()` and pass `validationSchema` instead of `testCaseSchema` to `testCaseValidationService.validateTestCase()`

## 3. Functional tests for correct validation state after import

- [x] 3.1 Add functional test: OVERRIDE mode with existing schema — imported test cases have `isValid=true` and no "Unknown data field" warnings when CSV data columns match the CSV-derived schema
- [x] 3.2 Add functional test: OVERRIDE mode with empty suite schema — imported test cases have `isValid=true` and no spurious warnings
- [x] 3.3 Add functional test: MERGE mode with new columns — imported test cases have `isValid=true` for new columns (no "Unknown data field" for newly merged columns)
- [x] 3.4 Add functional test: APPEND mode with empty schema — imported test cases have `isValid=true`
- [x] 3.5 Add functional test: CSV preview with OVERRIDE mode — sample rows in preview have correct `valid=true` state (no spurious warnings)
