## 1. New migration and jOOQ regen

- [x] 1.1 Add `V1.19__CreateTestCaseEvalScoresTable.sql`: `test_case_eval_scores` table with exactly `eval_summary_id` (PK), nullable `score`/`passed`, `computed_at_ms` — no denormalized run/computation/test-case context and no secondary indexes, since every read reaches this table via a join to `test_case_eval_summaries` (which already carries that context) and nothing queries it directly.
- [x] 1.2 Run `./gradlew generateJooq` and commit the regenerated sources (new `TestCaseEvalScores`/`Record` classes appear).

## 2. New component: EvalSummaryRowScoreComputer

- [x] 2.1 Create `EvalSummaryRowScoreComputer` (`query.service.metricscore`, sibling of `OverallScoreDefinitionResolver`/`FilteredMetricScoreAggregator`, not an extension of the latter): `computeBatch(definition, metricFieldNames, runId, computationId, rowIds)` — resolves the definition via `OverallScoreDefinitionResolver`, validates the resolved query's shape (`requireGroupableShape`: entity, mode, single aliased select column, no pre-existing `groupBy`), grafts `id` as a select column + `groupBy = ["id"]` + `id IN (:rowIds)` onto the filter, executes via `StructuredQueryService`, and maps result rows to `Map<UUID, Double>`.
- [x] 2.2 Unit tests (`EvalSummaryRowScoreComputerTest`): null-definition/empty-rowIds short-circuit; grafted query shape (select/groupBy/filter) for `Mean`; id→score mapping including a null-valued row and a rowId absent from the result set; `WeightedMean` computation; well-formed `CustomFunction` computation; rejection (empty map, no execute call) for non-aggregate mode, foreign entity, no alias, and pre-existing `groupBy`; unparseable `CustomFunction` → empty map.

## 3. Wiring in InProcessMetricEvaluationExecutor

- [x] 3.1 Add `id` (UUID) to `EvalSummaryBatchWriteItemDto`; generate it in `buildItem` via `UUID.randomUUID()`; change `EvalSummaryMapper.toEntity`'s `id` mapping to use the item's `id` when present, falling back to a fresh `UUID.randomUUID()` otherwise (preserves the external batch-write API's existing contract).
- [x] 3.2 Add a `discoverMetricFieldNames` helper (one call to `runMetricSnapshotRepository.findByRunIdAndComputationId` + `MetricFieldDiscoverer.discover`, once per `execute()` call, right after `writeRunMetricSnapshots`); thread the resulting `List<String>` through `flushIfNeeded`/`flushRemaining`/`doFlush`.
- [x] 3.3 Add `writeRowScores(buffer, context, metricFieldNames)`, called from `doFlush` immediately after `evalSummaryBatchWriteClient.batchWrite(...)` succeeds: skip if `context.getOverallScoreDefinition() == null`; otherwise call `evalSummaryRowScoreComputer.computeBatch(...)`, compute `passed` in Java (`score >= threshold`, null-propagating) via `toScoreItem`, and call `evalSummaryScoreService.batchCreate(...)`. Catch and log (not rethrow/cancel) any `RuntimeException` from this step.
- [x] 3.4 Unit tests: extend `InProcessMetricEvaluationExecutorTest` with `@Mock` fields for the four new dependencies (`RunMetricSnapshotRepository`, `MetricFieldDiscoverer`, `EvalSummaryRowScoreComputer`, `EvalSummaryScoreService`), lenient default stubs in `@BeforeEach`, and three new tests: score/passed written after flush via a stubbed `computeBatch`; `passed = null` when threshold is unconfigured; row-score computation skipped entirely when no `overallScore` definition is configured.

## 4. New write path: test_case_eval_scores

- [x] 4.1 New model `EvalSummaryScore` (`data.db.analytics.model`), new DTO `EvalSummaryScoreBatchWriteItemDto` (`service.domain.dto.analytics`: `evalSummaryId`, `score`, `passed` — no denormalized fields, mirroring `RunMetricSnapshotBatchWriteItemDto`'s envelope-vs-item split).
- [x] 4.2 New repository `EvalSummaryScoreRepository`/`PostgresEvalSummaryScoreRepository` (`saveAll`, jOOQ batch insert, `onConflict(EVAL_SUMMARY_ID).doNothing()` — PK-based, simpler than `test_case_eval_summaries`'s multi-column natural key).
- [x] 4.3 New service `EvalSummaryScoreService.batchCreate(computedAtMs, items)` (`@Transactional("analyticsTransactionManager")`, internal-only — no external REST endpoint or client-wrapper layer, since no chunking is needed: the buffer is already bounded by `metric-evaluation.batch-size`).

## 5. API exposure: LEFT JOIN

- [x] 5.1 Add `.leftJoin(TEST_CASE_EVAL_SCORES).on(TEST_CASE_EVAL_SCORES.EVAL_SUMMARY_ID.eq(TEST_CASE_EVAL_SUMMARIES.ID))` to all four `PostgresEvalSummaryRepository` query builders (`findById`, `buildListQuery`, `buildExportQuery`, `buildExportWithBodiesQuery`), with `TEST_CASE_EVAL_SCORES.SCORE`/`.PASSED` added to each projection (all tiers, since they're cheap scalars).
- [x] 5.2 Add `.score(r.getValue(TEST_CASE_EVAL_SCORES.SCORE))`/`.passed(...)` to `EvalSummaryRecordMapper`'s three generic-`Record` methods (`mapList`, `mapExport`, `mapExportWithBodies`); the typed-record `map()` method (no JOIN, currently unused in production code) intentionally does not carry score/passed.
- [x] 5.3 Add `@Schema` text to `EvalSummaryResponseDto`/`EvalSummaryDetailResponseDto`'s `score` field covering `Mean`/`WeightedMean`/`CustomFunction` support, with the roc_auc-style degenerate-null caveat.

## 6. Documentation

- [x] 6.1 `docs/database-schema.md` — add a new `## Table: test_case_eval_scores` section (placed after the `test_case_eval_summaries` JSONB-schemas subsection so it doesn't visually attach to the wrong table); add the `V1.19` migration-history row; update `overall_score_threshold`'s column description; add a per-row-reuse note to the "Metric-score statistics" section and a degenerate-null note to the `roc_auc_score` stored-function section.
- [x] 6.2 `docs/patterns/eval-summaries-read-surface.md` — document the score/passed read path: a sibling-table join populated by a second batch write per flush, not a follow-up UPDATE and not in-memory computation on the same INSERT.
- [x] 6.3 `docs/patterns/overall-score-definition.md` — document that Phase 3 and Phase 2 share the *same* resolved query (one implementation, two scopes), with the `StructuredQueryBuilder` grafting mechanism and the `roc_auc` degenerate-null explanation.
- [x] 6.4 `AGENTS.md` Unique Patterns one-liner and `docs/key-packages.md` (`.data.db.analytics.model`, `.query.service.metricscore`) updated to mention the new classes/table.

## 7. Functional and unit test coverage

- [x] 7.1 `EvalSummaryRowScoreComputerTest` (Group 2.2, above).
- [x] 7.2 `InProcessMetricEvaluationExecutorTest` extensions (Group 3.4, above).
- [x] 7.3 In `TestSuiteRunFunctionalTests.java`: `shouldComputePerRowScoreAndPassedFromWeightedMean` (end-to-end through the join); `shouldComputePerRowScoreForRowSafeCustomFunction` (a simple `avg` CustomFunction gets real per-row values); `shouldWriteNullScoreForRocAucCustomFunctionOverall` (roc_auc CustomFunction yields null score on every row, run still completes).

## 8. Spec maintenance

- [ ] 8.1 Update `openspec/specs/README.md` per the Spec Index Maintenance Policy — new spec folder `eval-summary-scoring` needs an index entry (done at archive time once the delta specs are synced to main).

## Verification

- [x] 9.1 `./gradlew spotlessApply`
- [x] 9.2 `./gradlew checkstyleMain checkstyleTest`
- [x] 9.3 `./gradlew test --tests "*EvalSummaryRowScoreComputerTest*"`
- [x] 9.4 `./gradlew test --tests "*InProcessMetricEvaluationExecutorTest*"`
- [x] 9.5 `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestSuiteRunTests"`
- [x] 9.6 Full `./gradlew test` (root module) — BUILD SUCCESSFUL, no failures
