## ADDED Requirements

### Requirement: Eval summary and eval score reads exclude deleted runs
Reads of `test_case_eval_summaries` and `test_case_eval_scores` — through their respective Query DSL entities (`eval_summaries`, `test_case_eval_scores`) and through direct repository reads (`PostgresEvalSummaryRepository`'s list/export/aggregate/comparison methods; `findById`) alike — SHALL exclude rows belonging to a test suite run that has an analytics tombstone (see `run-analytics-deletion`). Both tables SHALL be read through a corresponding `<table>_active` database view rather than the base table for every such read path.
Status: **Implemented**

#### Scenario: Eval summaries list excludes a deleted run's rows
- **WHEN** `GET /api/v1/analytics/eval-summaries` is queried with a filter that would otherwise match rows belonging to a deleted run
- **THEN** no row belonging to that run SHALL appear in the result

#### Scenario: Eval score Query DSL entity excludes a deleted run's rows
- **WHEN** a structured query against the `test_case_eval_scores` Query DSL entity would otherwise match rows belonging to a deleted run
- **THEN** no row belonging to that run SHALL appear in the result

#### Scenario: Aggregate and comparison reads exclude a deleted run's rows
- **WHEN** the eval-summary aggregate endpoint or the run-comparison matched-row logic reads rows for a run that has an analytics tombstone
- **THEN** that run's rows SHALL be excluded, consistent with every other read of `test_case_eval_summaries`
