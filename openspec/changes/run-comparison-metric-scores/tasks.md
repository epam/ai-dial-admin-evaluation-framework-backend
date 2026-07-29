Implement in batches of **max 5 tasks / max 1 task group** per iteration (AGENTS.md Agent Workflow Rules).
A task is not complete until its tests have been **executed** and pass — static review does not count.

## 1. Configuration and context boot

- [x] 1.1 Add `configuration/properties/analytics/RunComparisonProperties.java` — `@ConfigurationProperties(prefix = "analytics.comparison")`, `@Validated`, `@LogExecution`, `@Min(1) Integer maxUnmatchedRows`, **no Java field initializer** (done: class compiles, no default in Java)
- [x] 1.2 Add `analytics.comparison.max-unmatched-rows: 5000` under the existing `analytics:` block in `src/main/resources/application.yml` (done: value present, is the only place the default lives)
- [x] 1.3 Add a new `### 6.13 Analytics Run Comparison` section under the `## 6. Evaluation Engine` top-level group
  with the six-column table (`Property | Environment Variable | Default | Required | Applied when | Description`)
  and one row: `analytics.comparison.max-unmatched-rows` | `ANALYTICS_COMPARISON_MAX_UNMATCHED_ROWS` | `5000` |
  `No` | `-` | maximum non-matching eval-summary rows a single run-comparison may report **per run**; exceeding
  it fails with 409. Bounds the returned exclusion id list, the `IN` bind count and the worst-case response
  size (≈0.35 MB at the default). Also add the matching `### 6.13` bullet to the document's Table of Contents
  (`docs/configuration.md:5-49` enumerates every subsection) (done: new numbered section under group 6, six
  columns, `Required` is one of the four permitted terms, TOC bullet present)
- [x] 1.4 Run one existing context-booting functional test to prove the new `@ConfigurationProperties` bean binds: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$NoSecurityStartupTests"` (done: green — a compile-clean build does **not** prove the context starts)

## 2. Data layer — per-side anti-join

- [x] 2.1 Add `data/db/analytics/model/EvalSummaryMatchStats.java` — three count scalars (`totalRows`, `matchedRows`, `matchedSuccessRows`) plus `BigDecimal avgExecDurationMs` (jOOQ's `DSL.avg` return type over a `BIGINT` column), **nullable** for an empty matched set; **no `RecordMapper`**. Named `…Stats`, not `…Counts`, because it carries the average too (done: plain carrier, no parsing/validation logic in it)
- [x] 2.2 Add `countMatches(runId, computationId, otherRunId, otherComputationId)` to `EvalSummaryRepository` + `PostgresEvalSummaryRepository` — typed jOOQ, derived table `k` = other run's `selectDistinct(lower(name), run_index, turn_index)`, `leftJoin` on the 3-part key, selecting `count(*)`, `count(k.name_lower)`, `count().filterWhere(matched AND status = SUCCESS)`, and `avg(EXEC_DURATION_MS).filterWhere(matched)` — the average is a **fourth aggregate on this same statement**, deliberately not a separate query. `RunComparisonService` injects `EvalSummaryRepository`
  **directly** — the same direct-repository pattern `ComputationResolver:42` and `MetricScoreComputationExecutor`
  already use for `RunMetricSnapshotRepository`; `experimentalService` may access `data`
  (`LayeredArchitectureTest:48-49`), and `EvalSummaryService` returns DTOs only, so a domain-model-returning
  delegate does not belong there (done: returns correct counts against a two-run fixture; falls back to
  `count(when(...))` if `filterWhere` renders awkwardly; class carries `@LogExecution` per AGENTS.md)
- [x] 2.3 Add `findUnmatchedIds(runId, computationId, otherRunId, otherComputationId)` — same join, `where k.name_lower is null`, `order by lower(name), run_index, turn_index, id` (done: returns only non-matching ids, byte-identical order across two identical calls)
- [x] 2.4 Add a functional test for both queries covering the **duplicate-key** fixture — two rows on side A sharing `(lower(name), run_index, turn_index)` with distinct `test_case_id`, one such row on side B ⇒ A reports `matchedRows == 2`, B reports `1`, A's unmatched list is empty (done: executed and green; this is the assertion a `DISTINCT ON` implementation fails)
- [x] 2.5 Extend that same functional test with the duration average, pinned to a **hand-computed** value rather than a range: matched rows of `100`/`300` plus one **non-matching** row of `9000` ⇒ exactly `200`, where a run-wide `avg` would give `3133.33`. Assert the empty-matched-set case returns `null`, and that a matched non-SUCCESS row **is** included (done: executed and green; the unmatched-row value proves the scoping and the failure row documents decision 14's accepted bias as behaviour). `AnalyticsTestDataHelper.createEvalSummary` already takes `execDurationMs` (`:161`), so no new overload is needed for this

## 3. Shared metric-field discovery (behaviour-neutral Phase-3 refactor)

- [x] 3.1 Promote `MetricField` to a top-level record in `experimental/query/service/metricscore/` (done: `MetricScoreComputationExecutor`'s private record at `:244` removed, all references compile)
- [x] 3.2 Add `MetricFieldDiscoverer` (`@Component @LogExecution`) wrapping `OutputSchemaFieldExtractor`, owning the flattening rule via `EvalSummaryExportColumnConstants.METRIC_COLUMN_PREFIX`/`COLUMN_SEPARATOR` — **never** a hand-written `"metric::"`. Signature is `discover(List<RunMetricSnapshot>)`: the snapshots are a **parameter**, the discoverer loads nothing (done: the only copy used by **metric-field discovery**; the schema-provider (`EvalSummariesSchemaProvider:178`) and weighted-mean field-name (`OverallScoreDefinitionResolver:104`) copies are explicitly out of scope)
- [x] 3.3 Delete `MetricScoreComputationExecutor.discoverMetricFields` (`:207-218`) and delegate to the discoverer (done: executor no longer injects `OutputSchemaFieldExtractor` directly)
- [x] 3.4 Change **exactly one line** in `service/domain/job/MetricScoreComputationExecutorTest.java:72` — constructor arg becomes `new MetricFieldDiscoverer(outputSchemaFieldExtractor)`, a real collaborator wrapping the existing mock. Run `./gradlew test --tests "…MetricScoreComputationExecutorTest"` (done: green with **every** stub at `:90`/`:96` and every assertion — notably `:214` `containsOnly("Relevancy.score")` — unchanged. If any assertion must change, the refactor was not behaviour-neutral: stop and re-check)
- [x] 3.5 Add `MetricFieldDiscovererTest` — the flattening rule including a tsmd name **containing a dot** (done: executed; proves why `metric_name` must never be split on `.`)

## 4. Aggregation over the matched population

- [x] 4.1 Add the shared `withIdPredicate(StructuredQuery, FilterNode)` helper in `FilteredMetricScoreAggregator` — copies the query with `filter == null ? predicate : new LogicalNode(AND, List.of(filter, predicate))`, all other components unchanged; the predicate is `LogicalNode(NOT, [ComparisonNode(IN, [FieldExpr("id"), ArrayExpr(unmatched)])])`, and is `null` (graft nothing) when the unmatched list is empty. The `ArrayExpr` items MUST be literal `ValueExpr`s — `FilterTranslator.inValues` rejects anything else — and `eval_summaries.id` binds as a UUID-typed field over a `VARCHAR(36)` column, so each id goes in as `new ValueExpr(ValueType.UUID, id.toString())` (done: unit-tested both branches plus the no-graft branch; class carries `@LogExecution` per AGENTS.md)
- [x] 4.2 Implement the statistics path — one `structuredQueryService.execute(query, params)` per (statistic, field) over `builtInStatistics.perMetric()`, binding `PARAM_RUN_ID`/`PARAM_COMPUTATION_ID`/`PARAM_METRIC_FIELD`, reading `MetricScoreConstants.VALUE_ALIAS` from row 0, omitting non-`Number`/absent values. **No change to `BuiltInMetricStatistics`** (done: `BuiltInMetricStatisticsTest` passes unmodified)
- [x] 4.3 Implement the `overall` path — `defaultOverall()` when the definition is null, else `overallScoreDefinitionResolver.resolve(definition, fullDiscoveredFieldNames)`; **the un-filtered field list**, or a mean's divisor silently changes. Add the `custom_function` guards (entity equals `ENTITY_EVAL_SUMMARIES` constant-first, `mode == AGGREGATE`, exactly one select column, read by that column's own alias); anything else or a `null` resolve ⇒ skip + log (done: `OverallScoreDefinitionResolverTest` passes unmodified)
- [x] 4.4 Add `FilteredMetricScoreAggregatorTest` — grafting onto non-null/null filters, empty unmatched ⇒ query is `equals`-identical to `BuiltInMetricStatistics`' own, one query per (statistic, field), null aggregate ⇒ omitted, and every `overall` variant (null definition, `mean`, `weighted_mean`, `custom_function` with null filter, `custom_function` in ROW mode ⇒ skipped, `custom_function` on a foreign entity ⇒ skipped) (done: executed and green)

## 5. Orchestration (6 tasks — implement across two batches)

- [x] 5.1 Add `service/domain/analytics/RunComparisonProvider.java` (one method) and the response DTOs in `service/domain/dto/analytics/` — `RunComparisonResponseDto`, `RunComparisonRunDto` (`runId`, `computationId`, `totalRowCount`, `matchedRowCount`, `matchedSuccessRowCount`, `Double avgExecDurationMs`, `unmatchedEvalSummaryIds`, `scores`), `MetricScoreValueDto`, with `@Schema` examples. `avgExecDurationMs` is `Double` and **nullable** — the global `NON_NULL` inclusion drops it when a run has no matched rows, which is the specified behaviour, not an oversight (done: interface + DTOs in the stable layer, nothing experimental imported)
- [x] 5.2 Add `RunComparisonService implements RunComparisonProvider` in `experimental/query/service/metricscore/` with an **explicit constructor** injecting `@Qualifier("analyticsTransactionManager") PlatformTransactionManager` and building a read-only `TransactionTemplate` — there is **no** `TransactionTemplate` bean;
  `@Qualifier("analyticsTransactionTemplate")` fails context startup. The three collaborators are
  `TestSuiteRunService.getRun` for the meta reads (via the owning service — it already throws
  `EntityNotFoundException` ⇒ 404), plus `EvalSummaryRepository` and `RunMetricSnapshotRepository` injected
  **directly** (done: context boots — rerun 1.4; class carries `@LogExecution` per AGENTS.md)
- [x] 5.3 Implement guards in the spec's order: exactly 2 distinct ids (400) → `getRun` ×2, unknown ⇒ 404 → same-suite (409) → null-`suiteSnapshot` (422, version **not** checked) → then inside the analytics transaction `ComputationResolver.resolve(null, runId)` ×2 (409 on empty) → the exclusion-list cap (409, task 5.4) (done: each guard unit-tested, and a different-suite pair where one run is legacy returns 409 rather than 422)
- [x] 5.4 Stats → cap → ids: `countMatches` per side (its `avgExecDurationMs` passes straight through to the
  response, `BigDecimal → Double`, null-safe — no service-side arithmetic);
  `totalRowCount - matchedRowCount > maxUnmatchedRows` ⇒ 409
  **before** any id is fetched, message in exclusion terms naming the count, the limit and the property name; then
  `findUnmatchedIds` per side (done: unit test asserts `findUnmatchedIds` is never invoked when the cap trips, and
  that `matchedRowCount + unmatched.size() == totalRowCount`)
- [x] 5.5 Snapshots → discovery → short-circuits: load each run's snapshots via
  `RunMetricSnapshotRepository.findByRunIdAndComputationId(runId, resolvedComputationId)` and discover fields via
  `MetricFieldDiscoverer`, passing the **full** discovered list to the aggregator; zero matches ⇒ `scores: []` with
  no aggregate query; no discovered fields ⇒ `scores: []` (done: unit test asserts no
  `structuredQueryService.execute` call on either short-circuit)
- [x] 5.6 Add `RunComparisonServiceTest` — all guards, single computation resolution reused for both queries, both short-circuits, `includeOverall` follows Phase 3's rule, the overall path receives the **un-filtered** field list, cap-before-ids, and **asymmetric `matchedRowCount` accepted rather than rejected** (done: executed and green)

## 6. Web layer and OpenAPI

- [ ] 6.1 Add `web/controller/RunComparisonController.java` — `@RestController @Validated @LogExecution`, `@RequestMapping("/api/v1/analytics/metric-scores")`, `GET /comparison`, `@RequestParam @Size(min=2, max=2) List<UUID> runIds`; injects `RunComparisonProvider`, **not** the impl; do **not** use `@FilterParam` (done: endpoint responds; controller imports nothing from `experimental..`)
- [ ] 6.2 Add OpenAPI annotations and `src/main/resources/openapi/examples/api-v1-analytics-metric-scores-comparison-GET-response-200-{minimal,full}.json`, referenced via `@ExampleObject`. The `minimal` example is the full-match case, so it MUST show `avgExecDurationMs` present with `unmatchedEvalSummaryIds: []`; the `full` example MUST show the FE's follow-up filter as a **nested `not`-wrapped `in`** node — there is no `not_in` operator (this is not a filter/sort list endpoint — no `OpenApiQueryParamCustomizer`/`FilterWhitelists`/`SortWhitelists` registry entry) (done: examples render in Swagger UI and match the DTOs)
- [ ] 6.3 Run `./gradlew test --tests "…architectural.LayeredArchitectureTest"` (done: passes **unmodified** — `web` reaches the impl only through the `service`-layer interface)

## 7. Functional tests (8 tasks — implement across two batches)

- [ ] 7.1 Add `functional/tests/RunComparisonFunctionalTests.java` plus its `@Nested` registration in `PostgresFunctionalTests` (two-part pattern, cf. `MetricScoreComputationTests` at `:270`) (done: nested class runs via `PostgresFunctionalTests$RunComparisonTests`)
- [ ] 7.2 Extend `functional/helper/AnalyticsTestDataHelper.java` — `createEvalSummary` hardcodes `RUN_INDEX = 0` (`:201`) and cannot express `turn_index`/`total_turns`; add overloads plus a `createMetricScoreResult(...)` helper. All SQL stays inside the helper (done: no raw SQL in any test method)
- [ ] 7.3 Anti-divergence + full match in one fixture: identical case sets ⇒ `unmatchedEvalSummaryIds: []` on both sides, `matchedRowCount == totalRowCount`, no predicate grafted, and scores **equal the persisted `metric_score_result` values**. Carry `Foo`/`foo` and differing `numberOfRuns` as variants (done: executed and green, and the `metric_score_result` row count plus every `(metric_score_name, metric_name, value)` triple for both runs is unchanged after the comparison call, read via the repository rather than raw SQL — this is the spec's "Nothing is persisted" scenario)
- [ ] 7.4 **Duplicate keys all match** — the load-bearing test. Two side-A rows sharing the key with **differing metric values** ⇒ both match, A's `matchedRowCount` exceeds B's, A's exclusion list is empty, and A's AVG is the mean of **both** values. Do **not** assert on log output; nothing in `src/test` captures logs (done: executed; the differing values make a collapse numerically detectable)
- [ ] 7.5 Match-key matrix: zero overlap (short-circuit, no full-run bind); `turn_index` participates (3-turn vs 2-turn ⇒ 2 matches); `run_index` participates; asymmetric subset ⇒ `[]` on one side and a populated list on the other; and `matchedRowCount + unmatched.size() == totalRowCount` on every fixture (done: executed and green)
- [ ] 7.6 Aggregation edges: run with snapshots but no persisted scores still returns full aggregates; non-numeric declared output field ⇒ omitted, not a cast error; `Mean` with an all-NULL field still divides by the full discovered count; a run with no eval-summary rows at all ⇒ zero counts, empty exclusion list, empty `scores`, request succeeds (done: executed and green)
- [ ] 7.7 Error matrix in the spec's guard order: same run twice ⇒ 400, unknown run ⇒ 404, different suites ⇒ 409, null snapshot ⇒ 422, no computation ⇒ 409, cap exceeded ⇒ 409; plus CANCELLED run still comparable and a FAILED row matched but outside `matchedSuccessRowCount` (done: executed and green)
- [ ] 7.8 `avgExecDurationMs` end-to-end through the endpoint (task 2.5 covers the repository query; this covers the response): the hand-computed `200` from matched `100`/`300` with a `9000` non-matching row; a matched non-SUCCESS row included; the duplicate-key fixture averaging **both** rows; and zero overlap ⇒ the field **absent from the JSON body**, asserted as absent rather than as `0` or `null` (done: executed and green; assert on the serialized body so the `NON_NULL` drop is what is verified, not just the DTO field)

## 8. Verification, docs and spec sync

- [ ] 8.1 Run `EXPLAIN ANALYZE` on both anti-join queries against a realistic two-run fixture (done: hash-left-join plan confirmed, or the design's perf claim corrected — this is the one item the design records as unverified)
- [ ] 8.2 Update `openspec/specs/README.md` per Spec Index Maintenance Policy (done: index lists `run-comparison-metric-scores`, and the `metric-score-statistics` entry's trailing "no dedicated read endpoint" (`:126`) is amended to "no CRUD endpoint over stored results; a derived matched-row comparison endpoint exists — see `run-comparison-metric-scores`")
- [ ] 8.3 Sync the delta specs into `openspec/specs/` — the new `run-comparison-metric-scores` capability and the MODIFIED requirement in `metric-score-statistics` (done: main specs reflect shipped behaviour; the modified requirement keeps all five original scenarios plus the new derived-endpoint one). Check the `rules.archive` checklist in `openspec/config.yaml` before archiving
- [ ] 8.4 Run `./gradlew spotlessApply && ./gradlew checkstyleMain checkstyleTest && ./gradlew build` (done: all green; formatting owned by Spotless, not hand-applied). Confirm whether `AGENTS.md` needs a change — expected **no**, since this adds a controller/service/repo following already-documented patterns and introduces no new package, qualifier convention or cross-cutting pattern
- [ ] 8.5 Manual end-to-end check with `config.rest.security.mode=none`: call the endpoint for two runs of one suite and diff the `AVG` values against `metric_score_results` read via `POST /api/v1/queries/execute`; then run the FE's follow-up histogram query using the returned `not`-wrapped exclusion filter (done: values agree and the filter is accepted)
