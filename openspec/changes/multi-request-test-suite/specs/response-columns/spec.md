## MODIFIED Requirements

### Requirement: Store response column definitions on TestSuite

Response column definitions SHALL be stored as a JSONB array on the `test_suites` table, managed via existing suite create/update endpoints. For a multi-request suite, response column definitions additionally live on each `additionalRequests` element, and the suite's **effective** response column set is the chain union — request 0's flat `responseColumns` followed by each chain element's `responseColumns` in chain order. Response column names SHALL be unique across that union, so every response column is owned by exactly one chain request and is addressable by bare name without request qualification.

Status: **Planned**

#### Scenario: Create suite with response columns
- **WHEN** client calls `POST /api/v1/test-suites` with `responseColumns` in the request body
- **THEN** system SHALL persist the definitions and return them in the response

#### Scenario: Update suite response columns
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with updated `responseColumns`
- **THEN** system SHALL replace the existing definitions with the new array

#### Scenario: Suite with no response columns
- **WHEN** `responseColumns` is omitted or null on create/update
- **THEN** system SHALL default to an empty array `[]`

#### Scenario: Get suite returns response columns
- **WHEN** client calls `GET /api/v1/test-suites/{id}`
- **THEN** response SHALL include `responseColumns` array (empty if none defined)

#### Scenario: Chain union forms the effective response column set
- **WHEN** a multi-request suite declares `session_id` on request 0 and `answer` on request 1
- **THEN** the suite's effective response column set SHALL be `[session_id, answer]` in chain order, and both SHALL be addressable by bare name

#### Scenario: Duplicate name across chain requests is rejected
- **WHEN** a suite is saved declaring response column `answer` on two different chain requests
- **THEN** the system SHALL respond HTTP 400 `VALIDATION_ERROR` naming the duplicated column, and SHALL NOT persist the suite

#### Scenario: Same name on one request is still rejected
- **WHEN** a single chain element declares `answer` twice within its own `responseColumns`
- **THEN** the system SHALL respond HTTP 400 `VALIDATION_ERROR`

## ADDED Requirements

### Requirement: Extraction is scoped to the producing chain request
Response column extraction SHALL evaluate, for each chain request, only that request's own `responseColumns` against that request's response body. A result row's `extracted_columns` SHALL therefore contain only the columns owned by its request, and extraction warnings SHALL likewise be scoped to that request.
Status: **Planned**

#### Scenario: Extraction is request-local
- **WHEN** request 0 declares `session_id` and request 1 declares `answer`
- **THEN** request 0's row SHALL carry `extracted_columns` containing only `session_id`, and request 1's row only `answer`

#### Scenario: Extraction warning is scoped to its request
- **WHEN** request 1's `answer` expression matches nothing
- **THEN** the extraction warning SHALL be recorded on request 1's row only, and request 0's row SHALL be unaffected

## Implementation notes

`ResponseColumnExtractor` invoked once per chain request; chain-union uniqueness enforced by the shared chain normalizer at suite save.
