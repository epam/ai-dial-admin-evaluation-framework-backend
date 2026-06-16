## 1. Shared Component

- [x] 1.1 Create `TemplateVariableResolver` `@Component` in `service.domain` — extract the resolution priority logic (constantValue → dataField → default → null) from `ResolvedRequestService.resolveVariable()` into a public `resolveVariable(varName, defaultValue, binding, data, warnings)` method
- [x] 1.2 Refactor `ResolvedRequestService` to inject `TemplateVariableResolver` and delegate to it instead of the private `resolveVariable()` method — verify existing behavior is preserved

## 2. DTO

- [x] 2.1 Add `resolvedValue` (Object, nullable) field to `TemplateVariableDto` with `@Schema` annotation

## 3. Service Layer

- [x] 3.1 Update `TemplateVariableService.resolveVariables()` to accept an additional `Map<String, Object> data` parameter (nullable) and populate `resolvedValue` on each `TemplateVariableDto` using `TemplateVariableResolver`
- [x] 3.2 Update `getTemplateVariables()` (suite-level) to pass `null` as data — constant bindings and defaults still resolve
- [x] 3.3 Update `getTestCaseTemplateVariables()` (test-case-level) to load and deserialize `testCase.data` and pass it to `resolveVariables()`

## 4. OpenAPI Examples

- [x] 4.1 Create OpenAPI example JSON response files for both `/template-variables` endpoints (no existing files — these are new). File names per convention: `api-v1-test-suites-template-variables-GET-response-200-minimal.json`, `api-v1-test-suites-template-variables-GET-response-200-full.json`, `api-v1-test-suites-test-cases-template-variables-GET-response-200-minimal.json`, `api-v1-test-suites-test-cases-template-variables-GET-response-200-full.json` — include `resolvedValue` in all examples

## 5. Tests

- [x] 5.1 Write unit tests for `TemplateVariableResolver`: constant-value binding, data-field binding with data present, data-field binding with missing data + default fallback, unbound variable with default, unbound variable without default
- [x] 5.2 Write functional tests for suite-level `resolvedValue`: constant-value binding resolved, template default resolved, data-field binding returns null, unbound variable without default returns null
- [x] 5.3 Write functional tests for test-case-level `resolvedValue`: constant-value binding resolved, data-field binding resolved from test case data, data-field binding with missing data falls back to default, unbound variable with/without default, type preservation (Number, Boolean)
- [x] 5.4 Verify existing resolved-request functional tests still pass after `ResolvedRequestService` refactor (task 1.2) — run `TestCaseConvenienceApiFunctionalTests` resolved-request scenarios to confirm no regression
