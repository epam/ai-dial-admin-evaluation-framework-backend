## 1. New DTOs and enums

- [x] 1.1 Rename `SchemaFieldDto` to `FieldDefinitionDto` — same fields (name, type, required, description); update all references across DTOs, mappers, services, and tests
- [x] 1.2 Create `RequestTemplateDto` (urlTemplate, queryParams, headers, body)
- [x] 1.3 Create `KeyValueTemplateDto` (key, value)
- [x] 1.4 Create `InputBindingDto` (templateVariable, dataField, constantValue) with mutual exclusivity validation
- [x] 1.5 Create `TemplateVariableDto` (name, sources, hasDefault, defaultValue, binding, inferredType) — response-only DTO for convenience API
- [x] 1.6 Create `TemplateVariableSource` enum (BODY, URL, QUERY, HEADER)
- [x] 1.7 Create `ResolvedRequestDto` (url, queryParams, headers, body, warnings) — response-only DTO for resolved-request preview API

## 2. Template variable extraction

- [x] 2.1 Create `TemplateVariableExtractor` component — parses `${{variable}}` and `${{variable:default}}` from all `RequestTemplateDto` fields (urlTemplate, queryParams values, headers values, body strings at any depth); returns list of extracted variables with source and default info
- [x] 2.2 Write unit tests for `TemplateVariableExtractor` — simple variables, variables with defaults, multiple variables in one string, nested body values, duplicate variables across sections, no variables, malformed syntax edge cases

## 3. Database migration

- [x] 3.1 Create Flyway migration `V1.5__RequestTemplateRestructure.sql` — drop existing data from `test_suites` and `test_cases` (TRUNCATE CASCADE or DELETE); restructure `test_suites`: drop `test_cases_definition`, add `test_case_schema` (JSONB DEFAULT '[]'), `request_template` (JSONB), `input_bindings` (JSONB DEFAULT '[]'), `is_valid` (BOOLEAN DEFAULT true), `validation_warnings` (JSONB DEFAULT '[]'); restructure `test_cases`: drop `parameters` and `facts`, add `data` (JSONB DEFAULT '{}'), `request_template_override` (JSONB), `input_bindings_override` (JSONB)
- [x] 3.2 Verify migration handles dependent tables (revalidation_tasks FK, cascade deletes) and preserves table indexes (unique constraints on names)

## 4. Model and data access layer updates

- [x] 4.1 Update `TestSuite` model — replace `testCasesDefinition: String` with `testCaseSchema: String`, `requestTemplate: String`, `inputBindings: String` (all JSONB-backed); add `isValid: Boolean`, `validationWarnings: String` (JSONB-backed)
- [x] 4.2 Update `TestCase` model — replace `parameters: String` and `facts: String` with `data: String`; add `requestTemplateOverride: String` and `inputBindingsOverride: String`
- [x] 4.3 Update `TestSuiteRowMapper` — map new columns
- [x] 4.4 Update `TestCaseRowMapper` — map new columns
- [x] 4.5 Update `PostgresTestSuiteRepository` — update SQL for new columns in INSERT, UPDATE, SELECT
- [x] 4.6 Update `PostgresTestCaseRepository` — update SQL for new columns in INSERT, UPDATE, SELECT

## 5. Mapper and serialization updates

- [x] 5.1 Update `JsonbMapper` — add serialization/deserialization for `RequestTemplateDto`, `List<InputBindingDto>`, `List<FieldDefinitionDto>` (replacing `TestCasesDefinitionDto` handling)
- [x] 5.2 Update `TestSuiteMapper` — map between model (JSON strings) and DTOs for `testCaseSchema`, `requestTemplate`, `inputBindings`; remove `testCasesDefinition` mapping
- [x] 5.3 Update `TestCaseMapper` — map `data` (single map) instead of `parameters`/`facts`; add mapping for `requestTemplateOverride` and `inputBindingsOverride`

## 6. TestSuite DTO and service updates

- [x] 6.1 Update `TestSuiteRequestDto` — replace `testCasesDefinition` with `testCaseSchema: List<FieldDefinitionDto>`, `requestTemplate: RequestTemplateDto?`, `inputBindings: List<InputBindingDto>`
- [x] 6.2 Update `TestSuiteResponseDto` — same field replacement; remove computed `parameterFields`; add `isValid` and `validationWarnings` fields
- [x] 6.3 Update `EndpointContractDto` — rename `relativeUrl` to `relativeUrlPattern`; make `requestBodySchema` and `responseBodySchema` nullable/optional
- [x] 6.4 Update `TestSuiteService.create()` — persist new fields; run suite-level soft validation (template+binding checks); compute suite `isValid` + `validationWarnings`; remove `enrich()` / `parameterFields` computation
- [x] 6.5 Update `TestSuiteService.update()` — persist new fields; recalculate suite-level `isValid` + `validationWarnings`; trigger TestCase revalidation when testCaseSchema, requestTemplate, inputBindings, or endpointRef change
- [x] 6.6 Update `TestSuiteService.getById()` / `getAll()` — remove `enrich()` step (no more computed parameterFields)
- [x] 6.7 Remove or rework `EndpointSchemaExtractor` — the `flattenTopLevelProperties()` / `extractParameterFields()` approach is no longer used for parameterFields; evaluate if any extraction logic is still needed for type inference in template variables

## 7. TestCase DTO and service updates

- [x] 7.1 Update `TestCaseRequestDto` — replace `parameters`/`facts` with `data: Map<String,Object>`; add `requestTemplateOverride: RequestTemplateDto?` and `inputBindingsOverride: List<InputBindingDto>?`
- [x] 7.2 Update `TestCaseResponseDto` — same field replacement
- [x] 7.3 Update `TestCaseService.create()` — validate data against testCaseSchema; validate effective template variables against effective bindings and data; compute isValid
- [x] 7.4 Update `TestCaseService.update()` — same validation as create
- [x] 7.5 Update `TestCaseService.patch()` — handle merge patch for `data`, `requestTemplateOverride`, `inputBindingsOverride`; recalculate isValid

## 8. Validation service updates

- [x] 8.1 Update `SchemaValidationService` — adapt `validateTestCase()` to work with unified `data` map, `testCaseSchema`, effective template, and effective bindings instead of separate parameters/facts with source-tagged warnings
- [x] 8.2 Implement suite-level validation logic — extract variables from template, check each has a binding (or default), check binding dataFields exist in testCaseSchema, check for orphan bindings with no matching template variable, check urlTemplate validity, check template conformance to endpoint schema; store result in suite `isValid` + `validationWarnings`
- [x] 8.3 Implement test case data-vs-binding validation — for each required template variable (no default), check that binding's dataField has a value in data; for override path, re-check binding coverage and orphan bindings; generate fieldName-based warnings
- [x] 8.4 Update validation warning structure — ensure warnings use `fieldName` (not `source: PARAMETERS | FACTS`)
- [x] 8.5 Update `RevalidationService` — adapt async revalidation to use new validation logic

## 9. Template variables convenience API

- [x] 9.1 Create `TemplateVariableService` — extracts variables from suite's requestTemplate, resolves bindings, infers types (priority: endpointRef schema > testCaseSchema > STRING)
- [x] 9.2 Create `TemplateVariableController` — `GET /api/v1/test-suites/{id}/template-variables` endpoint with OpenAPI annotations
- [x] 9.3 Write functional tests for template variables API — suite with variables, suite without template, variable type inference, binding resolution

## 10. Test-case convenience APIs (template-variables + resolved-request)

- [x] 10.1 Create `ResolvedRequestService` — resolves effective template with effective bindings and test case data per resolution flow; returns `ResolvedRequestDto` with warnings
- [x] 10.2 Extend `TemplateVariableController` — add `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables` endpoint (effective template variables for a test case)
- [x] 10.3 Create `ResolvedRequestController` — `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request` endpoint with OpenAPI annotations
- [x] 10.4 Write functional tests for test-case template-variables API — test case with overrides, without overrides, non-existent test case
- [x] 10.5 Write functional tests for resolved-request API — successful resolution, missing data warnings, no template, non-existent test case

## 11. Max limits enforcement

- [x] 11.1 Add configurable properties for max template size (default 64KB) and max bindings count (default 64)
- [x] 11.2 Implement max template size validation — reject with HTTP 400 when `requestTemplate` or `requestTemplateOverride` serialized size exceeds limit
- [x] 11.3 Implement max bindings count validation — reject with HTTP 400 when `inputBindings` or `inputBindingsOverride` count exceeds limit
- [x] 11.4 Implement duplicate `templateVariable` validation — reject with HTTP 400 when `inputBindings` or `inputBindingsOverride` contains duplicate `templateVariable` values
- [x] 11.5 Write functional tests for max limits and duplicate binding rejection

## 12. CSV export/import updates

- [x] 12.1 Update `CsvExportService` — use `testCaseSchema` field order for column headers; map all columns from unified `data` (no param/fact prefix)
- [x] 12.2 Update `CsvImportService` — map all data columns to `data` map; detect unknown columns (not in testCaseSchema) as warnings; implement auto-detect schema from CSV when testCaseSchema is empty (scan all rows per column for type inference: OBJECT, ARRAY, BOOLEAN, INTEGER, NUMBER, STRING; persist to suite's testCaseSchema; include auto-detected schema in preview response; no auto-creation of inputBindings)
- [x] 12.3 Update CSV functional tests

## 13. OpenAPI examples and documentation

- [x] 13.1 Update OpenAPI example JSON files for test-suites endpoints (request/response with new fields including `isValid`, `validationWarnings`)
- [x] 13.2 Update OpenAPI example JSON files for test-cases endpoints (request/response with new fields)
- [x] 13.3 Create OpenAPI example JSON files for template-variables endpoint (suite-level and test-case-level)
- [x] 13.4 Create OpenAPI example JSON files for resolved-request endpoint
- [x] 13.5 Update `docs/database-schema.md` with new column definitions (including `is_valid`, `validation_warnings` on `test_suites`)
- [x] 13.6 Update `docs/configuration.md` with new configuration properties (max template size, max bindings count)

## 14. Functional tests

- [x] 14.1 Update TestSuite functional tests — CRUD with new fields (testCaseSchema, requestTemplate, inputBindings); suite-level `isValid` + `validationWarnings`; endpoint schemas optional
- [x] 14.2 Update TestCase functional tests — CRUD with unified data, requestTemplateOverride, inputBindingsOverride; validation against template/bindings; PATCH for new fields
- [x] 14.3 Update revalidation functional tests — ensure revalidation uses new validation logic; suite re-validates on endpointRef change
- [x] 14.4 Write new functional tests for template-binding edge cases — required variable without binding, orphan binding, constant vs dataField, duplicate template variables, per-case overrides with different templates/bindings, duplicate templateVariable in bindings (HTTP 400)
