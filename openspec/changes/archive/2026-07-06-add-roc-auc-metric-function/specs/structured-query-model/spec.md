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
| `roc_auc` | aggregate | `roc_auc(label, probability)` (2 args) | numeric (0-1) or null |
For `percentile_cont`/`percentile_disc`, `fraction` SHALL be a decimal literal in the closed interval
`[0, 1]` and `column` SHALL be any resolvable field expression; the call SHALL be evaluated as an
ordered-set aggregate over `column`. Ordered-set aggregates SHALL be used in aggregate mode (the
GROUP-BY-less whole-table form is permitted, yielding a single row). `roc_auc(label, probability)`
SHALL compute the ROC AUC score (rank-sum / Mann–Whitney formulation) over the matching rows: `label`
is a 0/1-valued field and `probability` is a field in `[0, 1]`; the function SHALL aggregate both
columns (via `array_agg`, index-aligned so `label[i]`/`probability[i]` correspond to the same row) and
delegate the rank-sum computation to a database-side stored function, returning `NULL` when the matched
rows contain only one class (no positive/negative pair exists to rank). Arithmetic functions
(`add`/`subtract`/`multiply`/`divide`) are the planned extension, each added as a further `QueryFunction` component.
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

### Requirement: Query validation and allowlist
The system SHALL validate a structured query before and during translation against the entity's
**discovered schema**, rejecting invalid queries with HTTP 400. Validation SHALL cover: entity
resolution (unknown entity rejected), field resolution (every referenced field — flat column or
`data:`/`response:`/`metric:`/`metricInfo:` JSONB path — must resolve against the entity's schema),
function resolution against the closed **Supported function catalog** (including arity and, for
`percentile_cont`/`percentile_disc`, a `fraction` decimal literal in `[0, 1]`, and for `roc_auc`, exactly
two arguments), `in` operand shape (an array of value literals), literal parsing per `value_type`, and
pagination governance (offset ≥ 0; cursor pagination rejected; limit clamped to its bounds).
Semantically invalid queries that nonetheless translate SHALL surface the database's grammar/type error
as HTTP 400 rather than 500. Per-field capability flags
(`filterable`/`projectable`/`groupable`/`aggregatable`/`sortable`), mode-coherence enforcement, and
array element type-homogeneity are NOT enforced in this implementation and remain available as a future
tightening.
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
