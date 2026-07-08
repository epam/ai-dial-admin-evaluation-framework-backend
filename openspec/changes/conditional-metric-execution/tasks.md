## 1. Data model & migration

- [ ] 1.1 Add Flyway meta migration `V{n}__AddTsmdCondition.sql` — `ALTER TABLE test_suite_metric_definitions ADD COLUMN condition VARCHAR(2000)` (nullable) (done: migration file present, `V{n}` is next after current head)
- [ ] 1.2 Run `./gradlew generateJooq` and commit the regenerated sources under `src/main/java-generated/` (done: generated `TestSuiteMetricDefinitions` table reflects the new column)
- [ ] 1.3 Add `condition` to `TestSuiteMetricDefinition` model, `TestSuiteMetricDefinitionRequestDto` (+ `@Size(max=2000)`), and `TestSuiteMetricDefinitionResponseDto` (done: field present, Lombok compiles)
- [ ] 1.4 Map `condition` in `TestSuiteMetricDefinitionMapper` and read/write it in `PostgresTestSuiteMetricDefinitionRepository` + `TestSuiteMetricDefinitionRecordMapper` (done: round-trips through persistence)
- [ ] 1.5 Update `docs/database-schema.md` with the new column (done: column documented)

## 2. Condition evaluation component

- [ ] 2.1 (RED) Write `ConditionFunctionRegistryTest` (duplicate-name rejection at startup; empty registry lookup returns absent) — watch it fail (done: test compiles and fails)
- [ ] 2.2 Add `ConditionFunction` SPI (`String name()`, `boolean evaluate(ConditionContext)`) and `ConditionFunctionRegistry` (`@Component`, collects beans, rejects duplicates) mirroring `QueryFunctionRegistry`; make 2.1 pass (done: `ConditionFunctionRegistryTest` green)
- [ ] 2.3 Add `ConditionContext` (builder-backed carrier exposing `data` and `response` maps) and bare `data`/`response` namespace token constants (reuse/derive from `EvalSummaryExportColumnConstants`) (done: type compiles)
- [ ] 2.4 (RED) Write `ConditionExpressionEvaluatorTest`: detection (bare `name()` vs JSONata, trimmed first), `evaluate` true→RUN / false→SKIP / non-boolean|null|throw→error, a present-but-null column stays visible to `$exists`, `validate` (blank passthrough, invalid JSONata throws, unregistered `name()` throws) — watch it fail (done: test fails)
- [ ] 2.5 Implement `ConditionExpressionEvaluator` (`@Component`): trim the condition, detect bare `name()`, serialize `{data,response}` to a JSON string **preserving explicit nulls** (typed `ObjectNode`/`putNull`, NOT the shared `NON_NULL` mapper), delegate to `JsonataEvaluationService`/registry, interpret strict boolean; make 2.4 pass (done: `ConditionExpressionEvaluatorTest` green)

## 3. Write-time validation wiring

- [ ] 3.1 (RED) Add functional cases to the TSMD controller test: create/update with valid `condition` → 200 + round-trip; invalid JSONata → 400; unregistered `name()` → 400 — watch them fail (done: tests fail)
- [ ] 3.2 Call `conditionExpressionEvaluator.validate(dto.getCondition())` in `TestSuiteMetricDefinitionService.create`/`update`; ensure `ValidationException` maps to 400 and does not affect `is_valid` (done: 3.1 green)
- [ ] 3.3 Add OpenAPI `@Schema` for `condition` on request/response DTOs (description + example `$exists(response.answer)`) and refresh the TSMD create/update/response example JSON files under `src/main/resources/openapi/examples/` to include `condition` (done: Swagger shows the field; operation examples updated)

## 4. Runtime integration

- [ ] 4.1 Add `TsmdEvaluationResult.ConditionError(String message, List<String> outputFieldNames)` sealed variant (done: compiles)
- [ ] 4.2 (RED) Extend `MetricOutputMapperTest`: `ConditionError` → no `metricValues` node + metric-level wholesale `metricInfos[tsmd]={error}` (renders as `metricError::<name>`, relying on `error` not being an output-schema field); and `checkForErrors` ignores `ConditionError` — watch it fail (done: test fails)
- [ ] 4.3 Handle `ConditionError` in `MetricOutputMapper.buildMetricValues`/`buildMetricInfos` and exclude it in `checkForErrors`; make 4.2 pass (done: test green)
- [ ] 4.4 (RED) Add an executor test: condition true dispatches; false omits the metric; error yields a `ConditionError` and result stays SUCCESS — watch it fail (done: test fails)
- [ ] 4.5 In `InProcessMetricEvaluationExecutor.evaluateAndBuild()` build one `ConditionContext` per result and gate each TSMD via `evaluate(condition, ctx)` (SKIP→continue, error→put `ConditionError`, RUN→existing dispatch); make 4.4 pass (done: test green)

## 5. Functional coverage, docs & final build

- [ ] 5.1 Add a `PostgresFunctionalTests` metric-evaluation case: run a suite where some rows skip a conditional metric; assert the eval summary omits skipped metrics, surfaces a condition error under `metricError`, and keeps `executionStatus` SUCCESS (done: functional test green, context boots with new beans)
- [ ] 5.2 Add the conditional-metric-execution inline convention bullet to AGENTS.md per AGENTS.md Maintenance guidelines (done: convention documented — per-conversation, namespaced `data`/`response`, bare `name()` detection, hard-400 validation, absent=skipped / metricError on error, SUCCESS preserved)
- [ ] 5.3 Update `openspec/specs/README.md` per Spec Index Maintenance Policy (done: index lists `conditional-metric-execution`)
- [ ] 5.4 Run `./gradlew spotlessApply build` (done: checkstyle, spotless, all unit + functional tests green)
