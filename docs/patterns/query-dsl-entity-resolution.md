# Query DSL entity resolution

`query.service.repository.StructuredQueryEntityResolver` is the single SPI every queryable entity implements: `entity()`, `dsl()`, `table()`, `bindings(StructuredQuery)`, `default rewrite(StructuredQuery)` (identity).

`StructuredQueryEntityRegistry` collects all resolver beans at startup into a `Map<String, resolver>` (one per entity, gated by `@ConditionalOnProperty` on datasource vendor); `require(entity)` is the single unknown-entity 400 check, used by `StructuredQueryBuilder`/`StructuredQueryExecutor`/`StructuredQueryService` alike.

`StructuredQueryBuilder.build`/`countRows` take only a `StructuredQuery` and resolve `dsl`/`table`/`bindings` from `entityRegistry.require(query.entity())` — no caller passes them in.

`test_suites`/`eval_summaries`/`metric_score_results` compute their (static, per-table) bindings once in their resolver's constructor; `metric_score_results`' resolver overrides `rewrite` to delegate to the unchanged `MetricScoreLatestComputationDefaulter`.

There is no per-`Table` bindings cache anymore — each non-instance-aware resolver's bindings are just a plain field.
