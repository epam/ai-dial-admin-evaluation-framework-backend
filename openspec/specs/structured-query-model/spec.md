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
`@JsonValue` enum wire codes). Validation/allowlist, SQL translation, and response envelopes are
documented here as **Planned** scope and become follow-up changes.

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
with five kinds: `field` (column reference), `value` (literal whose `value` is always a JSON string
governed by `value_type`), `param` (runtime parameter), `fn` (function call with a nestable
expression `args` list), and `array` (collection whose items use the key `items`). `value_type`
SHALL be a closed enum: `string`, `integer`, `long`, `decimal`, `boolean`, `date`, `timestamp`,
`uuid`, `null`.
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

### Requirement: `in` predicate with array operand
The system SHALL treat `in` as an ordinary binary predicate whose right operand is typically an
`array` expression, without structurally encoding "left scalar / right set" — that constraint is
deferred to validation.
Status: **Implemented**

#### Scenario: Set membership binds
- **WHEN** `execution_status IN ('SUCCESS', 'PARTIAL')` is deserialized
- **THEN** the predicate's `args` hold a field expression on the left and an array expression of
  two value literals on the right

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

### Requirement: Query validation and allowlist
The system SHALL, before building any SQL, validate a structured query against a data-driven
per-entity allowlist: entity resolution, mode coherence (§2), per-field capability flags
(`filterable`/`projectable`/`groupable`/`aggregatable`/`sortable`), function-registry resolution,
literal parsing per `value_type`, array homogeneity, `having`/`sort` reference resolution against
`group_by ∪ aggregate.as`, runtime-parameter source constraint, and pagination governance, and
SHALL reject invalid queries with specific error codes. (Not implemented in this change.)
Status: **Planned**

#### Scenario: Field not allowlisted
- **WHEN** a query references a field absent from the entity's allowlist or uses an operator the
  field does not permit
- **THEN** the query is rejected with a specific error before any SQL is built

### Requirement: SQL translation
The system SHALL translate a validated structured query into parameterized SQL — expanding flat
allowlisted properties to their physical sources (plain columns or JSONB navigation/casts),
emitting operator SQL per the §3 table, applying aggregation/having/group-by, and applying the
selected pagination strategy with a total-order tiebreaker. (Not implemented in this change.)
Status: **Planned**

#### Scenario: Flat metric property expands to JSONB source
- **WHEN** a validated query filters on a metric-derived flat property such as `accuracy_score`
- **THEN** the translator emits the parameterized JSONB-expanded predicate, invisible to the client

### Requirement: Response envelope
The system SHALL return results in a unified response envelope: a `data` array plus a `page` object
carrying offset/total (offset strategy) or `next_cursor` (cursor strategy); aggregate-mode rows use
a `keys` + `metrics` shape. (Not implemented in this change.)
Status: **Planned**

#### Scenario: Cursor response carries next_cursor
- **WHEN** a cursor-paginated query returns a non-final page
- **THEN** the response `page` carries a `next_cursor` valid as input for the next request of the
  same query

## Implementation notes

- Request object model: `com.epam.aidial.evaluation.experimental.query.model` — `StructuredQuery`,
  `FilterNode`/`LogicalNode`/`ComparisonNode`, `Expr`/`FieldExpr`/`ValueExpr`/`ParamExpr`/`FnExpr`/
  `ArrayExpr`, `AggregateCall`, `SortItem`, `PageSpec`/`OffsetPage`/`CursorPage`, and enums
  `QueryMode`/`LogicalOp`/`ComparisonOp`/`SortDir`/`ValueType`.
- Custom routing: `FilterNodeDeserializer` (wired via `using`/`contentUsing` at each use site,
  never on the `FilterNode` interface, to avoid inheritance-driven recursion).
- Wire contract: `docs/experimental/structured-query-model.md` (v7); design notes:
  `docs/experimental/structured-query-object-model-notes.md`.
- Binding proof: `experimental/query/model/StructuredQueryDeserializationTest` round-trips the spec
  examples through the production `JsonMapper`.
- Open decisions carried as `// TODO(Dn)` markers: D1 (mode explicit vs inferred), D5/D8 (tiebreaker
  / null ordering), D6 (aggregate response typing), D10 (param source/registry).
