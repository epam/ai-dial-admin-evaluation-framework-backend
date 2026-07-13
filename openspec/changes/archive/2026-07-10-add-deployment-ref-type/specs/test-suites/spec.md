## ADDED Requirements

### Requirement: Deployment reference carries an optional `type`
The DEPLOYMENT suite's `deploymentRef` object SHALL support an optional `type` field alongside its existing `id`, `name`, and `version` fields. `type` is a free-text string with a maximum length of 50 characters that conveys the deployment kind (expected values `dial-model` or `dial-application`). The field is OPTIONAL: it MUST NOT be required, and its absence is valid. When supplied on create/update/clone, the system SHALL persist `type` and return it on read; when omitted, the system SHALL treat and return it as `null`. The field SHALL round-trip both on the suite (`deploymentRef` in the suite response) and inside the frozen run snapshot (`suiteSnapshot.deploymentRef` on a test suite run).

#### Scenario: Create suite with deployment type persists and returns it
- **WHEN** client calls `POST /api/v1/test-suites` for a DEPLOYMENT suite with `deploymentRef.type = "dial-application"`
- **THEN** the created suite is persisted with that `type` and a subsequent `GET /api/v1/test-suites/{id}` returns `deploymentRef.type = "dial-application"`

#### Scenario: Deployment type is optional
- **WHEN** client creates or updates a DEPLOYMENT suite with a `deploymentRef` that omits `type`
- **THEN** the request succeeds (no HTTP 400 for the missing `type`) and reading the suite back returns `deploymentRef.type = null`

#### Scenario: Deployment type appears in the run snapshot
- **WHEN** a test suite run is created from a suite whose `deploymentRef.type = "dial-model"`
- **THEN** reading the run's detail returns `suiteSnapshot.deploymentRef.type = "dial-model"`

#### Scenario: Deployment type exceeding the length limit is rejected
- **WHEN** client submits a `deploymentRef.type` longer than 50 characters
- **THEN** the system responds with HTTP 400 (`VALIDATION_ERROR`)
