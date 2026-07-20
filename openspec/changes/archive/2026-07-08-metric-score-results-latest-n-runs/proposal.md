## Why

The Phase-3 metric-score table `metric_score_result` records only `test_suite_run_id` and no timestamp, so the Query DSL cannot answer "give me the latest N score aggregations for a suite": there is no suite-level scope key and no column to order "latest" by. Denormalizing `test_suite_id` and adding `computed_at_ms` lets the generic query engine express `filter test_suite_id eq X, ORDER BY computed_at_ms DESC LIMIT N` with no join and no new endpoint.

## What Changes

- Add two columns to the analytics `metric_score_result` table (Flyway `V1.12`):
  - `test_suite_id VARCHAR(36)` — suite scope key (denormalized from the run).
  - `computed_at_ms BIGINT` — epoch-millis compute timestamp, matching `run_metric_snapshots.computed_at_ms` and the project-wide epoch-millis `Long` convention.
- Backfill both columns for existing rows entirely within the analytics DB (`computed_at_ms` from `run_metric_snapshots` on `(test_suite_run_id, computation_id)`; `test_suite_id` from `test_case_eval_summaries` on `test_suite_run_id`), then set both `NOT NULL`.
- Add index `(test_suite_id, computed_at_ms)` to support suite-scoped latest-N ordering.
- Regenerate jOOQ sources and thread the two fields through the model, RecordMapper, and repository insert.
- Populate the new fields on the write path: `MetricScoreComputationExecutor` sets `test_suite_id` from `MetricScoreComputationContext.testSuiteId` (already present) and `computed_at_ms` from an injected `Clock` (one timestamp per computation).
- The `metric_score_results` Query-DSL entity gains the two fields **automatically** (schema derived from the jOOQ table), so results can be scoped by suite and ordered by compute time.
- **Extend the structured-query DSL with a subquery-valued `in`** so "all metric score results for the latest N runs of a suite" is answerable in a **single** `POST /api/v1/queries/execute` request. A new `subquery` expression kind may appear as the right operand of `in`; the translator compiles it to a nested `SELECT` (`<field> IN (SELECT …)`) — one SQL statement. Scope-limited MVP: **same-entity** subqueries only (the subquery reuses the enclosing table + bindings), membership = the subquery's **first** select column. This lifts the prior limitation that `in` accepted only a literal array (which forced a two-step client flow).

## Capabilities

### New Capabilities
<!-- None. This is an additive schema/persistence change to an existing capability. -->

### Modified Capabilities
- `metric-score-statistics`: the persistence requirement changes — `metric_score_result` now carries `test_suite_id` and `computed_at_ms` (backfilled, `NOT NULL`), and both are exposed as queryable fields on the `metric_score_results` entity so results can be scoped by suite and ordered by compute time. Natural-key uniqueness and the code-defined statistics are unchanged.
- `structured-query-model`: the expression grammar gains a `subquery` kind, and the `in` predicate accepts a `subquery` right operand (in addition to `array`). The translator compiles a same-entity subquery to a nested `IN (SELECT …)`, membership = its first select column. This is the DSL feature that makes single-request "latest N runs" possible.

## Impact

- **Schema (analytics DB)**: new migration `V1.12__AddSuiteAndTimestampToMetricScoreResult.sql`; regenerated jOOQ sources under `src/main/java-generated/.../analytics/tables/`. `docs/database-schema.md` updated for the `metric_score_result` entry.
- **Code**: `data/db/analytics/model/MetricScoreResult`, `data/db/analytics/mapper/MetricScoreResultRecordMapper`, `data/db/analytics/repository/PostgresMetricScoreResultRepository`, `experimental/query/service/metricscore/MetricScoreComputationExecutor` (adds `Clock` dependency).
- **Query DSL**: `metric_score_results` entity schema (via `MetricScoreResultSchemaProvider` → `JooqTableSchemaResolver`) gains `test_suite_id` (UUID) and `computed_at_ms` (LONG) — no provider change required.
- **Query DSL feature**: new `SubqueryExpr` in `experimental.query.model`; `FilterTranslator` compiles a subquery `in` operand to a nested `SELECT` (derived-table wrap on the first column), reusing the enclosing `dsl`/`table`/`bindings` via a `TranslationContext` and a lazy `ObjectProvider<StructuredQueryBuilder>` (breaks the builder↔translator cycle); `QueryParameterResolver` recurses into subqueries; `ExprTranslator` keeps a defensive reject for a surviving subquery. OpenAPI docs updated. No new endpoint — the existing `POST /api/v1/queries/execute` gains the capability.
- **Data**: existing rows are backfilled; scores are append-only and regenerable, so the migration deletes any un-backfillable orphan rows (should be none) before `SET NOT NULL`.
