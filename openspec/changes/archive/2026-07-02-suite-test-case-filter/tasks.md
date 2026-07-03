## 1. Database schema & jOOQ

- [x] 1.1 Add Flyway migration `src/main/resources/db/migration/meta/POSTGRES/V1.24__AddTestCaseFilterToTestSuites.sql` adding nullable `test_case_filter JSONB` to `test_suites` (done: file present, comment explains "NULL = no filter")
- [x] 1.2 Run `./gradlew generateJooq` and commit the regenerated meta sources under `src/main/java-generated/` (done: `TestSuites`/`TestSuitesRecord` expose `TEST_CASE_FILTER`; `JooqSchemaDriftTest` green)
- [x] 1.3 Update `docs/database-schema.md` with the new `test_suites.test_case_filter` column (done: row added)

## 2. Query DSL — `test_cases` complex entity (read/preview)

- [x] 2.1 Add `TestCaseFieldBindingsBuilder` in `experimental.query.service` producing `Map<String,QueryFieldBinding>` = base `TEST_CASES` columns + one type-aware `data::<field>` binding per dataset schema field (scalar → `data ->> 'field'`, array/object → `data -> 'field'`), preserving `QueryFieldType` incl. `ARRAY` (done: unit-testable component, JSONB paths use bound key params)
- [x] 2.2 Add `TestCasesSchemaProvider` (`experimental.query.service`) implementing `QueryableEntitySchemaProvider`: `descriptor()` → `QueryEntityDto("test_cases", true, "dataset_id")`; `baseSchema()` from `JooqTableSchemaResolver.resolve(TEST_CASES)`; `detailedSchema(params)` requires `dataset_id`, loads dataset `testCaseSchema` via `DatasetService`, flattens `data::<field>` (done: modeled on `EvalSummariesSchemaProvider`, ARRAY preserved)
- [x] 2.3 Add `TestCaseQueryRepository` + `PostgresTestCaseQueryRepository` (`experimental.query.service.repository`) binding entity `test_cases` to `TEST_CASES` on `@Qualifier("metaDsl")`, delegating to `StructuredQueryExecutor`; `supportedEntity()` returns `test_cases` (done: mirrors `PostgresTestSuiteQueryRepository`, auto-registered)
- [x] 2.4 Add a `StructuredQueryExecutor.execute(...)` overload accepting caller-supplied precomputed `Map<String,QueryFieldBinding>` bindings, bypassing the per-`Table<?>` `bindingsCache` (existing cache-backed overload stays for `test_suites`/`eval_summaries`); make `PostgresTestCaseQueryRepository` extract and require the query's `dataset_id` equality filter (the `schemaIdField`; reject with `ValidationException` → HTTP 400 when absent or not a UUID), build instance bindings via `TestCaseFieldBindingsBuilder`, and call the new overload (done: execute over `test_cases` resolves both base columns and type-aware `data::<field>` incl. ARRAY; missing/non-UUID `dataset_id` → 400; `test_cases` never uses the static binding cache)

## 3. Query DSL — array-element containment

- [x] 3.1 Extend `FilterTranslator.toComparison`: the array branch triggers ONLY when the `co`/`nc` left operand is a bare `FieldExpr` whose binding type is `QueryFieldType.ARRAY` (consulted via `bindings.get(name)`, which wins over the `JsonbFieldResolver` fallback); a non-`FieldExpr` left operand keeps the scalar substring-`LIKE` behavior. For an array field emit JSONB element containment (`?` operator for a string literal via jOOQ plain SQL escaped as `??`; `@>` one-element array for non-string), with operand bound as a parameter; `nc` = negation. Scalar/text `co`/`nc` unchanged (done: no string concatenation)
- [x] 3.2 Add `FilterTranslator`/`ExprTranslator` unit tests covering array `co`/`nc` vs scalar `co`/`nc`, plus the non-`FieldExpr` / non-array left-operand fall-through to case-insensitive LIKE (done: `./gradlew test --tests "*FilterTranslator*"` green)

## 4. Run selection interface (inversion)

- [x] 4.1 Add `service.domain.job.RunnableTestCaseSelector` interface (stable layer; only primitives + `data.db.model.TestCase` in signatures): `countRunnable(datasetId, filterJson, excludedIds)`, `loadRunnablePage(datasetId, filterJson, excludedIds, offset, limit)`, `validateFilter(datasetId, filterJson)` (done: interface compiles, no `experimental` types leak)
- [x] 4.2 Implement it in `experimental.query.service`: parse `filterJson` → `FilterNode`; build bindings via `TestCaseFieldBindingsBuilder`; translate → `Condition`; AND with base predicate `dataset_id = ? AND is_valid = TRUE AND NOT (id = ANY(?::text[]))` (reuse the shape from `PostgresTestCaseRepository.validNotExcludedCondition`); query `metaDsl` ordered `created_at_ms ASC, id ASC` with `limit`/`offset`; null/blank `filterJson` short-circuits to the base predicate; `validateFilter` runs translation only and throws `ValidationException` on failure (done: `@LogExecution`, participates in ambient tx)

## 5. Suite `testCaseFilter` persistence

- [x] 5.1 Add `testCaseFilter` (String JSONB) to `TestSuite` model; include in `PostgresTestSuiteRepository` SELECT/INSERT/UPDATE and `TestSuiteRecordMapper` (done: round-trips to DB)
- [x] 5.2 Add `JsonbMapper.mapTestCaseFilter` (Map→String write, String→Map read), mirroring `mapOverallScore` (done)
- [x] 5.3 Add `testCaseFilter` (`Map<String,Object>`) to `TestSuiteRequestDto` and `TestSuiteResponseDto` with `@Schema` example (`IN` + array `CONTAINS`); map in `TestSuiteMapper` `toEntity`/`update`/`toDto`/`toRequestDto`/`toCloneEntity` (clone inherits source filter) (done)

## 6. Wire validation & run application

- [x] 6.1 In `TestSuiteService` create/update, after the dataset binding is resolved, call `RunnableTestCaseSelector.validateFilter(datasetId, filterJson)`; reject a non-null filter on an unbound suite (`datasetId == null`) with `ValidationException` → HTTP 400 (done: 200 valid / 400 invalid & unbound)
- [x] 6.2 Change `RunnableTestCaseCounter.countRunnable` to delegate to `RunnableTestCaseSelector.countRunnable`, threading the suite's `testCaseFilter` from `TestSuiteRunService.createRun` guard #4. There is exactly ONE production caller (`TestSuiteRunService.createRun`, line ~84); its unit test `RunnableTestCaseCounterTest` must be updated for the new signature (done: `grep` confirms no other `countRunnable(` callers; the filter is applied ONLY on the run path, never on any suite-validity path per AGENTS.md "test-case presence is not a suite-validity concern"; guard order unchanged; zero filter-match → 409 `INVALID_OPERATION`)
- [x] 6.3 Change `TestSuiteEvaluationJob.attemptSnapshot` to select via `RunnableTestCaseSelector.loadRunnablePage(datasetId, filterJson, disabledIds, offset, SNAPSHOT_PAGE_SIZE)` instead of `testCaseRepository.findValidByDatasetIdExcludingIds`; retain `deserializeDisabledIds`; preserve `REPEATABLE READ` + `40001` retry (done: snapshot materializes only filter-matching rows)

## 7. OpenAPI & docs

- [x] 7.1 Add OpenAPI examples for the `test_cases` entity (schema discovery + `POST /api/v1/queries/execute`) per the openapi-examples spec (done: dropped — the `/queries/execute` `@ExampleObject`s were reverted per review; the endpoint is entity-agnostic and the `test_cases` contract is covered by the schema-discovery endpoint + the `testCaseFilter` DTO examples)
- [x] 7.2 Add an `OpenApiQueryParamCustomizer` / query-schema-discovery doc entry for `test_cases` if required by the openapi-query-param-docs spec (done: NOT required — the customizer registry covers only list/export endpoints using `filter`/`sort`/`page`/`size`; `/api/v1/queries/*` is path-var + body-delivered)
- [x] 7.3 Update `TestSuiteRequestDto`/`TestSuiteResponseDto` OpenAPI `@Schema` examples to include `testCaseFilter` (done in Group 5: request DTO carries an `IN` + array-`CONTAINS` example; response DTO documented)

## 8. Tests

- [x] 8.1 Unit tests for `TestCaseFieldBindingsBuilder` and `TestCasesSchemaProvider` (base + detailed schema, ARRAY preserved, unknown/missing `dataset_id` error contract) (done: green)
- [x] 8.2 Unit tests for `TestSuiteMapper`/`JsonbMapper` `testCaseFilter` round-trip and clone inheritance (done: green)
- [x] 8.3 Functional test: `test_cases` schema discovery + `POST /api/v1/queries/execute` (incl. array-field `CONTAINS`) returns expected rows (done: `PostgresFunctionalTests` green)
- [x] 8.4 Functional test: suite create/update `testCaseFilter` write validation — valid → 200, unknown field → 400, unbound suite + non-null filter → 400 (done)
- [x] 8.5 Functional test: run selection honors filter + disabled + `is_valid` (assert `test_case_run_inputs` contents and `numberOfTestCases`), and zero-match filter → 409 (done: boots context)

## 9. Verification & maintenance

- [x] 9.1 Update `openspec/specs/README.md` per Spec Index Maintenance Policy (done: refreshed `query-schema-discovery` + `structured-query-model` summaries for the `test_cases` entity and array containment; the new `suite-test-case-filter` folder entry is added during archive delta-sync to avoid a phantom link before the folder exists)
- [x] 9.2 Update AGENTS.md per AGENTS.md Maintenance guidelines (done: `test_cases` query entity + `RunnableTestCaseSelector` inversion noted; Key Packages / Inline conventions updated)
- [x] 9.3 Run `./gradlew spotlessApply` then `./gradlew build` — Spotless, Checkstyle, `LayeredArchitectureTest` (no `service → experimental` edge), and `JooqSchemaDriftTest` all green (done)
