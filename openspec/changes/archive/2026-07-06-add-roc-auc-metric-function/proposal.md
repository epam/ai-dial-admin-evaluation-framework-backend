## Why

Suite authors need to score binary classifiers with the ROC AUC metric (probability a random positive ranks above a random negative), computed from a dataset ground-truth column (`y`, 0/1) and a metric-produced probability column (`p`, 0-1), aggregated over every test case in a run. The Query DSL's function catalog (`QueryFunction` SPI, `structured-query-model`) is the existing extension point for this kind of aggregate, and the suite's custom `overallScore` expression (`metric-score-statistics`) is the existing mechanism for running an arbitrary aggregate formula per run — but the DSL has no ranking-capable function today, and its `QueryFunction` contract can only emit a single flat-`SELECT`-embeddable jOOQ `Field<?>`, so a naive window-function (`ROW_NUMBER`/`RANK`) translation isn't expressible without introducing CTE/subquery support the executor doesn't have.

## What Changes

- Add a new Postgres analytics-DB stored function `roc_auc_score(y double precision[], p double precision[]) RETURNS double precision` (Flyway migration `V1.11__CreateRocAucScoreFunction.sql`) that computes the rank-sum/Mann–Whitney ROC AUC formula over paired arrays, returning `NULL` when either class is absent (`n_pos = 0 OR n_neg = 0`).
- Add a new `QueryFunction` implementation, `roc_auc`, registered in the existing registry-driven function catalog (`experimental.query.service.translate.function`). It is a 2-arg aggregate function: `roc_auc(labelField, probabilityField)` translates to `roc_auc_score(array_agg(labelField), array_agg(probabilityField))`, requiring no changes to `StructuredQueryBuilder`, `StructuredQueryExecutor`, or `FunctionContext` — it fits the existing single-`SELECT` aggregate-mode shape the same way `percentile_cont`/`width_bucket` do.
- No new config surface: `roc_auc` is usable today wherever a `FnExpr` is valid, in particular in a suite's existing custom `overallScore` structured-query expression, referencing any real dataset field (`data::<field>`) and any real registered metric output (`metric:<metricName>:<outputField>`) — exactly like any other two-argument function call.
- Update `docs/database-schema.md` for the new migration and `AGENTS.md`'s Query DSL function catalog description to mention `roc_auc` as a stored-function-backed aggregate.

## Capabilities

### New Capabilities
(none — this extends the existing function catalog rather than introducing a new domain capability)

### Modified Capabilities
- `structured-query-model`: the Supported function catalog gains a new entry, `roc_auc(labelField, probabilityField)`, a 2-arg aggregate function that delegates to a Postgres stored function (`roc_auc_score`) via `array_agg`-wrapped arguments — the first catalog entry backed by a custom SQL function rather than a jOOQ built-in.

## Impact

- **Database**: new analytics-DB Flyway migration `V1.11__CreateRocAucScoreFunction.sql` defining `roc_auc_score(double precision[], double precision[])` as a `LANGUAGE sql` function. No table/index changes.
- **Code**: new `QueryFunction` bean (e.g. `RocAucFunction`) in `experimental.query.service.translate.function`, registered alongside `BuiltInQueryFunctions`. No changes to `MetricScoreComputationExecutor`, `TestSuiteEvaluationJob`, `MetricScoreResult`/`metric_score_result` persistence, or suite validation — the existing custom-overall execution path (`metric-score-statistics`) picks up any valid `FnExpr`, including `roc_auc`, unmodified.
- **Docs**: `docs/database-schema.md` (new migration) and `AGENTS.md` inline Query DSL conventions (new catalog entry) must be updated in this change.
- **Risk**: introduces the codebase's first custom Postgres stored function invoked via jOOQ's generic `DSL.function(...)` and first use of `array_agg` — new but self-contained patterns; scoped to one function, no impact on existing catalog entries or query modes.
