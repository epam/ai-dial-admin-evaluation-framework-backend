## 1. Tiebreak convergence (independent — can ship without the seam)

- [ ] 1.1 Add the `computation_id ASC` tiebreak to `PostgresEvalSummaryRepository.findLatestComputationId` (currently `ORDER BY computed_at_ms DESC` with no tiebreak); verify with a functional test that two computations of one run sharing `computed_at_ms` resolve to the smaller id, repeatably
- [ ] 1.2 Flip `PostgresRunMetricSnapshotRepository.findLatestComputationId` from `computation_id DESC` to `ASC`; verify `RunMetricSnapshotFunctionalTests.findLatestComputationIdBreaksTieByGreaterComputationId` (:197) is inverted — method name, `@DisplayName` and assertion now expect the *smaller* id — and passes
- [ ] 1.3 Flip the `metric_names` correlated subquery tiebreak in `PostgresTestSuiteRunEntityResolver` (:134) from `DESC` to `ASC`; verify `TestSuiteRunStructuredQueryFunctionalTests.metricNamesTiebreaksByGreaterComputationId` (:312) is inverted (name, `@DisplayName`, assertion) and passes
- [ ] 1.4 Run the four `ComputationResolver` consumers' existing suites (`EvalSummaryService`, `EvalSummaryExportService`, `MetricScoreLatestComputationDefaulter`, `RunComparisonService` paths) and verify none regress: `./gradlew test --tests "*EvalSummary*" --tests "*RunComparison*" --tests "*MetricScore*"`

## 2. Batch latest-computation lookup

- [ ] 2.1 Add `Optional`-free batch method `findLatestComputationIds(Collection<UUID>)` to `EvalSummaryRepository`; verify it compiles and the interface javadoc states that runs without eval summaries are absent from the map, never mapped to null
- [ ] 2.2 Implement it in `PostgresEvalSummaryRepository` via `unnest(ids) CROSS JOIN LATERAL (… ORDER BY computed_at_ms DESC, computation_id ASC LIMIT 1)` (design D6); verify a functional test covering a mixed page — scored runs, a run with no eval summaries, and a same-millisecond tie — returns one id per scored run and omits the unscored one
- [ ] 2.3 Add `ComputationResolver.resolveLatest(Collection<UUID>)` delegating to 2.2; verify a unit test asserts delegation and empty-input handling (no DB round trip on an empty collection)

## 3. Extension seam

- [ ] 3.1 Add the `QueryResultPageExtender` SPI in `query.service.repository` with `extend(StructuredQuery, QueryResultPage)`; verify javadoc states the additive / non-overwriting / order-preserving / omit-don't-null contract and that failure isolation is the coordinator's job, not the implementation's
- [ ] 3.2 Make `JooqStructuredQueryExecutorExtender` inject `List<QueryResultPageExtender>` and fold the page through each in bean order, wrapping **each** extender in its own try/catch that logs at warn with the exception as the last SLF4J argument and continues; verify unit tests cover: no extenders (page returned as-is), one extender adds keys, a throwing extender yields the unextended page, and a throwing extender does not suppress a later one
- [ ] 3.3 Drop the duplicate `@LogExecution` from `JooqStructuredQueryExecutorExtender` (both it and `JooqStructuredQueryExecutor` carry it, so every query logs twice); verify by running one query at DEBUG and observing a single aspect log line
- [ ] 3.4 Run a functional test that boots the context to confirm the `@Primary` wiring still resolves with a non-empty extender list: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$StructuredQueryExecuteTests"`

## 4. Overall-score extender

- [ ] 4.1 Add `MetricScoreConstants` entries for the `metric_score_results` entity name and the `metric_score_name` / `metric_name` field names, repoint the duplicated literals in `PostgresMetricScoreResultEntityResolver` (:27) and `MetricScoreResultSchemaProvider` (:24), and widen the class javadoc to the metric-score bounded context (design D8); verify both classes compile against the constants and no string literal remains
- [ ] 4.2 Add the `overall_score_value` key constant to `TestSuiteRunQueryFields`, documented as extension-only; verify it is absent from `PostgresTestSuiteRunEntityResolver.bindings()` and `TestSuiteRunsSchemaProvider`, and that a query referencing it in `filter`/`select`/`sort`/`group_by` returns HTTP 400
- [ ] 4.3 Implement `OverallScoreTestSuiteRunsPageExtender`: resolve latest computations via 2.3, then one `StructuredQuery` against `metric_score_results` filtered by `test_suite_run_id in […] AND computation_id in […] AND metric_score_name eq 'overall' AND metric_name eq 'overall'` (design D5 — the run-id list is required for the index plan), executed through the concrete `JooqStructuredQueryExecutor`; verify unit tests cover value attachment and the omit-when-absent rule
- [ ] 4.4 Implement the skip conditions — aggregate mode, projection without `id`, non-UUID `id`, row already carrying the key, `metric_score_results` entity not registered; verify one unit test per condition asserts the page is returned untouched
- [ ] 4.5 Add functional coverage for extended rows end to end: a scored run carries the value, an unscored run omits the key, and a run whose *latest* computation has no overall row omits it rather than showing an earlier computation's value
- [ ] 4.6 Add the cross-path invariant test: a run with two computations sharing `computed_at_ms` returns `metric_names` and `overall_score_value` from the same computation
- [ ] 4.7 Investigate whether any path can persist a non-finite `value` (`NaN`/`±Infinity`) for the overall row — `MetricScoreComputationExecutor` gates on `value != null` but not on finiteness, and Jackson would serialize `NaN` as the string `"NaN"` where the FE expects a number. Verify by attempting to produce one through a `CustomFunction`; add a guard only if reachable, and record the finding either way in design.md

## 5. Tests that must change for the open key set

- [ ] 5.1 Move `TestSuiteRunStructuredQueryFunctionalTests.emptySelectProjectsExactlyTheEntityFields` (:149) from exact key equality to containment of the 21 queryable fields; verify it still fails if a queryable field goes missing (it currently passes only because its fixture run has no eval summaries — design D10)
- [ ] 5.2 Fix `QuerySchemaDiscoveryFunctionalTests.shouldMatchTestSuiteRunsSchemaToExecutor` (:291): pin the query to the run the test creates **and** drop the exact-equality assertion. Do not add a sort — that hides the shared-database coupling without removing it (design D10). Verify it passes when run as part of the full `PostgresFunctionalTests` suite, not just in isolation

## 6. API surface and docs

- [ ] 6.1 Add the OpenAPI description of `overall_score_value` to `StructuredQueryController` / `StructuredQueryResultDto` — it is the key's only contract, since it appears in no schema; verify the text shows in `/v3/api-docs`
- [ ] 6.2 Write `docs/patterns/query-result-page-extension.md` (the seam, the open key set, the contract, why the value cannot be an entity field) and add its row to the AGENTS.md Unique Patterns table and `docs/patterns/README.md`; verify all three links resolve
- [ ] 6.3 Update `docs/patterns/{computation-versioning,test-suite-runs-query-entity,query-dsl-entity-resolution}.md` for the tiebreak convergence — `computation-versioning.md` currently documents `DESC` as the rule; verify no doc still states the greatest `computation_id` wins
- [ ] 6.4 Update AGENTS.md per AGENTS.md Maintenance guidelines (done: relevant sections reflect the change — new cross-cutting component pattern)
- [ ] 6.5 Update openspec/config.yaml per Config Maintenance Policy (done: relevant sections updated — the post-execution extension seam is a new project-wide pattern)
- [ ] 6.6 Update openspec/specs/README.md per Spec Index Maintenance Policy (done: index reflects current specs — new `query-result-page-extension` folder)

## 7. Verification

- [ ] 7.1 Run `./gradlew spotlessApply` then `./gradlew checkstyleMain checkstyleTest`; verify both pass with no manual reformatting
- [ ] 7.2 Run `./gradlew clean build` and verify the full suite passes, including `LayeredArchitectureTest`, `LoggingConventionTest` (the per-extender catch must pass the exception as the last SLF4J argument) and `JooqSchemaDriftTest`
- [ ] 7.3 Run `openspec validate "enrich-test-suite-runs-overall-score" --strict` and verify it reports the change valid before archiving
