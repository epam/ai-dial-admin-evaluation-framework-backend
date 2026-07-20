## MODIFIED Requirements

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
