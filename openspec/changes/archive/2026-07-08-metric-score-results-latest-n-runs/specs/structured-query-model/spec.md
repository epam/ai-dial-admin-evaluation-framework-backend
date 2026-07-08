## MODIFIED Requirements

### Requirement: Expression grammar
The system SHALL model expressions as a sealed `Expr` hierarchy discriminated by the `type` key
with six kinds: `field` (column reference), `value` (literal whose `value` is always a JSON string
governed by `value_type`), `param` (runtime parameter), `fn` (function call with a nestable
expression `args` list), `array` (collection whose items use the key `items`), and `subquery` (a
nested `StructuredQuery` under the `query` key, valid only as the right operand of `in` — see the
`in` predicate requirement). `value_type` SHALL be a closed enum: `string`, `integer`, `long`,
`decimal`, `boolean`, `date`, `timestamp`, `uuid`, `null`.
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
(`<left> IN (SELECT …)`) during translation — one SQL statement. The subquery SHALL target the
**same entity** as the enclosing query (so it reuses the enclosing table and field bindings); a
different entity SHALL be rejected (HTTP 400). The subquery's **first** select column SHALL be the
membership key projected into the `IN` (the built select is wrapped in a derived table selecting that
first column), so the subquery may additionally select aggregates purely to drive its own
`ORDER BY`/`LIMIT` (e.g. `max(computed_at_ms)` to take the latest N groups). An `in` subquery that
matches no rows SHALL cause the enclosing query to return no rows (nested `IN` over an empty set is
false). A `subquery` operand SHALL be valid **only** as the right operand of `in`; anywhere else it
SHALL be rejected (HTTP 400).
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

#### Scenario: Cross-entity subquery is rejected
- **WHEN** an `in` subquery targets a different entity than the enclosing query
- **THEN** the request is rejected with a validation error

#### Scenario: Subquery outside `in` is rejected
- **WHEN** a `subquery` expression appears anywhere other than the right operand of an `in` predicate
- **THEN** the request is rejected with a validation error
