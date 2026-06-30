## ADDED Requirements

### Requirement: Parameter binding via expression substitution
The system SHALL support binding `param` expressions to concrete expressions at execution time via an optional name → expression map supplied alongside a structured query. **Before** translation, a single resolution pass SHALL rewrite the query into a parameter-free form, replacing each `param` expression with the expression bound to its name — recursively, so parameters nested inside a bound expression are also resolved. Once resolved, a bound `field` expression resolves to its column (including JSONB metric paths) and a bound `value` expression translates to a bound SQL parameter. A `param` whose name has no binding SHALL be rejected with HTTP 400. A binding whose value is itself a `param` expression (parameter-to-parameter), or any cyclic binding chain, SHALL be rejected with HTTP 400. When no binding map is supplied, the map SHALL be treated as empty and the query SHALL behave identically to one that contains no `param` expressions. The translator/builder themselves are parameter-agnostic: resolution is isolated in the pre-pass, not threaded through translation.
Status: **Planned**

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
Status: **Planned**

#### Scenario: Param in public request is rejected
- **WHEN** a query containing a `param` expression is posted to `/api/v1/queries/execute`
- **THEN** the request is rejected with HTTP 400 because no binding is supplied

## MODIFIED Requirements

### Requirement: Supported function catalog
The system SHALL accept in a structured query's expressions only functions from a closed catalog, and
SHALL reject any other function name with HTTP 400. Each catalog entry SHALL define the function's
group (scalar, aggregate, ordered-set aggregate, or reduction), arity, operand types, and return type.
The catalog SHALL be **registry-driven**: each function is contributed as a separate component
(`QueryFunction`) collected by name at startup, so the set of supported functions is extended by adding
a component rather than editing a central switch; duplicate names SHALL be rejected at startup. The
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
| `mean` | reduction | `mean(array)` — one `array` argument (or a `param` resolving to one) | numeric |

For `percentile_cont`/`percentile_disc`, `fraction` SHALL be a decimal literal in the closed interval
`[0, 1]` and `column` SHALL be any resolvable field expression; the call SHALL be evaluated as an
ordered-set aggregate over `column`. Ordered-set aggregates SHALL be used in aggregate mode (the
GROUP-BY-less whole-table form is permitted, yielding a single row). For `mean`, the single argument
SHALL be an `array` expression (the one place an `array` is permitted outside the `in` predicate),
which MAY be supplied via a `param` binding; the function SHALL fold the array's items as their
arithmetic mean `(e₁ + … + eₙ) / n`. Arithmetic functions (`add`/`subtract`/`multiply`/`divide`,
weighted means) are the planned extension, each added as a further `QueryFunction` component.
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

#### Scenario: Mean reduces an array to its arithmetic mean
- **WHEN** a query selects `mean(...)` over an `array` of numeric terms (e.g. `avg(...)` aggregates)
- **THEN** the translator folds the terms as `(e₁ + … + eₙ) / n` and returns the numeric mean

#### Scenario: Mean rejects a non-array argument
- **WHEN** a query calls `mean` with an argument that does not resolve to an `array`
- **THEN** the query is rejected with HTTP 400
