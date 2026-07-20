## MODIFIED Requirements

### Requirement: Overall score (default; per-suite definition reserved)
The `overall` metric score is a **per-test-suite** definition. The test suite SHALL carry a nullable
`overall_score` column (JSONB), captured **verbatim** into the suite snapshot at run start, so each run
computes `overall` per the suite's configuration at run time. The column defaults to NULL, meaning "use
the system default".

The system SHALL expose `overall_score` for reading and writing through the suite API (`overallScore` on
suite create/update/get — see `test-suites`) as a **typed, sealed `OverallScoreDefinition`**, discriminated
by a `type` property, with exactly three variants:
- **`mean`** — no parameters. Resolved at Phase 3 by combining the run's **currently discovered** numeric
  metric fields (the same fields used for the per-metric AVG/P10/P90/MIN/MAX statistics) as
  `divide(add(coalesce(avg(f1), 0), coalesce(avg(f2), 0), ...), n)` (composed from the DSL's
  `add`/`divide`/`coalesce` functions — there is no dedicated `mean` DSL function) — NOT a list persisted
  on the suite or supplied by the caller at definition time.
- **`weighted_mean`** — an explicit, non-empty list of `{metricName, outputField, weight}` entries.
  Resolved at Phase 3 as `Σ(weight × coalesce(avg(metric::metricName::outputField), 0)) / Σweight`
  (composed from the DSL's `add`/`multiply`/`divide`/`coalesce` functions), evaluated over every test case
  in the run (each `avg(...)` term scoped only by the standard `:runId`/`:computationId` filter, with no
  additional per-test-case narrowing). The metric/weight references are **not validated at write time**
  against the suite's actually-configured metrics — a reference absent from a given run's data resolves to
  a SQL `NULL` average that is coalesced to `0` for that term, rather than propagating `NULL` through the
  arithmetic or failing suite create/update.
- **`custom_function`** — a self-contained Structured Query DSL expression over the configured metric
  columns (`metric::<metricName>::<outputField>`), run with only the run-scoping params (`:runId`,
  `:computationId`); stored opaquely and not validated as a runnable query at write time (the prior
  free-form escape hatch, unchanged in behavior, now carried under this discriminated variant). This
  variant is **not** subject to the `mean`/`weighted_mean` null-to-zero coalescing — a `custom_function`
  expression's own `avg`/`add`/`multiply`/`divide` calls retain standard SQL null-arithmetic semantics
  unless the expression itself uses `coalesce`.

When the column is NULL, `overall` is computed from the built-in **default** (the single metric's
`avg(:metricField)`) — unchanged from before this typed model, and distinct from the `mean` variant (which
must be explicitly set and, unlike the default, is computed for any metric count). The default is
likewise **not** coalesced to `0` — it is only ever computed when the run resolves to exactly one numeric
metric field, so there is no multi-term composition for a missing metric to poison.

At computation time (Phase 3) the system SHALL resolve the run's `overall` definition from the snapshot
via an `OverallScoreDefinitionResolver` into a `StructuredQuery`, then compute it **through the
structured-query DSL** (not hardcoded), persisting a single result with `metric_score_name` and
`metric_name` both equal to `overall`. A non-null definition (any of the three variants) SHALL be computed
**regardless of metric count**. The default (null column) SHALL be computed **only when the run resolves
to exactly one numeric metric field** — `overall` is then that metric's average (the system binds
`:metricField` to the single field); with more than one metric field the default produces **no** `overall`
result.
Status: **Implemented**

#### Scenario: Default overall for a single-metric run
- **WHEN** a run with exactly one numeric metric field completes (suite has no `overall_score`, i.e. the column is NULL)
- **THEN** an `overall` result is produced equal to that metric's average, computed by executing the default `avg(:metricField)` query bound to that field

#### Scenario: Default overall skipped for a multi-metric run
- **WHEN** a run with more than one numeric metric field completes (suite has no `overall_score`)
- **THEN** no `overall` result is produced (only the per-metric statistics)

#### Scenario: Mean resolves against the run's current metrics, not a stored list
- **WHEN** a suite's `overall_score` is `{"type":"mean"}` and a run completes with two or more numeric metric fields
- **THEN** an `overall` result is produced equal to the unweighted mean of every numeric metric field the run actually has — the set of metrics is discovered at Phase-3 computation time, not read from any list persisted with the `mean` definition (it carries none)

#### Scenario: Weighted mean combines an explicit metric/weight list
- **WHEN** a suite's `overall_score` is `{"type":"weighted_mean","weights":[{metricName, outputField, weight}, ...]}` and a run completes
- **THEN** an `overall` result is produced equal to `Σ(weight × avg(metric::metricName::outputField)) / Σweight` over the run's test cases — weights need not already sum to 1; the division normalizes them regardless

#### Scenario: Weighted mean tolerates duplicate metric/weight entries
- **WHEN** the same `{metricName, outputField}` pair appears in the `weighted_mean` list more than once, each with its own weight
- **THEN** the terms combine via ordinary arithmetic (equivalent to a single entry with the summed weight), with no special-cased deduplication

#### Scenario: Weighted mean coalesces a missing metric's term to zero instead of nulling the whole score
- **WHEN** a `weighted_mean` definition references a `metricName`/`outputField` that is not configured on the suite (or not present in a given run's data), alongside at least one other term that IS present
- **THEN** suite create/update still succeeds (no HTTP 400), the missing entry's `avg(...)` resolves to SQL `NULL` but is coalesced to `0` for that term via the DSL's `coalesce` function, and the overall `weighted_mean` result is a real number computed from the remaining present term(s) — a single missing metric no longer nulls the entire `overall` result

#### Scenario: Custom function targets one metric of many
- **WHEN** a suite's `overall_score` is `{"type":"custom_function","expression":{...}}` whose expression averages exactly one of two numeric metric fields (e.g. `avg(field metric::<metricA>::score)`), and a run completes
- **THEN** a single `overall` result is produced equal to that one metric's average — not the mean across both metrics — computed through the DSL for any metric count

#### Scenario: Overall configuration is pinned in the suite snapshot
- **WHEN** a run's suite snapshot is taken
- **THEN** the snapshot carries the suite's `overall_score` definition verbatim, and Phase 3 computes `overall` from the snapshotted value, not the live suite (later edits to the suite's `overall_score` do not change past runs)
