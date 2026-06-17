## ADDED Requirements

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

## MODIFIED Requirements

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

- Endpoint + dispatch: `experimental.query.web.StructuredQueryController`,
  `experimental.query.service.StructuredQueryService`, `…service.repository.StructuredQueryRepository`
  (+ `PostgresTestSuiteQueryRepository` on meta DSL, `PostgresEvalSummaryQueryRepository` on analytics
  DSL, both `@ConditionalOnProperty`).
- Execution + translation: `StructuredQueryExecutor`, `QueryResultPage`, and
  `…service.translate.{StructuredQueryBuilder, FilterTranslator, ExprTranslator, JsonbFieldResolver,
  ValueExprToObjectMapper}`.
- Response: `…service.dto.StructuredQueryResultDto`; JSONB row parsing via
  `experimental.query.web.JsonbRowConverter`.
- Error mapping: `ValidationException` → 400; `BadSqlGrammarException` /
  `DataIntegrityViolationException` caught in `StructuredQueryExecutor` and rethrown as
  `ValidationException` → 400.
- Tests: `StructuredQueryExecuteFunctionalTests`, `EvalSummaryStructuredQueryFunctionalTests`,
  `TestSuiteStructuredQueryFunctionalTests`, `StructuredQueryBuilderTest`, `EvalSummaryQueryRenderTest`,
  `ValueCoercerTest`, `StructuredQueryServiceTest`.
