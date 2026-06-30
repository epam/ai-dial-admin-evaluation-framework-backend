# Tasks

## 1. Code-defined metric-score statistics

- [x] 1.1 Add `BuiltInMetricStatistics` (`experimental.query.service.metricscore`): build the per-metric statistics AVG/P10/P90/MIN/MAX and the default `overall` (`avg(:metricField)`) as typed `StructuredQuery` objects via shared `aggregate(...)`/`runScopedFilter()`/`fn`/`param`/`field`/`decimal` helpers — each a single `value` aggregate over `eval_summaries`, run/computation scoped (`:runId`/`:computationId`), per-metric stats binding `:metricField` and P10/P90 baking the percentile fraction as a literal.
- [x] 1.2 Add `constants/MetricScoreConstants.java`: reserved param names (`runId`/`computationId`/`metricField`), `overall` score name, `value` output alias, `eval_summaries` entity, and run-scoping field names (`test_suite_run_id`/`computation_id`). Reuse `EvalSummaryExportColumnConstants` for metric-field tokens.
- [x] 1.3 Guard unit test `BuiltInMetricStatisticsTest`: each built-in is an aggregate `value` query over `eval_summaries` referencing `:metricField` (the default `overall` is the single metric's `avg(:metricField)`); all reference `:runId`/`:computationId`.

## 2. Analytics result table, model, repository

- [x] 2.1 Add `V1.10__CreateMetricScoreResultTable.sql` under `db/migration/analytics/POSTGRES/`: `metric_score_result(id VARCHAR(36) PK, test_suite_run_id, computation_id, metric_score_name, metric_name, value DOUBLE PRECISION)` with unique `(test_suite_run_id, computation_id, metric_score_name, metric_name)` and index `(test_suite_run_id, computation_id)`. No audit/computation timestamps — results join their computation via `computation_id`. Run `./gradlew generateJooq`; commit generated `MetricScoreResult*` under `.../jooq/analytics/`.
- [x] 2.2 Add `MetricScoreResult` model + `MetricScoreResultRecordMapper` (`@Component @LogExecution`) + `MetricScoreResultRepository`/`PostgresMetricScoreResultRepository` (`@Qualifier("analyticsDsl")`, `@Transactional("analyticsTransactionManager")`): `saveAll(List)` batched append-only with `.onConflict(...).doNothing()` (copy `PostgresEvalSummaryRepository.saveAll`), and `findByRunAndComputation(runId, computationId)`.

## 3. ParamExpr support in the Query DSL (single pre-pass)

- [x] 3.1 Add `QueryParameterResolver` (`experimental.query.service.translate`): rewrites a `StructuredQuery` into a parameter-free copy before translation (recursive substitution over `select`/`filter`/`having`; unbound/param-to-param/cyclic → 400; empty map → identity), invoked once at `StructuredQueryService.execute(query, params)`. The translator/builder/executor/repository stay parameter-agnostic; `ExprTranslator` keeps a defensive `case ParamExpr → 400`. The public `POST /api/v1/queries/execute` stays paramless.
- [x] 3.2 Tests: `QueryParameterResolverTest` (substitution + the three rejections + identity); paramless regression covered by `StructuredQueryBuilderTest`/`StructuredQueryServiceTest`.

## 4. Registry-driven function catalog + `mean`

- [x] 4.1 Make the DSL function catalog registry-driven: `experimental/query/service/translate/function/` with `QueryFunction` SPI, `FunctionContext`, `QueryFunctionRegistry` (`@Component`, reject duplicate names, unknown → `ValidationException`), and `BuiltInQueryFunctions` (one bean per built-in migrated out of the `ExprTranslator` switch). `ExprTranslator.toField` delegates to the registry.
- [x] 4.2 Add `MeanFunction` (`mean`): single `array` arg, folds `(e₁+…+eₙ)/n` via jOOQ `Field.add/.divide`; allow `ArrayExpr` to reach it. Render/reject unit tests in `StructuredQueryBuilderTest`.

## 5. Phase-3 computation executor + run-job hook

- [x] 5.1 Add `MetricScoreComputationExecutor` (`@Component @LogExecution @Slf4j`) in `experimental.query.service.metricscore`, implementing the one-method `MetricScoreComputation` interface declared in `service.domain.job` (dependency inversion — no `service → experimental.query.service` edge; `LayeredArchitectureTest` unchanged). Injects `BuiltInMetricStatistics`, `RunMetricSnapshotRepository`, `MetricScoreService`, `OutputSchemaFieldExtractor`, `StructuredQueryService`, `ObjectMapper`.
- [x] 5.2 Discover numeric metric fields from `RunMetricSnapshot.outputSchema` (`metric:<tsmd>:<field>` tokens); run each `BuiltInMetricStatistics.perMetric()` statistic once per field binding `:metricField`; catch `ValidationException` per (statistic, field) and continue (log exception as last arg); honor `cancellationSignal`.
- [x] 5.3 Run-level `overall` (`computeOverall`): use `BuiltInMetricStatistics.defaultOverall()` (`avg(:metricField)`) when `ctx.overallExpression == null`, computed only for a single metric field and binding `:metricField` to that field; else parse and run the custom self-contained JSON expression with only the run-scoping params. Batch-write all results via `MetricScoreService.saveAll`.
- [x] 5.4 Hook into `TestSuiteEvaluationJob`: hoist Phase-2 `computationId` so Phase 3 reuses it; invoke via the `MetricScoreComputation` interface after Phase 2, guarded by `!cancellationSignal.get()`, before the COMPLETED transition; non-fatal (log + continue).
- [x] 5.5 Unit test `MetricScoreComputationExecutorTest` with `new BuiltInMetricStatistics()` + mocked repos/`StructuredQueryService`: 5 built-ins always run (single metric → 5 + overall = 6, overall = the field's avg; multi → 10, no default overall; custom self-contained overall via direct context → 11; fault isolation → 5); skip-when-no-fields.

## 6. Per-suite `overall` (snapshotted, single-metric default)

- [x] 6.1 `test_suites.overall_score` nullable JSONB column (`meta/POSTGRES/V1.23__AddOverallScoreToTestSuites.sql`; null = system default). Regenerate jOOQ (`TestSuites.OVERALL_SCORE`). `TestSuite.overallScore` (String JSONB) + `TestSuiteRecordMapper` + `PostgresTestSuiteRepository`; `JsonbMapper.mapOverallScore(String)` (entity→snapshot); `TestSuiteMapper.toCloneEntity` inherits.
- [x] 6.2 Snapshot: `SuiteSnapshotDto.overallScore` (backward-compatible, no version bump); `SuiteSnapshotBuilder` captures `suite.overallScore` verbatim (null preserved; default resolved at Phase 3).
- [x] 6.3 Phase 3 wiring: `MetricScoreComputationContext.overallExpression`; `TestSuiteEvaluationJob.resolveOverallExpression(run)` reads it from the resolved snapshot.
- [x] 6.4 Setting `overall_score` is NOT exposed on the suite API in this version (no request/response DTO fields) — the column stays null and `overall` uses the built-in default; the column/snapshot/context/executor-branch remain as the future hook.
- [x] 6.5 Tests: `MetricScoreComputationFunctionalTests` (single → 6 incl. the default overall = the metric's avg; multi → 10, no overall) verified end-to-end on real Postgres. The custom-overall path is unit-tested only (mocked) — an authored custom expression referencing the dynamic `metric:<tsmd>:<field>` columns cannot translate end-to-end until those columns are authorable through the query schema (future).

## 7. Results read API + queryable entity

- [x] 7.1 `MetricScoreResultResponseDto`, `MetricScoreResultMapper` (MapStruct), `MetricScoreService.listResults(runId, computation)` resolving via `ComputationResolver`, and `MetricScoreResultController` (`GET /api/v1/analytics/metric-score-results?testSuiteRunId=&computation=latest|<uuid>`) with OpenAPI + `@Validated`. No management API.
- [x] 7.2 Expose `metric_score_result` as the `metric_score_results` queryable entity of the unified Query DSL (`MetricScoreResultSchemaProvider`, marker `MetricScoreResultQueryRepository`, `PostgresMetricScoreResultQueryRepository` on `analyticsDsl`; auto-registered). All 6 columns exposed; `in`/sort/aggregate supported; cross-computation reads via `computation_id in [...]`. Functional tests in `MetricScoreResultStructuredQueryFunctionalTests`. The bespoke controller is kept for the server-resolved `latest` read.

## 8. End-to-end verification + docs

- [x] 8.1 `MetricScoreComputationFunctionalTests` + `MetricScoreResultStructuredQueryFunctionalTests` green on real Postgres (validates `percentile_cont`/`mean` over JSONB numeric extraction and the reused `computation_id`). `BuiltInMetricStatisticsTest`, `MetricScoreComputationExecutorTest`, `LayeredArchitectureTest`, `JooqSchemaDriftTest` green. `./gradlew spotlessApply spotlessCheck` clean.
- [x] 8.2 Docs/spec: `docs/database-schema.md` (code-defined statistics note, `metric_score_result`, `test_suites.overall_score`); `AGENTS.md` (ParamExpr pre-pass, registry-driven functions, code-defined Phase-3 statistic queries); `openspec/specs/README.md` index. `openspec validate add-metric-score-statistics --strict` passes.
