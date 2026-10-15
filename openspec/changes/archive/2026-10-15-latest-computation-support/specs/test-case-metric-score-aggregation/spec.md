## MODIFIED Requirements

### Requirement: `test_case_metric_scores` Query DSL entity
The system SHALL expose `test_case_metric_scores_aggregated` as a Query DSL entity named
`test_case_metric_scores`, flattening the `metric_scores` JSONB column into addressable fields
`metric_scores::<metricName>::avg|min|max|count`, using the same JSONB-flattening mechanism already used
for `eval_summaries.metric_values`. This entity SHALL be reachable through the existing generic query
endpoint (`POST /api/v1/queries/execute`), consistent with how `metric_score_results` is exposed.
The entity SHALL accept the sentinel `computation_id eq "latest"` and resolve each selected run's own latest
computation, per `latest-computation-resolution`; an explicit `computation_id` value is honoured verbatim and
an omitted `computation_id` spans all computations.
Status: **Implemented**

#### Scenario: A flattened metric field is queryable
- **WHEN** a structured query selects `metric_scores::MetricA::avg` from `test_case_metric_scores`, filtered by `test_case_id`
- **THEN** the query returns that test case's aggregated average for `MetricA`

#### Scenario: `computation_id eq "latest"` is accepted on the entity
- **WHEN** a structured query on `test_case_metric_scores` filters `test_suite_run_id in [A, B] and computation_id eq "latest"`
- **THEN** run A's rows come from A's latest computation and run B's rows from B's latest computation, and the sentinel is not rejected as an invalid UUID
