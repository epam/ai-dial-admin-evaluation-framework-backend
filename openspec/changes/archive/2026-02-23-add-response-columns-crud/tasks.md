## 1. JSONata Library Spike

- [x] 1.1 Add JSONata dependency to `build.gradle` (try `com.dashjoin:jsonata:0.9.9` first; fall back to `com.ibm.jsonata4java:JSONata4Java:2.6.1` if issues arise)
- [x] 1.2 Write a spike test validating core expressions against sample response bodies: `choices[0].message.content`, `usage.total_tokens`, `choices[0].finish_reason`, array extraction, nested paths, missing paths (should return null/error gracefully)
- [x] 1.3 Confirm thread-safety and Jackson compatibility; document findings and final library choice

## 2. Database Migrations

- [x] 2.1 Add `V1.8__AddResponseColumnsToTestSuites.sql` in `db/migration/meta/POSTGRES/`: `ALTER TABLE test_suites ADD COLUMN response_columns JSONB NOT NULL DEFAULT '[]'::jsonb;`
- [x] 2.2 Add `V1.2__AddExtractedColumnsToTestCaseRunResults.sql` in `db/migration/analytics/POSTGRES/`: add `extracted_columns JSONB NOT NULL DEFAULT '{}'::jsonb` and `extraction_warnings JSONB NOT NULL DEFAULT '[]'::jsonb` to `test_case_run_results`
- [x] 2.3 Update `docs/database-schema.md` to document the new columns on both tables

## 3. Response Column Definition DTO & FieldDefinitionDto Update

- [x] 3.1 Create `ResponseColumnDefinitionDto` in `service.domain.dto` with: `@NotBlank @Size(max=255) name`, `@Size(max=255) displayName` (optional), `@NotBlank @Size(max=2000) expression`, `SchemaFieldType type` (optional, nullable in DTO — do NOT rely on `@Builder.Default` for this, as Jackson uses the no-args constructor and will leave it null; null must be defaulted to `SchemaFieldType.STRING` in `TestSuiteService.normalizeRequest()` when normalizing each response column definition)
- [x] 3.2 Add optional `@Size(max=255) displayName` field to `FieldDefinitionDto` (backwards-compatible)

## 4. TestSuite Model & DTO Changes

- [x] 4.1 Add `String responseColumns` field to `TestSuite` DB model (JSONB string, like `testCaseSchema`)
- [x] 4.2 Add `List<ResponseColumnDefinitionDto> responseColumns` to `TestSuiteRequestDto` (with `@Valid @Size(max = 50)`)
- [x] 4.3 Add `List<ResponseColumnDefinitionDto> responseColumns` to `TestSuiteResponseDto`
- [x] 4.4 Update `TestSuiteRowMapper` to read `response_columns` column
- [x] 4.5 Update `TestSuiteMapper` to map responseColumns between model (JSON string) and DTOs (List)
- [x] 4.6 Update `JsonbMapper` with `mapResponseColumns(String json)` method for deserialization
- [x] 4.7 Update `PostgresTestSuiteRepository` — add `response_columns` to INSERT, UPDATE, and SELECT SQL statements
- [x] 4.8 Update `TestSuiteService.normalizeRequest()` — default `responseColumns` to `List.of()` if null; for each column definition, default `type` to `SchemaFieldType.STRING` if null. Do NOT add `responseColumns` to `isSchemaChanged()` — response column expression changes are validated synchronously on save and are orthogonal to test case schema revalidation.

## 5. JSONata Validation on Suite Save

- [x] 5.1 Create `JsonataEvaluationService` interface in `service.domain` with `validateExpression(String expression)` (throws on invalid syntax) and `evaluate(String expression, String jsonData)` (returns `Object` or null). Implement as `DashjoinJsonataEvaluationService` — the ONLY class that imports from `com.dashjoin.jsonata`; all library exceptions caught and re-thrown as domain exceptions. Annotate impl with `@Service @LogExecution`.
- [x] 5.2 Update `TestSuiteService.validateTestSuiteSchemas()` — for each response column: validate `name` not blank, `expression` is valid JSONata (via `JsonataEvaluationService`), names are unique within the array
- [x] 5.3 Unit test: valid JSONata expressions pass validation
- [x] 5.4 Unit test: invalid JSONata expression causes ValidationException with column index and parse error
- [x] 5.5 Unit test: duplicate column names cause ValidationException

## 6. TestCaseRunResult Model & Repository Changes

- [x] 6.1 Add `String extractedColumns` and `String extractionWarnings` fields to `TestCaseRunResult` model
- [x] 6.2 Update `TestCaseRunResultRowMapper` to read new JSONB columns
- [x] 6.3 Update `PostgresTestCaseRunResultRepository` INSERT SQL and `toParameterSource()` to include new fields
- [x] 6.4 Create `ExtractionWarningDto` in `service.domain.dto.analytics` with: `column` (String), `expression` (String), `error` (String)
- [x] 6.5 Update `TestCaseRunResultResponseDto` to include `extractedColumns` (JsonNode/Map) and `extractionWarnings` (List<ExtractionWarningDto>)
- [x] 6.6 Add optional `extractedColumns` (JsonNode, nullable) and `extractionWarnings` (List<ExtractionWarningDto>, nullable) to `TestCaseRunResultItemDto` (the analytics batch write request DTO). When null/absent, the persistence layer SHALL default to `{}` and `[]` respectively. This allows external callers to pre-populate extracted values if needed; the job layer always provides them via `ResponseColumnExtractor`.

## 7. Eager Extraction in Run Execution Path

> **Scope**: Extraction is a job-layer concern. `MockResultsGenerator` (and future real runners) load the suite's `responseColumns` from meta DB and call `JsonataEvaluationService` before passing results to the analytics batch writer. The batch write API is pure persistence — it does NOT trigger extraction.

- [x] 7.1 Create a `ResponseColumnExtractor` component in `service.domain` that takes a list of `ResponseColumnDefinitionDto` and a JSON response body string, evaluates all expressions via `JsonataEvaluationService`, and returns a populated `extractedColumns` map + `extractionWarnings` list (serialized as JSON strings ready for persistence). Update `MockResultsGenerator.buildResult()` to call this component.
- [x] 7.2 Handle edge cases: null `responseBody` → all values null with warnings; empty `responseColumns` → empty map and empty warnings list
- [x] 7.3 Add `serializeExtractionWarnings(List<ExtractionWarningDto>)` (fail-fast) and `deserializeExtractionWarnings(String json)` (graceful degradation) methods to `ValidationWarningsSerializer`, following the same pattern as `serializeWarnings`/`deserializeWarnings`. Use `JsonbMapper`/`ObjectMapper` with fail-fast serialization for the `extractedColumns` map.

## 8. OpenAPI Examples & Documentation

- [x] 8.1 Update existing TestSuite request examples to include `responseColumns` field (minimal: without it; full: with 2 columns)
- [x] 8.2 Update existing TestSuite response examples to include `responseColumns`
- [x] 8.3 Update existing TestCaseRunResult response examples to include `extractedColumns` and `extractionWarnings`

## 9. Tests

- [x] 9.1 Unit test `ResponseColumnDefinitionDto` validation — name required, expression required, type defaults
- [x] 9.2 Unit test `JsonataEvaluationService` — validate + evaluate expressions, error handling, null input
- [x] 9.3 Functional test: create suite with responseColumns → GET returns them
- [x] 9.4 Functional test: update suite responseColumns → new columns persisted
- [x] 9.5 Functional test: create suite with invalid JSONata expression → 400
- [x] 9.6 Functional test: create suite with duplicate column names → 400
- [x] 9.7 Functional test: create suite without responseColumns → defaults to empty array
- [x] 9.8 Functional test: create suite with >50 responseColumns → 400 (max size exceeded)
- [x] 9.9 Functional test: run execution produces extractedColumns in results
- [x] 9.10 Functional test: extraction failure → null value + warning entry
- [x] 9.11 Functional test: displayName on FieldDefinitionDto persisted and returned
