# Query DSL `ParamExpr`

`param` expressions ARE supported (do not re-reject them), resolved by a **single pre-pass** (`QueryParameterResolver` in `query.service.translate`) that rewrites a `StructuredQuery` into a parameter-free copy *before* translation — substituting each `ParamExpr` with its bound `Expr` recursively (unbound → 400; param-to-param → 400; cyclic chain → 400).

The resolver is invoked once at the `StructuredQueryService.execute(query, params)` entry; the translator/builder/executor/resolver are **parameter-agnostic** (do NOT re-introduce a `Map<String,Expr> params` argument threaded through them — that was the prior shape and was deliberately removed). `ExprTranslator` keeps a defensive `case ParamExpr → 400` since a surviving param means it was unbound.

Internal callers use `StructuredQueryService.execute(query, params)`; the public `POST /api/v1/queries/execute` stays **paramless** by design (empty map → resolver is a no-op → any `param` is rejected by the translator guard → 400).

The `metric-score-statistics` Phase-3 computation uses this param path (`StructuredQueryService.execute(query, params)`) to run the built-in statistic queries. Those queries (AVG/P10/P90/MIN/MAX and the default `overall`) are **code-defined** as typed `StructuredQuery` objects in `BuiltInMetricStatistics`.

Because the executor depends on `query.service`, it **lives in** `query.service.metricscore`, alongside the rest of the metric-score DSL consumers; `TestSuiteEvaluationJob` (in `service.domain.job`) injects `MetricScoreComputationExecutor` directly to trigger Phase 3. `LayeredArchitectureTest` folds `query.service.*` into the `service` layer, so this is an ordinary `service`-layer dependency like any other.
