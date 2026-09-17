## 1. JSONB scalar extraction SPI

- [ ] 1.1 Add `Field<Integer> jsonbAtAsInteger(Field<JSONB> column, Field<String> key)` to `data/db/repository/sql/json/JsonPathAccessor.java` and implement it in `PostgresJsonPathAccessor` as `jsonbGetAttributeAsText(column, key).cast(SQLDataType.INTEGER)` (design Decision 5). Done: `./gradlew compileJava checkstyleMain` passes and the new method sits next to `jsonbAtAsNumeric` with javadoc noting it is one-level and NULL-on-missing-key.

## 2. Entity field vocabulary and resolution

- [ ] 2.1 Add `NUMBER_OF_RUNS_FIELD = "number_of_runs"` and `NUMBER_OF_RUNS_CONFIG_KEY = "numberOfRuns"` to `query/service/TestSuiteRunQueryFields.java`, with javadoc on both (and on `RUN_CONFIG_COLUMN`) stating that the column stays in `EXCLUDED_COLUMNS` while this scalar read from it is published, `source = run_config` (design Decision 6). Done: compiles; no literal `"number_of_runs"` or `"numberOfRuns"` appears in the resolver or schema provider.
- [ ] 2.2 In `query/service/repository/PostgresTestSuiteRunEntityResolver.buildTable`, append the aliased extraction `jsonPathAccessor.jsonbAtAsInteger(TEST_SUITE_RUNS.RUN_CONFIG, DSL.val(NUMBER_OF_RUNS_CONFIG_KEY)).as(DSL.name(NUMBER_OF_RUNS_FIELD))` immediately after the plain-column loop and before the `suite_type` extraction (design Decision 3). Done: `RUN_CONFIG` itself is still absent from the projection list (the `EXCLUDED_COLUMNS` filter is untouched).
- [ ] 2.3 In the same class's `buildBindings`, register `number_of_runs` with `QueryFieldType.INTEGER` in the same position, via `requireField(tsr, NUMBER_OF_RUNS_FIELD)` (design Decision 2). Done: binding key order is `<plain columns>`, `number_of_runs`, `suite_type`, refs, `metric_names`.
- [ ] 2.4 In `query/service/TestSuiteRunsSchemaProvider`, publish `new QuerySchemaFieldDto(NUMBER_OF_RUNS_FIELD, QueryFieldType.INTEGER, RUN_CONFIG_COLUMN)` in the same position. Done: `baseSchema()` order matches the binding order from 2.3.

## 3. Unit tests

- [ ] 3.1 Extend `src/test/java/com/epam/aidial/evaluation/query/service/repository/PostgresTestSuiteRunEntityResolverTest.java`: add `number_of_runs` to the expected binding-key list (in the position from 2.3) and assert its type is `QueryFieldType.INTEGER`; assert `run_config` is still absent from the bindings. Done: `./gradlew test --tests "com.epam.aidial.evaluation.query.service.repository.PostgresTestSuiteRunEntityResolverTest"` passes.
- [ ] 3.2 Extend `src/test/java/com/epam/aidial/evaluation/query/service/TestSuiteRunsSchemaProviderTest.java` with the `QuerySchemaFieldDto("number_of_runs", INTEGER, "run_config")` row in the expected exact-order list, keeping the existing resolver↔schema agreement assertion green. Done: `./gradlew test --tests "com.epam.aidial.evaluation.query.service.TestSuiteRunsSchemaProviderTest"` passes.

## 4. Functional tests

- [ ] 4.1 Add a `createTestSuiteRun(UUID suiteId, RunStatus status, String runConfigJson)` overload (or an intent-named equivalent such as `createTestSuiteRunWithRunConfig`) to `src/test/java/com/epam/aidial/evaluation/functional/helper/MetaTestDataHelper.java`, keeping the existing defaults delegating to it with `{"numberOfRuns":1}`. Done: no raw SQL leaves the helper and existing callers compile unchanged.
- [ ] 4.2 In `TestSuiteRunStructuredQueryFunctionalTests`, add `number_of_runs` to the `ALL_FIELDS` set (updating the "20 fields" javadoc to 21) so the empty-`select` projection test covers it. Done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestSuiteRunStructuredQueryTests"` passes.
- [ ] 4.3 Add functional tests in the same class for the spec scenarios: value matches the run's configuration (run created with `{"numberOfRuns":3}` → `3`), `gt` filter on `number_of_runs` returns only multi-repetition runs, `aggregate` `group_by: ["number_of_runs"]` with `count` sorted by the field, and a run whose `run_config` omits the key yielding null without failing. Done: the nested suite above passes with deterministic assertions (no if/else branching).
- [ ] 4.4 Assert in the same class that `run_config` referenced in `filter`/`select`/`sort`/`group_by` still fails with a `ValidationException` (HTTP 400) unknown-field error, and that `execution`/`retry`/`run_config::execution` are likewise unknown fields. Done: nested suite passes.
- [ ] 4.5 Add the `number_of_runs` field row to the expected `test_suite_runs` schema in `src/test/java/com/epam/aidial/evaluation/functional/tests/QuerySchemaDiscoveryFunctionalTests.java`. Done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$QuerySchemaDiscoveryTests"` passes.

## 5. Docs and verification

- [ ] 5.1 Update `docs/patterns/test-suite-runs-query-entity.md`: note that the entity publishes one scalar read out of `run_config` while the column stays unexposed, the fail-fast cast choice, and that the field is not index-served. Done: the doc's field/exclusion narrative no longer reads as "`run_config` is never touched".
- [ ] 5.2 Sync the delta into `openspec/specs/test-suite-runs-query-entity/spec.md` (field table gains `number_of_runs`, the new extraction requirement is added, the enrichment-cost requirement is updated, implementation notes mention `JsonPathAccessor`). Done: `/opsx:sync` applied and `openspec validate --changes` passes. No `openspec/specs/README.md` change (no new spec folder, no status change, summary still accurate) and no `openspec/config.yaml` change (feature work following existing patterns).
- [ ] 5.3 Run `./gradlew spotlessApply` then `./gradlew build`. Done: full build green including `spotlessCheck`, `checkstyleMain`, `checkstyleTest`, `JooqSchemaDriftTest` (unchanged schema) and the full test suite.
