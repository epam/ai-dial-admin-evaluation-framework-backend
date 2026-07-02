## 1. DTO

- [x] 1.1 Add optional `@Size(max = 2000) private String jsonataExpression;` to `service/domain/dto/TestCaseBindingSourceDto.java` with a `@Schema` description + example `"$[0]"` (done: field present, compiles). No mapper change (round-trips via existing polymorphic binding JSON).

## 2. Binding resolution

- [x] 2.1 In `service/domain/job/BindingResolver.resolveSource`, the `TestCase` branch SHALL, after the existing missing-column guard + `testCaseData.get(columnName)`, pass the value through the existing `applyJsonataSelector(value, testCaseSource.getJsonataExpression())` (done: same semantics as Response — raw value when absent, `null` on no-match).

## 3. Config-time validation

- [x] 3.1 In `service/domain/MetricDefinitionValidationService.validateBindings`, the `TestCase` branch SHALL syntax-check a non-blank `jsonataExpression` via `jsonataEvaluationService.validateExpression(...)` and emit an `INVALID_EXPRESSION` warning with the correct `$.configBindings`/`$.inputBindings` path on failure (done: mirrors the Response check).

## 4. Tests

- [x] 4.1 `BindingResolverTest` — TestCase binding: `jsonataExpression` array index (`$[0]`, `$[-1]`), object path; no-expr → whole value; no-match → `null`; missing column still throws (done: test class passes).
- [x] 4.2 `MetricDefinitionValidationServiceTest` — invalid `jsonataExpression` on a TestCase binding → `INVALID_EXPRESSION` warning; valid/absent → none (done: test class passes).
- [x] 4.3 Run the two unit classes; run `./gradlew spotlessApply` then `./gradlew compileJava` (done: BUILD SUCCESSFUL).

## 5. Docs & OpenAPI

- [x] 5.1 Update `TestCaseBindingSourceDto` `@Schema` example to include `jsonataExpression` (done: example reflects the new contract).
- [x] 5.2 Update the AGENTS.md metric-binding note: `jsonataExpression` is available on both `Response` and `TestCase` bindings (done: AGENTS.md updated).

## 6. Spec sync (archive time)

- [x] 6.1 At archive time, sync the two delta specs (`metric-evaluation`, `tsmd-validation`) into `openspec/specs/` via `/opsx:archive` (intelligent merge). Archive `multistep-per-turn-column-selection` FIRST so its Response-binding delta is in main before this change extends it to TestCase.
