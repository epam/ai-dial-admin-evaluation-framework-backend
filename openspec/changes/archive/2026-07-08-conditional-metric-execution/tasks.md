## 1. Data model & migration

- [x] 1.1 Add Flyway meta migration `V1.26__AddTsmdCondition.sql` — `ALTER TABLE test_suite_metric_definitions ADD COLUMN condition VARCHAR(2000)` (nullable)
- [x] 1.2 Run `./gradlew generateJooq` and commit the regenerated sources under `src/main/java-generated/` (generated `TestSuiteMetricDefinitions.CONDITION` + record getter/setter present)
- [x] 1.3 Add `condition` to `TestSuiteMetricDefinition` model, `TestSuiteMetricDefinitionRequestDto` (+ `@Size(max=2000)`), and `TestSuiteMetricDefinitionResponseDto`
- [x] 1.4 Map `condition` in `TestSuiteMetricDefinitionMapper` (toDto/toEntity/update) and read/write it in `PostgresTestSuiteMetricDefinitionRepository` (save/update/batchInsert/plain selects/row mapper) + `TestSuiteMetricDefinitionRecordMapper`
- [x] 1.5 Update `docs/database-schema.md` with the new column

## 2. Condition evaluation component

- [x] 2.1 (RED) Write `ConditionFunctionRegistryTest` (duplicate-name rejection at startup; empty registry lookup returns absent)
- [x] 2.2 Add `ConditionFunction` SPI (`String name()`, `boolean evaluate(ConditionContext)`) and `ConditionFunctionRegistry` (`@Component`, collects beans, rejects duplicates) mirroring `QueryFunctionRegistry`; `ConditionFunctionRegistryTest` green
- [x] 2.3 Add `ConditionContext` (builder-backed carrier holding `dataJson`/`responseJson`); `data`/`response` namespace tokens defined in `ConditionExpressionEvaluator`
- [x] 2.4 (RED) Write `ConditionExpressionEvaluatorTest`: detection (bare `name()` vs JSONata, trimmed first), `evaluate` true→RUN / false→SKIP / non-boolean|null|throw→error, present-but-null column stays visible to `$exists`, `validate` (blank passthrough, invalid JSONata throws, unregistered `name()` throws)
- [x] 2.5 Implement `ConditionExpressionEvaluator` (`@Component`): trim, detect bare `name()`, serialize `{data,response}` preserving explicit nulls (typed `ObjectNode`, NOT the shared `NON_NULL` mapper), delegate to `JsonataEvaluationService`/registry, strict boolean; `ConditionExpressionEvaluatorTest` green

## 3. Write-time validation wiring

- [x] 3.1 (RED) Add functional cases: create with valid `condition` → 201 + round-trip (GET); invalid JSONata → 400 (create & update); unregistered `name()` → 400
- [x] 3.2 Call `conditionExpressionEvaluator.validate(dto.getCondition())` in `TestSuiteMetricDefinitionService.create`/`update`; `ValidationException` maps to 400 and does not affect `is_valid`
- [x] 3.3 Add OpenAPI `@Schema` for `condition` on request/response DTOs (description + example `$exists(response.answer)`) and refresh the TSMD create/update/response full example JSON files under `src/main/resources/openapi/examples/`

## 4. Runtime integration

- [x] 4.1 Add `TsmdEvaluationResult.ConditionError(String message, List<String> outputFieldNames)` sealed variant (+ `condition` on `AggregatedMetricDefinition`, its row mapper, and the two aggregated selects so the runtime can read it)
- [x] 4.2 (RED) Extend `MetricOutputMapperTest`: `ConditionError` → no `metricValues` node + metric-level wholesale `metricInfos[tsmd]={error}`; success alongside ConditionError
- [x] 4.3 Handle `ConditionError` in `MetricOutputMapper.buildMetricValues`/`buildMetricInfos`; `checkForErrors` ignores `ConditionError` (distinct variant + comment)
- [x] 4.4 (RED) Add executor tests: condition false omits the metric (no results-map entry); condition error yields a `ConditionError` and result stays SUCCESS; existing tests stub RUN
- [x] 4.5 In `InProcessMetricEvaluationExecutor.evaluateAndBuild()` build one `ConditionContext` per result (synchronously, before dispatch) and gate each TSMD (SKIP→continue, error→put `ConditionError`, RUN→dispatch); timeout fallback only over dispatched TSMDs

## 5. Functional coverage, docs & final build

- [x] 5.1 Add a `PostgresFunctionalTests$TestSuiteMetricDefinitionTests` end-to-end case: run a suite with a conditional metric whose condition is false → eval summary omits the metric and keeps `executionStatus` SUCCESS (context boots with the new `ConditionExpressionEvaluator` bean). Plus write-time functional cases (create round-trip 201; invalid JSONata & unknown fn → 400 on create/update).
- [x] 5.2 Add the conditional-metric-execution inline convention bullet to AGENTS.md
- [x] 5.3 Update `openspec/specs/README.md` per Spec Index Maintenance Policy — **deferred to `/opsx:archive`**: the `conditional-metric-execution` main spec folder is created by `openspec-sync-specs` at archive time; adding a README entry now would be a phantom link. Add the index entry (and refresh the metric-evaluation / test-suite-metric-definitions summaries) as part of archive/sync.
- [x] 5.4 Run `./gradlew spotlessApply build` — BUILD SUCCESSFUL (checkstyle, spotless, all unit + functional tests green)
