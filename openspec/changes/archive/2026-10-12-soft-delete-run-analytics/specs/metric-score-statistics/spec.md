## ADDED Requirements

### Requirement: Metric score result reads exclude deleted runs
Reads of `metric_score_result` — through the `metric_score_results` Query DSL entity and through direct repository reads (`PostgresMetricScoreResultRepository.findByRunAndComputation`) alike — SHALL exclude rows belonging to a test suite run that has an analytics tombstone (see `run-analytics-deletion`). The exclusion SHALL be applied in the read query itself, as an anti-join against `run_deletions`.
Status: **Implemented**

#### Scenario: `metric_score_results` Query DSL entity excludes a deleted run's rows
- **WHEN** a structured query against the `metric_score_results` entity would otherwise match rows belonging to a deleted run
- **THEN** no row belonging to that run SHALL appear in the result

#### Scenario: Direct repository read excludes a deleted run's rows
- **WHEN** `findByRunAndComputation` is called for a run that has an analytics tombstone
- **THEN** no row SHALL be returned for that run, regardless of whether matching rows physically exist
