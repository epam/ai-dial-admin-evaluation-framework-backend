## ADDED Requirements

### Requirement: Metric-score definition model
The system SHALL model a metric-score definition with: an `id` (UUID), a `type` ∈ {`DEFAULT`, `TEST_SUITE`}, a `name`, an optional `description`, an `expression` (a serialized structured query), and an optional `target_id` (UUID). `DEFAULT` definitions SHALL have a null `target_id`; `TEST_SUITE` definitions SHALL carry the test-suite id in `target_id`. Definitions are configuration and SHALL be persisted in the **meta** datasource (alongside `test_suites`); `target_id` is a same-datasource reference to a test suite.
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
After a test suite run's metric-evaluation phase completes, the system SHALL compute metric scores for that run before the run transitions to its terminal completed state. It SHALL load all seeded `DEFAULT` per-metric definitions, enumerate the run's numeric metric output fields, and execute each definition once per metric field against the run's metric-evaluation `computation_id` (persisting one result per (statistic, metric field)). It SHALL additionally compute the run-level `overall` from the suite snapshot's `overall_score` (per the Overall score requirement). Computation SHALL reuse the same `computation_id` produced by the metric-evaluation phase.
Status: **Planned**

#### Scenario: Scores produced for each statistic and metric field
- **WHEN** a run with numeric metric output fields completes metric evaluation
- **THEN** a metric-score result exists for each (predefined statistic × metric field) under the run's computation id

### Requirement: Overall score (default; per-suite definition reserved)
The `overall` metric score is a **per-test-suite** definition rather than a seeded catalog entry. The test suite SHALL carry a nullable `overall_score` column (a structured-query `StructuredQuery` expression, JSONB), captured **verbatim** into the suite snapshot at run start, so each run computes `overall` per the suite's configuration at run time. The column is NOT seeded/defaulted in SQL; null means "use the system default".

In this version the system SHALL NOT expose any way to set a custom `overall_score` — the column and snapshot field are a **reserved extension point**, the column stays null, and `overall` is always computed from the built-in **default** expression (a Java constant, `mean(:metricAvgs)`). Setting a per-suite custom `overall` is future work; the Phase-3 executor already resolves the expression from the snapshot and computes a non-null one when present, so enabling it later requires no executor change.

At computation time (Phase 3) the system SHALL resolve the run's `overall` expression from the snapshot and compute it **through the structured-query DSL** (not hardcoded), binding `metricAvgs` to the run's per-metric `avg(...)` terms and executing once at run level, persisting a single result with `metric_score_name` and `metric_name` both equal to `overall`. The default SHALL be computed **only when the run resolves to exactly one numeric metric field** (so the mean is unambiguous = that metric's average); with more than one metric field the default produces **no** `overall` result (deferred to future per-suite metric selection).
Status: **Planned**

#### Scenario: Default overall for a single-metric run
- **WHEN** a run with exactly one numeric metric field completes (suite has no custom `overall_score`)
- **THEN** an `overall` result is produced equal to that metric's average, computed by executing the default `mean(:metricAvgs)` expression

#### Scenario: Default overall skipped for a multi-metric run
- **WHEN** a run with more than one numeric metric field completes (suite has no custom `overall_score`)
- **THEN** no `overall` result is produced (only the per-metric statistics)

#### Scenario: Overall configuration is pinned in the suite snapshot
- **WHEN** a run's suite snapshot is taken
- **THEN** the snapshot carries the suite's `overall_score` verbatim (null today), and Phase 3 computes `overall` from the snapshotted value, not the live suite

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

### Requirement: Metric-score results queryable via the unified Query API
The system SHALL expose computed metric-score results as a queryable entity (`metric_score_results`) of the structured-query model, so clients can read, filter, sort, paginate, and aggregate them through `POST /api/v1/queries/execute` over the flat columns `id`, `test_suite_run_id`, `computation_id`, `metric_score_name`, `metric_name`, `value`. The query layer is parameter-free with respect to computation selection — it operates on a concrete `computation_id`; resolving the `latest` computation remains a server-side concern (the dedicated read API above) and is NOT a feature of the generic query endpoint. Cross-computation reads (e.g. comparing the last N runs) SHALL be expressible by filtering `computation_id` with `in`.
Status: **Planned**

#### Scenario: Results queried for an explicit run and computation
- **WHEN** a structured query selects from `metric_score_results` filtered by `test_suite_run_id eq X and computation_id eq Y`
- **THEN** the matching result rows are returned with their `metric_score_name`/`metric_name`/`value`

#### Scenario: Results aggregated across computations
- **WHEN** a structured query filters `computation_id in [...]` and aggregates `value` grouped by `metric_score_name`
- **THEN** the aggregate is computed across the selected computations in a single response

### Requirement: Definitions are seed-only (no management API)
The seeded metric-score definitions (the `DEFAULT` per-metric statistics `AVG`/`P10`/`P90`/`MIN`/`MAX`) SHALL be provided exclusively by the seed migration and SHALL NOT be created, edited, or deleted through any `metric-score-definitions` HTTP endpoint; the system exposes no such API. The applicable definitions are read directly by the Phase-3 computation; only the computed results are exposed (via the results read API). The `overall` definition is NOT part of this seeded catalog — it is a per-suite property (`test_suites.overall_score`); its API exposure is reserved for future work, so today it stays null and `overall` uses the built-in default.
Status: **Planned**

#### Scenario: No definition management endpoints exist
- **WHEN** a client looks for endpoints to create, update, or delete metric-score definitions
- **THEN** none are exposed; definitions originate only from the seed migration

> **Note:** Per-suite metric-score configuration is reserved through `test_suites.overall_score` (the `overall` definition), snapshotted per run. Setting it (suite create/update exposure) and a richer per-suite "score config" (modes, weights, metric selection) compiled into a `StructuredQuery` are deferred; today the column stays null and `overall` uses the built-in default. The seeded `metric_score_definition` catalog holds only the generic `DEFAULT` per-metric statistics; its dormant `type`/`target_id` columns remain as an extension point.
