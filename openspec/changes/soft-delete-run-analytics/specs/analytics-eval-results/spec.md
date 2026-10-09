## ADDED Requirements

### Requirement: Test case run result reads exclude deleted runs
Reads of `test_case_run_results` — there is no Query DSL entity for this table, so this applies to its direct repository reads (`PostgresTestCaseRunResultRepository.findAll`/`findById`/`count`, including export and listing paths) — SHALL exclude rows belonging to a test suite run that has an analytics tombstone (see `run-analytics-deletion`). The table SHALL be read through a `test_case_run_results_active` database view rather than the base table for every such read path.
Status: **Implemented**

#### Scenario: Result listing excludes a deleted run's rows
- **WHEN** a repository read of `test_case_run_results` would otherwise match rows belonging to a deleted run
- **THEN** no row belonging to that run SHALL appear in the result

#### Scenario: An active run's result rows are unaffected
- **WHEN** a repository read targets a run with no analytics tombstone
- **THEN** all of that run's matching rows SHALL be returned exactly as before this change
