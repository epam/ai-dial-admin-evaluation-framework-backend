## MODIFIED Requirements

### Requirement: Delete a test suite run
The service SHALL provide `DELETE /api/v1/test-suite-runs/{id}` to delete a run and its related resources. Only runs in a terminal status (COMPLETED, FAILED, CANCELLED) MAY be deleted. PENDING and RUNNING runs MUST be cancelled first; a CANCELLING run MUST finish cancelling first. Deleting a run SHALL also delete its `run_metric_snapshots` rows via database CASCADE.
Status: **Implemented**

#### Scenario: Delete terminal run
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a run with status COMPLETED, FAILED, or CANCELLED
- **THEN** system SHALL delete the run record (and any further related resources via CASCADE) and return HTTP 204 No Content

#### Scenario: Run metric snapshots are removed with the run
- **WHEN** a run holding `run_metric_snapshots` rows is deleted
- **THEN** those snapshot rows SHALL be deleted by database CASCADE, leaving no snapshot row referencing a non-existent run

#### Scenario: Analytics result rows are not removed with the run
- **WHEN** a run holding analytics rows (`test_case_run_results`, `test_case_eval_summaries`, `metric_score_results`) is deleted
- **THEN** those rows SHALL remain, because no foreign key can span the meta and analytics databases. Callers MUST NOT rely on run deletion to reclaim analytics storage.

#### Scenario: Delete RUNNING run rejected
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a run with status RUNNING
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message suggesting to cancel the run first

#### Scenario: Delete PENDING run rejected
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a run with status PENDING
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message indicating that PENDING runs must complete or be cancelled before deletion

#### Scenario: Delete CANCELLING run rejected
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a run with status CANCELLING
- **THEN** system SHALL respond with HTTP 409 Conflict and error code `INVALID_OPERATION` with a message indicating the run is still being cancelled

#### Scenario: Delete non-existent run
- **WHEN** client calls `DELETE /api/v1/test-suite-runs/{id}` for a non-existent id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

#### Scenario: Cascade delete on test suite removal
- **WHEN** a test suite is deleted via `DELETE /api/v1/test-suites/{id}`
- **THEN** all associated test suite runs SHALL be deleted automatically via database CASCADE, and each deleted run's `run_metric_snapshots` rows SHALL be deleted in turn

## Implementation notes

- Cascade is declared on the meta `run_metric_snapshots.test_suite_run_id` foreign key (`V1.32__CreateRunMetricSnapshotsTable.sql`); `TestSuiteRunService.deleteRun` needs no extra cleanup call.
