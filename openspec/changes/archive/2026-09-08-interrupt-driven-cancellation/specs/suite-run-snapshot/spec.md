## MODIFIED Requirements

### Requirement: Retention cleanup
Expired `test_case_run_inputs` rows SHALL be deleted by a daily cleanup job.
Status: **Implemented**

#### Scenario: Retention job deletes expired inputs
- **WHEN** `TestCaseRunInputsRetentionJob.deleteExpiredInputs()` runs
- **THEN** it SHALL delete rows from `test_case_run_inputs` where `run_id IN (SELECT id FROM test_suite_runs WHERE status IN ('COMPLETED','FAILED') AND updated_at_ms < NOW() - retention)`

#### Scenario: Non-terminal run inputs preserved
- **WHEN** a run has status PENDING, RUNNING or CANCELLING
- **THEN** its `test_case_run_inputs` rows SHALL NOT be deleted regardless of age

#### Scenario: Recent terminal run inputs preserved
- **WHEN** a run reached terminal state within the retention window (default 1 day)
- **THEN** its `test_case_run_inputs` rows SHALL NOT be deleted

#### Scenario: Retention failure does not crash
- **WHEN** the retention job encounters an exception
- **THEN** it SHALL log a warning and return normally (no re-throw)
