## MODIFIED Requirements

### Requirement: Database schema for run metric snapshots
The **meta** database SHALL contain a `run_metric_snapshots` table storing per-computation binding and version snapshots. The table SHALL hold a foreign key to `test_suite_runs` so a run and its captured metric catalog live in one database and cannot diverge.
Status: **Implemented**

#### Scenario: Table structure
- **WHEN** the meta Flyway migration V1.32 is applied
- **THEN** the `run_metric_snapshots` table SHALL have columns: `id` (VARCHAR(36), NOT NULL, PK), `computation_id` (VARCHAR(36), NOT NULL), `test_suite_run_id` (VARCHAR(36), NOT NULL), `tsmd_id` (VARCHAR(36), NOT NULL), `tsmd_name` (VARCHAR(255), NOT NULL), `metric_declaration_id` (VARCHAR(36), NOT NULL), `metric_declaration_version_id` (VARCHAR(36), NOT NULL), `config_bindings` (JSONB, NOT NULL, DEFAULT '[]'), `input_bindings` (JSONB, NOT NULL, DEFAULT '[]'), `output_schema` (JSONB, NOT NULL, DEFAULT '{}'), `computed_at_ms` (BIGINT, NOT NULL)

#### Scenario: UNIQUE constraint
- **WHEN** the migration is applied
- **THEN** a UNIQUE index (`CREATE UNIQUE INDEX`, not a table constraint) SHALL exist on `(computation_id, tsmd_id)`

#### Scenario: Index for run lookup
- **WHEN** the meta migrations up to and including V1.34 are applied
- **THEN** exactly one non-unique index SHALL exist with `test_suite_run_id` as its leading column: `idx_run_metric_snapshots_run_computed_at` on `(test_suite_run_id, computed_at_ms DESC, computation_id DESC)`. It SHALL serve every lookup by run (including the FK cascade) through its leading column and SHALL let the latest-computation lookup (`ORDER BY computed_at_ms DESC, computation_id DESC LIMIT 1` for one run) complete without a sort step. The former single-column index `idx_run_metric_snapshots_run` SHALL NOT exist (it is a strict prefix of the composite index).

#### Scenario: Foreign key to test suite runs
- **WHEN** the migration is applied
- **THEN** `test_suite_run_id` SHALL carry a foreign key referencing `test_suite_runs(id)` with `ON DELETE CASCADE`

#### Scenario: Snapshot for an unknown run is rejected
- **WHEN** a snapshot row is written whose `test_suite_run_id` does not exist in `test_suite_runs`
- **THEN** the database SHALL reject the write, so orphaned snapshot rows cannot be created

#### Scenario: Analytics copy is no longer read
- **WHEN** any component reads run metric snapshots
- **THEN** it SHALL read the meta `run_metric_snapshots` table. The same-named table in the analytics database SHALL NOT be read or written by any code path.

### Requirement: Computation versioning model
Metric computations SHALL be versioned via `computation_id` with no mutable `is_latest` flag. "Latest" SHALL be resolved at query time, from the table the caller reads.
Status: **Implemented**

#### Scenario: Recalculation creates new computation
- **WHEN** metrics are recalculated for a run
- **THEN** the metric computation pipeline SHALL generate a new `computation_id`, insert new eval summary rows and new run metric snapshots, without modifying existing rows

#### Scenario: Latest resolution
- **WHEN** the API needs to determine the latest computation for a run
- **THEN** it SHALL query `test_case_eval_summaries` for the maximum `computed_at_ms` for that run and use the corresponding `computation_id`

#### Scenario: Latest resolution is independent of a run's row count
- **WHEN** the latest computation of a run is resolved and the run has many eval summary rows spread across more than one computation
- **THEN** resolution SHALL return the `computation_id` with the greatest `computed_at_ms` for that run, whatever the number of rows involved

#### Scenario: Latest resolution requires readable rows
- **WHEN** a computation wrote `run_metric_snapshots` rows for a run but wrote no eval summaries (e.g. its batch write failed)
- **THEN** latest resolution SHALL NOT select that computation, and SHALL select the run's most recent computation that does have eval summaries

#### Scenario: Metric-catalog lookups stay on run metric snapshots
- **WHEN** a caller needs the metric column families or metric names of a run's latest computation rather than its readable rows (Query DSL detailed schema discovery, the `test_suite_runs` query entity's `metric_names`)
- **THEN** it SHALL resolve that computation from `run_metric_snapshots` as the row with the greatest `computed_at_ms` for the run, ties broken by the greatest `computation_id`, so that two computations captured in the same millisecond resolve deterministically; and SHALL return no metric families / an empty name list for a run that has none

#### Scenario: Comparison between computations
- **WHEN** client provides two computation UUIDs
- **THEN** the list endpoint SHALL support filtering by specific `computation_id` to enable side-by-side comparison
