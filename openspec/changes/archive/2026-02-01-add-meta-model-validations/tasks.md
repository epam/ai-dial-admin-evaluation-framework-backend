## 1. Schema validation (requestBodySchema, responseBodySchema, parameters[].schema)

- [x] 1.1 In SchemaValidationService, add validation of user schemas against JSON Schema Draft-07 (meta-schema via networknt if supported); return first error message from new or extended method
- [x] 1.2 If networknt meta-schema validation is not practical in 1.5.9, evaluate and integrate `justify` (leadpony) or similar actively-maintained library for schema-vs-meta-schema validation before implementing recursive fallback; avoid deprecated libraries like everit-org/json-schema
- [x] 1.3 Add check to reject schemas containing `$ref` keyword with HTTP 400 ("$ref not supported in v1"); apply to requestBodySchema, responseBodySchema, and parameters[i].schema
- [x] 1.4 Call schema validation from getSchemaValidationError or TestSuiteService before persist for endpointRef.requestBodySchema, endpointRef.responseBodySchema, and each endpointRef.parameters[i].schema; throw ValidationException with clear message on failure
- [x] 1.5 Bundle Draft-07 meta-schema as classpath resource (schemas/json-schema-draft-07.json) to avoid network dependency

## 2. Bean Validation (fact fields, parameters)

- [x] 2.1 Add `@Valid` annotation on `TestCasesDefinitionDto.factFields` and on `EndpointContractDto.parameters` to trigger nested Bean Validation on existing `@NotBlank`/`@NotNull` constraints in SchemaFieldDto and ParameterDefinitionDto
- [x] 2.2 Keep factFields optional: when testCasesDefinition.factFields is missing or null, init to empty list in normalization (no error)
- [x] 2.3 Ensure description in SchemaFieldDto remains optional (null/absent handled without error); do not add @NotNull on description
- [x] 2.4 Optionally add explicit loop in TestSuiteService (validateFactFields or inside validateTestSuiteSchemas) that rejects any fact field with blank name or null type with index-based error message (defense in depth)

## 3. Structured validation warnings

- [x] 3.1 Add ValidationWarningDto (source, path, property, message, code) in service.domain.dto; add @Schema on fields for OpenAPI; define ValidationWarningCode enum (REQUIRED, TYPE, FORMAT, PATTERN, ENUM, ADDITIONAL, UNKNOWN)
- [x] 3.2 In SchemaValidationService (or dedicated mapper), build structured warnings from networknt ValidationMessage: tag with source (parameters | facts), set path and message, derive property from path (see design for extraction rules: $.model→"model", $.items[0]→"items", $.a.b.c→"c", $→null), map message type to code
- [x] 3.3 Change ValidationResult to carry List<ValidationWarningDto> (or equivalent) for warnings; update validateTestCase to merge param and facts results with source tagging
- [x] 3.4 Change TestCaseResponseDto.validationWarnings from List<String> to List<ValidationWarningDto>; update TestCaseMapper and all code that sets validationWarnings
- [x] 3.5 Update TestCase entity and repository: store validation_warnings as jsonb array of objects (serialize ValidationWarningDto list); add Flyway migration to change column type from text[] to jsonb and prune existing data (set to empty array); update RowMapper and updateValidation/insert
- [x] 3.6 Update CSV import/preview and revalidation flows to use structured warnings when building TestCaseResponseDto or equivalent

## 4. DTO and query-param constraints

- [x] 4.1 Add @Size(max=255) on SchemaFieldDto.name, @Size(max=2000) on description (description optional—null allowed; no @NotNull); @Size(max=255) on ParameterDefinitionDto.name; @Pattern(regexp="^/[^\\s]*$") on EndpointContractDto.relativeUrl, @Size(max=255) on operationId; @Size(max=255) on DeploymentReferenceDto.id and name, @Size(max=50) on version
- [x] 4.2 Add @Size(max=128) on TestCasesDefinitionDto.factFields
- [x] 4.3 In TestSuiteController, TestCaseController, and MetricDefinitionController, enforce separate limits: filter max 32, sort max 32; return HTTP 400 when either limit exceeded (e.g. via ValidationException or @Size on param)
- [x] 4.4 Validate CSV delimiter as single ASCII character (length 1, no Unicode for v1) in TestCaseController (export, import, import preview); default delimiter is comma; return HTTP 400 when invalid

## 5. Documentation and OpenAPI

- [x] 5.1 Document validationWarnings as list of objects; update OpenAPI schema for TestCaseResponseDto.validationWarnings and any CSV preview DTOs
- [x] 5.2 Update docs/configuration.md if new config properties (e.g. validation.factFieldsMaxSize, filterMaxSize, sortMaxSize) are added; update AGENTS.md if new validation patterns are adopted
- [x] 5.3 Update OpenAPI example JSON files for test-case responses under openapi/examples/ to reflect new validationWarnings structure (source, path, property, message, code)

## 6. Tests

- [x] 6.1 Add functional tests: create/update TestSuite with invalid requestBodySchema/responseBodySchema type (e.g. "abc") → HTTP 400; with fact field missing name or type → HTTP 400; with parameter missing "in" → HTTP 400
- [x] 6.2 Add functional test: create/update TestSuite with schema containing $ref → HTTP 400 ("$ref not supported")
- [x] 6.3 Add functional test: create/update TestSuite with parameter having invalid `in` enum value (e.g. "cookie") → HTTP 400
- [x] 6.4 Add functional tests: list endpoints with filter count > 32 → HTTP 400; sort count > 32 → HTTP 400; both at 32 → accepted
- [x] 6.5 Add functional tests: CSV export/import with delimiter length != 1 → HTTP 400; Unicode delimiter → HTTP 400; default comma → accepted
- [x] 6.6 Add functional tests: create/get/update TestCase that fails validation; assert validationWarnings is list of objects with source, path, property, message; assert source is "parameters" or "facts" and property matches expected column
- [x] 6.7 Update existing functional tests that assert on validationWarnings (string list) to use new structured shape; fix any tests broken by new validation (invalid payloads now rejected)
