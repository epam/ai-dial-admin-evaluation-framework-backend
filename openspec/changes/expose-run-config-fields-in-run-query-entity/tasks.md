## 1. JSONB scalar extraction SPI

- [x] 1.1 Add `Field<Integer> jsonbAtAsInteger(Field<JSONB> column, Field<String> key)` to `data/db/repository/sql/json/JsonPathAccessor.java`; implement in `PostgresJsonPathAccessor` as `jsonbGetAttributeAsText(column, key).cast(SQLDataType.INTEGER)` with javadoc (one-level, NULL on missing key). Done: `compileJava checkstyleMain` pass.

## 2. Entity field vocabulary and resolution

- [x] 2.1 Add `NUMBER_OF_RUNS_FIELD` / `NUMBER_OF_RUNS_CONFIG_KEY` to `query/service/TestSuiteRunQueryFields.java`; javadoc on them and on `RUN_CONFIG_COLUMN` noting the column stays excluded while this scalar is published. Done: no literal `"number_of_runs"`/`"numberOfRuns"` in resolver or schema provider.
- [x] 2.2 In `PostgresTestSuiteRunEntityResolver.buildTable`, append `jsonPathAccessor.jsonbAtAsInteger(TEST_SUITE_RUNS.RUN_CONFIG, DSL.val(NUMBER_OF_RUNS_CONFIG_KEY)).as(DSL.name(NUMBER_OF_RUNS_FIELD))` after the plain-column loop, before `suite_type`. Done: `RUN_CONFIG` still absent from the projection.
- [x] 2.3 In `buildBindings`, register `number_of_runs` as `QueryFieldType.INTEGER` in the same position via `requireField(tsr, NUMBER_OF_RUNS_FIELD)`. Done: order `<plain columns>`, `number_of_runs`, `suite_type`, refs, `metric_names`.
- [x] 2.4 In `TestSuiteRunsSchemaProvider`, publish `new QuerySchemaFieldDto(NUMBER_OF_RUNS_FIELD, QueryFieldType.INTEGER, RUN_CONFIG_COLUMN)` in the same position. Done: `baseSchema()` order matches 2.3.

## 3. Unit tests

- [x] 3.1 `PostgresTestSuiteRunEntityResolverTest`: add `number_of_runs` to expected binding keys, assert `INTEGER`, assert `run_config` still absent. Done: test passes.
- [x] 3.2 `TestSuiteRunsSchemaProviderTest`: add `QuerySchemaFieldDto("number_of_runs", INTEGER, "run_config")` to the exact-order list. Done: test passes.

## 4. Functional tests

- [x] 4.1 Add a run-config-aware `createTestSuiteRun` overload to `functional/helper/MetaTestDataHelper.java`; existing defaults delegate with `{"numberOfRuns":1}`. Done: no raw SQL outside the helper, existing callers unchanged.
- [x] 4.2 `TestSuiteRunStructuredQueryFunctionalTests`: add `number_of_runs` to `ALL_FIELDS` (javadoc 20 → 21). Done: `PostgresFunctionalTests$TestSuiteRunStructuredQueryTests` passes.
- [x] 4.3 Same class: tests for the three spec scenarios — value `3` for a run created with `{"numberOfRuns":3}`; `gt 1` + `group_by`/`count`/sort aggregate; missing key → null. Done: nested suite passes, deterministic assertions.
- [x] 4.4 Same class: `run_config` in `filter`/`select`/`sort`/`group_by` still yields HTTP 400 unknown field. Done: nested suite passes.
- [x] 4.5 `QuerySchemaDiscoveryFunctionalTests`: add the `number_of_runs` row to the expected `test_suite_runs` schema. Done: `PostgresFunctionalTests$QuerySchemaDiscoveryTests` passes.

## 5. Docs and verification

- [x] 5.1 `docs/patterns/test-suite-runs-query-entity.md`: note the one scalar read from `run_config` while the column stays unexposed, the fail-fast cast, no index. Done: doc no longer reads as "`run_config` is never touched".
- [ ] 5.2 `/opsx:sync` delta into `openspec/specs/test-suite-runs-query-entity/spec.md`. Done: `openspec validate --changes` passes. No `specs/README.md` or `config.yaml` change needed.
- [x] 5.3 `./gradlew spotlessApply && ./gradlew build`. Done: green.
