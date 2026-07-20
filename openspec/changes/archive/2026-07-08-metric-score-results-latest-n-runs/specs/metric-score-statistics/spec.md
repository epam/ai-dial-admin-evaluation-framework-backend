## MODIFIED Requirements

### Requirement: Metric-score result persistence
The system SHALL persist each metric-score result in the analytics datasource with: `id` (primary key), `test_suite_run_id`, `test_suite_id`, `computation_id`, `metric_score_name` (the statistic/definition name), `metric_name` (the metric output field), a numeric `value`, and `computed_at_ms` (the epoch-millisecond compute timestamp). `test_suite_id` denormalizes the run's owning suite so results can be scoped by suite without a join; `computed_at_ms` records when the computation ran, so results can be ordered by compute time. Both `test_suite_id` and `computed_at_ms` SHALL be non-null on every persisted result. All results of a single computation SHALL share one `computed_at_ms`. Results SHALL be append-only per computation, uniquely identified by (`test_suite_run_id`, `computation_id`, `metric_score_name`, `metric_name`).
Status: **Implemented**

#### Scenario: Result uniqueness within a computation
- **WHEN** the same (statistic, metric field) is written twice for the same run and computation
- **THEN** only one result row exists for that combination

#### Scenario: Result carries suite and compute timestamp
- **WHEN** metric-score results are computed for a run
- **THEN** every persisted result has a non-null `test_suite_id` equal to the run's owning suite and a non-null `computed_at_ms`, and all results of that computation share the same `computed_at_ms`

### Requirement: Metric-score results read exclusively via the unified Query API
The system SHALL expose computed metric-score results **only** as a queryable entity (`metric_score_results`) of the structured-query model — there is no dedicated metric-score-results REST endpoint. Clients read, filter, sort, paginate, and aggregate results through `POST /api/v1/queries/execute` over the flat columns `id`, `test_suite_run_id`, `test_suite_id`, `computation_id`, `metric_score_name`, `metric_name`, `value`, and `computed_at_ms`. `test_suite_id` is queryable as a UUID field and `computed_at_ms` as a numeric (LONG) field, so results can be scoped to a suite and ordered by compute time (e.g. the latest N runs of a suite) using the model's existing filter, sort, and offset-limit primitives.

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

#### Scenario: Latest N results for a suite ordered by compute time
- **WHEN** a structured query selects from `metric_score_results` filtered by `test_suite_id eq X`, sorted by `computed_at_ms` descending, with an offset page limit of N
- **THEN** the N most recently computed matching result rows for that suite are returned in descending `computed_at_ms` order
