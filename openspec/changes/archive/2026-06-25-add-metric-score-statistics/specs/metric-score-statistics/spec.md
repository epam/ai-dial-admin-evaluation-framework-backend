## ADDED Requirements

### Requirement: Metric-score definition model
The system SHALL model a metric-score definition with: an `id` (UUID), a `type` ∈ {`DEFAULT`, `TEST_SUITE`}, a `name`, an optional `description`, an `expression` (a serialized structured query), and an optional `target_id` (UUID). `DEFAULT` definitions SHALL have a null `target_id`; `TEST_SUITE` definitions SHALL carry the test-suite id in `target_id`. Definitions SHALL be persisted in the analytics datasource.
Status: **Planned**

#### Scenario: Global definition has no target
- **WHEN** a `DEFAULT` definition is persisted
- **THEN** its `target_id` is null and the definition applies to every run regardless of suite

#### Scenario: Suite-scoped definition carries a target
- **WHEN** a `TEST_SUITE` definition is persisted
- **THEN** its `target_id` holds the test-suite id it is scoped to

### Requirement: Predefined default statistics
The system SHALL provide predefined `DEFAULT` definitions for the statistics AVG, P10, P90, MIN, and MAX, each expressed as a self-contained structured query over the `eval_summaries` entity in aggregate mode that selects a single aliased `value`. P10 and P90 SHALL use `percentile_cont` with the fraction (0.1, 0.9) bound as a literal.
Status: **Planned**

#### Scenario: Percentile statistic is available
- **WHEN** the predefined `P90` definition is resolved
- **THEN** its expression aggregates the target metric field via `percentile_cont` with fraction 0.9

#### Scenario: Average statistic is available
- **WHEN** the predefined `AVG` definition is resolved
- **THEN** its expression aggregates the target metric field via `avg`

### Requirement: Definition expression is a reusable parameterized query
A metric-score definition's `expression` SHALL store a full structured query — the aggregate select and the run-scoping filter (`test_suite_run_id` and `computation_id`) — using runtime parameters (`runId`, `computationId`, and either `metricField` for a per-metric statistic or `metricAvgs` for a run-level score) rather than hardcoded values, so that it is reusable across all runs and computations. At computation time the system SHALL execute this expression through the structured-query service, binding the run, computation, and the appropriate metric parameter. The system SHALL determine whether a definition is per-metric or run-level by which parameter its expression references (no separate discriminator column).
Status: **Planned**

#### Scenario: Definition is not bound to a specific run
- **WHEN** a definition's expression is inspected
- **THEN** the run id and computation id appear as parameters, not literal values, and the metric field is a parameter

### Requirement: Automatic metric-score computation at run completion
After a test suite run's metric-evaluation phase completes, the system SHALL compute metric scores for that run before the run transitions to its terminal completed state. It SHALL load the applicable definitions (all `DEFAULT` definitions plus `TEST_SUITE` definitions whose `target_id` matches the run's suite), enumerate the run's numeric metric output fields, and execute each definition against the run's metric-evaluation `computation_id`: per-metric definitions are executed once per metric field (persisting one result per (statistic, metric field)); run-level definitions are executed once (persisting one result). Computation SHALL reuse the same `computation_id` produced by the metric-evaluation phase.
Status: **Planned**

#### Scenario: Scores produced for each statistic and metric field
- **WHEN** a run with numeric metric output fields completes metric evaluation
- **THEN** a metric-score result exists for each (predefined statistic × metric field) under the run's computation id

#### Scenario: Suite-scoped definitions applied only to their suite
- **WHEN** a `TEST_SUITE` definition targets suite A and a run of suite B completes
- **THEN** that definition is not computed for the run of suite B

### Requirement: Overall score
The system SHALL produce an `overall` metric score per run computed **through the structured-query DSL** (not hardcoded) as the unweighted arithmetic mean of the run's per-metric averages. The `overall` definition SHALL be a seeded `DEFAULT` definition whose expression selects `mean(:metricAvgs)`; at computation time the system SHALL bind `metricAvgs` to the run's per-metric `avg(...)` terms and execute the expression once at run level, persisting a single result with `metric_score_name` and `metric_name` both equal to `overall`.
Status: **Planned**

#### Scenario: Overall is the DSL-computed mean of metric averages
- **WHEN** a run produces metric averages for its metric fields
- **THEN** the `overall` result value equals the unweighted arithmetic mean of those averages, computed by executing the stored `mean(:metricAvgs)` expression

### Requirement: Metric-score result persistence
The system SHALL persist each metric-score result in the analytics datasource with: `id` (primary key), `test_suite_run_id`, `computation_id`, `metric_score_name` (the statistic/definition name), `metric_name` (the metric output field), and a numeric `value`. Results SHALL be append-only per computation, uniquely identified by (`test_suite_run_id`, `computation_id`, `metric_score_name`, `metric_name`).
Status: **Planned**

#### Scenario: Result uniqueness within a computation
- **WHEN** the same (statistic, metric field) is written twice for the same run and computation
- **THEN** only one result row exists for that combination

### Requirement: Metric-score computation fault isolation
Failure to compute metric scores SHALL NOT fail the test suite run; the run SHALL still reach its completed state and the failure SHALL be logged. A failure computing one (statistic, metric field) pair SHALL NOT prevent computation of the remaining pairs.
Status: **Planned**

#### Scenario: Run completes despite score-computation failure
- **WHEN** metric-score computation throws for a run
- **THEN** the run still transitions to completed and the error is logged

#### Scenario: One bad metric field does not abort the rest
- **WHEN** computing a statistic over one metric field fails with a validation error
- **THEN** the remaining (statistic, metric field) pairs are still computed and persisted

### Requirement: Metric-score results read API
The system SHALL expose metric-score results for a run via `GET /api/v1/analytics/metric-score-results`, accepting a required `testSuiteRunId` and an optional `computation` (a computation id or `latest`, defaulting to `latest`). The response SHALL list results with `metric_score_name`, `metric_name`, numeric `value`, and `computation_id`.
Status: **Planned**

#### Scenario: Latest computation resolved by default
- **WHEN** results are requested for a run without specifying a computation
- **THEN** the response returns the results of the run's most recent computation

#### Scenario: Explicit computation requested
- **WHEN** results are requested with an explicit computation id
- **THEN** the response returns the results for that computation

### Requirement: Definitions are seed-only (no management API)
Metric-score definitions SHALL be provided exclusively by the seed migration (the `DEFAULT` statistics and `overall`) and SHALL NOT be created, edited, or deleted through any HTTP endpoint. The system SHALL expose no `metric-score-definitions` API. The applicable definitions are read directly by the Phase-3 computation; only the computed results are exposed (via the results read API).
Status: **Planned**

#### Scenario: No definition management endpoints exist
- **WHEN** a client looks for endpoints to create, update, or delete metric-score definitions
- **THEN** none are exposed; definitions originate only from the seed migration

> **Note:** A user-facing API to author `TEST_SUITE` (per-suite) definitions is deferred. The data model and `findApplicable` query already accommodate `TEST_SUITE` rows as the extension point, but no creation path exists yet.
