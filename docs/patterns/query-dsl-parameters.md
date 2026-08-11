# Query DSL `ParamExpr`

`param` expressions ARE supported (do not re-reject them), resolved by a **single pre-pass** (`QueryParameterResolver` in `experimental.query.service.translate`) that rewrites a `StructuredQuery` into a parameter-free copy *before* translation — substituting each `ParamExpr` with its bound `Expr` recursively (unbound → 400; param-to-param → 400; cyclic chain → 400).

The resolver is invoked once at the `StructuredQueryService.execute(query, params)` entry; the translator/builder/executor/resolver are **parameter-agnostic** (do NOT re-introduce a `Map<String,Expr> params` argument threaded through them — that was the prior shape and was deliberately removed). `ExprTranslator` keeps a defensive `case ParamExpr → 400` since a surviving param means it was unbound.

Internal callers use `StructuredQueryService.execute(query, params)`; the public `POST /api/v1/queries/execute` stays **paramless** by design (empty map → resolver is a no-op → any `param` is rejected by the translator guard → 400).

The `metric-score-statistics` Phase-3 computation uses this param path (`StructuredQueryService.execute(query, params)`) to run the built-in statistic queries. Those queries (AVG/P10/P90/MIN/MAX and the default `overall`) are **code-defined** as typed `StructuredQuery` objects in `BuiltInMetricStatistics`.

Because the executor depends on `experimental.query.service`, it **lives in** `experimental.query.service.metricscore` (implementing the `MetricScoreComputation` interface declared in the stable `service.domain.job` layer); `TestSuiteEvaluationJob` triggers Phase 3 through that interface, so there is **no** `service → experimental.query.service` bytecode edge and `LayeredArchitectureTest` stays unmodified.

**When wiring a stable-layer trigger to experimental code, invert via a `service`-layer interface — do not relax the layering test.**
