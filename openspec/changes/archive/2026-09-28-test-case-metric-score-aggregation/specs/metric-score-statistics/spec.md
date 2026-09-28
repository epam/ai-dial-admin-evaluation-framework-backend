## MODIFIED Requirements

### Requirement: Overall score (default; per-suite definition reserved)
The `overall` metric score is a **per-test-suite** definition. The test suite SHALL carry a nullable
`overall_score` column (JSONB), captured **verbatim** into the suite snapshot at run start, so each run
computes `overall` per the suite's configuration at run time. The column defaults to NULL, meaning "use
the system default".

The system SHALL expose `overall_score` for reading and writing through the suite API (`overallScore` on
suite create/update/get — see `test-suites`) as a **typed, sealed `OverallScoreDefinition`**, discriminated
by a `type` property, with exactly three variants:
- **`mean`** — no parameters. Resolved at Phase 3 by building the query **directly** against
  `test_case_metric_scores` (see `test-case-metric-score-aggregation`) — the run's **currently discovered**
  numeric metric fields (the same fields used for the per-metric AVG/P10/P90/MIN/MAX statistics), combined
  as the unweighted average, **across the run's test cases**, of each metric's per-test-case average
  (`metric_scores::<name>::avg`), rather than an aggregate over raw `eval_summaries` rows: every test case
  contributes exactly one sample per metric it has, regardless of how many turns/requests/reruns produced
  it. This reuses `OverallScoreDefinitionResolver`'s pre-existing formula and null-handling unchanged — a
  metric that no test case in the run ever produced a value for is **coalesced to `0`** for its term (not
  excluded), same as the formula has always done.
- **`weighted_mean`** — an explicit, non-empty list of `{metricName, outputField, weight}` entries.
  Resolved at Phase 3 as `Σ(weight × per-test-case-averaged metric value) / Σweight`, built directly
  against `test_case_metric_scores` the same way as `mean` above (one sample per test case per metric,
  equally weighted across test cases). A metric/weight entry whose metric no test case in the run ever
  produced a value for is **coalesced to `0`** for its term, unchanged from the pre-existing formula. The
  metric/weight references are still **not validated at write time** against the suite's
  actually-configured metrics.
- **`custom_function`** — a self-contained Structured Query DSL expression, originally authored over the
  configured metric columns (`metric::<metricName>::<outputField>`) of raw `eval_summaries`, run with only
  the run-scoping params (`:runId`, `:computationId`); stored opaquely and not validated as a runnable
  query at write time. This variant is **not** retargeted onto `test_case_metric_scores` — at Phase 3
  computation time the system SHALL resolve and execute it directly against `eval_summaries`, ungrouped,
  exactly as before this change: a test case's row count still weights its contribution to the result.
  This is an accepted, out-of-scope limitation for this capability, the same as the per-metric AVG/P10/
  P90/MIN/MAX statistics (`BuiltInMetricStatistics`) and run-comparison (`FilteredMetricScoreAggregator`).
  This variant is **not** subject to the `mean`/`weighted_mean` null-exclusion handling — a `custom_function`
  expression's own `avg`/`add`/`multiply`/`divide` calls retain standard SQL null-arithmetic semantics
  unless the expression itself uses `coalesce`.

When the column is NULL, `overall` is computed from the built-in **default** (the single metric's
`avg(:metricField)` over raw `eval_summaries`) — unchanged from before this typed model and unaffected by
the per-test-case aggregation introduced for `mean`/`weighted_mean`, and distinct from the `mean` variant
(which must be explicitly set and, unlike the default, is computed for any metric count). The default is
likewise **not** coalesced to `0` — it is only ever computed when the run resolves to exactly one numeric
metric field, so there is no multi-term composition for a missing metric to poison.

At computation time (Phase 3) the system SHALL resolve the run's `overall` definition from the snapshot
via an `OverallScoreDefinitionResolver`. For `mean` and `weighted_mean`, the resolved query SHALL target
the `test_case_metric_scores` entity (equal per-test-case weighting); for `custom_function` and the
default, the resolved query SHALL target `eval_summaries` as before. In every case computation SHALL happen
**through the structured-query DSL** (not hardcoded), persisting a single result with `metric_score_name`
and `metric_name` both equal to `overall`. A non-null definition (any of the three variants) SHALL be
computed **regardless of metric count**. The default (null column) SHALL be computed **only when the run
resolves to exactly one numeric metric field** — `overall` is then that metric's average (the system
binds `:metricField` to the single field); with more than one metric field the default produces **no**
`overall` result.
Status: **Implemented**

#### Scenario: Default overall for a single-metric run
- **WHEN** a run with exactly one numeric metric field completes (suite has no `overall_score`, i.e. the column is NULL)
- **THEN** an `overall` result is produced equal to that metric's average, computed by executing the default `avg(:metricField)` query bound to that field over raw `eval_summaries`

#### Scenario: Default overall skipped for a multi-metric run
- **WHEN** a run with more than one numeric metric field completes (suite has no `overall_score`)
- **THEN** no `overall` result is produced (only the per-metric statistics)

#### Scenario: Mean weights every test case equally regardless of row count
- **WHEN** a suite's `overall_score` is `{"type":"mean"}` and a run completes with two test cases, one having 8 `EvalSummary` rows (multiple turns/requests/reruns) and the other having 1
- **THEN** the `overall` result is the unweighted mean of each test case's own per-metric average (via `test_case_metric_scores`), so each test case contributes exactly one sample per metric it has — the 8-row test case does not outweigh the 1-row test case

#### Scenario: Mean resolves against the run's current metrics, not a stored list
- **WHEN** a suite's `overall_score` is `{"type":"mean"}` and a run completes with two or more numeric metric fields
- **THEN** an `overall` result is produced equal to the unweighted mean of every numeric metric field the run actually has — the set of metrics is discovered at Phase-3 computation time, not read from any list persisted with the `mean` definition (it carries none)

#### Scenario: Weighted mean combines an explicit metric/weight list, equally weighted per test case
- **WHEN** a suite's `overall_score` is `{"type":"weighted_mean","weights":[{metricName, outputField, weight}, ...]}` and a run completes
- **THEN** an `overall` result is produced equal to `Σ(weight × per-test-case-averaged metric value) / Σweight`, where each test case in the run contributes exactly one sample per metric it has — weights need not already sum to 1; the division normalizes them regardless

#### Scenario: Weighted mean tolerates duplicate metric/weight entries
- **WHEN** the same `{metricName, outputField}` pair appears in the `weighted_mean` list more than once, each with its own weight
- **THEN** the terms combine via ordinary arithmetic (equivalent to a single entry with the summed weight), with no special-cased deduplication

#### Scenario: Weighted mean coalesces a metric no test case ever produced to zero
- **WHEN** a `weighted_mean` definition references a `metricName`/`outputField` that no test case in the run ever produced a value for, alongside at least one other term that IS present for at least one test case
- **THEN** suite create/update still succeeds (no HTTP 400), the missing entry's term is **coalesced to `0`** in both the numerator and denominator sums (the same formula/semantics as before this change), and the overall `weighted_mean` result is a real number computed from the remaining terms

#### Scenario: Custom function overall is unaffected by this change, unweighted by test-case row count
- **WHEN** a suite's `overall_score` is `{"type":"custom_function","expression":{...}}`, whether the expression references only `metric::` fields (e.g. `roc_auc(metric::Label::value, metric::Probability::score)`) or mixes a metric field with a non-metric one (e.g. `roc_auc(data::y, metric::Classifier::probability)`), and a run completes with test cases of uneven row counts
- **THEN** the resolved query executes directly over raw `eval_summaries`, ungrouped, exactly as it would have before this change — every raw row contributes its own point to the result, not one point per test case; this is an accepted, out-of-scope limitation shared with the per-metric AVG/P10/P90/MIN/MAX statistics and run-comparison

#### Scenario: Overall configuration is pinned in the suite snapshot
- **WHEN** a run's suite snapshot is taken
- **THEN** the snapshot carries the suite's `overall_score` definition verbatim, and Phase 3 computes `overall` from the snapshotted value, not the live suite (later edits to the suite's `overall_score` do not change past runs)
