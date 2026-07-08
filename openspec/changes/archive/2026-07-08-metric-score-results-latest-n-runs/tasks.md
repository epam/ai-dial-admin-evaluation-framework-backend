## 1. Schema migration & jOOQ regen

- [x] 1.1 Add analytics migration `src/main/resources/db/migration/analytics/POSTGRES/V1.12__AddSuiteAndTimestampToMetricScoreResult.sql`: add nullable `test_suite_id VARCHAR(36)` and `computed_at_ms BIGINT`; backfill `computed_at_ms` from `run_metric_snapshots` (`MIN(computed_at_ms) GROUP BY (test_suite_run_id, computation_id)`); backfill `test_suite_id` from `SELECT DISTINCT test_suite_run_id, test_suite_id FROM test_case_eval_summaries`; `DELETE` any rows still null in either column; `ALTER … SET NOT NULL` on both; `CREATE INDEX idx_metric_score_result_suite_computed ON metric_score_result (test_suite_id, computed_at_ms)` (done: migration file present and SQL valid).
- [x] 1.2 Run `./gradlew generateJooq` and commit the regenerated sources under `src/main/java-generated/.../data/db/jooq/analytics/` (adds `TEST_SUITE_ID`, `COMPUTED_AT_MS` to `MetricScoreResult` table + `MetricScoreResultRecord`); do not hand-edit generated files (done: generated diff committed, `JooqSchemaDriftTest` passes).

## 2. Data layer wiring

- [x] 2.1 Add `private UUID testSuiteId;` and `private Long computedAtMs;` to `data/db/analytics/model/MetricScoreResult` (done: fields present with existing Lombok annotations).
- [x] 2.2 Map the two new columns in `data/db/analytics/mapper/MetricScoreResultRecordMapper` (`.testSuiteId(UUID.fromString(r.getTestSuiteId()))`, `.computedAtMs(r.getComputedAtMs())`) (done: mapper builds both fields).
- [x] 2.3 Set the two new columns in `PostgresMetricScoreResultRepository.saveAll` insert builder (`.set(METRIC_SCORE_RESULT.TEST_SUITE_ID, r.getTestSuiteId().toString())`, `.set(METRIC_SCORE_RESULT.COMPUTED_AT_MS, r.getComputedAtMs())`) (done: inserts persist both columns; `onConflict…doNothing` unchanged).

## 3. Write-path population

- [x] 3.1 Inject `java.time.Clock` into `experimental/query/service/metricscore/MetricScoreComputationExecutor`; capture `long computedAtMs = clock.millis()` once at the top of `execute(ctx)` and thread it into `buildResult(...)` (done: no direct `Instant.now()`/`System.currentTimeMillis()` calls).
- [x] 3.2 In `buildResult(...)` set `.testSuiteId(ctx.getTestSuiteId())` and `.computedAtMs(computedAtMs)` (done: every built result carries suite id and the shared timestamp).

## 4. Tests

- [x] 4.1 Update `MetricScoreComputationExecutorTest` to inject `Clock.fixed(...)`; assert built/persisted results carry the fixed `computedAtMs` and the context's `testSuiteId` (done: unit test passes).
- [x] 4.2 Update `MetricScoreComputationFunctionalTests` to assert persisted rows have non-null `test_suite_id` (= run's suite) and `computed_at_ms`, read via `MetricScoreResultRepository` (done: functional test passes).
- [x] 4.3 Add a case to `MetricScoreResultStructuredQueryFunctionalTests`: seed score rows for one `test_suite_id` across ≥3 runs with distinct `computed_at_ms` via `AnalyticsTestDataHelper`; execute a `StructuredQuery` (`filter test_suite_id eq X`, `sort computed_at_ms DESC`, offset page `limit N`) and assert the latest N rows in descending order; assert schema discovery lists `test_suite_id` (UUID) and `computed_at_ms` (LONG) (done: functional test passes).

## 5. Docs & spec sync

- [x] 5.1 Update `docs/database-schema.md` `metric_score_result` entry with the two new columns and the new index (done: doc matches migration).
- [x] 5.2 Sync the delta specs into `openspec/specs/metric-score-statistics/spec.md` and `openspec/specs/structured-query-model/spec.md` at archive time (done: main specs reflect the new columns, latest-N-by-suite scenario, and subquery-valued `in`).

## 6. Verification (columns)

- [x] 6.1 `./gradlew spotlessApply` then `./gradlew clean build` (Checkstyle + Spotless + full test suite incl. `JooqSchemaDriftTest`) passes (done: build green).
- [x] 6.2 Run the touched suites explicitly: `./gradlew test --tests "com.epam.aidial.evaluation.experimental.query.service.metricscore.MetricScoreComputationExecutorTest"`, `…MetricScoreResultStructuredQueryFunctionalTests`, `…MetricScoreComputationFunctionalTests` (done: all pass).

## 7. DSL model — `subquery` expression

- [x] 7.1 Add `SubqueryExpr(StructuredQuery query)` to `experimental.query.model` implementing `Expr`; register the `@JsonSubTypes.Type(name = "subquery")` on the `Expr` sealed interface (done: `{"type":"subquery","query":{…}}` deserializes to a nested `StructuredQuery`).
- [x] 7.2 Update every exhaustive `switch (Expr)` for the new variant: `ExprTranslator.toField` rejects a bare `subquery` with a clear message (like `array`); confirm no other sealed switch is left non-exhaustive (compiler-guided) (done: compiles).

## 8. DSL translation — subquery-valued `in` (nested SQL)

- [x] 8.1 `SubqueryExpr` in `experimental.query.model` (`"subquery"` Jackson subtype); `ExprTranslator.toField` rejects a bare `subquery` (defensive, like `array`) (done: compiles).
- [x] 8.2 `TranslationContext(dsl, table, entity)` reaches `FilterTranslator` via a `ThreadLocal` (set/restore in the 3-arg `toCondition` `finally`, mirroring `AuthorizationTokenHolder`) — recursive methods keep their plain `(node, bindings)` signatures. `subquerySelect` reads the ThreadLocal, validates same-entity (else 400), builds the nested query via a lazy `ObjectProvider<StructuredQueryBuilder>` (breaks the builder↔translator cycle), and wraps it in a derived table selecting the **first** column → `left.in(select(firstCol).from(derived))`. `StructuredQueryBuilder.build`/`buildAggregate`/`countRows` create and pass the context; the 2-arg overload stays for non-DSL callers (reject subqueries) (done: compiles).

## 9. Parameter resolver

- [x] 9.1 Update `QueryParameterResolver` to recurse into `SubqueryExpr` (resolve params within the nested query) so params inside a subquery substitute; unbound/param-to-param still 400; public paramless endpoint unchanged (done: resolver handles the new kind).

## 10. Tests (subquery)

- [x] 10.1 Unit: the `...translate.*` render tests construct `FilterTranslator` with a mock `ObjectProvider<StructuredQueryBuilder>`; they still pass (translation behavior unchanged for non-subquery filters) (done: unit tests pass).
- [x] 10.2 Functional: `MetricScoreResultStructuredQueryFunctionalTests` — a **single-request** case (seed one suite, 3 runs with distinct `computed_at_ms`; `test_suite_run_id in (<same-entity aggregate subquery: test_suite_run_id + max(computed_at_ms), group by run, order by max desc, limit 2>)`) asserts all rows belong to exactly the latest 2 runs (compiles to nested `IN (SELECT …)`); plus empty-subquery→no-rows, subquery-outside-`in`→400, and cross-entity→400 cases (done: functional tests pass).
- [x] 10.3 Functional (REST/JSON): `StructuredQueryExecuteFunctionalTests` — POST a raw JSON `metric_score_results` query with an `in`-subquery to `/api/v1/queries/execute`, seeding all six score names (AVG/MAX/MIN/P10/P90/overall) for 2 runs (+1 older, excluded); assert HTTP 200, 12 rows, every score name present, only the latest 2 run ids — proving the subquery wire format parses and nested SQL returns all aggregations end-to-end (done: functional test passes).

## 11. DSL docs

- [x] 11.1 Document the `subquery` operand of `in` in the query-DSL OpenAPI docs — extended the `POST /queries/execute` `@Operation` description and added a `@Schema` to `SubqueryExpr` (the execute body is documented via `@Operation`/`@Schema`, not the list-endpoint `OpenApiQueryParamCustomizer`) (done: docs mention subquery membership).
- [x] 11.2 Update AGENTS.md inline Query-DSL conventions to note subquery-valued `in` (nested `SELECT` via lazy builder + per-call worker, same-entity, first-column membership) (done: convention documented).
- [x] 11.3 Checked `openspec/specs/README.md` per Spec Index Maintenance Policy — the `structured-query-model` summary does not enumerate operators, so the subquery addition does not make it materially inaccurate; no change needed (done: index accurate).

## 12. Verification (subquery)

- [x] 12.1 `./gradlew spotlessApply` then `./gradlew clean build` passes (done: build green).
- [x] 12.2 Run the touched suites: `…MetricScoreResultStructuredQueryFunctionalTests` and the translate unit tests (done: all pass).
