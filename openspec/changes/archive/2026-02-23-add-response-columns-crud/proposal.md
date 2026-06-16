## Why

The prototype's Eval Results grid lets users define "Response Columns" — named columns that extract specific fields from each test result's response body using a JSONata expression. These column definitions (expression, display name, result type) need to be persisted per test suite so the UI can restore them across sessions and share them across team members. Additionally, the backend needs to evaluate these expressions at run time, store the extracted values, and enable sort/filter/analytics over them.

Currently there is no backend storage for response column definitions, no server-side JSONata evaluation, and no extracted-value persistence in eval results.

## What Changes

- Add a `response_columns` JSONB column to `test_suites` for per-suite response column definitions (same pattern as `test_case_schema`).
- Add `displayName` (optional) to `FieldDefinitionDto` for testCaseSchema fields (backwards-compatible).
- Add server-side JSONata expression validation on suite create/update.
- Add `extracted_columns` and `extraction_warnings` JSONB columns to `test_case_run_results` in the analytics DB.
- Evaluate JSONata expressions at result write time (eager, during run execution) and store extracted values.
- Reuse `SchemaFieldType` for response column types (unified type system).
- No new REST endpoints — response columns are managed as part of TestSuite create/update.
- No breaking changes to existing TestSuite endpoints.

## Capabilities

### New Capabilities

- `response-columns`: User-defined JSONata-based response column definitions stored as JSONB on TestSuite. Each column captures: `name` (stable identifier), `displayName` (optional UI label), `expression` (JSONata), `type` (SchemaFieldType). Columns are validated server-side (JSONata syntax check) and evaluated at run time against each result's response body.

### Modified Capabilities

- `test-suite`: TestSuiteRequestDto/ResponseDto extended with `responseColumns` field.
- `test-case-schema`: `FieldDefinitionDto` gets optional `displayName` field.
- `test-suite-run-results`: `TestCaseRunResult` extended with `extractedColumns` and `extractionWarnings`.

## Impact

- **DB migration (meta)**: `V1.8__AddResponseColumnsToTestSuites.sql` — adds `response_columns` JSONB to `test_suites`.
- **DB migration (analytics)**: `V1.2__AddExtractedColumnsToTestCaseRunResults.sql` — adds `extracted_columns` and `extraction_warnings` JSONB to `test_case_run_results`.
- **New dependency**: JSONata Java library (`com.dashjoin:jsonata` or `com.ibm.jsonata4java:JSONata4Java` — final choice deferred).
- **New DTO**: `ResponseColumnDefinitionDto` (column definition within the JSONB array).
- **New service component**: `JsonataEvaluationService` — validates expressions, evaluates against response bodies.
- **Modified DTOs**: `TestSuiteRequestDto`, `TestSuiteResponseDto` (add `responseColumns`), `FieldDefinitionDto` (add `displayName`), `TestCaseRunResult` model + RowMapper + Repository (add extracted columns and warnings).
- **Modified service**: `TestSuiteService` (validate JSONata on save), `MockResultsGenerator` / result write path (evaluate expressions).
- **OpenAPI examples**: Updated suite examples to include `responseColumns`.
- **Import/export**: Suite config export/import naturally includes `responseColumns` (JSONB on suite). Eval results export will include extracted columns when that feature is built (deferred).
