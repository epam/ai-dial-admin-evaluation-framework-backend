# Query DSL function catalog is registry-driven

DSL functions are NOT a hardcoded switch. Each is a `QueryFunction` bean (SPI in `experimental.query.service.translate.function`) collected by `QueryFunctionRegistry`; `ExprTranslator` delegates to the registry. To add a function, drop in a new `@Component QueryFunction` (or a `@Bean` in `BuiltInQueryFunctions`) — no translator/registry edits. Built-ins live in `BuiltInQueryFunctions`. Duplicate names are rejected at startup; unknown names → `ValidationException` (400).

## Delegating to a stored Postgres function

Most built-ins wrap a jOOQ built-in directly, but a function's `Field<?>` result can also delegate to a **custom Postgres stored function** for computations `FunctionContext`'s single-`Field`-per-call contract can't express as pure jOOQ (e.g. multi-row ranking): `roc_auc(label, probability)` aggregates both columns via `DSL.arrayAgg(...)` and calls the stored function `roc_auc_score(double precision[], double precision[])` (analytics DB, `V1.11__CreateRocAucScoreFunction.sql`) via `DSL.function(...)` — usable anywhere a `FnExpr` is valid, with no changes to `StructuredQueryBuilder`/`StructuredQueryExecutor`.

## Arithmetic

`add`/`multiply` (n-ary, ≥1 arg, left-folded) and `subtract`/`divide` (binary only, exactly 2 args) are further `BigDecimal`-cast `Field` arithmetic built-ins in the same catalog.

## No `mean`/`weighted_mean` function

There is deliberately **no** `mean`/`weighted_mean` DSL function — a suite's `overallScore` mean/weighted-mean composition (`divide(add(coalesce(avg(f1), 0), coalesce(avg(f2), 0), ...), n)` / `divide(add(multiply(w1, coalesce(avg(m1), 0)), ...), add(w1, ...))`) is built server-side by `OverallScoreDefinitionResolver` from these primitives, using the general-purpose `coalesce(value, default)` built-in (`DSL.coalesce`) to turn a missing metric's `NULL` average into `0` for that term (see [Typed `OverallScoreDefinition`](overall-score-definition.md)), never expressed as DSL JSON by a caller.
