## ADDED Requirements

### Requirement: Supported function catalog
The system SHALL accept in a structured query's expressions only functions from a closed catalog, and
SHALL reject any other function name with HTTP 400. Each catalog entry SHALL define the function's
group (scalar, aggregate, or ordered-set aggregate), arity, operand types, and return type. The
catalog is:

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

For `percentile_cont`/`percentile_disc`, `fraction` SHALL be a decimal literal in the closed interval
`[0, 1]` and `column` SHALL be any resolvable field expression; the call SHALL be evaluated as an
ordered-set aggregate over `column`. Ordered-set aggregates SHALL be used in aggregate mode (the
GROUP-BY-less whole-table form is permitted, yielding a single row).
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

## MODIFIED Requirements

### Requirement: Query validation and allowlist
The system SHALL validate a structured query before and during translation against the entity's
**discovered schema**, rejecting invalid queries with HTTP 400. Validation SHALL cover: entity
resolution (unknown entity rejected), field resolution (every referenced field — flat column or
`data:`/`response:`/`metric:`/`metricInfo:` JSONB path — must resolve against the entity's schema),
function resolution against the closed **Supported function catalog** (including arity and, for
`percentile_cont`/`percentile_disc`, a `fraction` decimal literal in `[0, 1]`), `in` operand shape (an
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
`co`/`nc` to case-insensitive `LIKE`/`NOT LIKE` with wildcards, `in` to `IN`, and `eq`/`ne` against a
null literal to `IS NULL`/`IS NOT NULL`. Aggregate functions SHALL translate to their SQL aggregates;
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

#### Scenario: Aggregate query groups by base field and select alias
- **WHEN** an aggregate-mode query groups by a field and selects an aliased aggregate
- **THEN** the translator builds GROUP BY against the field and resolves `having`/`sort` references
  against the base fields plus the select alias

#### Scenario: Percentile translates to WITHIN GROUP ORDER BY
- **WHEN** a query selects `percentile_cont(0.1, "metric:Accuracy:score")`
- **THEN** the translator emits `percentile_cont(?) WITHIN GROUP (ORDER BY <numeric-cast JSONB path>)`
  with the fraction bound as a parameter

## Implementation notes

- Translation: `experimental.query.service.translate.ExprTranslator.toFunction` — add
  `percentile_cont`/`percentile_disc` cases delegating to a two-arg handler that parses the `fraction`
  literal (validated to `[0, 1]`) and emits `DSL.percentileCont(fraction).withinGroupOrderBy(orderField)`
  / `DSL.percentileDisc(...)`. No change to `StructuredQueryBuilder` (aggregate mode already supports
  aliased select entries and empty `group_by`) or the request model.
- Tests: a translator render test asserting the `WITHIN GROUP (ORDER BY …)` SQL, unit tests for the
  `[0, 1]` range and two-arg arity rejections, and a functional test executing a GROUP-BY-less p10/p90
  query over a run's metric scores on the analytics datasource.
