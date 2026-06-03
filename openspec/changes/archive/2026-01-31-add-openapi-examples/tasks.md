## 1. Documentation

- [x] 1.1 Add OpenAPI examples subsection to AGENTS.md (conventions: @Schema/@ExampleObject, minimal + full rule, exception for simple endpoints, update examples when changing endpoint/request/response)
- [x] 1.2 Optionally add docs/api-documentation.md or subsection in docs for consumers (where to find spec: Swagger UI, /v3/api-docs)

## 2. DTO examples

- [x] 2.1 Add @Schema(example = "…") to TestSuiteRequestDto and TestSuiteResponseDto fields where helpful
- [x] 2.2 Add @Schema(example = "…") to TestCaseRequestDto and TestCaseResponseDto fields where helpful
- [x] 2.3 Add @Schema(example = "…") to MetricDefinitionResponseDto and page/PageResponseDto where helpful

## 3. Controller request/response examples

- [x] 3.1 Add request and response examples (minimal + full where applicable) to TestSuiteController (POST, PUT, GET list, GET by id)
- [x] 3.2 Add request and response examples to TestCaseController (POST, PUT, PATCH, GET list, GET by id; skip or single example for import/export/delete as per design)
- [x] 3.3 Add response examples to MetricDefinitionController (GET list with params, GET by id); request examples only if query params warrant minimal+full

## 4. Verification

- [x] 4.1 Run checkstyleMain and checkstyleTest and fix any issues
