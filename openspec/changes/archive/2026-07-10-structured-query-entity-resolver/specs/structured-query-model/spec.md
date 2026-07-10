## MODIFIED Requirements

### Requirement: Expression grammar
The system SHALL model expressions as a sealed `Expr` hierarchy discriminated by the `type` key
with six kinds: `field` (column reference), `value` (literal whose `value` is always a JSON string
governed by `value_type`), `param` (runtime parameter), `fn` (function call with a nestable
expression `args` list), `array` (collection whose items use the key `items`), and `subquery` (a
nested `StructuredQuery` under the `query` key — usable anywhere any other expression is, subject to
the constraints in the `in` predicate requirement and elsewhere). `value_type` SHALL be a closed enum:
`string`, `integer`, `long`, `decimal`, `boolean`, `date`, `timestamp`, `uuid`, `null`.
Status: **Implemented**

#### Scenario: Nested function expression binds
- **WHEN** `round(div(sum(accuracy_score), count()), 2)` is deserialized as an expression
- **THEN** the nested `fn` records bind recursively, `count` binds with an empty args list, and the
  trailing integer literal binds as a value expression

#### Scenario: Null literal binds
- **WHEN** a `value` expression with `value_type: "null"` and `value: null` is deserialized
- **THEN** it binds to a value expression carrying the `NULL` value-type and a null value

#### Scenario: Array expression binds with items key
- **WHEN** an `array` expression `{ "type": "array", "items": [ <value>, <value> ] }` is
  deserialized
- **THEN** it binds to the array record using the `items` key, distinct from a function's `args`

#### Scenario: Subquery expression binds with query key
- **WHEN** a `subquery` expression `{ "type": "subquery", "query": { <StructuredQuery> } }` is
  deserialized
- **THEN** it binds to the subquery record carrying a nested `StructuredQuery` under the `query` key

### Requirement: `in` predicate with array or subquery operand
The system SHALL treat `in` as an ordinary binary predicate whose right operand is either an
`array` expression (set membership over literals) or a `subquery` expression (set membership over a
nested query's result), without structurally encoding "left scalar / right set" — that constraint
is deferred to validation.

When the right operand is a `subquery`, the system SHALL compile it to a nested `SELECT`
(`<left> IN (SELECT …)`) during translation — one SQL statement. The subquery's **first** select
column SHALL be the membership key projected into the `IN` (the built select is wrapped in a derived
table selecting that first column), so the subquery may additionally select aggregates purely to
drive its own `ORDER BY`/`LIMIT` (e.g. `max(computed_at_ms)` to take the latest N groups). An `in`
subquery that matches no rows SHALL cause the enclosing query to return no rows (nested `IN` over an
empty set is false). The subquery MAY target a different entity than the enclosing query; if the two
entities live on different datasources, the resulting nested SQL is rejected by the database itself
(surfaced as HTTP 400 through the system's existing SQL-error mapping) rather than by a structural
same-entity check.
Status: **Implemented**

#### Scenario: Set membership binds
- **WHEN** `execution_status IN ('SUCCESS', 'PARTIAL')` is deserialized
- **THEN** the predicate's `args` hold a field expression on the left and an array expression of
  two value literals on the right

#### Scenario: Subquery membership resolves the latest N groups in one request
- **WHEN** a `metric_score_results` row query filters `test_suite_run_id in (<subquery>)` where the
  subquery is a same-entity aggregate selecting `test_suite_run_id` (first) and `max(computed_at_ms)`,
  grouped by `test_suite_run_id`, ordered by that aggregate descending with `limit: 2`
- **THEN** the query compiles to `… WHERE test_suite_run_id IN (SELECT …)` and returns all rows whose
  `test_suite_run_id` is one of the 2 most-recently computed runs — in a single statement

#### Scenario: Cross-datasource subquery is rejected by the database
- **WHEN** an `in` subquery targets an entity on a different datasource than the enclosing query
- **THEN** the request is rejected with HTTP 400, surfaced through the same SQL-error-to-validation
  mapping used for any other database grammar/type error

#### Scenario: Subquery used as a scalar comparison operand
- **WHEN** a `subquery` expression appears as the operand of a non-`in` comparison (e.g.
  `computed_at_ms eq (subquery)`), a `select` projection, or a function argument
- **THEN** it compiles to a scalar value derived from the subquery's first selected column, usable
  anywhere any other expression is valid

## ADDED Requirements

### Requirement: Query entity resolution is centralized per entity
The system SHALL resolve, for each queryable entity, its datasource, its backing table, and its field
bindings through a single per-entity component, keyed by the entity's wire name and consulted purely
from the entity name carried on the query (or a nested subquery's own entity name) — not through a
collection of independent, per-entity request-handling classes. An entity whose field bindings depend
on the specific query being executed (rather than being fixed for the entity as a whole) SHALL resolve
those bindings from the query itself, using the same per-entity resolution mechanism as every other
entity.
Status: **Implemented**

#### Scenario: Unknown entity is rejected once, uniformly
- **WHEN** a structured query names an entity with no registered resolver
- **THEN** the request is rejected with HTTP 400, naming the supported entities

#### Scenario: Instance-aware entity resolves bindings from the query
- **WHEN** a query against an entity whose field typing depends on request content (e.g. `test_cases`,
  whose flattened fields are scoped by the query's own `dataset_id` filter) is executed
- **THEN** that entity's field bindings are derived from the query being executed, using the same
  resolution mechanism every other entity uses, not a separate execution path

## Implementation notes

- Execution endpoint + dispatch: `experimental.query.web.StructuredQueryController`,
  `experimental.query.service.StructuredQueryService`, `…service.repository.StructuredQueryEntityResolver`
  (SPI) + `…service.repository.StructuredQueryEntityRegistry` (+ `PostgresTestSuiteEntityResolver` on
  meta DSL, `PostgresEvalSummaryEntityResolver` on analytics DSL, `PostgresMetricScoreResultEntityResolver`
  on analytics DSL, `PostgresTestCaseEntityResolver` on meta DSL, all `@ConditionalOnProperty`).
- Execution + translation: `StructuredQueryExecutor`, `QueryResultPage`, and
  `…service.translate.{StructuredQueryBuilder, FilterTranslator, ExprTranslator, JsonbFieldResolver,
  ValueExprToObjectMapper}`. `ExprTranslator` holds the sole `ObjectProvider<StructuredQueryBuilder>` in
  the pipeline (lazy, breaks the `StructuredQueryBuilder → FilterTranslator/ExprTranslator` constructor
  cycle); `StructuredQueryBuilder.compileSubqueryMembership(SubqueryExpr)` builds and wraps a subquery's
  nested select, reached from `ExprTranslator` and (via `ExprTranslator`) from `FilterTranslator`'s `in`
  handling. Response: `…service.dto.StructuredQueryResultDto`; JSONB row parsing via
  `experimental.query.web.JsonbRowConverter`.
