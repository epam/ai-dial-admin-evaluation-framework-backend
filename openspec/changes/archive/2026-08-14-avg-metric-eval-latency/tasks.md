## 1. Database schema

- [x] 1.1 Add Flyway migration `src/main/resources/db/migration/analytics/POSTGRES/V1.16__AddAvgMetricEvalDurationToEvalSummaries.sql`: `ALTER TABLE test_case_eval_summaries ADD COLUMN avg_metric_eval_duration_ms BIGINT NOT NULL DEFAULT 0;`
- [x] 1.2 Run `./gradlew generateJooq` and commit the regenerated `TestCaseEvalSummaries`/`TestCaseEvalSummariesRecord` sources under `src/main/java-generated/`
- [x] 1.3 Update `docs/database-schema.md` with the new `avg_metric_eval_duration_ms` column on `test_case_eval_summaries`

## 2. Latency measurement and aggregation

- [x] 2.1 Add `long durationMs` to `TsmdEvaluationResult.Success` and `TsmdEvaluationResult.Failure` records (`src/main/java/com/epam/aidial/evaluation/service/domain/job/TsmdEvaluationResult.java`); `ConditionError` is unchanged (no provider call was made)
- [x] 2.2 Inject `Clock` into `InProcessMetricEvaluationExecutor` (constructor injection via existing `@RequiredArgsConstructor`, backed by the `ClockConfiguration` bean)
- [x] 2.3 In `InProcessMetricEvaluationExecutor.evaluateAndBuild`, capture `clock.millis()` immediately before each TSMD's `CompletableFuture.runAsync(...)` dispatch, and again around the `worker.evaluate(...)` call inside that lambda, so both `Success` and `Failure` (interrupt/`RuntimeException` catch branches) carry the elapsed `durationMs`
- [x] 2.4 In the post-timeout reconciliation loop (`dispatchedTsmds.forEach(tsmd -> tsmdResults.putIfAbsent(...))`), compute the synthetic `Failure`'s `durationMs` as `clock.millis() - dispatchStartedAtMs` for that TSMD, using the dispatch timestamp captured in 2.3
- [x] 2.5 After `tsmdResults` is fully reconciled, compute `avgMetricEvalDurationMs` as the average `durationMs` across all `Success`/`Failure` entries (excluding `ConditionError`), defaulting to `0` when no TSMDs were dispatched; pass it into `buildItem(...)` and `buildPropagatedItem(...)`

## 3. Persistence wiring

- [x] 3.1 Add `Long avgMetricEvalDurationMs` to `EvalSummary` model (`data/db/analytics/model/EvalSummary.java`)
- [x] 3.2 Add `avgMetricEvalDurationMs` mapping to all four methods in `EvalSummaryRecordMapper` (`map`, `mapList`, `mapExport`, `mapExportWithBodies`)
- [x] 3.3 Add `.set(TEST_CASE_EVAL_SUMMARIES.AVG_METRIC_EVAL_DURATION_MS, ...)` to the batch insert in `PostgresEvalSummaryRepository` (also added the column to all four SELECT projections — `findById`, `buildListQuery`, `buildExportQuery`, `buildExportWithBodiesQuery` — so the record mappers can read it back)
- [x] 3.4 Add `@NotNull Long avgMetricEvalDurationMs` to `EvalSummaryBatchWriteItemDto` (`service/domain/dto/analytics/EvalSummaryBatchWriteItemDto.java`)
- [x] 3.5 Add the corresponding `@Mapping` in `EvalSummaryMapper` (`service/domain/mapper/EvalSummaryMapper.java`)

## 4. API exposure

- [x] 4.1 Add `avgMetricEvalDurationMs` (Long, with `@Schema(example = ...)`) to `EvalSummaryResponseDto` and `EvalSummaryDetailResponseDto`
- [x] 4.2 Add an `avgMetricEvalDurationMs` column descriptor to `EvalSummaryExportColumnPlanner`, positioned immediately after `execDurationMs` in the identity/execution column list
- [x] 4.3 Verify no changes are needed in `PostgresEvalSummaryEntityResolver`/`JooqTableSchemaResolver`/`EvalSummariesSchemaProvider` — confirmed: `TestCaseEvalSummaries.AVG_METRIC_EVAL_DURATION_MS` is a generated `TableField`, and `JooqTableSchemaResolver.bindings(table)` walks `table.fields()` automatically, so no resolver/registry changes are needed for DSL queryability/aggregatability

## 5. Tests

- [x] 5.1 Unit test `InProcessMetricEvaluationExecutor`/`TsmdEvaluationResult`: mixed `Success`/`Failure`/`ConditionError`/timeout scenarios assert `avgMetricEvalDurationMs` is computed correctly (average excludes `ConditionError`, includes timed-out `Failure` with real elapsed time, defaults to `0` with zero dispatched TSMDs) — ran `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.job.*"`, all pass (also fixed pre-existing `MetricOutputMapperTest` for the new `TsmdEvaluationResult` constructor arity)
- [x] 5.2 Unit test `EvalSummaryRecordMapper` and `EvalSummaryMapper`: round-trip `avgMetricEvalDurationMs` through all mapping methods (new `EvalSummaryRecordMapperTest`, extended `EvalSummaryMapperTest`)
- [x] 5.3 Functional test (`@PostgresFunctionalTests`): extended `EvalSummaryStructuredQueryFunctionalTests` — seeds rows via `EvalSummaryFixture`/`AnalyticsTestDataHelper`, asserts the persisted `avg_metric_eval_duration_ms` via `EvalSummaryRepository.findById`, and asserts a structured Query DSL `avg` aggregate over `avg_metric_eval_duration_ms` filtered by `test_suite_run_id` — ran `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$EvalSummaryStructuredQueryTests"`, all pass
- [x] 5.4 Ran `./gradlew checkstyleMain checkstyleTest` and full `./gradlew :build` — BUILD SUCCESSFUL, all 2153+ tests pass (also required updating pre-existing `EvalSummaryExportColumnPlannerTest` column-order assertion and adding `avgMetricEvalDurationMs` to `EvalSummaryBatchWriteItemDto` builder call sites across 6 existing functional test files, since the field is `@NotNull`)

## 6. Docs

- [x] 6.1 Confirmed `docs/database-schema.md` update from task 1.3 is complete and accurately reflects the new column (row + migration history entry)
