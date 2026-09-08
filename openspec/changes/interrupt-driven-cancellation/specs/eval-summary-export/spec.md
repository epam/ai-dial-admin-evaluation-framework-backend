## MODIFIED Requirements

### Requirement: Run state guard (terminal-only)
Both the export endpoint and the preview endpoint SHALL reject requests targeting a `TestSuiteRun` whose `status` is not terminal (`PENDING`, `RUNNING` or `CANCELLING`) with `HTTP 409 Conflict` and error code `RUN_NOT_TERMINAL`. Terminal statuses (`COMPLETED`, `FAILED`, `CANCELLED`) are the only ones allowed because cursor pagination over `test_case_eval_summaries` requires a stable snapshot of the underlying table; concurrent inserts during a non-terminal run would produce skipped or duplicated rows in the response. The check SHALL use `RunStatus.isTerminal(run.getStatus())` after loading the `TestSuiteRun` and before any column-planning or repository read.
Status: **Implemented**

#### Scenario: Export rejects a RUNNING run
- **WHEN** a client invokes `POST /api/v1/analytics/eval-summaries/export.csv` for a `TestSuiteRun` whose `status` is `RUNNING`
- **THEN** the service SHALL return `HTTP 409` with error code `RUN_NOT_TERMINAL` and an error message that references the run's current status

#### Scenario: Export rejects a PENDING run
- **WHEN** a client invokes the export endpoint for a `TestSuiteRun` whose `status` is `PENDING`
- **THEN** the service SHALL return `HTTP 409` with error code `RUN_NOT_TERMINAL`

#### Scenario: Export rejects a CANCELLING run
- **WHEN** a client invokes the export endpoint for a `TestSuiteRun` whose `status` is `CANCELLING`
- **THEN** the service SHALL return `HTTP 409` with error code `RUN_NOT_TERMINAL` — the run is still being finalized and its eval summaries may still change

#### Scenario: Preview applies the same guard
- **WHEN** a client invokes `GET /api/v1/analytics/eval-summaries/export/preview` for a `TestSuiteRun` whose `status` is `RUNNING`, `PENDING` or `CANCELLING`
- **THEN** the service SHALL return `HTTP 409` with error code `RUN_NOT_TERMINAL`

#### Scenario: Terminal runs are allowed
- **WHEN** a client invokes either endpoint for a `TestSuiteRun` whose `status` is `COMPLETED`, `FAILED`, or `CANCELLED`
- **THEN** the service SHALL proceed with the request (no 409 is raised by the state guard; other validation still applies)
