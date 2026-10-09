## ADDED Requirements

### Requirement: Aggregated metric score reads exclude deleted runs
Reads of `test_case_metric_scores_aggregated` — through the `test_case_metric_scores` Query DSL entity and through direct repository reads (`PostgresTestCaseMetricScoreAggregatedRepository.findByRunIdAndComputationId`) alike — SHALL exclude rows belonging to a test suite run that has an analytics tombstone (see `run-analytics-deletion`). The table SHALL be read through a `test_case_metric_scores_aggregated_active` database view rather than the base table for every such read path.
Status: **Implemented**

#### Scenario: `test_case_metric_scores` Query DSL entity excludes a deleted run's rows
- **WHEN** a structured query against the `test_case_metric_scores` entity would otherwise match rows belonging to a deleted run
- **THEN** no row belonging to that run SHALL appear in the result

#### Scenario: Direct repository read excludes a deleted run's rows
- **WHEN** `findByRunIdAndComputationId` is called for a run that has an analytics tombstone
- **THEN** no row SHALL be returned for that run, regardless of whether matching rows physically exist
