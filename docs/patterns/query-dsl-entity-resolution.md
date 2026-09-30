# Query DSL entity resolution

`query.service.repository.StructuredQueryEntityResolver` is the single SPI every queryable entity implements: `entity()`, `dsl()`, `table()`, `bindings(StructuredQuery)`, `default rewrite(StructuredQuery)` (identity).

`StructuredQueryEntityRegistry` collects all resolver beans at startup into a `Map<String, resolver>` (one per entity, gated by `@ConditionalOnProperty` on datasource vendor); `require(entity)` is the single unknown-entity 400 check, used by `StructuredQueryBuilder`/`StructuredQueryExecutor`/`StructuredQueryService` alike.

`StructuredQueryBuilder.build`/`countRows` take only a `StructuredQuery` and resolve `dsl`/`table`/`bindings` from `entityRegistry.require(query.entity())` — no caller passes them in.

`test_suites`/`eval_summaries`/`metric_score_results` compute their (static, per-table) bindings once in their resolver's constructor; `metric_score_results`' resolver overrides `rewrite` to delegate to the unchanged `MetricScoreLatestComputationDefaulter`, which resolves the `computation_id eq "latest"` sentinel via `ComputationResolver` — same-millisecond ties break on the smallest `computation_id`, the system-wide convention described in [Computation Versioning](computation-versioning.md).

`PostgresEvalSummaryEntityResolver.table()` is the bare `TEST_CASE_EVAL_SUMMARIES` table; `score`/`passed` are read from the `test_case_eval_scores` entity instead.

`EvalSummariesSchemaProvider` (the `eval_summaries` entity's schema-*discovery* endpoint, a separate SPI from this resolver) derives its `baseSchema` solely from `schemaResolver.resolve(TEST_CASE_EVAL_SUMMARIES)`, so it does not advertise `score`/`passed` — matching this resolver's `bindings()`, which no longer accepts them (read them from the `test_case_eval_scores` entity).

There is no per-`Table` bindings cache anymore — each non-instance-aware resolver's bindings are just a plain field.

`PostgresTestSuiteRunEntityResolver.table()` for `test_suite_runs` is a third shape: a **fully derived table**, not a bare table (`test_suites`) or a bare-table-LEFT-JOIN-derived (`eval_summaries`). It projects only `DSL.select(...).from(TEST_SUITE_RUNS).asTable("tsr")` — no join — so that the row-mode empty-`select` field enumeration (`table().fields()`) omits `test_suite_runs`' heavy/opaque `suite_snapshot`/`run_config`/`error_details` columns entirely, with no builder-side filtering. Postgres pulls the derived table up into the outer query when nothing at its top level blocks it (no aggregate/`LIMIT`/`DISTINCT`), so plain-column filters and sorts still hit `test_suite_runs`' own indexes; see [`test_suite_runs` query entity](test-suite-runs-query-entity.md) for the EXPLAIN evidence and the correlated-scalar-subquery `metric_names` field this shape enables.

`PostgresTestCaseEvalScoreEntityResolver.table()` for `test_case_eval_scores` is a derived-table projection of `TEST_CASE_EVAL_SCORES` (run/test case/computation ids, name, execution status, score, passed, computed_at_ms). No dedup is needed: a unique constraint on `(test_suite_run_id, test_case_id, computation_id)` guarantees one row per test case per computation. Reuses `MetricScoreLatestComputationDefaulter` for `computation_id eq "latest"` the same way `metric_score_results` does.
