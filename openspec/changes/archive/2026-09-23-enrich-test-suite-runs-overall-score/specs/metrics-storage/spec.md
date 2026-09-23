## MODIFIED Requirements

### Requirement: Computation versioning model
Metric computations SHALL be versioned via `computation_id` with no mutable `is_latest` flag. "Latest" SHALL be resolved at query time, from the table the caller reads.

Every latest-computation lookup, on either table, SHALL order by `computed_at_ms DESC, computation_id ASC` and take the first row, so that two computations of one run captured in the same millisecond resolve to the same computation on every path and on repeated calls. Resolution SHALL also be available for a set of runs at once, returning at most one computation per run in a single statement; a run with no eval summaries SHALL be absent from the result rather than mapped to a null computation. An empty set of runs SHALL resolve to an empty result without issuing any statement.
Status: **Implemented**

#### Scenario: Recalculation creates new computation
- **WHEN** metrics are recalculated for a run
- **THEN** the metric computation pipeline SHALL generate a new `computation_id`, insert new eval summary rows and new run metric snapshots, without modifying existing rows

#### Scenario: Latest resolution
- **WHEN** the API needs to determine the latest computation for a run
- **THEN** it SHALL query `test_case_eval_summaries` for the maximum `computed_at_ms` for that run and use the corresponding `computation_id`, breaking a tie on the smallest `computation_id`

#### Scenario: Latest resolution is independent of a run's row count
- **WHEN** the latest computation of a run is resolved and the run has many eval summary rows spread across more than one computation
- **THEN** resolution SHALL return the `computation_id` with the greatest `computed_at_ms` for that run, whatever the number of rows involved

#### Scenario: Latest resolution requires readable rows
- **WHEN** a computation wrote `run_metric_snapshots` rows for a run but wrote no eval summaries (e.g. its batch write failed)
- **THEN** latest resolution SHALL NOT select that computation, and SHALL select the run's most recent computation that does have eval summaries

#### Scenario: Same-millisecond computations resolve identically on every path
- **WHEN** two computations of one run share `computed_at_ms` and both the eval-summary-based and the snapshot-based lookups are performed for that run
- **THEN** both return the same `computation_id` — the smallest of the tied ids — and repeated calls return the same value

#### Scenario: Latest resolution for a set of runs
- **WHEN** the latest computation is resolved for a set of runs at once
- **THEN** the result maps each run that has eval summaries to exactly one `computation_id`, resolved by the same ordering as the single-run lookup, and omits runs that have none

#### Scenario: Empty run set resolves without a database round trip
- **WHEN** the latest computation is resolved for an empty set of runs
- **THEN** the result is empty and no statement is issued against the database

#### Scenario: Metric-catalog lookups stay on run metric snapshots
- **WHEN** a caller needs the metric column families or metric names of a run's latest computation rather than its readable rows (Query DSL detailed schema discovery, the `test_suite_runs` query entity's `metric_names`)
- **THEN** it SHALL resolve that computation from `run_metric_snapshots` as the row with the greatest `computed_at_ms` for the run, ties broken by the smallest `computation_id`, so that two computations captured in the same millisecond resolve deterministically and agree with the eval-summary-based lookup; and SHALL return no metric families / an empty name list for a run that has none

#### Scenario: Comparison between computations
- **WHEN** client provides two computation UUIDs
- **THEN** the list endpoint SHALL support filtering by specific `computation_id` to enable side-by-side comparison
