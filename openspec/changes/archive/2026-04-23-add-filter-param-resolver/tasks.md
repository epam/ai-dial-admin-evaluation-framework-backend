## 1. Binding infrastructure (web.pagination)

- [x] 1.1 Add `@FilterParam` annotation (target `PARAMETER`, retention `RUNTIME`) in `com.epam.aidial.evaluation.web.pagination` with `name()` (default `"filter"`) and `max()` (default `ValidationConstants.MAX_LIST_FILTER_PARAMS`). Done: file compiles; annotation present with expected attributes.
- [x] 1.2 Add `FilterParamArgumentResolver implements HandlerMethodArgumentResolver` in `com.epam.aidial.evaluation.web.pagination`. Done: `supportsParameter` returns true iff `@FilterParam` is present and parameter type is `List<String>`; `resolveArgument` reads `HttpServletRequest.getParameterValues(name)`, returns `List.of()` when absent, throws a validation exception matching the current `@Size` error shape when count exceeds `max`.
- [x] 1.3 Add (or extend) `FilterWebMvcConfiguration implements WebMvcConfigurer` in `com.epam.aidial.evaluation.web.pagination` and register the resolver in `addArgumentResolvers`. Done: `@Configuration` bean exists; Spring picks it up on startup (verified by a smoke test or application context load).
- [x] 1.4 Add `@LogExecution` to any new `@Component`/`@Configuration` classes. Done: all new Spring beans are annotated per project convention.

## 2. Controller migration

- [x] 2.1 Migrate `TestCaseController` (3 sites) from `@RequestParam(name = "filter", …) @Size List<String>` to `@FilterParam(max = …) List<String>`. Done: file compiles; imports updated; `@Size` removed where redundant.
- [x] 2.2 Migrate `TestSuiteController` (1 site). Done as above.
- [x] 2.3 Migrate `TestSuiteMetricDefinitionController` (1 site). Done as above.
- [x] 2.4 Migrate `MetricDeclarationController` (1 site). Done as above.
- [x] 2.5 Migrate `TestSuiteRunController` (1 site). Done as above.
- [x] 2.6 Migrate `EvalSummaryController` (3 sites). Done as above.
- [x] 2.7 Migrate `AnalyticsResultController` (2 sites). Done as above.
- [x] 2.8 Migrate `RunMetricSnapshotController` (1 site). Done as above.
- [x] 2.9 Grep-audit: `grep -rn '@RequestParam.*name = "filter"' src/main/java` returns zero matches. Done: no leftover sites.

## 3. Remove parser-level heuristic

- [x] 3.1 Remove `recombineIfSpringTokenized` and `looksLikeFilterExpression` from `FilterParser`; restore the `parse(...)` method body to iterate `filterParams` directly. Done: methods deleted; unit test `FilterParserTest.shouldRecombineSpringTokenizedInValues` and `shouldNotRecombineWhenNextElementLooksLikeAFilter` removed.
- [x] 3.2 Verify `FilterParserTest` still passes (`./gradlew test --tests FilterParserTest`). Done: all remaining tests green.

## 4. Tests

- [x] 4.1 Add unit test `FilterParamArgumentResolverTest` covering: single comma-containing value stays intact, repeated parameters produce an ordered list, absent parameter yields empty list, count > `max` raises the expected validation exception. Done: tests pass and cover each scenario.
- [x] 4.2 Confirm functional test `TestCaseFunctionalTests.shouldBulkDeleteByInFilterWithTwoNames` passes without parser-level recombination. Done: test green on the fresh build.
- [x] 4.3 Run full build: `./gradlew clean build`. Done: checkstyle + all tests pass.

## 5. OpenAPI verification

- [x] 5.1 Verify `OpenApiQueryParamCustomizer` still emits rich descriptions for the `filter` parameter after the annotation swap (inspect `/v3/api-docs` or the Swagger UI for one migrated endpoint). Done: parameter description/example still rendered; if regressed, augment `@FilterParam` with `@Parameter`-compatible metadata or extend the customizer.

## 6. Spec maintenance

- [x] 6.1 Update `openspec/specs/entity-filtering/spec.md` with the ADDED requirements from this change's delta (delta sync on archive). Done: main spec reflects the new binding contract.
