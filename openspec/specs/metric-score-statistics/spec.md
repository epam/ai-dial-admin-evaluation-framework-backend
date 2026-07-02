# Metric Score Statistics

## Purpose

The metric score statistics capability defines how per-metric summary statistics (AVG, P10, P90, MIN,
MAX, and an overall score) are computed, persisted, and accessed for each test suite run. Statistics
are defined in code as typed structured-query objects, computed automatically at run completion, and
exposed exclusively via the unified Query DSL — there is no dedicated REST endpoint for reading or
managing metric-score results.

## Requirements

### Requirement: Predefined per-metric statistics defined in code
The system SHALL provide predefined per-metric statistics — AVG, P10, P90, MIN, and MAX — **defined in code** as typed structured-query objects (`BuiltInMetricStatistics`). Each SHALL be a self-contained query over the `eval_summaries` entity in aggregate mode that selects a single aliased `value`. P10 and P90 SHALL use `percentile_cont` with the fraction (0.1, 0.9) bound as a literal. A statistic's name (e.g. `AVG`, `P90`) is the persisted `metric_score_name` of its results.
Status: **Implemented**

#### Scenario: Percentile statistic is available
- **WHEN** the predefined `P90` statistic query is inspected
- **THEN** it aggregates the metric field via `percentile_cont` with fraction 0.9

#### Scenario: Average statistic is available
- **WHEN** the predefined `AVG` statistic query is inspected
- **THEN** it aggregates the metric field via `avg`

### Requirement: Statistic queries are reusable parameterized templates
Each predefined statistic SHALL be a full structured query — the aggregate select and the run-scoping filter (`test_suite_run_id` and `computation_id`) — using runtime parameters (`runId`, `computationId`, and `metricField`) rather than hardcoded values, so that it is reusable across all runs and computations. At computation time the system SHALL execute the query through the structured-query service, binding the run, computation, and metric field. The default run-level `overall` is the single metric's `avg(:metricField)` (see the Overall score requirement).
Status: **Implemented**

#### Scenario: Statistic query is not bound to a specific run
- **WHEN** a predefined statistic query is inspected
- **THEN** the run id and computation id appear as parameters, not literal values, and the metric field is a parameter

### Requirement: Automatic metric-score computation at run completion
After a test suite run's metric-evaluation phase completes, the system SHALL compute metric scores for that run before the run transitions to its terminal completed state. It SHALL take the code-defined per-metric statistics, enumerate the run's numeric metric output fields, and execute each statistic once per metric field against the run's metric-evaluation `computation_id` (persisting one result per (statistic, metric field)). It SHALL additionally compute the run-level `overall` from the suite snapshot's `overall_score` (per the Overall score requirement). Computation SHALL reuse the same `computation_id` produced by the metric-evaluation phase.
Status: **Implemented**

#### Scenario: Scores produced for each statistic and metric field
- **WHEN** a run with numeric metric output fields completes metric evaluation
- **THEN** a metric-score result exists for each (predefined statistic × metric field) under the run's computation id

### Requirement: Overall score (default; per-suite definition reserved)
The `overall` metric score is a **per-test-suite** definition. The test suite SHALL carry a nullable `overall_score` column (a structured-query `StructuredQuery` expression, JSONB), captured **verbatim** into the suite snapshot at run start, so each run computes `overall` per the suite's configuration at run time. The column defaults to NULL, meaning "use the system default".

The system SHALL expose `overall_score` for reading and writing through the suite API (`overallScore` on suite create/update/get — see `test-suites`). A non-null value is a self-contained expression over the configured metric columns (`metric::<metricName>::<outputField>`), run with only the run-scoping params (`:runId`, `:computationId`); it is stored opaquely and not validated as a runnable query at write time. When the column is NULL, `overall` is computed from the built-in **default** (the single metric's `avg(:metricField)`).

At computation time (Phase 3) the system SHALL resolve the run's `overall` expression from the snapshot and compute it **through the structured-query DSL** (not hardcoded), persisting a single result with `metric_score_name` and `metric_name` both equal to `overall`. A non-null custom expression SHALL be computed **regardless of metric count**. The default (null column) SHALL be computed **only when the run resolves to exactly one numeric metric field** — `overall` is then that metric's average (the system binds `:metricField` to the single field); with more than one metric field the default produces **no** `overall` result.
Status: **Implemented**

#### Scenario: Default overall for a single-metric run
- **WHEN** a run with exactly one numeric metric field completes (suite has no custom `overall_score`)
- **THEN** an `overall` result is produced equal to that metric's average, computed by executing the default `avg(:metricField)` query bound to that field

#### Scenario: Default overall skipped for a multi-metric run
- **WHEN** a run with more than one numeric metric field completes (suite has no custom `overall_score`)
- **THEN** no `overall` result is produced (only the per-metric statistics)

#### Scenario: Custom overall targets one metric of many
- **WHEN** a suite with two numeric metric fields has a custom `overall_score` whose expression averages exactly one of them (e.g. `avg(field metric::<metricA>::score)`), and a run completes
- **THEN** a single `overall` result is produced equal to that one metric's average — not the mean across both metrics — computed through the DSL for any metric count

#### Scenario: Overall configuration is pinned in the suite snapshot
- **WHEN** a run's suite snapshot is taken
- **THEN** the snapshot carries the suite's `overall_score` verbatim, and Phase 3 computes `overall` from the snapshotted value, not the live suite (later edits to the suite's `overall_score` do not change past runs)

### Requirement: Metric-score result persistence
The system SHALL persist each metric-score result in the analytics datasource with: `id` (primary key), `test_suite_run_id`, `computation_id`, `metric_score_name` (the statistic/definition name), `metric_name` (the metric output field), and a numeric `value`. Results SHALL be append-only per computation, uniquely identified by (`test_suite_run_id`, `computation_id`, `metric_score_name`, `metric_name`).
Status: **Implemented**

#### Scenario: Result uniqueness within a computation
- **WHEN** the same (statistic, metric field) is written twice for the same run and computation
- **THEN** only one result row exists for that combination

### Requirement: Metric-score computation fault isolation
Failure to compute metric scores SHALL NOT fail the test suite run; the run SHALL still reach its completed state and the failure SHALL be logged. A failure computing one (statistic, metric field) pair SHALL NOT prevent computation of the remaining pairs.
Status: **Implemented**

#### Scenario: Run completes despite score-computation failure
- **WHEN** metric-score computation throws for a run
- **THEN** the run still transitions to completed and the error is logged

#### Scenario: One bad metric field does not abort the rest
- **WHEN** computing a statistic over one metric field fails with a validation error
- **THEN** the remaining (statistic, metric field) pairs are still computed and persisted

### Requirement: Metric-score results read exclusively via the unified Query API
The system SHALL expose computed metric-score results **only** as a queryable entity (`metric_score_results`) of the structured-query model — there is no dedicated metric-score-results REST endpoint. Clients read, filter, sort, paginate, and aggregate results through `POST /api/v1/queries/execute` over the flat columns `id`, `test_suite_run_id`, `computation_id`, `metric_score_name`, `metric_name`, `value`.

For a query that targets a single run (`test_suite_run_id eq X`), the sentinel `computation_id eq "latest"` (case-insensitive) SHALL be resolved to the run's most recent computation and the query scoped to it — the sentinel is rewritten to the resolved id before translation, so `"latest"` is never parsed as a UUID. Latest resolution is delegated to the shared `ComputationResolver` (the single authority for "latest"). An explicit `computation_id` with a real value (`eq <uuid>` or `in [...]`) SHALL be honored verbatim; **omitting** `computation_id` spans all of the run's computations (there is no implicit latest-defaulting on omission). Cross-computation reads (e.g. comparing the last N runs) SHALL be expressible by filtering `computation_id` with `in`.
Status: **Implemented**

#### Scenario: `computation_id eq "latest"` resolves to the run's latest computation
- **WHEN** a structured query selects from `metric_score_results` filtered by `test_suite_run_id eq X and computation_id eq "latest"`
- **THEN** only the rows of run X's most recent computation are returned

#### Scenario: Results queried for an explicit run and computation
- **WHEN** a structured query selects from `metric_score_results` filtered by `test_suite_run_id eq X and computation_id eq Y`
- **THEN** the matching result rows are returned with their `metric_score_name`/`metric_name`/`value`

#### Scenario: Results aggregated across computations
- **WHEN** a structured query filters `computation_id in [...]` and aggregates `value` grouped by `metric_score_name`
- **THEN** the aggregate is computed across the selected computations in a single response

#### Scenario: Omitted computation_id returns results from all computations
- **WHEN** a structured query selects from `metric_score_results` filtered only by `test_suite_run_id` (no `computation_id` filter)
- **THEN** results from all computations for that run are returned, with no implicit latest-defaulting

### Requirement: Statistics are code-defined (no management API)
The predefined per-metric statistics (`AVG`/`P10`/`P90`/`MIN`/`MAX`) SHALL be defined in code as typed structured-query objects (`BuiltInMetricStatistics`), and SHALL NOT be created, edited, or deleted through any HTTP endpoint; the system exposes no `metric-score-definitions` API. The Phase-3 computation reads these built-in statistics directly; only the computed results are exposed (via the `metric_score_results` Query DSL entity). The `overall` definition is a per-suite property (`test_suites.overall_score`) set through the suite create/update API (`overallScore` — see `test-suites`), not through a dedicated metric-score-definitions endpoint; when unset it stays null and `overall` uses the built-in default.
Status: **Implemented**

#### Scenario: No definition management endpoints exist
- **WHEN** a client looks for endpoints to create, update, or delete metric-score definitions
- **THEN** none exist; the per-metric statistics originate only from code, and the per-suite `overall` definition is set via the suite API, not a definitions endpoint

> **Note:** Per-suite metric-score configuration is set through `test_suites.overall_score` (the `overall` definition), exposed via the suite create/update/get API (`overallScore` — see `test-suites`) and snapshotted per run. A richer per-suite "score config" (modes, weights, metric selection) compiled into a `StructuredQuery` is deferred; the per-metric statistics are a fixed code-owned catalog (`BuiltInMetricStatistics`).
