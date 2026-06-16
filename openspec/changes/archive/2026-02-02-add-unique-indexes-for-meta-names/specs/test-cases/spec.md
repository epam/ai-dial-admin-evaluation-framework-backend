# Test Cases (delta)

## ADDED Requirements

### Requirement: Unique testCaseName within TestSuite
The service SHALL enforce that `testCaseName` is unique within a TestSuite (case-insensitive). `"TestA"` and `"testa"` are considered duplicates within the same suite. Create, update (PUT/PATCH when changing name), and CSV import SHALL reject requests that would result in a duplicate `(testSuiteId, testCaseName)` with HTTP 409 Conflict and error code `UNIQUE_CONSTRAINT_VIOLATION`. The error message SHALL include the duplicated name(s).

#### Scenario: Duplicate testCaseName on create
- **WHEN** client calls `POST /api/v1/test-suites/{testSuiteId}/test-cases` with a `testCaseName` that already exists in that TestSuite (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message including the duplicated name

#### Scenario: Duplicate testCaseName on update
- **WHEN** client calls `PUT /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}` with a body whose `testCaseName` already exists for another TestCase in the same TestSuite (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message including the duplicated name

#### Scenario: Duplicate testCaseName on PATCH
- **WHEN** client calls `PATCH .../test-cases/{testCaseId}` with a `testCaseName` that already exists for another TestCase in the same TestSuite (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message including the duplicated name

#### Scenario: Same testCaseName in different suites succeeds
- **WHEN** client creates a TestCase with `testCaseName` that exists in a different TestSuite (but not in the target suite)
- **THEN** system SHALL create it successfully (HTTP 201, no 409)

#### Scenario: Case variation is duplicate within suite
- **WHEN** a TestCase named `"CaseOne"` exists in a suite and client creates a TestCase named `"caseone"` in the same suite
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: CSV import fails entirely on duplicate within CSV
- **WHEN** client calls CSV import with a file containing two or more rows with the same `testCaseName` (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message listing the duplicated name(s); no rows SHALL be imported

**Note**: CSV import uses replace-all mode (deletes existing test cases before importing), so conflicts with pre-existing data in the suite do not apply—only duplicates within the imported CSV are detected.
