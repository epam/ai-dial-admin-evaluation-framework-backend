## Why

Complex REST APIs (e.g., OpenAI `/chat/completions`) have 20+ fields in request body schemas, but test cases typically vary only 1–5 fields per case. The current flat `parameterFields` approach forces users to either repeat boilerplate in every test case or leave most fields empty with unclear assembly semantics. Additionally, nested structures (arrays of messages, nested objects) cannot be meaningfully varied per test case with the current flat extraction model. A Postman-style request template system with embedded `${{variable}}` placeholders and explicit bindings would let suite authors configure once and keep test cases minimal, while providing an escape hatch for complex per-case variations.

## What Changes

- **BREAKING**: Replace `testCasesDefinition` (embedded `parameterFields` + `factFields`) with three separate top-level concepts on TestSuite: `testCaseSchema` (column definitions), `requestTemplate` (Postman-style `RequestTemplateDto` with `${{variable}}` syntax), and `inputBindings` (explicit mapping from template variables to data fields or constants).
- **BREAKING**: Replace separate `parameters` and `facts` maps in TestCase with a single unified `data` map. Field roles (input vs fact) are emergent — derived from bindings and metric configuration, not stored.
- **NEW**: `requestTemplate` on TestSuite — a structured `RequestTemplateDto` with `urlTemplate`, `queryParams`, `headers`, and `body` fields, each supporting embedded `${{variable}}` / `${{variable:default}}` placeholders. Nullable for bodyless endpoints or suites that don't need request templating.
- **NEW**: `inputBindings` on TestSuite — a required explicit mapping list. Each `InputBindingDto` maps a `templateVariable` name to either a `dataField` (from `testCaseSchema`) or a `constantValue`. No implicit auto-mapping by name.
- **NEW**: Per-test-case `requestTemplateOverride` and `inputBindingsOverride` — escape hatch for cases needing different request structures (e.g., multi-turn conversations with variable-length message arrays). Full replacement semantics.
- **NEW**: `testCaseSchema` — pure column definitions (name, type, required, description) with no role annotations. Replaces both `parameterFields` and `factFields`.
- **NEW**: `GET /api/v1/test-suites/{id}/template-variables` — convenience API returning extracted `TemplateVariableDto` entries with source locations (`TemplateVariableSource` enum: BODY, URL, QUERY, HEADER), defaults, resolved bindings, and inferred types.
- **NEW**: `KeyValueTemplateDto` — structure for query parameters and headers (literal `key` + `value` with `${{}}` support).
- **BREAKING**: Remove computed `parameterFields` and the `EndpointSchemaExtractor.flattenTopLevelProperties()` approach.
- **CHANGED**: `endpointRef.requestBodySchema` and `endpointRef.responseBodySchema` become optional (nullable). Suites can be created with only a `requestTemplate` and no endpoint schemas.
- **CHANGED**: Validation warnings use `fieldName` instead of `source: PARAMETERS | FACTS` since the single `data` map eliminates the distinction.
- **CHANGED**: CSV import/export adapted for unified `data` map and new schema structure.

## Capabilities

### New Capabilities
- `request-template`: Request template system — storing, parsing `${{variable}}` placeholders, managing explicit input bindings, template variable extraction API, per-test-case overrides, and runtime assembly contract.

### Modified Capabilities
- `test-suites`: Replace `testCasesDefinition` with `testCaseSchema`, `requestTemplate`, and `inputBindings`. Remove computed `parameterFields`. Make endpoint schemas optional. Add request template and binding management to suite CRUD.
- `test-cases`: Replace `parameters`/`facts` with unified `data` map. Add `requestTemplateOverride` and `inputBindingsOverride`. Update validation to check template variables against effective bindings and data. Update CSV import/export for new column model.

## Impact

- **Database**: Migration to restructure `test_suites` columns (drop `test_cases_definition`, add `test_case_schema`, `request_template`, `input_bindings`, `is_valid`, `validation_warnings`). Migration to restructure `test_cases` columns (drop existing data; replace `parameters`+`facts` with `data`, add `request_template_override`, `input_bindings_override`). No data migration — existing rows are dropped.
- **API**: Breaking changes to TestSuite request/response DTOs and TestCase request/response DTOs. All clients must update.
- **Services**: `TestSuiteService`, `TestCaseService`, `SchemaValidationService`, `CsvExportService`, `CsvImportService`, `EndpointSchemaExtractor` (removed or reworked), `RevalidationService`. New: `TemplateVariableExtractor` (parses `${{}}` from templates), `TemplateVariableService` (convenience API logic).
- **Dependencies**: May use an existing library (e.g. Apache Commons Text `StringSubstitutor`) for `${{}}` template parsing; otherwise lightweight regex-based extraction. No heavy template engine required.
- **Frontend**: Must adopt new DTO structure, derive column grouping (input vs fact) from bindings, support Postman-style template editing and binding configuration UI.
