## MODIFIED Requirements

### Requirement: Computation versioning model
Metric computations SHALL be versioned via `computation_id` with no mutable `is_latest` flag. "Latest" SHALL be resolved at query time, from the table the caller reads. When two computations of one run share the greatest `computed_at_ms`, the greater `computation_id` SHALL be the latest, where "greater" is the canonical lowercase 36-character UUID string compared lexicographically (text ordering, matching the `VARCHAR(36)` storage and equivalent to SQL `ORDER BY computation_id DESC`) — **not** `java.util.UUID.compareTo`, which orders signed 64-bit halves and disagrees with text order for some UUID pairs; every latest-resolution path SHALL apply this same tie-break.
Status: **Implemented**

#### Scenario: Recalculation creates new computation
- **WHEN** metrics are recalculated for a run
- **THEN** the metric computation pipeline SHALL generate a new `computation_id`, insert new eval summary rows and new run metric snapshots, without modifying existing rows

#### Scenario: Latest resolution
- **WHEN** the API needs to determine the latest computation for a run
- **THEN** it SHALL query `test_case_eval_summaries` for the maximum `computed_at_ms` for that run and use the corresponding `computation_id`, breaking a tie on `computed_at_ms` by the greatest `computation_id`. The same tie-break rule applies identically when latest resolution instead queries `run_metric_snapshots` (e.g. metric-catalog lookups).

#### Scenario: Latest resolution is deterministic under equal timestamps
- **WHEN** two computations of one run share the same greatest `computed_at_ms`
- **THEN** resolution SHALL return the `computation_id` that is greater under lexicographic (text) comparison of its canonical UUID string — not under `java.util.UUID.compareTo` ordering — and SHALL return the same id on every call

#### Scenario: Latest resolution is independent of a run's row count
- **WHEN** the latest computation of a run is resolved and the run has many eval summary rows spread across more than one computation
- **THEN** resolution SHALL return the `computation_id` with the greatest `computed_at_ms` for that run, whatever the number of rows involved

#### Scenario: Latest resolution requires readable rows
- **WHEN** a computation wrote `run_metric_snapshots` rows for a run but wrote no eval summaries (e.g. its batch write failed)
- **THEN** latest resolution SHALL NOT select that computation, and SHALL select the run's most recent computation that does have eval summaries

#### Scenario: Metric-catalog lookups stay on run metric snapshots
- **WHEN** a caller needs the metric column families of a run's latest computation rather than its readable rows (Query DSL detailed schema discovery)
- **THEN** it SHALL resolve that computation from `run_metric_snapshots` and SHALL return no metric families for a run that has none

#### Scenario: Comparison between computations
- **WHEN** client provides two computation UUIDs
- **THEN** the list endpoint SHALL support filtering by specific `computation_id` to enable side-by-side comparison
