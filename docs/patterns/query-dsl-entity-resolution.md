# Query DSL entity resolution

`query.service.repository.StructuredQueryEntityResolver` is the single SPI every queryable entity implements: `entity()`, `dsl()`, `table()`, `bindings(StructuredQuery)`, `default rewrite(StructuredQuery)` (identity).

`StructuredQueryEntityRegistry` collects all resolver beans at startup into a `Map<String, resolver>` (one per entity, gated by `@ConditionalOnProperty` on datasource vendor); `require(entity)` is the single unknown-entity 400 check, used by `StructuredQueryBuilder`/`StructuredQueryExecutor`/`StructuredQueryService` alike.

`StructuredQueryBuilder.build`/`countRows` take only a `StructuredQuery` and resolve `dsl`/`table`/`bindings` from `entityRegistry.require(query.entity())` — no caller passes them in.

`test_suites`/`eval_summaries`/`metric_score_results` compute their (static, per-table) bindings once in their resolver's constructor; `metric_score_results`' resolver overrides `rewrite` to delegate to the unchanged `MetricScoreLatestComputationDefaulter`.

`PostgresEvalSummaryEntityResolver.table()` is not a bare generated table — it's `TEST_CASE_EVAL_SUMMARIES` LEFT JOINed to a **narrowed** derived-table projection of `TEST_CASE_EVAL_SCORES` (`eval_summary_id`/`score`/`passed` only, via `DSL.select(...).asTable(...)`), so `score`/`passed` are queryable/groupable like any other field. The projection deliberately excludes `test_case_eval_scores.computed_at_ms`: joining the raw generated table would make it collide with `test_case_eval_summaries`'s own `computed_at_ms` column, breaking `buildRow`'s `select.addSelect(List.of(table.fields()))` fallback (a row-mode query with no explicit `select`) with an ambiguous-column error. The join column is `test_case_eval_scores`'s primary key, so Postgres eliminates the join entirely for queries that reference neither field — no cost added to `execution_status`-style queries that predate this.

`EvalSummariesSchemaProvider` (the `eval_summaries` entity's schema-*discovery* endpoint, a separate SPI from this resolver) explicitly appends `score`/`passed` to its `baseSchema` in its constructor — `schemaResolver.resolve(TEST_CASE_EVAL_SUMMARIES)` alone can't derive them, since they're not columns of that generated table. Kept in sync manually with this resolver's `bindings()`; the detailed schema keeps both as-is (neither is in `FLATTENABLE_JSONB_FIELDS`), same as any other plain column.

There is no per-`Table` bindings cache anymore — each non-instance-aware resolver's bindings are just a plain field.
