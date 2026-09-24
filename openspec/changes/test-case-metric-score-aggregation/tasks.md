## 0. Design correction: testCaseOverallScore is metric-only (post-Group-7 discussion)

Discovered while discussing how `roc_auc` behaves for `testCaseOverallScore`: a population-dependent
`CustomFunction` (e.g. `roc_auc`) is meaningless for a single test case, and `Mean`/`WeightedMean` are
structurally metric-only (`WeightedMetric(metricName, outputField, weight)` can never reference a
`data::`/`response::` field). Per-test-case scoring never needed the `CustomFunction` grafting machinery
in the first place — it was justified only by the assumption that `testCaseOverallScore` could be an
arbitrary `CustomFunction`, which the user determined is not a real use case.

- [x] 0.1 `TestSuiteRequestValidator.validateTestCaseOverallScore` — reject a `CustomFunction`
      `testCaseOverallScore` with a hard 400 at suite create/update (`TestSuiteService`) and clone
      (`TestSuiteCloneService`) revalidation. `overallScore` remains unrestricted.
- [x] 0.2 `TestSuiteEvaluationJob.resolveTestCaseOverallScoreDefinition` — the existing
      "`testCaseOverallScore` null → falls back to `overallScore`" fallback no longer falls back when
      `overallScore` is a `CustomFunction` (would otherwise silently defeat 0.1); per-test-case scoring is
      then simply not computed, same as an unconfigured suite.
- [x] 0.3 `EvalSummaryRowScoreComputer` — removed `computeCustomFunctionByTestCase`,
      `requireGroupableShape`, `groupByTestCaseId`, `runAndComputationIdParams`, and the
      `overallScoreDefinitionResolver`/`customFunctionQueryRewriter` dependencies; the `CustomFunction`
      branch of `computeByTestCase`'s switch is now a defensive no-op (logged, should be unreachable).
      `CustomFunctionQueryRewriter` itself was unaffected at the time — Phase 3
      (`MetricScoreComputationExecutor`, `overallScore`) still used it. **Superseded by Group 13**: the
      rewriter was later deleted entirely; this note describes the state as of this task only.
- [x] 0.4 Updated tests: `EvalSummaryRowScoreComputerTest` (removed the `CustomFunction`-grafting tests,
      added one defensive-branch test), `TestSuiteEvaluationJobTest` (added a no-fallback-for-CustomFunction
      test), `TestSuiteFunctionalTests` (added 2 new 400-rejection tests for create/update),
      `TestSuiteRunFunctionalTests` (`shouldUseTestCaseOverallScoreForPerRowWhileOverallScoreDrivesRunLevel`
      now pairs `overallScore=CustomFunction` with `testCaseOverallScore=Mean` instead of a second
      `CustomFunction`; `shouldComputePerRowScoreForRowSafeCustomFunction` renamed to
      `shouldWriteNoPerRowScoreForRowSafeCustomFunctionWithoutTestCaseOverallScore` and now asserts null
      scores, since the old GROUP-BY-id fallback for a row-safe `CustomFunction` overallScore no longer
      applies; the 2 new mixed-metric-and-response-field tests from Group 8 discussion now pass `null` for
      `testCaseOverallScore` instead of reusing `overallScore`).

## 1. Schema and jOOQ codegen

- [x] 1.1 Add `src/main/resources/db/migration/analytics/POSTGRES/V1.20__CreateTestCaseMetricScoresAggregatedTable.sql` creating `test_case_metric_scores_aggregated` (`id`, `test_suite_run_id`, `test_case_id`, `computation_id`, `metric_scores` JSONB `NOT NULL DEFAULT '{}'`, `created_at_ms` set once and never updated by later upserts — reserved for future partitioning, mirroring `test_case_eval_summaries`'s convention —, `computed_at_ms`), a unique index on `(test_suite_run_id, test_case_id, computation_id)`, and a lookup index on `computation_id`
- [x] 1.2 Run `./gradlew generateJooq`; verify `TEST_CASE_METRIC_SCORES_AGGREGATED` appears under `src/main/java-generated/com/epam/aidial/evaluation/data/db/jooq/analytics/Tables`; commit the generated diff
- [x] 1.3 Update `docs/database-schema.md` with the new table's columns, indexes, and JSONB shape

## 2. Domain model, mapper, repository, service for the new table

- [x] 2.1 Add `data/db/analytics/model/TestCaseMetricScoreAggregated.java` (`id`, `testSuiteRunId`, `testCaseId`, `computationId`, `metricScores` as raw JSON `String`, `createdAtMs`, `computedAtMs`), mirroring `TestCaseEvalScore`
- [x] 2.2 Add `data/db/analytics/mapper/TestCaseMetricScoreAggregatedRecordMapper.java` (`@Component`, `map(Record)`), mirroring the existing `TestCaseEvalScore` record mapper
- [x] 2.3 Add `data/db/analytics/repository/TestCaseMetricScoreAggregatedRepository.java` (interface) with `saveAll(List<TestCaseMetricScoreAggregated>)` (batched upsert) and `findByRunIdAndComputationId(UUID runId, UUID computationId)`
- [x] 2.4 Add `PostgresTestCaseMetricScoreAggregatedRepository.java` (`@Qualifier("analyticsDsl")`, `@LogExecution`) implementing `saveAll` as `INSERT ... ON CONFLICT (test_suite_run_id, test_case_id, computation_id) DO UPDATE SET metric_scores = EXCLUDED.metric_scores, computed_at_ms = EXCLUDED.computed_at_ms` — `created_at_ms` is inserted once and deliberately left out of the `DO UPDATE SET` clause so it never changes on a later re-aggregation
- [x] 2.5 Add `service/domain/dto/analytics/TestCaseMetricScoreAggregatedBatchWriteItemDto.java`, mirroring `TestCaseEvalScoreBatchWriteItemDto`
- [x] 2.6 Add `service/domain/analytics/TestCaseMetricScoreAggregatedService.java` (`@LogExecution`) owning `computedAtMs` stamping and delegating to the repository, mirroring `TestCaseEvalScoreService`
- [x] 2.7 Unit tests for the record mapper and repository (Testcontainers) covering insert and the upsert-on-conflict path

## 3. Aggregation computation

- [x] 3.1 Add `query/service/metricscore/TestCaseMetricScoreAggregator.java` building, per discovered `MetricField` (reusing `MetricFieldDiscoverer`), one `UNION ALL` CTE arm computing `avg`/`min`/`max`/`count` grouped by `test_case_id` with `HAVING count(...) > 0`, combined via an outer `jsonb_object_agg`/`jsonb_build_object` query producing one row per test case. **Superseded by 11.1**: the per-`MetricField`/`UNION ALL` design described here was retired in favor of a single `jsonb_each`-based scan requiring no `MetricField` input at all — this entry's text is stale, kept for history.
- [x] 3.2 Scope the aggregator's query to `test_suite_run_id = ?`, `computation_id = ?`, `test_case_id IN (:affectedTestCaseIds)`, aggregating each affected test case's **entire** row set for the computation (not just the current flush batch)
- [x] 3.3 Unit tests: avg/min/max/count computed correctly per metric per test case; a metric absent from the JSONB map when it never fired for any of that case's rows; the exact repro scenario (2 turns × 2 requests × 2 reruns = 8 rows, a metric firing on only 4 of them → `count = 4`, stats computed over just those 4)

## 4. Flush-cycle hookup

- [x] 4.1 In `InProcessMetricEvaluationExecutor`, add `TestCaseMetricScoreAggregator` and `TestCaseMetricScoreAggregatedService` as constructor-injected collaborators
- [x] 4.2 In `doFlush`, add `writeAggregatedMetricScores(...)`: collect the batch's distinct `test_case_id`s, skip if there are no discovered metric fields, call the aggregator, then `batchUpsert` via the new service — wrapped in its own `try/catch (RuntimeException)` (log + continue), isolated from the row-score write's own error handling. **Corrected during Group 6**: originally placed after the row-score write, but re-ordered to run *before* it once Group 6 made the row-score computation itself read `test_case_metric_scores_aggregated` (see task 6.4's implementation note)
- [x] 4.3 Unit test: the new write happens after the row-score write; a `RuntimeException` from the aggregator is caught/logged without failing the flush or blocking the eval-summary/row-score writes. **Stale, superseded by 4.2's own correction**: this entry's "after the row-score write" claim describes the *original* ordering, reverted by 4.2 once Group 6 required the opposite order — the corresponding test asserts the write happens **before** `writeRowScores`, matching 4.2's implementation note, not this text.

## 5. `test_case_metric_scores` Query DSL entity

- [x] 5.1 Add `query/service/repository/PostgresTestCaseMetricScoreAggregatedEntityResolver.java` (`StructuredQueryEntityResolver` for `test_case_metric_scores`), mirroring `PostgresMetricScoreResultEntityResolver`'s plain-table template, gated on `datasource.analytics.vendor=POSTGRES`
- [x] 5.2 Extend the JSONB-flattening mechanism to flatten `metric_scores` into `metric_scores::<name>::<stat>` fields: added a `metric_scores::` branch to `JsonbFieldResolver` (execution-side resolution, mirroring its existing `metric::` handling) plus a plain `TestCaseMetricScoresSchemaProvider` (static base schema only, mirroring `MetricScoreResultSchemaProvider`) — per-run *detailed*-schema flattening (the `EvalSummariesSchemaProvider` pattern) was not needed: the spec's own scenario only requires the field to be *queryable*, which `JsonbFieldResolver` alone provides, independent of what the schema-discovery endpoint advertises
- [x] 5.3 Unit/functional test: a structured query selecting `metric_scores::<name>::avg` from `test_case_metric_scores`, filtered by `test_case_id`, returns the expected aggregated value

## 6. Per-test-case score rebuild (Phase 2 / `eval-summary-scoring`)

- [x] 6.1 Add a present-values-only combiner (e.g. `AggregatedOverallScoreComputer`) computing `Mean` as sum-of-present/count-of-present and `WeightedMean` as sum-of-(weight×present)/sum-of-weights-of-present over a `Map<String, Double>` of per-metric values (nulls excluded). **Superseded by 10.4**: `AggregatedOverallScoreComputer` was deleted entirely once `Mean`/`WeightedMean` were rebuilt to query `test_case_metric_scores` directly (Decision 8) — no combiner needed. Kept `[x]` for history; no artifact of this task survives in the current codebase.
- [x] 6.2 Add a generic `CustomFunction` query rewriter that walks a resolved `StructuredQuery`, replacing `entity: eval_summaries` with `entity: test_case_metric_scores` and every `FieldExpr` matching `metric::<name>::<outputField>` with `metric_scores::<name>::avg`. **Corrected during Group 7 verification**: the `metric_scores` JSONB key must be `<tsmdName>.<outputField>` (matching `MetricField.metricName()` / `TestCaseMetricScoreAggregator`'s own `jsonb_object_agg` key), not the bare `tsmdName` — the original implementation dropped the output field, silently colliding a TSMD's distinct output fields (e.g. a classifier's `label` and `probability`) and returning null for every retargeted `CustomFunction`. Also added a guard: a query referencing any non-metric flattened family (`data::`, `response::`, `metricInfo::`, `metricError::`) anywhere in its tree is left entirely untouched (kept on raw `eval_summaries`, unweighted, as before this change) rather than retargeted onto an entity with no matching column — surfaced to and confirmed by the user, since `test_case_metric_scores_aggregated` has no dataset/response/metadata columns to answer such a reference. **Superseded by Group 13**: this task's outcome (superseded already by Group 0 for Phase 2's use of it — see task 0.3's note) was fully deleted, including the guard — `CustomFunction` is never retargeted anywhere in the codebase as of Group 13. Kept `[x]` for history.
- [x] 6.3 In `EvalSummaryRowScoreComputer`, for `Mean`/`WeightedMean`: select each configured metric's `avg` field from `test_case_metric_scores` for the batch's distinct affected test cases (one row per test case, one column per metric), then combine via the new combiner; broadcast the resulting score/passed to every `test_case_eval_scores` row of that test case (implemented in `InProcessMetricEvaluationExecutor.writeRowScores`). **Superseded by 10.3**: the row-select-per-metric-plus-combiner approach described here was replaced by grafting `test_case_id IN (:ids) GROUP BY test_case_id` directly onto the resolver's already-correctly-targeted query — no separate select-then-combine step. The broadcast-to-every-row behavior (`InProcessMetricEvaluationExecutor.writeRowScores`) remains correct and unaffected.
- [x] 6.4 In `EvalSummaryRowScoreComputer`, for `CustomFunction`: apply the rewriter to the resolved query, then reuse the existing `IN (:ids) GROUP BY id` graft — generalized to group by `test_case_id` instead of a raw row's own `id` — with its shape guard updated to check `entity == "test_case_metric_scores"`, scoped to the batch's distinct affected test cases. **Implementation note**: this required swapping the flush-cycle order from Group 4 — `writeAggregatedMetricScores` now runs **before** `writeRowScores` in `doFlush`, since the per-test-case score computation reads `test_case_metric_scores_aggregated`, which only the aggregation step populates. Fixed in `InProcessMetricEvaluationExecutor` and documented in both methods' javadoc. **Superseded by Group 0**: this `CustomFunction`-per-test-case graft was removed entirely once `testCaseOverallScore` was restricted to `Mean`/`WeightedMean` (see task 0.3) — `EvalSummaryRowScoreComputer` no longer has this code path. The flush-cycle reordering documented here remains correct and unaffected.
- [x] 6.5 Unit tests: `Mean`/`WeightedMean` exclude a never-fired metric from the average rather than coalescing to zero; every row of a multi-row test case receives the identical score; `CustomFunction` shape-guard rejection scenarios (wrong entity, non-aggregate mode, multiple/unaliased columns, pre-existing `groupBy`) hold against the rewritten query; the `roc_auc`-style `CustomFunction` path is additionally verified end-to-end via the existing `RocAucScoreFunctionalTests` suite. **Superseded by Group 0/10/13**: the `CustomFunction`-specific assertions here no longer apply — `CustomFunction` was removed from Phase 2 entirely (Group 0) and is never rewritten anywhere (Group 13); the `Mean`/`WeightedMean` exclusion/broadcast assertions survive, reworked against the Group 10 direct-build query shape (see 10.5).

## 7. Run-level overall rebuild (Phase 3 / `metric-score-statistics`)

- [x] 7.1 In `MetricScoreComputationExecutor.computeOverall`, for `Mean`/`WeightedMean`: issue an ungrouped DSL aggregate query selecting `avg(metric_scores::<name>::avg)` per configured metric field over `test_case_metric_scores`, filtered to the run/computation, then combine via the Task 6.1 combiner. **Fixed during verification**: `fetchAggregatedMetricAverages` must deduplicate metric keys before building the select list — a `WeightedMean` with a duplicated `(metricName, outputField)` weight term (an explicitly-tested, legitimate scenario) otherwise builds two SQL columns under the same alias and jOOQ throws `InvalidResultException: Field ... is not unique in Record`. The same dedup was applied to `EvalSummaryRowScoreComputer.computeMeanOrWeightedMeanByTestCase` (Task 6.3), which has the identical select-per-metric-key shape.
- [x] 7.2 In `MetricScoreComputationExecutor.computeOverall`, for `CustomFunction`: apply the Task 6.2 rewriter to the resolved query and execute it ungrouped against `test_case_metric_scores`
- [x] 7.3 Leave the default (null `overall_score` column) single-metric computation unchanged, still targeting raw `eval_summaries`
- [x] 7.4 Unit tests: `Mean`/`WeightedMean` weight two test cases with different row counts equally; a metric never present for any test case in the run is excluded rather than coalesced to zero; a `roc_auc`-style `CustomFunction` produces a real (non-degenerate) result ranking per-test-case averages, equally weighted regardless of row count; the default single-metric computation is unaffected. Functional verification (`MetricScoreComputationFunctionalTests`) required seeding `test_case_metric_scores_aggregated` before invoking the executor directly (via a new `aggregateMetricScores` test helper mirroring Phase 2's flush-cycle aggregation) and updating `computesWeightedMeanWithMissingMetricAsZero`'s expected value from the old coalesce-to-zero semantics (0.25) to the new exclude semantics (0.5). The full `PostgresFunctionalTests` suite and root-module unit tests all pass.

## 8. Functional tests and documentation

- [x] 8.1 Add a functional test scenario extending an existing full-run functional test suite (e.g. `MetricScoreComputationFunctionalTests`/`EvalSummaryFunctionalTests`): one test case with 2 turns × 2 requests × 2 reruns (8 rows) where a metric's `condition` fires on only 4, plus a second test case with a different row count in the same suite; assert `test_case_metric_scores_aggregated`'s `metric_scores` and `test_case_eval_scores.score` are correct for the uneven case, and `metric_score_result.overall` weights both test cases equally. **Implemented as a new class** (`TestCaseMetricScoreAggregationEndToEndFunctionalTests`, extending `AbstractMultiTurnFunctionalTest`) rather than extending an existing suite, since it needed real multi-turn test-case authoring (not synthetic row seeding) to drive the repro through the actual pipeline — scaled to 2 turns vs. 1 turn (not the full 2×2×2) since `AbstractMultiTurnFunctionalTest`'s helpers only support multi-turn, not multi-request chains, but the structural shape (uneven row count + a metric conditionally absent on some of one test case's rows) is the same. Asserts both `eval_summaries.score` (both of the multi-turn case's rows share the identical, correctly-computed score) and `metric_score_result.overall` (equal per-test-case weighting, not row-weighted).
- [x] 8.2 In the PR description, flag as explicit out-of-scope follow-ups: `BuiltInMetricStatistics` (run-level AVG/P10/P90/MIN/MAX) and `FilteredMetricScoreAggregator`/`RunComparisonFunctionalTests` retain the pre-existing per-test-case weight-skew behavior
- [x] 8.3 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew test`; confirm `JooqSchemaDriftTest` passes with the new migration. All pass — full root-module suite, `checkstyleMain`/`checkstyleTest`, `JooqSchemaDriftTest`, and the `evaluation-runner-core`/`eval-cli` subprojects.

## 9. Spec maintenance

- [x] 9.1 Sync the delta specs for `eval-summary-scoring`, `metric-score-statistics`, and the new `test-case-metric-score-aggregation` capability from this change into `openspec/specs/`. **Included the Group 0 design correction** (not just the original delta content) — the delta specs themselves were first updated to reflect `testCaseOverallScore`'s Mean/WeightedMean-only restriction and the `CustomFunctionQueryRewriter` non-metric-field guard, discovered after the original delta specs were written, before merging into the main specs. Merged by hand (preserving every other existing requirement in both base specs verbatim), not via a sync tool. `openspec validate --specs --strict` passes for all three affected specs (22 unrelated, pre-existing failures elsewhere in the repo, untouched by this change).
- [x] 9.2 Update `openspec/specs/README.md` per the Spec Index Maintenance Policy (new spec folder `test-case-metric-score-aggregation` added; `eval-summary-scoring` and `metric-score-statistics` one-line summaries updated to reflect the per-test-case rebuild and the `testCaseOverallScore` restriction)

## 10. Simplification: build Mean/WeightedMean directly against the new entity (post-Group-9 discussion)

Discussed after Group 9 completed: the user found the Group 6/7 fetch-and-combine approach
(`AggregatedOverallScoreComputer`, selecting each metric's `avg` as a separate column and combining in
Java) too complex for what should be "just pointing the DSL at the new table on the new `avg` field," and
pointed out that `CustomFunctionQueryRewriter` is only genuinely needed for `CustomFunction` (opaque,
client-authored, already-stored content) — `Mean`/`WeightedMean` are queries **we** author ourselves, so
they can be built directly against `test_case_metric_scores` from the start, with no retarget step and no
separate combiner.

- [x] 10.1 `OverallScoreDefinitionResolver`: `Mean`/`WeightedMean` now build their query directly against
      `entity: test_case_metric_scores` with `metric_scores::<key>::avg` field references — the exact same
      `divide(add(coalesce(avg(f1),0), coalesce(avg(f2),0), ...), n)` formula this class has always built,
      just targeting the new entity/fields from construction instead of `eval_summaries`/`metric::`.
      `CustomFunction` is unchanged (still resolves against `eval_summaries` verbatim; still needs
      `CustomFunctionQueryRewriter` afterward, since it's the one variant that's opaque/client-authored).
      Added `BuiltInMetricStatistics.aggregateSelecting(String entity, Expr)` (entity-parameterized
      sibling of the existing `aggregateSelecting(Expr)`) to support this without duplicating
      run-scoped-filter construction.
- [x] 10.2 `MetricScoreComputationExecutor.computeOverall`: unified all three definition types into one
      `computeOverallScore` helper (`resolve(...)` → `rewrite(...)` only if `CustomFunction` →
      `executeScalar(...)`); deleted `fetchAggregatedMetricAverages`/`paramEq`.
- [x] 10.3 `EvalSummaryRowScoreComputer.computeByTestCase`: `Mean`/`WeightedMean` now `resolve(...)` (query
      already targets `test_case_metric_scores`) then graft `test_case_id IN (:ids)`/`GROUP BY
      test_case_id` directly — reinstated the `requireGroupableShape`/`groupByTestCaseId` graft methods
      (generalized past their `CustomFunction`-only naming from Groups 6/7, since `CustomFunction` no
      longer reaches this class at all) rather than the Group 6 row-select-per-metric approach. Deleted
      `computeMeanOrWeightedMeanByTestCase`/`buildTestCaseMetricScoresRowQuery`.
- [x] 10.4 Deleted `AggregatedOverallScoreComputer.java` entirely — no remaining call sites. This also
      **eliminates the duplicate-weighted-term-causes-ambiguous-column bug** patched around in Group 7.1:
      that bug only existed because of the one-column-per-metric-name select shape; a single arithmetic
      expression evaluates `avg(field)` twice inline with no naming conflict, so the dedup fix and its
      test coverage were removed as no longer applicable.
- [x] 10.5 Updated unit tests (`OverallScoreDefinitionResolverTest`, `EvalSummaryRowScoreComputerTest`,
      `MetricScoreComputationExecutorTest`) to assert the new direct-build query shape and reverted
      "excludes a missing metric" assertions back to coalesce-to-zero (the formula's original, unchanged
      semantics — the exclude behavior never actually needed a separate combiner, and the originally
      reported partial-firing bug is fixed at the aggregation layer, not by this formula). Reverted
      `MetricScoreComputationFunctionalTests.computesWeightedMeanWithMissingMetricAsZero` to its
      pre-Group-7 form (DisplayName and expected value `0.25`). Updated `proposal.md`/`design.md` (new
      Decision 8) and the three affected specs (delta + main) to match. Full root-module test suite,
      `checkstyleMain`/`checkstyleTest`, and `openspec validate --specs --strict` all pass.

## 11. Simplification: single jsonb_each scan instead of per-metric UNION ALL (post-Group-10 discussion)

Discussed after Group 10: `TestCaseMetricScoreAggregator#aggregate` already ran as one SQL round-trip, but
was built by enumerating every discovered `MetricField` in Java and `UNION ALL`-ing one arm per field —
one independent scan of `test_case_eval_summaries` per field. The user asked whether this could be one
true single-pass query instead. It can: `metric_values` is a two-level JSONB map
(`{"<tsmdName>": {"<outputField>": value}}`), which Postgres can walk generically via two chained
`jsonb_each` calls filtered to numeric leaves (`jsonb_typeof(...) = 'number'`), without Java needing to
know which metric fields exist ahead of time.

- [x] 11.1 Rewrote `TestCaseMetricScoreAggregator.aggregate` to `aggregate(UUID runId, UUID computationId,
      List<UUID> testCaseIds)` — dropped the `metricFields`/`MetricField` parameter. The CTE's inner
      `SELECT` is now one scan of `test_case_eval_summaries` joined against two chained `jsonb_each(...)`
      table expressions (`jsonb_each(metric_values)` then `jsonb_each(<that>.value)`), mirroring the
      raw-SQL table-function pattern already used by `QueryDslRunnableTestCaseSelector#compile`
      (`DSL.table("...", ...).as(alias, columns...)`), filtered by `jsonb_typeof(...) = 'number'` and
      grouped by `(test_case_id, tsmdName, outputField)`; the metric name is built in SQL via
      `tsmdKey.concat(".").concat(outputKey)` instead of Java-side string splitting. Retired
      `splitFlattenedName`/`buildMetricAccessor`/`buildMetricArm` and the `unionAll` loop. The outer
      `jsonb_object_agg`/`jsonb_build_object`/CTE-wrapping shape is unchanged. **Fixed during
      verification**: referencing the two chained `jsonb_each` tables' columns via
      `table.field(name, class)` (mirroring how the outer CTE's fields are read later in this same method)
      returns `null` for a raw-SQL-templated table built via `DSL.table("jsonb_each({0})", ...).as(alias,
      cols...)` — that overload only resolves against a table with real column metadata, which a hand-written
      SQL template doesn't have. This surfaced as an NPE (`"tsmdName" is null`) at every call site,
      silently swallowed by `writeAggregatedMetricScores`'s fail-soft `catch (RuntimeException)`, which
      cascaded into 13 unrelated-looking functional test failures elsewhere (`MetricScoreComputationTests`,
      `RunComparisonTests`, `TestSuiteRunTests`, `TestCaseMetricScoreAggregationEndToEndTests`) whose
      `Mean`/`WeightedMean` reads of the now-never-populated `test_case_metric_scores_aggregated` silently
      resolved to 0/null instead of the expected value. Fixed by referencing those columns the same way
      `QueryDslRunnableTestCaseSelector.compile` does — `DSL.field(DSL.name(alias, column), class)` — which
      works for any aliased table, raw-SQL or not.
- [x] 11.2 Updated the two call sites (`InProcessMetricEvaluationExecutor.writeAggregatedMetricScores`,
      `MetricScoreComputationFunctionalTests.aggregateMetricScores`) to drop the `metricFields` argument;
      `writeAggregatedMetricScores` keeps its existing `if (metricFields.isEmpty()) return;` early-out
      as-is (a legitimate optimization using the list it already has for `writeRowScores`'s sake).
- [x] 11.3 Updated tests: `TestCaseMetricScoreAggregatorFunctionalTests` (dropped the trailing
      `List.of(METRIC_A[, METRIC_B])` argument from all three `aggregate(...)` calls; removed the
      now-unused `METRIC_A`/`METRIC_B` constants and `MetricField` import) and
      `InProcessMetricEvaluationExecutorTest` (both `aggregate(...)` stubs dropped their 4th argument
      matcher). Updated `design.md` Decision 2 and the main `test-case-metric-score-aggregation` spec's
      Implementation Notes to describe the `jsonb_each`-based single-pass design in place of the retired
      `MetricFieldDiscoverer`/`MetricField` reuse claim, including the deliberate, accepted trade-off:
      numeric-field detection moves from schema-declared to runtime-typed (`jsonb_typeof`), so a value
      that doesn't match its declared schema type is now silently excluded instead of throwing a cast
      error — consistent with this table's existing fail-soft, regenerable-derived-data philosophy.
      `./gradlew spotlessApply compileJava compileTestJava checkstyleMain checkstyleTest` and the targeted
      tests (`TestCaseMetricScoreAggregatorFunctionalTests`, `InProcessMetricEvaluationExecutorTest`,
      `MetricScoreComputationFunctionalTests`, plus the three downstream suites the NPE had cascaded into)
      all pass. **Unrelated pre-existing failures** (untouched by this change, confirmed via `git log` —
      last modified in an unrelated `#161` commit): `FilteredMetricScoreAggregatorTest`'s "Should resolve a
      mean against the full discovered field list, not a subset" and "Should compute overall from a
      weighted mean definition" — left as-is, out of scope for this task. **Correction (Group 12)**: these
      two tests were, in fact, fixed as part of Group 12's investigation, not left as-is — see Group 12's
      preamble and 12.1 for what actually changed in `FilteredMetricScoreAggregator` to make them pass.

## 12. Fixed during verification: RunComparisonFunctionalTests' Mean-overall test read an empty aggregated table

`RunComparisonFunctionalTests#shouldDivideMeanByFullDiscoveredFieldCount` failed (`overall` computed as
`0.0` instead of the expected `0.25`) once `FilteredMetricScoreAggregatorTest`'s two pre-existing failures
(Group 11's note above) were fixed and `FilteredMetricScoreAggregator.computeOverall` correctly started
resolving a `Mean` definition via `OverallScoreDefinitionResolver` — which, per Decision 8/Group 10, builds
directly against `test_case_metric_scores`. This test's `seedScore` helper only writes
`test_case_eval_summaries` directly; it never invokes `TestCaseMetricScoreAggregator`/
`InProcessMetricEvaluationExecutor`, so `test_case_metric_scores_aggregated` stayed empty for the seeded
computation, and `coalesce(avg(...), 0)` returned `0` for both discovered fields.

- [x] 12.1 Same class of gap `MetricScoreComputationFunctionalTests` already hit in Group 7.4 (a functional
      test seeding scores directly, bypassing the flush cycle that populates
      `test_case_metric_scores_aggregated`). Fixed the same way: `RunComparisonFunctionalTests` now injects
      `TestCaseMetricScoreAggregator`/`TestCaseMetricScoreAggregatedService` and adds an `aggregateMetricScores`
      helper (mirroring `InProcessMetricEvaluationExecutor#writeAggregatedMetricScores`) that
      `shouldDivideMeanByFullDiscoveredFieldCount` now calls after seeding run A's row, so the `Mean` overall
      has real aggregated data to divide. **Correction**: `FilteredMetricScoreAggregator`'s own `Mean`/
      `WeightedMean` wiring did **not** need "no change," as originally recorded here — making the two
      `FilteredMetricScoreAggregatorTest` failures pass (11.3's note) required real production changes:
      the resolver call now keys metric fields by `MetricField::metricName()` (matching
      `test_case_metric_scores`'s field naming) instead of `flattenedName()`; `findValueAlias`'s entity
      guard was widened to accept both `eval_summaries` and `test_case_metric_scores`; and a new
      `overallIdPredicate` method drops the row-exclusion predicate entirely (logged via `log.warn`, not
      silent) whenever the resolved query targets `test_case_metric_scores` — since that entity has no
      per-row ids for the predicate to exclude. **This last point is a known, accepted limitation, not a
      full fix**: run-comparison's `Mean`/`WeightedMean` `overall` can include unmatched-row data the
      per-metric statistics correctly excluded. A real fix (translating the row exclusion into an
      equivalent `test_case_id` exclusion) is explicitly deferred as a follow-up — see Group 13's note on
      this same limitation, confirmed with the user during Group 13's discussion.
- [x] 12.2 `./gradlew spotlessApply compileTestJava checkstyleTest` and
      `./gradlew :test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$RunComparisonTests"`
      (21/21 pass, including the previously-failing test) confirmed; the broader
      `com.epam.aidial.evaluation.query.service.metricscore.*`, `MetricScoreComputationExecutorTest`, and
      `PostgresFunctionalTests$TestCaseMetricScoreAggregatorTests` suites, plus the full root-module
      `./gradlew :test` (310 test classes), all pass with no regressions.

## 13. Simplification: revert CustomFunction retargeting entirely (post-Group-12 review)

Reviewed after Group 12: `CustomFunctionQueryRewriter` (a full tree-walk over an opaque, client-authored
`StructuredQuery`, plus a non-metric-field guard, plus a documented semantic redefinition of population
functions like `roc_auc`) was judged disproportionate complexity for the value it added. `CustomFunction`
was already fully excluded from Phase 2 by Group 0 (it never had a real per-test-case use case), so
retargeting only ever mattered for Phase 3's run-level `overall` — and Phase 3 already accepts the
identical weight-skew limitation, unfixed, for `BuiltInMetricStatistics` and `FilteredMetricScoreAggregator`
(both explicit Non-Goals from the start). The user's proposal: stop retargeting `CustomFunction` at all —
it keeps resolving and executing directly against `eval_summaries`, exactly as before this whole change,
the same accepted limitation as those other two components. Checked against the functional test fixtures
before making the change: the two tests exercising a retargeted `CustomFunction`
(`MetricScoreComputationFunctionalTests.computesCustomOverallForOneOfTwoMetrics`/`computesCustomOverallRocAuc`)
use one-row-per-test-case data, so per-row vs. per-test-case-average is numerically identical there —
reverting changes no expected values, only removes an unnecessary pre-aggregation call.

- [x] 13.1 Deleted `CustomFunctionQueryRewriter.java` entirely. `MetricScoreComputationExecutor`: removed
      the `customFunctionQueryRewriter` field/constructor param; `computeOverallScore` now executes the
      resolver's result directly for every definition type (`CustomFunction`'s resolved query already
      targets `eval_summaries`, unretargeted). `FilteredMetricScoreAggregator`: same removal; `computeOverall`
      no longer branches on `instanceof CustomFunction`. Updated both classes' javadoc, plus
      `OverallScoreDefinitionResolver`'s class javadoc, to describe `CustomFunction` as unretargeted, an
      accepted out-of-scope weight-skew limitation. Trimmed `FilteredMetricScoreAggregator#overallIdPredicate`/
      `#findValueAlias`'s javadoc to drop the now-impossible "retargeted `CustomFunction`" case (the
      dropped-predicate mechanism itself is unchanged and still needed for `Mean`/`WeightedMean` — see
      12.1's note, deliberately not fixed here, per the user's explicit decision to defer that
      investigation to a follow-up rather than bundle it into this simplification).
- [x] 13.2 Updated tests: `FilteredMetricScoreAggregatorTest` — deleted
      `shouldNotGraftExclusionPredicateWhenRetargetedToTestCaseMetricScores` (a `CustomFunction` can no
      longer reach `test_case_metric_scores`), simplified `shouldGraftOntoCustomFunctionWithoutFilter` to
      use the plain `customFunction(...)` helper (removed the now-redundant `customFunctionOverNonMetricField`
      helper — every `CustomFunction` behaves the same way now), and added
      `shouldNotGraftExclusionPredicateForMeanOverTestCaseMetricScores` to keep the accepted-limitation
      behavior (12.1) covered now that the `CustomFunction` test exercising the same mechanism is gone.
      `MetricScoreComputationExecutorTest` — dropped the `CustomFunctionQueryRewriter` import/constructor
      arg (no assertion changes needed; its `CustomFunction` test fixture already targeted `eval_summaries`
      with a field name that never matched the rewriter's `metric::` prefix anyway).
      `MetricScoreComputationFunctionalTests` — removed the now-unneeded `aggregateMetricScores(...)` calls
      from `computesCustomOverallForOneOfTwoMetrics`/`computesCustomOverallRocAuc` and updated the shared
      `aggregateMetricScores` helper's javadoc. `TestSuiteRunFunctionalTests` — updated comments in the two
      mixed-metric/response-field `CustomFunction` tests that referenced the now-deleted rewriter/implied a
      contrast with a "metric-only" `CustomFunction` that no longer exists; no assertion changes (both
      tests were already exercising the unretargeted path).
- [x] 13.3 Updated OpenSpec artifacts: `design.md` — rewrote Decision 5 (was: retargeting is a deliberate
      redefinition; now: retargeting was reverted post-implementation, with the full rationale), removed
      Decision 7 (the non-metric-field guard, now moot) and renumbered 8/9 → 7/8, added `CustomFunction`-
      defined `overall` to Non-Goals, replaced the CustomFunction-redefinition Risk entries with the
      `FilteredMetricScoreAggregator` dropped-predicate risk (12.1, explicitly noting the real fix is
      deferred), and updated the Migration Plan/Open Questions accordingly. `proposal.md` — reworded the
      `CustomFunction` "What Changes"/"Modified Capabilities"/"Impact" bullets to describe it as unaffected/
      unretargeted, folded into the existing "Out of scope" bullet alongside `BuiltInMetricStatistics`/
      `FilteredMetricScoreAggregator`. `specs/metric-score-statistics/spec.md` (delta) and
      `openspec/specs/metric-score-statistics/spec.md` (main, byte-identical per this change's established
      manual-sync pattern) — rewrote the `custom_function` paragraph and replaced the three
      retargeting-specific scenarios with one scenario confirming `custom_function` is unaffected/unweighted
      by test-case row count, the same accepted limitation as the other two components.
      `openspec/specs/README.md`'s `metric-score-statistics` summary updated to match (only `mean`/
      `weighted_mean` target `test_case_metric_scores`; `custom_function` stays on `eval_summaries`).
- [x] 13.4 Fixed pre-existing documentation debt in this file while already touching these areas (found
      during review, not introduced by Groups 11/12): 3.1's stale per-`MetricField`/`UNION ALL` description
      (marked superseded by 11.1); 4.3's stale "write happens after the row-score write" claim (marked
      superseded by 4.2's own reordering correction); 0.3, 6.1, 6.2, 6.3, 6.5's now-inaccurate references to
      `CustomFunctionQueryRewriter`/the combiner/the rewritten-query test assertions (marked superseded by
      Group 10 and/or this group, kept `[x]` for history); and the 11.3-vs-12 contradiction over whether the
      two `FilteredMetricScoreAggregatorTest` failures were "left as-is" or "fixed" (they were fixed, in
      12.1 — 11.3 corrected to point there; 12.1 corrected to describe the actual `FilteredMetricScoreAggregator`
      changes instead of claiming "no change needed").
- [x] 13.5 Verification: `./gradlew spotlessApply checkstyleMain checkstyleTest` clean;
      `./gradlew :test --tests "com.epam.aidial.evaluation.query.service.metricscore.*" --tests "com.epam.aidial.evaluation.service.domain.job.MetricScoreComputationExecutorTest"`
      (all pass, including the new `shouldNotGraftExclusionPredicateForMeanOverTestCaseMetricScores`);
      `./gradlew :test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$MetricScoreComputationTests" --tests "...\$RunComparisonTests" --tests "...\$TestSuiteRunTests"` all pass; full
      root-module `./gradlew :test` (exit code 0, no regressions) and `openspec validate --specs --strict`
      (the three affected specs — `metric-score-statistics`, `eval-summary-scoring`,
      `test-case-metric-score-aggregation` — all pass; the same 22 unrelated pre-existing failures noted in
      9.1 remain, untouched by this group) confirmed.

## 14. `test_case_eval_scores` extended with run/case context; exposed as its own deduplicated Query DSL entity

Discovered while manually verifying Group 13's fix on a real multi-turn/multi-rerun run: `test_case_eval_scores`
still has one row per raw `test_case_eval_summaries` row (e.g. 6 rows for a 2-turn × 2-rerun test case),
all carrying the same, correctly-computed score — storage duplication (not a value bug), an explicit
Non-Goal of the original change. Several redesigns were discussed and walked back (merging into
`test_case_metric_scores_aggregated` via a hand-embedded DSL expression or a Java reimplementation of the
score formula; re-keying `test_case_eval_scores` itself via a destructive drop/recreate-with-backfill
migration) before landing on the lowest-risk option: **leave `test_case_eval_scores`'s PK/grain/write-path/
`eval_summaries` join completely unchanged**, just extend it with denormalized `test_suite_run_id`/
`test_case_id`/`test_case_name`/`computation_id` columns, and register it as its own Query DSL entity
presented as a `SELECT DISTINCT ON (test_suite_run_id, test_case_id, computation_id) ... ORDER BY ...,
computed_at_ms DESC` view — one row per test case per computation at query time, with zero migration
data-loss risk and zero changes to any `eval_summaries` read path. Confirmed against two live FE query
shapes (see design.md, to be added) that both only ever needed "list/compare test-case scores," never the
raw per-row grain, and that neither needs to change as part of this group — the new entity is available for
the FE to migrate to at its own pace, not a forced/breaking change.

- [x] 14.1 Added `V1.21__AddRunCaseContextToTestCaseEvalScores.sql` (analytics DB): `ALTER TABLE ADD COLUMN`
      for `test_suite_run_id`/`test_case_id`/`test_case_name`/`computation_id` (all eventually `NOT NULL`),
      backfilled in place via `UPDATE ... FROM test_case_eval_summaries` (no data loss, no drop/recreate),
      plus `idx_test_case_eval_scores_natural_key` on `(test_suite_run_id, test_case_id, computation_id,
      computed_at_ms DESC)` shaped for the new entity's `DISTINCT ON` access pattern (14.4).
      `eval_summary_id` remains the primary key, untouched. `./gradlew generateJooq` run; diff committed.
- [x] 14.2 Extended `TestCaseEvalScore` (model) and `TestCaseEvalScoreBatchWriteItemDto` with the 4 new
      fields, additive only. `PostgresTestCaseEvalScoreRepository.saveAll` now sets them on insert and
      upgraded the upsert from `ON CONFLICT (eval_summary_id) DO NOTHING` to `DO UPDATE SET score, passed,
      computed_at_ms` (cheap correctness improvement — a previously-frozen stale score can now be corrected
      by a later flush; unrelated to this group's main goal but essentially free). `TestCaseEvalScoreService`:
      renamed `batchCreate` → `batchUpsert` to match.
- [x] 14.3 `InProcessMetricEvaluationExecutor`: no structural change to `writeRowScores` (still one item per
      buffered raw row, matching the unchanged table grain); `toScoreItem` gained the 4 new field
      assignments (`testSuiteRunId`/`computationId` from `context`, `testCaseId`/`testCaseName` from the
      buffered item, both already present on `EvalSummaryBatchWriteItemDto`). Updated
      `InProcessMetricEvaluationExecutorTest` (renamed `batchCreate`→`batchUpsert` verify calls; added
      assertions that the 4 new fields are populated on each written item) and
      `EvalSummaryStructuredQueryFunctionalTests`'s two tests that hand-build `TestCaseEvalScore` rows
      (`groupsByPassedForPassFailCount`, `selectsTestCaseNameAndScoreByRunId`) — switched their
      `createEvalSummary` calls to the full-control `EvalSummaryFixture` builder with explicit `testCaseId`s
      so the new NOT-NULL columns can be populated correctly on the `TestCaseEvalScore` rows built alongside
      them. Both suites pass.
- [x] 14.4 Added `PostgresTestCaseEvalScoreEntityResolver` (`StructuredQueryEntityResolver` for
      `test_case_eval_scores`, `@ConditionalOnProperty` POSTGRES) resolving to a `SELECT DISTINCT ON
      (test_suite_run_id, test_case_id, computation_id) ... ORDER BY ..., computed_at_ms DESC` derived table
      over `TEST_CASE_EVAL_SCORES`, excluding `eval_summary_id` from the projection (which raw row "wins"
      the dedup is an implementation detail, not meaningful to a client). Reuses
      `MetricScoreLatestComputationDefaulter` for `computation_id eq "latest"` support (the same one-line
      `rewrite()` override `PostgresMetricScoreResultEntityResolver` has — entity-agnostic, no changes
      needed to that class). Added `TestCaseEvalScoresSchemaProvider` (plain static-base-schema pattern,
      mirroring `TestCaseMetricScoresSchemaProvider`) — reads the resolver's `public static final DEDUPED`
      table constant directly rather than injecting the vendor-gated resolver bean, so schema discovery
      stays independent of which analytics vendor is configured (matching how `TestCaseMetricScoresSchemaProvider`
      itself references its static jOOQ table directly). Confirmed `OpenApiQueryParamCustomizer`'s registry
      does **not** apply here (grep found no entry for `test_case_metric_scores`/`metric_score_results`
      either — that registry is for paginated REST list endpoints, not generic Query DSL entities, which
      auto-register via component scanning). `spotlessApply`/`compileJava`/`compileTestJava`/
      `checkstyleMain`/`checkstyleTest` all clean.
- [x] 14.5 Added `TestCaseEvalScoresStructuredQueryFunctionalTests` (mirroring
      `TestCaseMetricScoresStructuredQueryFunctionalTests`): `dedupesMultiRowTestCaseToOneRowPerTestCase`
      (a 3-row simulated multi-turn test case plus a 1-row test case, queried via `test_suite_run_id in
      [...]`, returns exactly 2 rows); `freshestRowWinsOnDisagreement` (two disagreeing rows for the same
      test case, freshest `computed_at_ms` wins — covers the 14.2 `DO NOTHING`→`DO UPDATE` correction);
      `resolvesLatestSentinel` (mirrors `MetricScoreResultStructuredQueryFunctionalTests`'s pattern).
      **Fixed during verification**: the latest-sentinel test initially used an `in` filter on
      `test_suite_run_id`, which `MetricScoreLatestComputationDefaulter.singleEqValue` doesn't recognize
      (only a plain `eq`) — left the `"latest"` sentinel unresolved and the query returned 0 rows; fixed by
      adding a dedicated `runIdEq` helper alongside `runIdIn` and using it only for that test. Registered as
      `TestCaseEvalScoresStructuredQueryTests` in `PostgresFunctionalTests`. Also updated
      `QuerySchemaDiscoveryFunctionalTests.shouldListQueryableEntities`'s exact entity list to include
      `test_case_eval_scores` (alphabetically before `test_case_metric_scores`) — no dedicated base-schema
      test added, matching `test_case_metric_scores`'s own precedent of listing-only coverage. All pass.
- [x] 14.6 Added `PostgresTestCaseEvalScoreRepositoryFunctionalTests`. `TestCaseEvalScoreRepository` has no
      finder of its own (unlike `TestCaseMetricScoreAggregatedRepository`) and AGENTS.md disallows adding a
      test-only repository method, so writes are verified by reading them back through the `test_case_eval_scores`
      Query DSL entity (14.4/14.5) instead: `saveAllInsertsWithRunCaseContext` (all 4 new columns populated
      correctly) and `saveAllUpsertsOnConflict` (a later `saveAll` for the same `eval_summary_id` corrects
      `score`/`passed`/`computed_at_ms`). Registered as `PostgresTestCaseEvalScoreRepositoryTests` in
      `PostgresFunctionalTests`. Both pass.
- [x] 14.7 Docs: `docs/database-schema.md` — extended the `test_case_eval_scores` overview row and dedicated
      section with the 4 new columns/index (noting the write grain/PK/computation-flow are otherwise
      unchanged), added the `V1.21` migration-history row (analytics DB's own sequence — note V1.20/V1.21
      also exist independently in the meta DB's sequence, unrelated). `docs/patterns/query-dsl-entity-resolution.md`
      — added the entity as a fourth resolver shape ("`DISTINCT ON` derived table"), alongside the existing
      bare-table/LEFT-JOIN-derived/fully-derived shapes, including the predicate-pushdown caveat (`DISTINCT
      ON` doesn't get pulled up by Postgres the way the `test_suite_runs` shape's plain projection does, so
      the composite index is what keeps it efficient, not automatic pushdown).
- [x] 14.8 Spec maintenance: extended `design.md` with a new **Decision 9** documenting the two directions
      considered and rejected (embedding into `test_case_metric_scores_aggregated` via `ExprTranslator` or a
      Java reimplementation; re-keying `test_case_eval_scores` itself) before landing on extend-in-place +
      `DISTINCT ON` entity, plus the performance note (worth an `EXPLAIN ANALYZE` check, not a blocker) and
      a Migration Plan step 9 / Rollback update; corrected the Non-Goals bullet that claimed no schema
      change to `test_case_eval_scores`. Extended `proposal.md`'s "What Changes"/"Capabilities"/"Impact"
      with the new columns, the new entity, the `metrics-storage` capability (newly touched by this change),
      and the `DO UPDATE` behavior change; explicitly noted `eval_summaries` and its FE-facing read surface
      are untouched, and the FE migration to the new entity is a follow-up. Added a **new**
      `specs/metrics-storage/spec.md` delta under this change (`## MODIFIED Requirements` for "Database
      schema for eval summary scores"/"Batch write eval summary scores", `## ADDED Requirements` for the
      new entity) and synced it into `openspec/specs/metrics-storage/spec.md` immediately (matching this
      change's established proactive-sync convention from Groups 9/10/13, rather than deferring to archive
      time as originally planned), including updating that main spec's stale `Implementation Notes` bullets
      (`batchCreate`→`batchUpsert`, `doNothing()`→`doUpdate()`, model/DTO field lists, migration list).
      **Confirmed `query-schema-discovery` does *not* need touching**: grepped for `test_case_metric_scores`
      there and found no reference — that spec was never updated for the sibling entity either (it
      describes the discovery *mechanism* generically, not a per-entity enumeration), so `test_case_eval_scores`
      follows the same precedent. `openspec validate --specs --strict` confirms all 3 requirements
      (2 modified + 1 added) are well-formed; the one `metrics-storage` validation failure it reports
      (`requirements.17`, "Configuration properties for eval summaries" missing a SHALL/MUST keyword) is
      pre-existing and unrelated — part of the same 22 pre-existing failures noted since 9.1.
- [x] 14.9 Verification: `./gradlew spotlessApply checkstyleMain checkstyleTest` clean. Targeted tests all
      pass: `InProcessMetricEvaluationExecutorTest`,
      `PostgresFunctionalTests$QuerySchemaDiscoveryTests`,
      `PostgresFunctionalTests$TestCaseEvalScoresStructuredQueryTests`,
      `PostgresFunctionalTests$PostgresTestCaseEvalScoreRepositoryTests`,
      `PostgresFunctionalTests$EvalSummaryStructuredQueryTests`. The "manually re-derive the exact
      scenario" check is covered by `dedupesMultiRowTestCaseToOneRowPerTestCase`
      (`TestCaseEvalScoresStructuredQueryFunctionalTests`), which seeds a simulated multi-turn test case
      (3 rows) plus a second test case (1 row) — matching the reported 6-rows-instead-of-2 shape — and
      confirms the new entity returns exactly one row per test case (2 total) with correct `score`/
      `passed`, while `EvalSummaryStructuredQueryFunctionalTests` confirms the pre-existing
      `eval_summaries` queries remain byte-for-byte unaffected (same field names, same join, same
      behavior). Full root-module `./gradlew :test` exits 0, no regressions.
      `openspec validate --specs --strict` for `metrics-storage`: same 46 passed / 22 failed totals as the
      pre-Group-14 baseline (task 9.1) — the one pre-existing, unrelated failure in this spec
      (`requirements.17`, "Configuration properties for eval summaries") is untouched by this group.

**Group 14 complete: 61/61 tasks.** All planning artifacts (`proposal.md`, `design.md`, `tasks.md`, and the
delta specs including the new `metrics-storage` one) are up to date and synced into the main specs. Ready
for `/opsx:archive`.


