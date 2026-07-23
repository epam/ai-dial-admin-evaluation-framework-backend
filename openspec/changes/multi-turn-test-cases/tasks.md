## 1. Database migrations & jOOQ

- [x] 1.1 Add meta migration `V1.26__AddConditionToTestSuiteMetricDefinitions.sql` (`condition VARCHAR(2000)` nullable)
- [x] 1.2 Add meta migration `V1.27__AddMultiTurnDataToTestCases.sql` (`multi_turn_data JSONB` nullable + CHECK `chk_test_cases_multi_turn_exclusive` = `multi_turn_data IS NULL OR data = '{}'::jsonb`)
- [x] 1.3 Add meta migration `V1.28__AddMultiTurnDataToTestCaseRunInputs.sql` (`multi_turn_data JSONB` nullable)
- [x] 1.4 Add analytics migration `V1.13__AddTurnColumnsToTestCaseRunResults.sql` (`turn_index`/`total_turns` NOT NULL DEFAULT 0/1; drop+recreate `uq_results_run_case_index` with `turn_index`)
- [x] 1.5 Add analytics migration `V1.14__AddTurnColumnsToEvalSummaries.sql` (`turn_index`/`total_turns` NOT NULL DEFAULT 0/1; recreate `uq_eval_summaries_natural_key` with `turn_index`)
- [x] 1.6 Run `./gradlew generateJooq`, commit generated sources; update `docs/database-schema.md`

## 2. Domain models & record mappers

- [x] 2.1 Add `multiTurnData` to `data.db.model.TestCase` + `TestCaseRecordMapper` (JSON string carrier)
- [x] 2.2 Add `multiTurnData` to `data.db.model.TestCaseRunInput` + its RecordMapper
- [x] 2.3 Add `condition` to `data.db.model.TestSuiteMetricDefinition`, `AggregatedMetricDefinition`, and their (record) mappers
- [x] 2.4 Add `turnIndex`/`totalTurns` to `data.db.analytics.model.TestCaseRunResult` + `EvalSummary` and their RecordMappers/MapStruct mappers (defaults 0/1)
- [x] 2.5 Add optional `turnIndex` to `ValidationWarningDto`

## 3. Test-case authoring (DTOs, validation, persistence)

- [x] 3.1 Add `multiTurnData` to `TestCaseRequestDto`, `TestCaseResponseDto`, `TestCaseBatchPutItemDto` (`@JsonInclude(NON_NULL)`) + `TestCaseMapper`
- [x] 3.2 Add `MultiTurnFieldsValidator` (`@Component`): mutual exclusivity, non-empty array, configurable max-turns cap
- [x] 3.3 Extend `TestCaseValidationService` to validate each turn against the dataset schema; aggregate warnings with turn index; `is_valid` = all turns pass
- [x] 3.4 Wire validation into `TestCaseService` create/update/PATCH/batch; add `multiTurnData` to the merge-PATCH whitelist with mutual-exclusivity clearing
- [x] 3.5 Update `PostgresTestCaseRepository` writes (insert/update/upsert) to persist `multi_turn_data`; add `existsMultiTurnByDatasetId` to repo + interface
- [ ] 3.6 Unit tests: `MultiTurnFieldsValidatorTest`, per-turn `TestCaseValidationService` cases; functional tests: multi-turn create/read/PATCH round-trip, CHECK-constraint & 400 paths

## 4. CSV import/export (flat multiplication)

- [x] 4.1 Reserve `turnIndex` header in `CsvImportService` (excluded from `data` & schema auto-detection); parse as int ordering hint
- [x] 4.2 Implement contiguous-run grouping → assemble one `TestCase` with `multiTurnData` sorted by `turnIndex`; report non-contiguous/duplicate as row/conflict errors; keep streaming/bounded-memory
- [x] 4.3 Update `CsvExportService` to emit one row per turn (`turnIndex` `0..N-1`; blank for single-turn); header `testCaseName,turnIndex,<schema fields>`
- [ ] 4.4 Unit + functional tests: export multiplication, import assembly, round-trip, conflict strategies over assembled cases

## 5. Suite filter — ALL-turns-match (query DSL)

- [x] 5.1 Add `TestCaseFieldBindingsBuilder` overload (in `experimental.query.service`) binding `data::<field>` against a supplied JSONB element `Field` (`elem`)
- [x] 5.2 In `QueryDslRunnableTestCaseSelector.compile()` (`experimental.query.service`), wrap the `FilterTranslator`-compiled `Condition` as `NOT EXISTS(jsonb_array_elements(COALESCE(multi_turn_data, jsonb_build_array(data))) AS t(elem) WHERE (<filter@elem>) IS NOT TRUE)`, added only when a filter is present; pass the finished `Condition` to `PostgresTestCaseRepository` as the opaque `extraCondition` (covers both count and load paths since `compile()` feeds both — the repo does not build the lateral, to avoid a `data → experimental.query.service` dependency forbidden by `LayeredArchitectureTest`)
- [ ] 5.3 Functional tests: all-turns-match include/exclude, missing-field turn fails, single-turn parity, no-filter query unchanged

## 6. Conditional metrics

- [x] 6.1 Add `condition` to `TestSuiteMetricDefinitionRequestDto`/`ResponseDto` + `TestSuiteMetricDefinitionMapper`; persist via repository writes/reads
- [x] 6.2 Add `ConditionExpressionEvaluator` (validate + evaluate), `ConditionContext`, `ConditionDecision`; reuse `JsonataEvaluationService` + export column namespace tokens
- [x] 6.3 Validate `condition` syntax at write time in `TestSuiteMetricDefinitionService` (400 on malformed)
- [ ] 6.4 Unit tests: `ConditionExpressionEvaluatorTest` (RUN/SKIP/ERROR, `turn.last`, present-null vs missing, single-turn); functional: condition round-trip + 400

## 7. Snapshot & multi-turn execution

- [x] 7.1 Carry `multi_turn_data` into `test_case_run_inputs` in the `TestSuiteEvaluationJob` snapshot phase (one input row per case; existing paging)
- [x] 7.2 Add `DeploymentInvocationSupport` (shared status/timeout/backoff/retry/query-params) and `DeploymentTurnInvoker` + `TurnOutcome` (non-streaming, retry, streaming/oversize rejection)
- [x] 7.3 Add `MultiTurnExecutor`: sequential turn loop, full-history resend, verbatim `choices[0].message` append, per-turn scalar extraction, per-turn result rows, fail-fast
- [x] 7.4 Change `EvaluationWorker.execute` to return `List<TestCaseRunResult>`; dispatch on `multiTurnData != null`; wrap single-turn/MCP in `List.of(...)`; stamp `turnIndex/totalTurns`
- [x] 7.5 Update `ResultBatchWriter.addResults(list)` (one progress unit per conversation); update `InProcessEvaluationExecutor` call site
- [x] 7.6 Wire MCP+multi-turn 409 guard into `TestSuiteRunService` run-creation guards (via `existsMultiTurnByDatasetId`)
- [ ] 7.7 Unit tests: `MultiTurnExecutorTest`, `DeploymentTurnInvokerTest`, `DeploymentInvocationSupportTest`; functional: `MultiTurnRunFunctionalTests` (2-turn, fail-fast, non-chat body, MCP 409)

## 8. Metric evaluation integration (per-turn + conditional)

- [x] 8.1 Build `ConditionContext` per result row in `InProcessMetricEvaluationExecutor`; evaluate each TSMD condition before dispatch (skip/omit, dispatch, or record error); reconcile only dispatched TSMDs
- [x] 8.2 Add `TsmdEvaluationResult.ConditionError` variant; handle in `MetricOutputMapper` (omit from `metricValues`, wholesale entry in `metricInfos`)
- [x] 8.3 Propagate `turnIndex`/`totalTurns` onto `EvalSummaryBatchWriteItemDto` in `buildItem`
- [ ] 8.4 Unit tests: condition-false not dispatched + SUCCESS, condition-error surfaced + SUCCESS, `MetricOutputMapper` ConditionError

## 9. Analytics results & summaries persistence

- [x] 9.1 Update `PostgresTestCaseRunResultRepository` batch insert + `ON CONFLICT` target with `turn_index`; expose `turnIndex/totalTurns` on `TestCaseRunResultItemDto`/`ResponseDto` (defaults 0/1)
- [x] 9.2 Update `PostgresEvalSummaryRepository` insert/conflict/read projections with `turn_index`; expose fields on `EvalSummaryResponseDto`/`EvalSummaryBatchWriteItemDto`
- [ ] 9.3 Functional tests: per-turn result & summary rows, turn-key uniqueness, single-turn batch-write defaults

## 10. Config, docs, OpenAPI, spec sync

- [x] 10.1 Add configurable max-turns property (default 10) in `application.yml` + `@ConfigurationProperties`; add a row to `docs/configuration.md`
- [ ] 10.2 Update OpenAPI `@Schema`/example files for `multiTurnData`, `condition`, and turn fields on result/summary DTOs
- [ ] 10.3 Sync delta specs into `openspec/specs/` and update `openspec/specs/README.md` per Spec Index Maintenance Policy (new folders `multi-turn-conversation`, `conditional-metric-execution`)
- [x] 10.4 Update AGENTS.md per AGENTS.md Maintenance guidelines (new `MultiTurnExecutor`/`DeploymentTurnInvoker` execution pattern, conditional-metric evaluator, per-turn analytics rows)
- [ ] 10.5 Run `./gradlew spotlessApply checkstyleMain checkstyleTest test`; fix any violations
