# Test Suites (delta)

## ADDED Requirements

### Requirement: Unique TestSuite name
The service SHALL enforce that TestSuite `name` is unique across all suites (case-insensitive). `"MyTest"` and `"mytest"` are considered duplicates. Create and update SHALL reject requests that would result in a duplicate name with HTTP 409 Conflict and error code `UNIQUE_CONSTRAINT_VIOLATION`. The error message SHALL include the duplicated name.

#### Scenario: Duplicate name on create
- **WHEN** client calls `POST /api/v1/test-suites` with a body whose `name` already exists for another TestSuite (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message including the duplicated name

#### Scenario: Duplicate name on update
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a body whose `name` already exists for a different TestSuite (case-insensitive match, another id)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message including the duplicated name

#### Scenario: Update to own current name succeeds
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a body whose `name` equals the suite's current name (no change, or only case change)
- **THEN** system SHALL accept the request and return HTTP 200 (no 409)

#### Scenario: Case variation is duplicate
- **WHEN** a TestSuite named `"Alpha"` exists and client creates a TestSuite named `"alpha"`
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`
