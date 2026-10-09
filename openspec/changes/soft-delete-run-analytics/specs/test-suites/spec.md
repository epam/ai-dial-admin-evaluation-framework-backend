## MODIFIED Requirements

### Requirement: Delete a TestSuite
The service SHALL allow deleting a TestSuite by id. The deletion SHALL cascade only to suite-owned children (TSMDs, runs, etc.). The referenced `Dataset` SHALL NOT be deleted (it may be shared with other suites). Test cases SHALL NOT be deleted (they live in the dataset and are reachable from any other suite referencing the same dataset). The delete response SHALL NOT include a `deletedTestCases` count. Before the suite row is deleted (and its runs cascade via the meta-DB foreign key), the system SHALL write an analytics tombstone (see `run-analytics-deletion`) for every run owned by the suite, so those runs' analytics rows become excluded from reads the same as a run deleted individually via `DELETE /api/v1/test-suite-runs/{id}`.
Status: **Planned**

#### Scenario: Existing id
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` for an existing TestSuite
- **THEN** system SHALL delete the suite and its owned children (TSMDs, runs); the response SHALL be HTTP 204 (or HTTP 200 with a delete-response body per project convention) and SHALL NOT carry `deletedTestCases`

#### Scenario: Cascade delete suite-owned children only
- **WHEN** system deletes a TestSuite
- **THEN** it SHALL delete suite-owned children (TSMDs, runs, eval-summaries) but SHALL NOT delete test cases under the dataset nor the dataset itself

#### Scenario: Missing id
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` for a non-existent TestSuite
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Suite deletion tombstones every owned run's analytics data
- **WHEN** a suite with one or more test suite runs is deleted
- **THEN** an analytics tombstone SHALL be written for each of the suite's run ids before the suite row (and its runs, via cascade) is deleted, so every one of those runs' analytics rows becomes excluded from reads

#### Scenario: Suite with no runs deletes without any tombstone write
- **WHEN** a suite with zero test suite runs is deleted
- **THEN** no analytics tombstone write SHALL be attempted
