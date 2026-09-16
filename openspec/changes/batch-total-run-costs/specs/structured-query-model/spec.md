## MODIFIED Requirements

### Requirement: Expression grammar
The system SHALL model expressions as a sealed `Expr` hierarchy discriminated by the `type` key
with seven kinds: `field` (column reference), `value` (literal whose `value` is always a JSON string
governed by `value_type`), `param` (runtime parameter), `fn` (function call with a nestable
expression `args` list), `array` (collection whose items use the key `items`), `subquery` (a
nested `StructuredQuery` under the `query` key — usable anywhere any other expression is, subject to
the constraints in the `in` predicate requirement and elsewhere), and `case` (a conditional expression
carrying an ordered `when` list of `{when, then}` clauses — `when` a `FilterNode`, `then` an `Expr` —
and an `else` `Expr`, evaluated as a SQL-style `CASE WHEN ... THEN ... ELSE ... END`). `value_type`
SHALL be a closed enum: `string`, `integer`, `long`, `decimal`, `boolean`, `date`, `timestamp`, `uuid`,
`null`. The `case` kind SHALL be supported only for queries built directly against `dial_usage_log` by
`AdasCostQueryBuilder` (see `batch-run-costs`); it SHALL be rejected for every internal entity query
routed through `ExprTranslator`, and SHALL support `param` substitution inside its `when`/`then`/`else`
expressions via `QueryParameterResolver` on the same basis as every other expression kind.
Status: Planned.

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

#### Scenario: Case expression binds with when/then/else
- **WHEN** a `case` expression `{ "type": "case", "when": [{ "when": <FilterNode>, "then": <Expr> }, ...],
  "else": <Expr> }` is deserialized
- **THEN** it binds to the case record's `when` list (each clause's `when` binding as a `FilterNode`, its
  `then` as an `Expr`) and its `else` binding as an `Expr`

#### Scenario: Case expression is rejected for internal entity queries
- **WHEN** a `case` expression appears anywhere in a query submitted against an internal entity (e.g.
  `test_cases`, `eval_summaries`) — including via `POST /api/v1/queries/execute`
- **THEN** the request is rejected with HTTP 400, since `ExprTranslator` explicitly rejects `CaseExpr`
  for every entity it translates

#### Scenario: Param substitution reaches inside a case expression
- **WHEN** a `case` expression's `when` filter or `then`/`else` expression contains a `param` expression,
  and that query is resolved with a binding map
- **THEN** the parameter resolution pass rewrites the `param` occurrence with its bound expression in
  place, the same as it would for a `param` anywhere else in the query
