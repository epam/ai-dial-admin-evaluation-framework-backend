# Proposal: Add meta-model validations

## Why

The API currently accepts invalid or underspecified meta-model data: fact field definitions without required `name`/`type`, endpoint parameters without `in`, and JSON Schema objects (requestBodySchema, responseBodySchema, parameters[].schema) with invalid `type` values (e.g. `"abc"`). There are also no explicit size limits on list inputs (factFields, filter, sort). Closing these gaps improves data quality, prevents confusing runtime behavior, and aligns stored schemas with JSON Schema Draft-07 and OpenAPI expectations.

## What Changes

- **Schema validation**: Validate `endpointRef.requestBodySchema`, `endpointRef.responseBodySchema`, and each `endpointRef.parameters[i].schema` against the JSON Schema Draft-07 meta-schema. Prefer existing networknt library; if networknt does not support meta-schema validation, try an **additional library** (e.g. `justify` from leadpony—actively maintained; avoid deprecated `everit-org/json-schema`) for schema validation before falling back to a recursive `type` walk so invalid `type` and other invalid keywords are rejected with HTTP 400 before persist. **Schemas containing `$ref` SHALL be rejected** with HTTP 400 in v1; complex `$ref` support is deferred (TODO).
- **CSV delimiter**: Default delimiter is comma (`,`); validate as single ASCII character (length 1, no Unicode for v1); return HTTP 400 when invalid.
- **Fact fields**: Treat `testCasesDefinition.factFields` as **optional**—when missing (or null), system SHALL initialize it to an empty list and not reject the request. When present, require every entry to have non-blank `name` and non-null `type`; reject requests that omit those. `description` in each fact field entry is **optional**; when not defined (null/absent), system SHALL handle without error. Enforce name/type via `@Valid` on the list and optional explicit service-level checks. Cap `factFields` list size at **128** (default).
- **Parameters**: Require every `endpointRef.parameters` entry to have non-null `in` (query/path/header). Enforce via `@Valid` on `EndpointContractDto.parameters` so nested Bean Validation runs.
- **DTO constraints**: Add `@Size` (and where needed `@Pattern`) on nested request DTOs (SchemaFieldDto, ParameterDefinitionDto, EndpointContractDto, DeploymentReferenceDto).
- **Query/request params**: Cap list size for `filter` and `sort` at **32** (default); validate CSV `delimiter` as a single character where used.
- **Structured test case validation warnings**: Replace the current flat list of string warnings with a structured format (source, path, property, message, optional code) so the client/FE can bind to parameters vs facts and match to grid cells. See [Structured test case validation warnings](#structured-test-case-validation-warnings) below. Early phase, no external clients today; replace `validationWarnings` with structured list only; existing stored warnings are **not** backfilled—leave empty for current rows.
- No new endpoints; invalid payloads that are currently accepted will be rejected with HTTP 400.

## Structured test case validation warnings

**Current problem**: Test case `validationWarnings` are a list of opaque strings (e.g. `"$: required property 'model' not found"`, `"$: required property 'expected_status' not found"`). It is unclear which part of the test case each warning refers to (parameters vs facts), the format is not designed for machine parsing, and the FE cannot reliably match a warning to a specific grid column or cell (e.g. parameters.model vs facts.expected_status).

**Goal**: Provide structured, parseable warnings so that:
- The **source** of each warning is explicit: `parameters` or `facts` (the two schema-validated parts of a test case).
- The **location** is machine-friendly: e.g. JSONPath-like `path` and/or **property** name, so the FE can highlight the right column/cell in a grid.
- The **message** remains human-readable for tooltips or logs.
- Optionally, a stable **code** (e.g. `required`, `type`) helps the FE show icons or actions.

**Proposed shape** (to be refined in specs/design): Each warning is an object, for example:

```json
{
  "source": "parameters",
  "path": "$",
  "property": "model",
  "message": "required property 'model' not found",
  "code": "required"
}
```

- `source`: `"parameters"` | `"facts"` — which part of the test case failed validation.
- `path`: JSONPath-like path from the validator (e.g. `"$"`, `"$.model"`); helps when nesting or arrays are introduced later.
- `property`: Top-level property name when applicable (e.g. `"model"`, `"expected_status"`); enables direct mapping to grid columns.
- `message`: Human-readable message (current backend message).
- `code`: Optional, stable identifier (e.g. `required`, `type`) for FE behavior or i18n.

**API impact**: `TestCaseResponseDto.validationWarnings` changes from `List<String>` to a list of structured objects (e.g. `List<ValidationWarningDto>`). Early phase, no external clients today; replace with structured list only. Existing rows in DB: do **not** backfill; leave `validationWarnings` empty for current test cases.

**Implementation note**: Validation today runs parameters and facts separately (networknt returns `ValidationMessage` with path and message). The backend can tag each warning with `source` and map path/message to the structured shape when building the response.

## Capabilities

### New Capabilities

- None. All changes tighten validation within existing APIs.

### Modified Capabilities

- **test-suites**: Require valid JSON Schema (meta-schema or type validation) for requestBodySchema, responseBodySchema, parameters[].schema; require fact field entries to have name and type; require parameter entries to have `in`. Add scenarios for rejection of invalid schema types, incomplete fact fields, and missing parameter `in`.
- **test-cases**: Return **structured** validation warnings (source, path, property, message, optional code) in test case responses (create, get, update, list, CSV import/preview) so FE can bind warnings to parameters vs facts and match to grid cells. Add requirement and scenarios for the new response shape (list of warning objects).
- **entity-filtering**: Add an upper bound on the number of `filter` and `sort` parameters per request (e.g. 64) and document HTTP 400 when exceeded. Aligns with list endpoints (test-suites, test-cases, metric-definitions).

## Impact

- **Code**: `SchemaValidationService` (meta-schema validation or recursive type check; building structured warnings from networknt `ValidationMessage` with source tagging), `TestSuiteService` (fact-field and schema validation flow), `TestCaseService` and CSV import/preview (validation result → structured warnings), DTOs under `service.domain.dto` (annotations; new `ValidationWarningDto` or equivalent, `TestCaseResponseDto.validationWarnings` type change), controllers (query-param validation for filter/sort/delimiter).
- **APIs**: Same contracts for request shapes; previously accepted invalid or underspecified payloads will return HTTP 400. Test case response `validationWarnings` change from `List<String>` to list of structured objects (source, path, property, message, optional code) wherever test case validation warnings are returned (create, get, update, list, CSV import/preview).
- **Dependencies**: Use existing networknt json-schema-validator for meta-schema validation if supported; otherwise evaluate `justify` (leadpony) or similar actively-maintained library before implementing recursive type walk fallback. Avoid deprecated libraries (e.g. `everit-org/json-schema`). Use networknt for path/message when building structured warnings.
- **Tests**: Functional tests for new validation cases (invalid schema type, fact field without name/type, parameter without `in`, filter/sort list over limit, delimiter not single char) and for structured validation warnings shape (source, path, property, message) in test case responses.
