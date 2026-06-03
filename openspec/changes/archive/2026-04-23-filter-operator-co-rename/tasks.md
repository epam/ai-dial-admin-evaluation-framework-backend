## 1. Core Model and SQL

- [x] 1.1 Rename `FilterOperator.CONTAINS` → `FilterOperator.CO` in `FilterOperator.java`
- [x] 1.2 Update `WhereBuilder.java`: rename all `CONTAINS` → `CO`; add `FilterFieldType type` parameter to `buildPredicate()` and `buildJsonbPredicate()`; change EQ/NE SQL for `STRING` and `JSONB_STRING` fields to use `lower(column) = lower(:param)` / `lower(column) <> lower(:param)`; pass `definition.getType()` at call sites in `build()`
- [x] 1.3 Update `FilterWhitelists.java`: rename all `FilterOperator.CONTAINS` → `FilterOperator.CO` (14 occurrences)
- [x] 1.4 Update `QueryParamDescriptionGenerator.java`: rename 3 explicit `FilterOperator.CONTAINS` references to `FilterOperator.CO` (operator table and example selection auto-adapt via `op.name().toLowerCase()`)

## 2. Unit Tests

- [x] 2.1 Update `FilterParserTest.java`: replace all `"contains"` operator strings with `"co"`; add a test asserting `"contains"` returns HTTP 400 / `FilterValidationException`
- [x] 2.2 Update `WhereBuilderTest.java`: rename `CONTAINS` → `CO` in test inputs; add cases for EQ/NE case-insensitivity on STRING fields (assert SQL uses `lower()`); verify EQ/NE on non-STRING fields still emit `=` / `<>`
- [x] 2.3 Update `WhereBuilderJsonbTest.java`: rename `CONTAINS` → `CO`; add cases for case-insensitive EQ/NE on JSONB_STRING fields
- [x] 2.4 Update `QueryParamDescriptionGeneratorTest.java`: replace expected `"contains"` strings with `"co"` in operator table and example assertions
- [x] 2.5 Update `OpenApiQueryParamCustomizerTest.java`: replace expected `"contains"` strings with `"co"` (no changes needed — only uses AssertJ `.contains()` method, not filter operator strings)
- [x] 2.6 Run unit tests: `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.filter.FilterParserTest"` and `--tests "*.WhereBuilderTest"` and `--tests "*.WhereBuilderJsonbTest"` and `--tests "*.QueryParamDescriptionGeneratorTest"` — all must pass

## 3. Functional Tests

- [x] 3.1 Search all functional test files for `":contains:"` filter strings and replace with `":co:"`; verify no remaining `":contains:"` usage in test filter params
- [x] 3.2 Add functional test scenarios to the relevant list-endpoint functional test (e.g., `TestSuiteFunctionalTests`) covering: `co` substring match, `eq` case-insensitive match, `ne` case-insensitive match, and `contains` rejected with HTTP 400
- [x] 3.3 Run functional tests: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests"` — all must pass

## 4. Spec Sync

- [x] 4.1 Sync delta spec `specs/entity-filtering/spec.md` into the main spec at `openspec/specs/entity-filtering/spec.md` (update operator list, add EQ/NE case-insensitivity rules and scenarios, add `contains` rejection scenario)
