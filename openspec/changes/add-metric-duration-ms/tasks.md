## 1. Schema and generated sources

- [ ] 1.1 Add `src/main/resources/db/migration/analytics/POSTGRES/V1.15__AddMetricDurationToEvalSummaries.sql` with `ALTER TABLE test_case_eval_summaries ADD COLUMN metric_duration_ms BIGINT;` — nullable, no DEFAULT, no index (done: file exists, migration applies cleanly on a fresh Testcontainers boot)
- [ ] 1.2 Run `./gradlew generateJooq` and commit the regenerated `src/main/java-generated/**/jooq/analytics/**` diff (done: `TestCaseEvalSummaries.METRIC_DURATION_MS` exists and `./gradlew test --tests "*JooqSchemaDriftTest"` passes)
- [ ] 1.3 Update `docs/database-schema.md`: add the `metric_duration_ms` row to the `test_case_eval_summaries` table, describe the null-vs-zero semantics, and note next to both duration columns that `exec_duration_ms` covers only the final HTTP attempt while `metric_duration_ms` covers the whole per-row evaluation including provider retries — so they do not sum to a row total (done: both notes present)

## 2. Model and persistence

- [ ] 2.1 Add `private Long metricDurationMs;` to `data/db/analytics/model/EvalSummary.java` (done: field present, `Long` not `long`)
- [ ] 2.2 Map the column in all three `data/db/analytics/mapper/EvalSummaryRecordMapper.java` map methods (done: each method reads `TEST_CASE_EVAL_SUMMARIES.METRIC_DURATION_MS`)
- [ ] 2.3 Set the column in the `PostgresEvalSummaryRepository` batch insert (done: `.set(TEST_CASE_EVAL_SUMMARIES.METRIC_DURATION_MS, s.getMetricDurationMs())` present)
- [ ] 2.4 Add the column to all four select projections in `PostgresEvalSummaryRepository` — list, `findById`, `buildExportQuery`, `buildExportWithBodiesQuery` (done: all four include it; a written value round-trips through every read path)

## 3. Write path — measurement

- [ ] 3.1 Inject `java.time.Clock` into `service/domain/job/InProcessMetricEvaluationExecutor.java` via the existing `@RequiredArgsConstructor` (done: field added, context boots)
- [ ] 3.2 Measure the row window in `evaluateAndBuild`: `clock.millis()` as the first statement, and again after the `allOf(...).get(perResultTimeoutMs)` join **and** the timeout/missing-TSMD reconciliation loop (done: value covers condition evaluation, dispatch, semaphore wait and timeout)
- [ ] 3.3 Add a `Long metricDurationMs` parameter to `buildItem`, pass the measured value from `evaluateAndBuild` and `null` from `buildPropagatedItem` (done: non-SUCCESS rows carry `null`, scored rows carry a measured value)

## 4. Write path — contract

- [ ] 4.1 Add optional `private Long metricDurationMs;` to `service/domain/dto/analytics/EvalSummaryBatchWriteItemDto.java` — **no** `@NotNull` — with an OpenAPI `@Schema(description, example)` (done: omitting the field still yields HTTP 201)
- [ ] 4.2 Add `@Mapping(source = "item.metricDurationMs", target = "metricDurationMs")` to `EvalSummaryMapper.toEntity` — required because it is a multi-source method (done: MapStruct compiles without an unmapped-target warning and the value reaches the entity)
- [ ] 4.3 Update `src/main/resources/openapi/examples/eval-summary-batch-write-request.json` to include `metricDurationMs` (done: example reflects the new contract)

## 5. Read surfaces

- [ ] 5.1 Add `metricDurationMs` to `EvalSummaryResponseDto` and `EvalSummaryDetailResponseDto` with `@Schema` examples; confirm `toDto`/`toDetailDto` need no `@Mapping` (single-source, mapped by name) (done: both endpoints return the field)
- [ ] 5.2 Add the `plain("metricDurationMs", …)` descriptor to `EvalSummaryExportColumnPlanner` in the execution-columns block, immediately after `execDurationMs` (done: header order is `… executionStatus, execDurationMs, metricDurationMs, responseStatusCode`; a null value renders as an empty cell)
- [ ] 5.3 Confirm the field is **absent** from `FilterWhitelists.EVAL_SUMMARIES` and `SortWhitelists`, and reachable through the query DSL with no DSL-side edit (done: `filter=metricDurationMs,gt,1` → 400; `avg(metric_duration_ms)` over `eval_summaries` succeeds)

## 6. Tests

- [ ] 6.1 Add a ticking clock stub to `InProcessMetricEvaluationExecutorTest` (the existing `Clock.fixed` always yields `0` elapsed) (done: helper returns successive millis)
- [ ] 6.2 Unit-test the three measurement outcomes: scored row records the elapsed value, propagated non-SUCCESS row records `null`, timed-out row records at least `perResultTimeoutMs` (done: `./gradlew test --tests "*InProcessMetricEvaluationExecutorTest"` passes)
- [ ] 6.3 Unit-test that a row whose every metric is condition-skipped records a non-null duration (done: assertion is on non-nullness, not a fixed number)
- [ ] 6.4 Functional test (`PostgresFunctionalTests`): batch-write → list/detail read round-trip preserves the value, and an omitted field persists as `NULL` (done: assertions use the repository/API, no raw SQL in the test)
- [ ] 6.5 Functional test: export CSV header contains `metricDurationMs` in the specified position, an explicit `columns: ["testCaseName","metricDurationMs"]` request yields exactly those two columns, the preview manifest lists it, and `filter=metricDurationMs,gt,1` returns 400 (done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests*"` passes for the affected nested classes)

## 7. Verification and spec sync

- [ ] 7.1 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` (done: clean, no hand-formatting left)
- [ ] 7.2 Run `./gradlew clean build` (done: full suite green, including `JooqSchemaDriftTest`, `LoggingConventionTest`, `LayeredArchitectureTest`)
- [ ] 7.3 Sync the three delta specs into `openspec/specs/metrics-storage/spec.md`, `openspec/specs/metric-evaluation/spec.md`, `openspec/specs/eval-summary-export/spec.md`, flipping each touched requirement's `Status` back to **Implemented** (done: main specs describe the shipped behavior; `openspec/specs/README.md` needs no change — no new spec folder, no status change, summaries still accurate)
