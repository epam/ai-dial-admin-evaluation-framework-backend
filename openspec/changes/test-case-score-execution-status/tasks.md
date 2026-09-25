## 1. Migration

- [x] 1.1 Edit `src/main/resources/db/migration/analytics/POSTGRES/V1.21__AddRunCaseContextToTestCaseEvalScores.sql` in place to add `execution_status VARCHAR(20)`, backfill it from each row's own `test_case_eval_summaries.execution_status` (collapsed to `SUCCESS`/anything-else → `FAILED`), then `ALTER COLUMN ... SET NOT NULL` (done: column present, backfilled, NOT NULL; no new migration file added)
- [x] 1.2 Run `./gradlew generateJooq` and commit the regenerated sources under `src/main/java-generated/` (done: `TEST_CASE_EVAL_SCORES.EXECUTION_STATUS` exists in generated jOOQ code)
- [x] 1.3 Note the local-only breaking-schema-edit operational step (drop/recreate or `flyway repair` + re-migrate any locally migrated DB) in the PR description — not a spec requirement (done: called out in the PR/implementation summary, no spec text added for this)

## 2. `TestCaseExecutionStatusAggregator`

- [x] 2.1 Add `com.epam.aidial.evaluation.query.service.metricscore.TestCaseExecutionStatusAggregator` (`@Component @LogExecution @RequiredArgsConstructor`, `@Qualifier("analyticsDsl") DSLContext`) with `Map<UUID, ExecutionStatus> aggregate(UUID runId, UUID computationId, List<UUID> testCaseIds)`, querying `test_case_eval_summaries` `GROUP BY test_case_id` with `bool_or(execution_status <> 'SUCCESS')`, re-aggregating each test case's entire row set for the computation (done: class exists, matches `TestCaseMetricScoreAggregator`'s conventions, returns an entry for every input id)
- [x] 2.2 Unit test `TestCaseExecutionStatusAggregatorTest` (Testcontainers, mirroring `TestCaseMetricScoreAggregator`'s own test if one exists): mixed SUCCESS/FAILED rows for one test case → `FAILED`; all-SUCCESS rows → `SUCCESS`; empty `testCaseIds` → empty map (done: test class created and passing via `./gradlew test --tests "...TestCaseExecutionStatusAggregatorTest"`)

## 3. Wire the aggregate into the flush/score-write path

- [x] 3.1 Inject `TestCaseExecutionStatusAggregator` into `InProcessMetricEvaluationExecutor` and rewrite `writeRowScores`: compute `statusByTestCase` for the batch's distinct test case ids first (return early if empty), then call `evalSummaryRowScoreComputer.computeByTestCase(...)` only for the ids whose status is `SUCCESS` (done: `writeRowScores` no longer computes scores for FAILED-aggregate test cases)
- [x] 3.2 Change the write-item construction so every buffered item whose test case id is a key in `statusByTestCase` gets a `TestCaseEvalScoreBatchWriteItemDto` (not just ones with a computed score), fixing the "zero numeric samples + FAILED row → silently absent" gap (done: a fully-failed test case with no numeric metric samples still produces a write item)
- [x] 3.3 Update `toScoreItem` to accept the aggregated `ExecutionStatus`, force `score = null` when `FAILED` (letting `passed` fall out `null` via the existing threshold check), and set `.executionStatus(...)` on the built DTO (done: method signature and logic updated, no separate null-passed branch added)
- [x] 3.4 Update the `writeRowScores`/class-level javadoc to describe the new per-batch execution-status query and the SUCCESS-only score computation (done: javadoc reflects actual behavior)

## 4. Model / DTO / repository / service

- [x] 4.1 Add `private ExecutionStatus executionStatus;` (import `com.epam.aidial.evaluation.runner.model.ExecutionStatus`) to `data/db/analytics/model/TestCaseEvalScore.java` (done: field added, `@Data @Builder` regenerate accordingly)
- [x] 4.2 Add the same field to `service/domain/dto/analytics/TestCaseEvalScoreBatchWriteItemDto.java` (done: field added)
- [x] 4.3 Update `service/domain/analytics/TestCaseEvalScoreService.batchUpsert` to pass `item.getExecutionStatus()` into the `TestCaseEvalScore` builder (done: field threaded through)
- [x] 4.4 Update `data/db/analytics/repository/PostgresTestCaseEvalScoreRepository.saveAll` to add `EXECUTION_STATUS` to the same `insertInto(...).set(...)` chain that already sets `SCORE`/`PASSED`/`COMPUTED_AT_MS`, and to the same `onConflict(EVAL_SUMMARY_ID).doUpdate()` clause (`.set(EXECUTION_STATUS, excluded(EXECUTION_STATUS))`) — one upsert per row, no separate `UPDATE` (done: single-statement upsert covers all four columns)

## 5. Query DSL exposure

- [x] 5.1 Add `TEST_CASE_EVAL_SCORES.EXECUTION_STATUS` to the `DEDUPED` select list in `query/service/repository/PostgresTestCaseEvalScoreEntityResolver.java` (done: `execution_status` selectable/filterable/groupable on the `test_case_eval_scores` entity, same as `score`/`passed`)
- [x] 5.2 Verify no separate `FilterWhitelists`/`SortWhitelists` entry is needed for the generic Query DSL entity (bindings are auto-derived by `JooqTableSchemaResolver`) — add one only if verification shows it's required (done: confirmed via a new functional test selecting/asserting `execution_status` through the entity — no whitelist changes needed, bindings come from `JooqTableSchemaResolver.bindings(DEDUPED)`)

## 6. Tests

- [x] 6.1 Extend the `InProcessMetricEvaluationExecutor` unit test class: metric execution failure (null output field) → row `FAILED` → aggregated `FAILED` → written `score`/`passed` are `null`, `execution_status = FAILED` (done: test added and passing)
- [x] 6.2 Extend the same test class: condition-skip-only test case (metric omitted, row stays `SUCCESS`) → aggregated `SUCCESS`, `score` computed as before (done: test added and passing, guards against regressing the condition-skip contract)
- [x] 6.3 Extend the same test class: a test case with zero numeric metric samples but a `FAILED` row still produces a written `test_case_eval_scores` item (done: test added and passing)
- [x] 6.4 Extend the relevant `PostgresFunctionalTests` nested class that already exercises the eval-summary/score flush path with an `execution_status` assertion, using `AnalyticsTestDataHelper`/repository reads (no raw SQL in the test method) (done: functional test updated and passing, confirms the app boots and the LEFT JOIN/Query DSL entity surface the new column end-to-end)

## 7. Docs

- [x] 7.1 Update `docs/database-schema.md`'s `test_case_eval_scores` table: add the `execution_status` column row and a note on the collapsed SUCCESS/FAILED semantics plus the score/passed-null-when-FAILED behavior (done: table and prose match the new schema)
- [x] 7.2 Update `docs/patterns/eval-summaries-read-surface.md`'s "`score`/`passed` are a sibling table" section to describe `execution_status`'s aggregation (`TestCaseExecutionStatusAggregator`, OR-across-rows collapsed to SUCCESS/FAILED, independent of `test_case_metric_scores_aggregated`) and the score-gating behavior (done: section updated)
- [x] 7.3 Update `docs/patterns/overall-score-definition.md` to note the new score-gating precondition (done: doc mentions the execution-status gate)
- [x] 7.4 Add an Implementation Notes pointer in `openspec/specs/test-case-metric-score-aggregation/spec.md` to `TestCaseExecutionStatusAggregator` as a sibling component, clarifying it is not part of `test_case_metric_scores_aggregated`'s contract (doc-only edit, no requirement text change) (done: note added)

## 8. Spec sync

- [x] 8.1 After implementation and verification, sync the `eval-summary-scoring` delta spec in this change into `openspec/specs/eval-summary-scoring/spec.md` (done: main spec reflects the ADDED/MODIFIED requirements from this change's delta)

## 9. Verification

- [x] 9.1 Run `./gradlew test --tests "com.epam.aidial.evaluation...TestCaseExecutionStatusAggregatorTest"` and the updated `InProcessMetricEvaluationExecutor` test class; confirm pass (done: green — `PostgresFunctionalTests$TestCaseExecutionStatusAggregatorTests` 3/3, `InProcessMetricEvaluationExecutorTest` 20/20)
- [x] 9.2 Run the relevant `PostgresFunctionalTests` nested class end-to-end (`./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$<NestedTests>"`); confirm the app boots and the new column round-trips correctly (done: green — `TestCaseEvalScoresStructuredQueryTests` 4/4, `PostgresTestCaseEvalScoreRepositoryTests` 2/2, `EvalSummaryStructuredQueryTests` 14/14, `TestCaseMetricScoreAggregationEndToEndTests` 2/2; full `./gradlew :test` suite also green)
- [x] 9.3 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` before committing (done: no formatting/checkstyle violations)
