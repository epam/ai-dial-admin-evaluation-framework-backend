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

### Requirement: Query execution endpoint
The system SHALL execute a structured query submitted as a request body at
`POST /api/v1/queries/execute` and return its results. Execution SHALL be entity-agnostic: the request
SHALL be routed by its `entity` to the matching execution repository, and a query naming an entity
that has no registered repository SHALL be rejected with a validation error (HTTP 400) that names the
supported entities. Each entity SHALL be executed against its own datasource (meta for `test_suites`,
analytics for `eval_summaries`), and the query SHALL be translated to parameterized SQL — never raw
SQL text — before execution.
Status: **Implemented**

#### Scenario: Row-mode query executes and returns rows
- **WHEN** a valid row-mode query for a supported entity is posted to `/api/v1/queries/execute`
- **THEN** the response carries the projected rows, with any JSONB-backed columns parsed back to nested
  JSON

#### Scenario: Unknown entity is rejected
- **WHEN** a query naming an entity with no registered repository is posted
- **THEN** the request is rejected with HTTP 400 and the error names the supported entities

### Requirement: Query validation and allowlist
The system SHALL validate a structured query before and during translation against the entity's
**discovered schema**, rejecting invalid queries with HTTP 400. Validation SHALL cover: entity
resolution (unknown entity rejected), field resolution (every referenced field — flat column or
`data:`/`response:`/`metric:`/`metricInfo:` JSONB path — must resolve against the entity's schema),
function resolution against a closed supported set (scalar `lower`/`upper`/`length`/`trim`/`abs`/
`width_bucket`; aggregate `count`/`sum`/`avg`/`min`/`max`, with `distinct` where applicable), `in`
operand shape (an array of value literals), literal parsing per `value_type`, and pagination governance
(offset ≥ 0; cursor pagination rejected; limit clamped to its bounds). Semantically invalid queries
that nonetheless translate SHALL surface the database's grammar/type error as HTTP 400 rather than 500.
Per-field capability flags (`filterable`/`projectable`/`groupable`/`aggregatable`/`sortable`),
mode-coherence enforcement, and array element type-homogeneity are NOT enforced in this implementation
and remain available as a future tightening.
Status: **Implemented**

#### Scenario: Unknown field is rejected
- **WHEN** a query references a field name that does not resolve against the entity's discovered schema
- **THEN** the query is rejected with HTTP 400 before execution

#### Scenario: Unsupported function is rejected
- **WHEN** a query uses a function name outside the closed supported set
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
`co`/`nc` to case-insensitive `LIKE`/`NOT LIKE` with wildcards, `in` to `IN`, and `eq`/`ne` against a
null literal to `IS NULL`/`IS NOT NULL`. Aggregate mode SHALL require aliased select entries and SHALL
build `group_by`/`having`/`sort` against the base fields together with the select aliases. The offset
pagination strategy SHALL be applied with a default limit of 100 and a maximum of 1000.
Status: **Implemented**

#### Scenario: Flat metric property expands to JSONB source
- **WHEN** a validated query filters on a flattened metric property such as `metric:Accuracy:score`
- **THEN** the translator emits the parameterized JSONB-navigated, numeric-cast predicate, invisible to
  the client

#### Scenario: Contains operator maps to case-insensitive LIKE
- **WHEN** a query uses the `co` operator on a string field
- **THEN** the translator emits a case-insensitive `LIKE '%value%'` predicate with a bind parameter

#### Scenario: Aggregate query groups by base field and select alias
- **WHEN** an aggregate-mode query groups by a field and selects an aliased aggregate
- **THEN** the translator builds GROUP BY against the field and resolves `having`/`sort` references
  against the base fields plus the select alias

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
- Execution endpoint + dispatch: `experimental.query.web.StructuredQueryController`,
  `experimental.query.service.StructuredQueryService`, `…service.repository.StructuredQueryRepository`
  (+ `PostgresTestSuiteQueryRepository` on meta DSL, `PostgresEvalSummaryQueryRepository` on analytics
  DSL, both `@ConditionalOnProperty`).
- Execution + translation: `StructuredQueryExecutor`, `QueryResultPage`, and
  `…service.translate.{StructuredQueryBuilder, FilterTranslator, ExprTranslator, JsonbFieldResolver,
  ValueExprToObjectMapper}`. Response: `…service.dto.StructuredQueryResultDto`; JSONB row parsing via
  `experimental.query.web.JsonbRowConverter`.
- Execution error mapping: `ValidationException` → 400; `BadSqlGrammarException` /
  `DataIntegrityViolationException` caught in `StructuredQueryExecutor` and rethrown as
  `ValidationException` → 400.
- Execution tests: `StructuredQueryExecuteFunctionalTests`, `EvalSummaryStructuredQueryFunctionalTests`,
  `TestSuiteStructuredQueryFunctionalTests`, `StructuredQueryBuilderTest`, `EvalSummaryQueryRenderTest`,
  `ValueCoercerTest`, `StructuredQueryServiceTest`.
- Discovery of queryable entities and their flat field schemas is a separate capability —
  see [query-schema-discovery](../query-schema-discovery/spec.md).
