## 1. Analytics schema + repository primitives

- [ ] 1.1 Add `src/main/resources/db/migration/analytics/POSTGRES/V1.15__AddEvalSummariesRunComputedAtIndex.sql` creating `idx_eval_summaries_run_computed_at ON test_case_eval_summaries (test_suite_run_id, computed_at_ms DESC, computation_id)` (done: migration applies on a clean Testcontainers boot)
- [ ] 1.2 Run `./gradlew generateJooq` and commit the regenerated sources under `src/main/java-generated/` (done: diff limited to the analytics `Indexes` class; `./gradlew test --tests "*JooqSchemaDriftTest"` passes)
- [ ] 1.3 Add `Optional<UUID> findLatestComputationId(UUID runId)` to `EvalSummaryRepository` + `PostgresEvalSummaryRepository` via typed jOOQ DSL (`select(COMPUTATION_ID) … orderBy(COMPUTED_AT_MS.desc()).limit(1)`), mirroring `PostgresRunMetricSnapshotRepository.findLatestComputationId` (done: compiles, no `JdbcTemplate`, no SQL text block)
- [ ] 1.4 Add `boolean existsByRunIdAndComputationId(UUID runId, UUID computationId)` to the same pair using `fetchExists` (done: presence decided without fetching rows; used by task 4.1)
- [ ] 1.5 Update `docs/database-schema.md` with the new index on `test_case_eval_summaries` (done: index list matches V1.15)

## 2. Computation resolution switch

- [ ] 2.1 Point `ComputationResolver`'s `latest` branch at `EvalSummaryRepository.findLatestComputationId` and drop the `RunMetricSnapshotRepository` constructor dependency; update the class javadoc's result conventions to say "no eval summaries for the run" (done: `RunMetricSnapshotRepository` no longer imported in this class)
- [ ] 2.2 Leave `RunMetricSnapshotRepository.findLatestComputationId` and `EvalSummariesSchemaProvider` untouched (done: `grep` confirms the schema provider still resolves metric families from snapshots; `EvalSummariesSchemaProviderTest` passes unchanged)
- [ ] 2.3 Unit-test `ComputationResolver`: explicit UUID never hits a repository; `latest`/`null` returns the eval-summary answer; empty when the run has no summaries; malformed string throws `ValidationException` (done: `./gradlew test --tests "*ComputationResolverTest"` passes)
- [ ] 2.4 Fix any existing unit test that stubs the snapshot repository for resolution (done: `./gradlew test --tests "*EvalSummary*Test" --tests "*MetricScoreLatestComputationDefaulterTest"` passes)

## 3. Metric evaluation executor writes summaries without metrics

- [ ] 3.1 Remove the `getAggregatedTsmds().isEmpty()` early return from `InProcessMetricEvaluationExecutor.execute`, replacing it with a log line that records the metric-less run (done: no early return; `writeRunMetricSnapshots` still called and no-ops on the empty list)
- [ ] 3.2 Unit-test the empty-TSMD path in `InProcessMetricEvaluationExecutorTest`: one `EvalSummaryBatchWriteItemDto` per result row, `metricValues`/`metricInfos` empty, zero `RunMetricSnapshot` writes, zero `MetricProviderClient` interactions, zero `ConditionExpressionEvaluator` interactions (done: `./gradlew test --tests "*InProcessMetricEvaluationExecutorTest"` passes)
- [ ] 3.3 Unit-test status and payload fidelity on the empty-TSMD path: SUCCESS rows stay SUCCESS, non-SUCCESS rows keep their own status, and `testCaseData` / `extractedColumns` / `extractionWarnings` / `turnIndex` / `totalTurns` / `execDurationMs` / `responseStatusCode` are carried through (done: same test class passes)

## 4. Export accepts metric-free computations

- [ ] 4.1 In `EvalSummaryExportService.resolveContext`, replace the "no `RunMetricSnapshot` rows ⇒ 404" guard with an eval-summary existence check via `existsByRunIdAndComputationId`, for both the explicit-UUID and resolved-`latest` paths (done: a metric-free computation returns 200; a genuinely unknown UUID still returns 404 `NOT_FOUND`)
- [ ] 4.2 Verify `EvalSummaryExportColumnPlanner` and `EvalSummaryExportColumnSelector` need no change for an empty snapshot list (done: no edits; behavior covered by task 5.4)

## 5. Functional coverage

- [ ] 5.1 In `EvalSummaryFunctionalTests`, add a metric-less run scenario seeded via `AnalyticsTestDataHelper` (results + eval summaries, no run metric snapshots): `GET /api/v1/analytics/eval-summaries?filter=runId:eq:<id>` returns the rows with `metricValues: {}` and `/count` returns their number (done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$EvalSummaryTests"` passes)
- [ ] 5.2 Add a multi-computation resolution test: two computations for one run with different `computed_at_ms`, no snapshots — `latest` resolves the newer one; an explicit older UUID returns the older rows (done: same suite passes)
- [ ] 5.3 Add a resolution-independence test asserting `latest` still resolves for a run whose summaries far outnumber a metric-bearing run's (done: assertion is on correctness of the resolved id and on the plan/timing not scaling with row count, with a deterministic assertion — no if/else branching)
- [ ] 5.4 In `EvalSummaryExportFunctionalTests`, add a metric-free export: header contains `data::*` / `response::*` and no `metric::*`, `metricInfo::*`, `metricError::*`; `computation=<uuid>` of that computation returns 200; a random UUID still returns 404 (done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$EvalSummaryExportTests"` passes)
- [ ] 5.5 Add an end-to-end run over a suite with zero TSMDs (`TestSuiteRunFunctionalTests`): run reaches COMPLETED, `test_case_eval_summaries` has one row per result row, `run_metric_snapshots` is empty, no `metric_score_results` rows (done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestSuiteRunTests"` passes)
- [ ] 5.6 Regression: confirm a metric-bearing run is unchanged end to end — snapshots written, `metricValues` populated, list/aggregate/export identical to before (done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$EvalSummaryAggregationTests" --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$MetricScoreComputationTests"` passes)

## 6. Quality gates and spec sync

- [ ] 6.1 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` (done: clean; no hand-formatting reverted by the formatter)
- [ ] 6.2 Run the full `./gradlew clean build` (done: green, including `LoggingConventionTest`, `LayeredArchitectureTest`, `JdbcTemplateFenceTest`, `JooqSchemaDriftTest`)
- [ ] 6.3 Sync the four delta specs into `openspec/specs/` (`metric-evaluation`, `metrics-storage`, `eval-summary-export`, `test-suite-runs`) and flip their requirement statuses to `Implemented` (done: main specs match implemented behavior; no stale "return without writing any records" text remains)
- [ ] 6.4 Re-check `openspec/specs/README.md` per the Spec Index Maintenance Policy and update only if a listed capability's one-line summary became inaccurate (done: index verified)
