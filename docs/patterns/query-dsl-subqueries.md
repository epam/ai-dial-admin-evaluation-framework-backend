# Query DSL subquery-valued `in`, and anywhere else an expression is valid

The `in` predicate's right operand may be an `array` of literals **or** a `subquery` (`SubqueryExpr`, wire `{"type":"subquery","query":{…}}`); a `subquery` may also appear as any other comparison's operand, a `select` projection, or a function argument.

`StructuredQueryBuilder.compileSubqueryMembership(SubqueryExpr)` builds and wraps the nested query (`build(inner)` — a plain self-call, resolving `inner`'s own entity via the registry — then a derived-table wrap selecting the **first** column as the membership key, e.g. `left IN (SELECT firstCol FROM (<subquery>) …)`; extra columns may drive the inner query's own `ORDER BY`/`LIMIT`, e.g. `max(computed_at_ms)`).

`FilterTranslator`'s `in` handling and `ExprTranslator.toField`'s `SubqueryExpr` case (which wraps the same compiled select as a scalar `Field` via `DSL.field(...)`) both reach it through **`ExprTranslator`'s lazy `ObjectProvider<StructuredQueryBuilder>`** — the only lazy-bean reference in the pipeline, breaking the `StructuredQueryBuilder → FilterTranslator/ExprTranslator` constructor cycle. `FilterTranslator` itself has **no dependency on the builder** and no signature change from this — it just calls `exprTranslator.compileSubqueryMembership(subquery)`.

No same-entity check: a subquery may target any registered entity; if it lives on a different datasource than the enclosing query, the nested SQL fails at the database with a normal grammar error, mapped to 400 like any other DB-level type/grammar mismatch — not a structural validation rule.

`QueryParameterResolver` recurses into `SubqueryExpr`.
