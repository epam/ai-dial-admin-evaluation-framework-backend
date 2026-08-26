# Structured Query Model

## Purpose

The structured query model defines a single structured query contract — sent in a request body,
never as SQL — that all listable entities (meta and analytics) share, replacing the legacy
list-query DSL (`filter=`/`sort=`/`page`/`size`/`cursor` query params) and its structural limits:
URL-length caps on complex filters, no boolean composition (`OR`/`NOT`/nested groups), per-entity
hand-picked allowlists, two incompatible pagination models (meta=offset, analytics=cursor), no
aggregation, and no client-driven projection.

This capability formalizes the **request-side wire contract and its Java object model**: a
top-level envelope (`entity`, `filter`, `mode`, `select`, `group_by`, `aggregate`, `having`, `sort`,
`page`), a CQL2-JSON filter tree (`op`/`args`), an expression grammar and pagination shapes
(discriminated by `type`), and the Jackson binding contract (discriminators, `snake_case` keys,
`@JsonValue` enum wire codes). Validation/allowlist, SQL translation, and the response envelope are
also specified here and **Implemented** — body-delivered execution runs at
`POST /api/v1/queries/execute` — though the implemented validation and response shape are narrower
than the original vision (see those requirements for the precise scope).

## Requirements

### Requirement: Structured query envelope
The system SHALL provide an immutable request object model representing a single structured
query envelope with the fields `entity` (required), `filter`, `mode`, `select`, `group_by`,
`aggregate`, `having`, `sort`, and `page`, mirroring §1 of the wire contract. JSON keys SHALL be
`snake_case` (`group_by`, `include_total`, `value_type`, and the aggregate alias key `as`).
Status: **Implemented**

#### Scenario: Full row-mode envelope binds
- **WHEN** a row-mode JSON query with `entity`, `mode: "row"`, `filter`, `select`, `sort`, and an
  `offset` page is deserialized into the envelope record
- **THEN** every field binds to its typed component, `mode` resolves to `ROW`, and `select`/`sort`
  resolve to their element record types

#### Scenario: Full aggregate-mode envelope binds
- **WHEN** an aggregate-mode JSON query with `group_by`, `aggregate` (alias `as`), `having`, and a
  `cursor` page is deserialized
- **THEN** `mode` resolves to `AGGREGATE`, `group_by` binds to a string list, `aggregate` binds to
  `AggregateCall` records with their aliases, and `having` binds to a filter node

#### Scenario: Only entity is structurally required
- **WHEN** a JSON object carrying only `entity` is deserialized
- **THEN** the envelope binds with the remaining fields null/empty, leaving mode-coherence (§2) to
  the future validation layer

### Requirement: Filter sub-grammar (CQL2-JSON)
The system SHALL model a recursive filter tree as a sealed `FilterNode` hierarchy discriminated by
the `op` key: logical nodes (`and`, `or`, `not`) carrying a list of child nodes, and predicate
nodes carrying an operator code (`eq`, `ne`, `co`, `nc`, `lt`, `gt`, `le`, `ge`, `in`) and a
positional `args` list of expressions. Because `op` is both the discriminator and operator data,
routing SHALL be performed by a custom deserializer wired at each use site, not by a declarative
`@JsonTypeInfo` on the interface.
Status: **Implemented**

#### Scenario: Nested boolean tree binds
- **WHEN** a filter `(execution_status = 'SUCCESS' AND accuracy_score > 0.8) OR (...)` is
  deserialized
- **THEN** the root binds to a logical `OR` node whose children are logical `AND` nodes containing
  predicate nodes with their operator codes and operand expressions

#### Scenario: Both operands are general expressions
- **WHEN** a predicate `length(test_suite_id) = 3` is deserialized
- **THEN** the predicate's `args` hold a function expression on the left and a value expression on
  the right, with no requirement that either operand be a bare column

#### Scenario: Logical-vs-predicate routing by op
- **WHEN** a node's `op` is `and`/`or`/`not`
- **THEN** it routes to the logical node type; otherwise it routes to the predicate node type

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

### Requirement: Aggregation, sort, and pagination request shapes
The system SHALL model an aggregate call (`fn`, expression `args`, optional `distinct`, required
output alias bound from the `as` key), a sort item (`field`, `dir` ∈ `asc`/`desc`), and a sealed
`PageSpec` hierarchy discriminated by `type` with an offset strategy (`offset`, `limit`,
`include_total`) and a cursor strategy (`cursor`, `limit`). The pagination type SHALL be renamed
from the wire name `Page` to avoid collision with the existing `data.db.model.pagination.Page<T>`.
Status: **Implemented**

#### Scenario: Offset page binds
- **WHEN** `{ "type": "offset", "offset": 0, "limit": 25, "include_total": true }` is deserialized
- **THEN** it binds to the offset page record with `include_total` mapped from the snake_case key

#### Scenario: Cursor page binds with absent cursor
- **WHEN** `{ "type": "cursor", "limit": 100 }` (no `cursor`) is deserialized
- **THEN** it binds to the cursor page record with a null cursor (first page)

#### Scenario: Aggregate alias binds from `as`
- **WHEN** `{ "fn": "count", "args": [], "as": "total_cases" }` is deserialized
- **THEN** the alias component binds from the `as` key

### Requirement: Stable wire serialization
The system SHALL serialize the request model back to JSON that round-trips: deserialize → serialize
→ deserialize SHALL produce an equal object, preserving the `op`/`type` discriminators, the
`snake_case` keys, and the lowercase enum wire codes (e.g. `eq`, `and`, `row`, `asc`, `string`,
`field`, `array`).
Status: **Implemented**

#### Scenario: Round-trip equality
- **WHEN** an envelope containing an `in` predicate with an array operand and an offset page is
  deserialized, re-serialized, and deserialized again
- **THEN** the second object equals the first and the serialized form contains the expected
  discriminator and snake_case keys

### Requirement: Query execution endpoint
The system SHALL execute a structured query submitted as a request body at
`POST /api/v1/queries/execute` and return its results. Execution SHALL be entity-agnostic: the request
SHALL be routed by its `entity` to the matching entity resolver, and a query naming an entity
that has no registered resolver SHALL be rejected with a validation error (HTTP 400) that names the
supported entities. Each entity SHALL be executed against its own datasource (meta for `test_suites`,
analytics for `eval_summaries`), and the query SHALL be translated to parameterized SQL — never raw
SQL text — before execution.
Status: **Implemented**

#### Scenario: Row-mode query executes and returns rows
- **WHEN** a valid row-mode query for a supported entity is posted to `/api/v1/queries/execute`
- **THEN** the response carries the projected rows, with any JSONB-backed columns parsed back to nested
  JSON

#### Scenario: Unknown entity is rejected
- **WHEN** a query naming an entity with no registered resolver is posted
- **THEN** the request is rejected with HTTP 400 and the error names the supported entities

### Requirement: Supported function catalog
The system SHALL accept in a structured query's expressions only functions from a closed catalog, and
SHALL reject any other function name with HTTP 400. Each catalog entry SHALL define the function's
group (scalar, aggregate, ordered-set aggregate, reduction, or arithmetic), arity, operand types, and
return type. The catalog SHALL be **registry-driven**: each function is contributed as a separate
component (`QueryFunction`) collected by name at startup, so the set of supported functions is
extended by adding a component rather than editing a central switch; duplicate names SHALL be
rejected at startup. The catalog is:

| Function | Group | Arity / signature | Returns |
|---|---|---|---|
| `lower` | scalar | `lower(text)` | text |
| `upper` | scalar | `upper(text)` | text |
| `length` | scalar | `length(text)` | integer |
| `trim` | scalar | `trim(text)` | text |
| `abs` | scalar | `abs(numeric)` | numeric |
| `width_bucket` | scalar | `width_bucket(operand, low, high, count)` (4 args) | integer (bucket) |
| `count` | aggregate | `count()` or `count(col)`; `distinct` allowed | long |
| `sum` | aggregate | `sum(numeric)`; `distinct` allowed | numeric |
| `avg` | aggregate | `avg(numeric)`; `distinct` allowed | numeric |
| `min` | aggregate | `min(col)` | col type |
| `max` | aggregate | `max(col)` | col type |
| `percentile_cont` | ordered-set aggregate | `percentile_cont(fraction, column)` | numeric (interpolated) |
| `percentile_disc` | ordered-set aggregate | `percentile_disc(fraction, column)` | type of `column` (an actual member) |
| `roc_auc` | aggregate | `roc_auc(label, probability)` (2 args) | numeric (0-1) or null |
| `add` | arithmetic | `add(e1, e2, ...)` (n-ary, ≥1 arg) | numeric |
| `multiply` | arithmetic | `multiply(e1, e2, ...)` (n-ary, ≥1 arg) | numeric |
| `subtract` | arithmetic | `subtract(a, b)` (exactly 2 args) | numeric |
| `divide` | arithmetic | `divide(a, b)` (exactly 2 args) | numeric |
For `percentile_cont`/`percentile_disc`, `fraction` SHALL be a decimal literal in the closed interval
`[0, 1]` and `column` SHALL be any resolvable field expression; the call SHALL be evaluated as an
ordered-set aggregate over `column`. Ordered-set aggregates SHALL be used in aggregate mode (the
GROUP-BY-less whole-table form is permitted, yielding a single row). `roc_auc(label, probability)`
SHALL compute the ROC AUC score (rank-sum / Mann–Whitney formulation) over the matching rows: `label`
is a 0/1-valued field and `probability` is a field in `[0, 1]`; the function SHALL aggregate both
columns (via `array_agg`, index-aligned so `label[i]`/`probability[i]` correspond to the same row) and
delegate the rank-sum computation to a database-side stored function, returning `NULL` when the matched
rows contain only one class (no positive/negative pair exists to rank). `add` and `multiply` SHALL
accept one or more arguments and combine them left-to-right (`e1 op e2 op ... op en`); `subtract` and
`divide` SHALL accept exactly two arguments (`a - b` and `a / b` respectively) and SHALL be rejected
with HTTP 400 for any other arity. All four arithmetic functions operate on already-resolved numeric
expressions (e.g. the result of an `avg(...)` aggregate call) — combining multiple per-metric
aggregates into a single suite `overall` score (a plain mean of several metrics, `divide(add(avg(m1),
avg(m2), ...), n)`, or a weighted mean `Σ(wᵢ×mᵢ)/Σwᵢ` expressed as `divide(add(multiply(w1, m1),
multiply(w2, m2), ...), add(w1, w2, ...))`) is composed from these primitives rather than a dedicated
`mean`/`weighted_mean` function. This composition is performed server-side by the `metric-score-statistics`
capability's `OverallScoreDefinitionResolver` — a caller of this DSL never has to build that composition
by hand (see `metric-score-statistics`'s "Overall score" requirement).
Status: **Implemented**

#### Scenario: Catalog function resolves
- **WHEN** a query uses a function listed in the catalog with a matching arity
- **THEN** the function is accepted and translated

#### Scenario: Non-catalog function is rejected
- **WHEN** a query uses a function name absent from the catalog
- **THEN** the query is rejected with HTTP 400

#### Scenario: Percentile computes a quantile over a metric column
- **WHEN** an aggregate-mode query with no `group_by` selects
  `percentile_cont(0.9, "metric:Accuracy:score")` aliased `p90`
- **THEN** the response is a single row whose `p90` is the 0.9 continuous percentile of the metric's
  `score` across the matching rows

#### Scenario: Out-of-range percentile fraction is rejected
- **WHEN** a query calls `percentile_cont`/`percentile_disc` with a `fraction` outside `[0, 1]`, with a
  non-literal `fraction`, or with other than two arguments
- **THEN** the query is rejected with HTTP 400

#### Scenario: ROC AUC computes over label and probability columns
- **WHEN** an aggregate-mode query with no `group_by` selects
  `roc_auc("data:y", "metric:Classifier:probability")` aliased `value`, over rows containing both
  positive (`y = 1`) and negative (`y = 0`) examples
- **THEN** the response is a single row whose `value` is the ROC AUC score computed by ranking rows by
  `probability` and summing ranks of the positive class, matching the standard rank-sum/Mann–Whitney
  formula

#### Scenario: ROC AUC with a single class returns null
- **WHEN** a `roc_auc(label, probability)` query's matching rows contain only one distinct `label`
  value (all positive or all negative)
- **THEN** the response's aggregated value is `NULL`, since no positive/negative pair exists to rank

#### Scenario: ROC AUC with wrong arity is rejected
- **WHEN** a query calls `roc_auc` with a number of arguments other than two
- **THEN** the query is rejected with HTTP 400

#### Scenario: Weighted mean of specific metrics via arithmetic composition
- **WHEN** an aggregate-mode query with no `group_by` selects
  `divide(add(multiply(w1, avg(metric:A:f1)), multiply(w2, avg(metric:B:f2))), add(w1, w2))` aliased
  `value`, where `w1`/`w2` are numeric literals and `avg(metric:A:f1)`/`avg(metric:B:f2)` are each that
  metric's average across the matching rows
- **THEN** the response is a single row whose `value` equals `Σ(wᵢ×mᵢ)/Σwᵢ` for the two metric
  averages — mathematically identical to normalizing the weights to sum to 1 and then summing their
  weighted terms

#### Scenario: Mean of all metric output via arithmetic composition
- **WHEN** an aggregate-mode query with no `group_by` selects `divide(add(avg(metric:A:f1),
  avg(metric:B:f2), avg(metric:C:f3)), 3)` aliased `value`
- **THEN** the response is a single row whose `value` equals the unweighted average of the three
  metric averages

#### Scenario: `add`/`multiply` accept three or more arguments
- **WHEN** a query calls `add` or `multiply` with three or more arguments
- **THEN** the arguments are combined left-to-right into a single value without requiring manual
  pairwise nesting

#### Scenario: `subtract`/`divide` reject non-binary arity
- **WHEN** a query calls `subtract` or `divide` with a number of arguments other than two
- **THEN** the query is rejected with HTTP 400

#### Scenario: Duplicate metric terms in an arithmetic composition combine naturally
- **WHEN** a weighted-mean composition references the same metric field in more than one `multiply`
  term (e.g. the same metric listed twice with different weights)
- **THEN** the terms combine via ordinary arithmetic (equivalent to a single term with the summed
  weight) with no special-cased deduplication logic required

### Requirement: Query validation and allowlist
The system SHALL validate a structured query before and during translation against the entity's
**discovered schema**, rejecting invalid queries with HTTP 400. Validation SHALL cover: entity
resolution (unknown entity rejected), field resolution (every referenced field — flat column or
`data:`/`response:`/`metric:`/`metricInfo:` JSONB path — must resolve against the entity's schema),
function resolution against the closed **Supported function catalog** (including arity and, for
`percentile_cont`/`percentile_disc`, a `fraction` decimal literal in `[0, 1]`, and for `roc_auc`, exactly
two arguments), `in` operand shape (an
array of value literals), literal parsing per `value_type`, and pagination governance (offset ≥ 0;
cursor pagination rejected; limit clamped to its bounds). Semantically invalid queries that
nonetheless translate SHALL surface the database's grammar/type error as HTTP 400 rather than 500.
Per-field capability flags (`filterable`/`projectable`/`groupable`/`aggregatable`/`sortable`),
mode-coherence enforcement, and array element type-homogeneity are NOT enforced in this implementation
and remain available as a future tightening.
Status: **Implemented**

#### Scenario: Unknown field is rejected
- **WHEN** a query references a field name that does not resolve against the entity's discovered schema
- **THEN** the query is rejected with HTTP 400 before execution

#### Scenario: Unsupported function is rejected
- **WHEN** a query uses a function name outside the Supported function catalog
- **THEN** the query is rejected with HTTP 400

#### Scenario: Cursor pagination is rejected
- **WHEN** a query supplies a cursor-strategy page
- **THEN** the query is rejected with HTTP 400 (cursor pagination is not supported on this path)

#### Scenario: Invalid literal is rejected
- **WHEN** a value literal cannot be parsed to its declared `value_type` (e.g. a non-numeric `integer`)
- **THEN** the query is rejected with HTTP 400

### Requirement: SQL translation
The system SHALL translate a validated structured query into parameterized jOOQ SQL. Flat allowlisted
properties SHALL expand to their physical sources — plain columns or JSONB navigation/casts, with
metric-value paths cast to numeric — using bind parameters rather than string concatenation. Comparison
operators SHALL map to SQL per the wire contract: `eq`/`ne`/`lt`/`gt`/`le`/`ge` to direct comparisons,
`co`/`nc` to case-insensitive `LIKE`/`NOT LIKE` with wildcards **when the left operand is a scalar
(text/numeric) field**, `in` to `IN`, and `eq`/`ne` against a null literal to `IS NULL`/`IS NOT NULL`.
When the left operand of `co`/`nc` is an **array-typed field** (a JSONB field declared `array`),
`co`/`nc` SHALL instead translate to JSONB array-element containment / its negation rather than
`LIKE`: a string right operand SHALL use the JSONB `?` element-existence operator, and a non-string
literal SHALL use `@> to_jsonb(<operand>)` (JSONB containment of the scalar element), with the operand
bound as a parameter (never concatenated). When the left operand of `co`/`nc` is a **case-normalizing
function** (`lower` or `upper`) applied to a bare array-typed field, the wrapper SHALL be discarded and
`co`/`nc` SHALL translate to **case-insensitive whole-element** array containment / its negation: the row
matches when some array element equals the right operand ignoring case, and an element that merely
contains the operand as a substring SHALL NOT match. Elements SHALL be compared by their JSON **text
rendering**, so a string operand also matches a non-string element whose rendering equals it (e.g. `"1"`
matches the element `1`) — unlike the bare-field `?` form, which inspects string elements only. Under this
case-insensitive form a row whose array-declared field holds a **non-array** value (or no value) SHALL NOT
match, and SHALL NOT cause the statement to fail — a further divergence from the bare-field form, where
`?` matches a string value equal to the operand and an object value carrying it as a key. The operand
SHALL be bound as a parameter. The wrapper name SHALL be matched case-insensitively, as the function
registry resolves it. For a non-string right operand the
wrapper SHALL be discarded and the case-sensitive `@>` form SHALL be used (a non-string literal has no
case). No other operator and no other function SHALL be unwrapped: outside the `co`/`nc` array branch,
`lower`/`upper` SHALL keep translating to the SQL function itself.
Aggregate functions SHALL translate to their SQL aggregates;
ordered-set aggregates `percentile_cont`/`percentile_disc` SHALL translate to
`percentile_cont(fraction) WITHIN GROUP (ORDER BY column)` /
`percentile_disc(fraction) WITHIN GROUP (ORDER BY column)` with the `fraction` bound as a parameter.
Aggregate mode SHALL require aliased select entries and SHALL build `group_by`/`having`/`sort` against
the base fields together with the select aliases. The offset pagination strategy SHALL be applied with
a default limit of 100 and a maximum of 1000.
Status: **Implemented**

#### Scenario: Flat metric property expands to JSONB source
- **WHEN** a validated query filters on a flattened metric property such as `metric:Accuracy:score`
- **THEN** the translator emits the parameterized JSONB-navigated, numeric-cast predicate, invisible to
  the client

#### Scenario: Contains operator maps to case-insensitive LIKE
- **WHEN** a query uses the `co` operator on a string field
- **THEN** the translator emits a case-insensitive `LIKE '%value%'` predicate with a bind parameter

#### Scenario: Contains operator on an array field maps to JSONB containment
- **WHEN** a query uses the `co` operator on an array-typed field with a string right operand (e.g.
  `tags CONTAINS 'text'`)
- **THEN** the translator emits a JSONB element-existence predicate (the `?` operator) with the
  operand bound as a parameter, not a `LIKE`, and `nc` emits its negation

#### Scenario: Contains on a case-normalized array field matches whole elements ignoring case
- **WHEN** a query uses the `co` operator whose left operand is `lower(<array-typed field>)` (or
  `upper(...)`) with a string right operand (e.g. `lower(tags) CONTAINS 'tee'`)
- **THEN** the translator discards the wrapper and emits case-insensitive whole-element array
  containment with the operand bound as a parameter, so a row whose array holds `"Tee"` matches, a row
  whose array holds only `"tee-shirt"` does not, and the emitted SQL never applies `lower`/`upper` to a
  JSONB value

#### Scenario: NOT CONTAIN on a case-normalized array field negates the containment
- **WHEN** a query uses the `nc` operator whose left operand is `lower(<array-typed field>)` with a
  string right operand
- **THEN** the translator emits the negation of the case-insensitive whole-element containment, and it
  stays total over null operands (a row whose array is null or absent matches)

#### Scenario: Case-normalized array containment ignores a non-array value instead of failing
- **WHEN** a query filters `lower(<array-typed field>) CONTAINS 'tee'` over rows where one row's
  array-declared field holds a non-array JSON value (e.g. the string `"tee"` or an object) and another
  holds no value at all
- **THEN** the statement executes successfully, those rows do not match, and the rows whose arrays hold a
  matching element still match; under `nc` the non-array and missing-value rows match

#### Scenario: Case-normalized array containment compares elements by their text rendering
- **WHEN** a query filters `lower(<array-typed field>) CONTAINS '1'` (a string operand) and a row's array
  is `[1, 2]`
- **THEN** the row matches, even though the bare-field `?` form of the same comparison would not

#### Scenario: Wrapper name is recognized regardless of its case
- **WHEN** a query uses the `co` operator whose left operand is `LOWER(<array-typed field>)` (an upper-case
  spelling the function registry resolves the same way as `lower`)
- **THEN** the translator routes it to case-insensitive whole-element containment, exactly as for `lower`,
  and never emits the SQL function against the JSONB value

#### Scenario: Case-normalized array field with a non-string operand keeps JSON containment
- **WHEN** a query uses the `co` operator whose left operand is `lower(<array-typed field>)` and whose
  right operand is a non-string literal (e.g. an integer)
- **THEN** the translator discards the wrapper and emits the `@> to_jsonb(<operand>)` containment
  predicate

#### Scenario: Contains on a non-array left operand falls through to LIKE
- **WHEN** a query uses the `co` operator whose left operand is neither a bare array-typed field nor a
  `lower`/`upper` wrapper around one (e.g. a scalar field, a `lower(<string field>)`, or any other
  function-wrapped expression)
- **THEN** the translator does not apply array detection and emits the case-insensitive `LIKE`
  predicate (its scalar `co`/`nc` behavior)

#### Scenario: Aggregate query groups by base field and select alias
- **WHEN** an aggregate-mode query groups by a field and selects an aliased aggregate
- **THEN** the translator builds GROUP BY against the field and resolves `having`/`sort` references
  against the base fields plus the select alias

#### Scenario: Percentile translates to WITHIN GROUP ORDER BY
- **WHEN** a query selects `percentile_cont(0.1, "metric:Accuracy:score")`
- **THEN** the translator emits `percentile_cont(?) WITHIN GROUP (ORDER BY <numeric-cast JSONB path>)`
  with the fraction bound as a parameter

### Requirement: Null handling in comparison and negation operators
The system SHALL make **negated** filter operators total over null operands: `nc` (in both its
scalar-`LIKE` form and its array-element-containment form) and `ne` with a non-null right operand SHALL
evaluate to **true** when either operand is null, rather than to SQL UNKNOWN. `eq`/`ne` against an explicit
null literal SHALL keep their `IS NULL`/`IS NOT NULL` translation, which is already total. The `not`
logical node SHALL likewise be total: `not(<child>)` SHALL evaluate to true when the child predicate is
false **or** unknown.

**Positive** operators (`co`, `eq`, `lt`, `gt`, `le`, `ge`, `in`) SHALL retain SQL three-valued semantics —
a null operand yields UNKNOWN, which excludes the row in a `WHERE` clause and counts as non-matching under
the multi-turn all-turns quantifier. This asymmetry is intentional: an absent value cannot satisfy a
positive assertion, but it trivially satisfies a negated one.

These semantics SHALL apply uniformly to every queryable entity and to every filter consumer (the query
execution endpoint, list-endpoint filters, and suite `testCaseFilter` run selection).
Status: **Implemented**

#### Scenario: NOT CONTAIN matches a row whose field is null
- **WHEN** a filter is `nc(field, "London")` and a row's `field` is null or absent
- **THEN** the row SHALL match, because a missing value does not contain "London"

#### Scenario: NOT CONTAIN on an array field matches a null array
- **WHEN** a filter is `nc(arrayField, "text")` and a row's `arrayField` JSONB value is null or absent
- **THEN** the row SHALL match

#### Scenario: NOT EQUALS matches a row whose field is null
- **WHEN** a filter is `ne(field, "London")` with a non-null right operand and a row's `field` is null
- **THEN** the row SHALL match

#### Scenario: Explicit null literal comparison is unchanged
- **WHEN** a filter is `eq(field, null)` or `ne(field, null)`
- **THEN** the translator SHALL emit `IS NULL` / `IS NOT NULL` respectively, unchanged by this requirement

#### Scenario: CONTAINS does not match a row whose field is null
- **WHEN** a filter is `co(field, "London")` and a row's `field` is null or absent
- **THEN** the row SHALL NOT match

#### Scenario: Negation of a positive predicate over a null field matches
- **WHEN** a filter is `not(co(field, "London"))` and a row's `field` is null or absent
- **THEN** the row SHALL match, consistent with `nc(field, "London")`

### Requirement: Response envelope
The system SHALL return query results as a response object carrying a `rows` array and a nullable
`totalCount`. Each row SHALL be a field-name → value map with JSONB-backed columns parsed back to nested
JSON. The `totalCount` SHALL be populated only for row-mode queries that opt in via the offset page's
`include_total`, computed as a separate count of the same filter; otherwise it SHALL be null. The
richer planned envelope — a `page` object carrying offset/total or `next_cursor`, and an aggregate-mode
`keys`+`metrics` row shape — is NOT implemented on this path.
Status: **Implemented**

#### Scenario: Total count returned when requested
- **WHEN** a row-mode query sets the offset page's `include_total` to true
- **THEN** the response `totalCount` is the count of all rows matching the filter, independent of the
  page's offset and limit

#### Scenario: Total count omitted by default
- **WHEN** a query does not request the total (or is aggregate-mode)
- **THEN** the response `totalCount` is null and only `rows` is populated

### Requirement: Parameter binding via expression substitution
The system SHALL support binding `param` expressions to concrete expressions at execution time via an optional name → expression map supplied alongside a structured query. **Before** translation, a single resolution pass SHALL rewrite the query into a parameter-free form, replacing each `param` expression with the expression bound to its name — recursively, so parameters nested inside a bound expression are also resolved. Once resolved, a bound `field` expression resolves to its column (including JSONB metric paths) and a bound `value` expression translates to a bound SQL parameter. A `param` whose name has no binding SHALL be rejected with HTTP 400. A binding whose value is itself a `param` expression (parameter-to-parameter), or any cyclic binding chain, SHALL be rejected with HTTP 400. When no binding map is supplied, the map SHALL be treated as empty and the query SHALL behave identically to one that contains no `param` expressions. The translator/builder themselves are parameter-agnostic: resolution is isolated in the pre-pass, not threaded through translation.
Status: **Implemented**

#### Scenario: Field parameter resolves to a column
- **WHEN** a query containing `param` `metricField` is executed with `metricField` bound to a `field` expression
- **THEN** the resolved query references that column (including JSONB numeric-cast metric paths) as if the field had been written inline

#### Scenario: Value parameter resolves to a bound SQL parameter
- **WHEN** a query containing `param` `runId` is executed with `runId` bound to a `value` expression
- **THEN** the executed query emits a bound SQL parameter carrying that value

#### Scenario: Unbound parameter is rejected
- **WHEN** a query containing a `param` is executed with no binding for that parameter's name
- **THEN** the query is rejected with HTTP 400

#### Scenario: Parameter-to-parameter binding is rejected
- **WHEN** a parameter is bound to another `param` expression
- **THEN** the query is rejected with HTTP 400

#### Scenario: Cyclic binding chain is rejected
- **WHEN** parameters are bound such that resolving one re-enters the same parameter through nested expressions
- **THEN** the query is rejected with HTTP 400

### Requirement: Public execution endpoint is parameterless
The public query execution endpoint (`POST /api/v1/queries/execute`) SHALL NOT accept parameter bindings; a query submitted to it that contains an unbound `param` expression SHALL be rejected with HTTP 400. Parameter binding SHALL be available only to internal callers that invoke execution with an explicit binding map.
Status: **Implemented**

#### Scenario: Param in public request is rejected
- **WHEN** a query containing a `param` expression is posted to `/api/v1/queries/execute`
- **THEN** the request is rejected with HTTP 400 because no binding is supplied

### Requirement: JSONB sub-field resolver prefix families
The system SHALL support resolving flattened JSONB sub-field names for the `test_suites` entity via
the `JsonbFieldResolver`. Two new prefix families SHALL be recognised:
- `deployment_ref::<key>` — resolves to `deployment_ref ->> '<key>'` (text extraction) over the
  `deployment_ref` JSONB column on the `test_suites` table. Exposed sub-fields: `id`, `name`,
  `version`, `type`.
- `mcp_deployment_ref::<key>` — resolves to `mcp_deployment_ref ->> '<key>'` (text extraction) over
  the `mcp_deployment_ref` JSONB column on the `test_suites` table. Exposed sub-fields: `id`, `name`,
  `type`, `transport`.

Both families SHALL use the `textPath()` resolution path in `JsonbFieldResolver`, guarded by the
same `jsonbColumn()` check that ensures the resolver only activates when the entity's bindings
include the backing column. The resulting `QueryFieldType` for all sub-fields resolved via these
families SHALL be `STRING`. A suffix that resolves to a JSON key absent from the stored object SHALL
return SQL `NULL` (standard Postgres `->>` semantics); this is not an error.
Status: **Implemented**

#### Scenario: deployment_ref::name resolves to text extraction
- **WHEN** a `test_suites` query names the field `deployment_ref::name` in a filter or select
- **THEN** the query builder emits `deployment_ref ->> 'name'` as the SQL expression for that field

#### Scenario: mcp_deployment_ref::id resolves to text extraction
- **WHEN** a `test_suites` query names the field `mcp_deployment_ref::id` in a filter or select
- **THEN** the query builder emits `mcp_deployment_ref ->> 'id'` as the SQL expression for that field

#### Scenario: deployment_ref sub-field on an MCP suite returns NULL
- **WHEN** a `test_suites` query selects `deployment_ref::name` for a suite whose `deployment_ref`
  column is NULL (e.g. an MCP-type suite)
- **THEN** the projected value for that row is SQL `NULL`, not an error

#### Scenario: Opaque OBJECT binding is unaffected
- **WHEN** a `test_suites` query names the plain field `deployment_ref` (no `::` suffix)
- **THEN** the query builder uses the existing `OBJECT`-typed `deployment_ref` column binding,
  not the sub-field resolver path

#### Scenario: Resolver does not activate for entities without the backing column
- **WHEN** a query targets an entity other than `test_suites` and names `deployment_ref::name`
- **THEN** `JsonbFieldResolver` returns null (backing column not in bindings) and the field is
  rejected as unknown with HTTP 400

## Implementation notes

- Request object model: `com.epam.aidial.evaluation.query.model` — `StructuredQuery`,
  `FilterNode`/`LogicalNode`/`ComparisonNode`, `Expr`/`FieldExpr`/`ValueExpr`/`ParamExpr`/`FnExpr`/
  `ArrayExpr`, `AggregateCall`, `SortItem`, `PageSpec`/`OffsetPage`/`CursorPage`, and enums
  `QueryMode`/`LogicalOp`/`ComparisonOp`/`SortDir`/`ValueType`.
- Outbound reuse: `query.model` is a pure-carrier package, deliberately left out of every
  `LayeredArchitectureTest` layer (unlike `query.web`/`query.service`, which are folded into `web`
  and `service`), so it may be built and serialized directly by code outside `query.*` — including
  `client.*`, which sits below the layered packages. `service.domain.RunCostQueryBuilder`
  (see `test-suite-run-costs`) is the first such consumer: dial-adas, an external analytics service,
  runs the same query DSL, confirmed against a real deployment, so building a `StructuredQuery` and
  posting it to dial-adas's `/v1/queries/execute` is the canonical shape for that call, not a
  coincidentally similar one recreated by hand.
- Custom routing: `FilterNodeDeserializer` (wired via `using`/`contentUsing` at each use site,
  never on the `FilterNode` interface, to avoid inheritance-driven recursion).
- Null polarity: `ComparisonOp.negated()` declares which operators assert absence (`nc`, `ne`);
  `FilterTranslator` wraps those comparisons in `(<pred>) IS NOT FALSE` and the `not` node in
  `(<child>) IS NOT TRUE`, leaving positive comparisons unwrapped so they stay sargable. Rendered-SQL
  proof: `query/service/translate/FilterTranslatorNullSemanticsTest`.
- Wire contract: `docs/query-dsl/structured-query-model-v8.html`.
- Binding proof: `query/model/StructuredQueryDeserializationTest` round-trips the spec
  examples through the production `JsonMapper`.
- Open decisions carried as `// TODO(Dn)` markers: D1 (mode explicit vs inferred), D5/D8 (tiebreaker
  / null ordering), D6 (aggregate response typing), D10 (param source/registry).
- Execution endpoint + dispatch: `query.web.StructuredQueryController`,
  `query.service.StructuredQueryService`,
  `…service.repository.StructuredQueryEntityResolver` (SPI) + `…service.repository.StructuredQueryEntityRegistry`
  (+ `PostgresTestSuiteEntityResolver` on meta DSL, `PostgresEvalSummaryEntityResolver` on analytics DSL,
  `PostgresMetricScoreResultEntityResolver` on analytics DSL, `PostgresTestCaseEntityResolver` on meta
  DSL, all `@ConditionalOnProperty`).
- Execution + translation: `StructuredQueryExecutor`, `QueryResultPage`, and
  `…service.translate.{StructuredQueryBuilder, FilterTranslator, ExprTranslator, JsonbFieldResolver,
  ValueExprToObjectMapper}`. `JsonbFieldResolver` handles `data::`, `response::`, `metric::`,
  `metricInfo::` families for `eval_summaries` and `deployment_ref::` (`id`, `name`, `version`,
  `type`) and `mcp_deployment_ref::` (`id`, `name`, `type`, `transport`) families for `test_suites`
  — all backed by `jsonbAtAsText` path expressions over the respective JSONB columns.
  `ExprTranslator` holds the sole `ObjectProvider<StructuredQueryBuilder>` in
  the pipeline (lazy, breaks the `StructuredQueryBuilder → FilterTranslator/ExprTranslator` constructor
  cycle); `StructuredQueryBuilder.compileSubqueryMembership(SubqueryExpr)` builds and wraps a subquery's
  nested select, reached from `ExprTranslator` and (via `ExprTranslator`) from `FilterTranslator`'s `in`
  handling. Response: `…service.dto.StructuredQueryResultDto`; JSONB row parsing via
  `query.web.JsonbRowConverter`.
- Execution error mapping: `ValidationException` → 400; `BadSqlGrammarException` /
  `DataIntegrityViolationException` caught in `StructuredQueryExecutor` and rethrown as
  `ValidationException` → 400.
- Execution tests: `StructuredQueryExecuteFunctionalTests`, `EvalSummaryStructuredQueryFunctionalTests`,
  `TestSuiteStructuredQueryFunctionalTests`, `StructuredQueryBuilderTest`, `EvalSummaryQueryRenderTest`,
  `ValueCoercerTest`, `StructuredQueryServiceTest`.
- Ordered-set aggregates: `ExprTranslator.toFunction` handles `percentile_cont`/`percentile_disc`
  via a two-arg handler that parses the `fraction` literal (validated to `[0, 1]`) and emits
  `DSL.percentileCont(fraction).withinGroupOrderBy(orderField)` / `DSL.percentileDisc(...)`. Covered by
  `EvalSummaryQueryRenderTest` (render + rejection) and `EvalSummaryStructuredQueryFunctionalTests`
  (p10/p90 over metric scores).
- Discovery of queryable entities and their flat field schemas is a separate capability —
  see [query-schema-discovery](../query-schema-discovery/spec.md).
- `FilterTranslator.toComparison`: array-field detection triggers when the left operand **resolves to** a
  bare `FieldExpr` whose `QueryFieldBinding` type is `QueryFieldType.ARRAY` (looked up via
  `bindings.get(name)`, which wins over the `JsonbFieldResolver` fallback) — either directly, or by
  unwrapping a single-argument `lower`/`upper` `FnExpr` around such a field, which additionally selects the
  case-insensitive containment form. Any other left operand — a scalar field, a `lower` over a non-array
  field, any other function — keeps scalar LIKE. jOOQ plain SQL escapes the `?` operator as `??`.
  Array-typed flattened `data::<field>` bindings are produced by `TestCaseFieldBindingsBuilder` (see
  `query-schema-discovery`).
- The case-insensitive form expands the array with `jsonb_array_elements_text` over a
  `case when jsonb_typeof(<col>) = 'array' then <col> else '[]'::jsonb end` argument: the guard must sit
  inside the function argument, since `jsonb_array_elements_text` raises on a scalar/object value and
  `AND` conjunct evaluation order is not guaranteed by the planner.
- The wrapper is meaningful only as a case-normalization hint: `lower`/`upper` are undefined on `jsonb`
  in Postgres, so translating such an operand literally yields a statement that fails at execution
  (SQLSTATE 42883) rather than a different result set (GH #142).
- Case-insensitive whole-element containment expands the array with `jsonb_array_elements_text` and
  compares each element to the bound operand case-insensitively — a per-row element scan. This costs no
  index access that the bare form has: `test_cases.data` carries no GIN index, and both forms put the
  extracted expression `data -> '<field>'` on the left, which `jsonb_ops` cannot serve anyway.
- The `CASE` type guard — not the `is not false` wrapper — is what makes the wrapped `nc` total over
  null/absent/non-array values: `EXISTS` is never UNKNOWN, so `nullSatisfies` is inert on this branch and
  is kept only for uniformity with the `?`/`@>` forms.
- Consumers inherit the behavior without change: the `/queries/execute` endpoint, list-endpoint filters,
  and suite `testCaseFilter` run selection (`QueryDslRunnableTestCaseSelector`, whose ALL-turns-match
  quantifier wraps whatever leaf predicate the translator produces).
