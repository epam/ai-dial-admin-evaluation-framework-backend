## ADDED Requirements

### Requirement: Metric definition mutations refresh suite score-reference warnings
After a successful TSMD create, update or delete, the system SHALL recompute the owning suite's score-reference warnings (see `test-suites` "Score metric reference validation") synchronously, in the same transaction, before responding. Only score-reference warnings (those whose `path` starts with `$.overallScore.` or `$.testCaseOverallScore.`) SHALL be replaced; all other suite warnings SHALL be kept unchanged. The suite's `isValid` SHALL then equal "warnings list is empty". The rest of the suite configuration SHALL NOT be revalidated. When the resulting warnings and validity are unchanged, the suite SHALL NOT be written (its `updatedAt` stays the same). Unreadable stored score content SHALL NOT fail the TSMD mutation (an unreadable `overallScore` yields an `UNSUPPORTED_SCORE_FUNCTION` warning; an unreadable `testCaseOverallScore` is ignored); other refresh failures SHALL roll back the TSMD mutation.

Status: **Implemented**

#### Scenario: Creating the referenced metric resolves the warning
- **WHEN** a suite carries an `UNRESOLVED_REFERENCE` warning for `Accuracy` and no other warnings, and client creates a TSMD named `Accuracy`
- **THEN** a subsequent `GET /api/v1/test-suites/{suiteId}` SHALL return `valid = true` with no warnings

#### Scenario: Deleting a referenced metric adds a warning
- **WHEN** client deletes the TSMD named `Accuracy` that the suite's `overallScore` references
- **THEN** the suite SHALL carry an `UNRESOLVED_REFERENCE` warning for `Accuracy` and `valid = false`

#### Scenario: Renaming a referenced metric adds a warning
- **WHEN** client updates the TSMD `Accuracy` to name `Precision` while the suite's score still references `Accuracy`
- **THEN** the suite SHALL carry an `UNRESOLVED_REFERENCE` warning for `Accuracy`

#### Scenario: Unrelated suite warnings are preserved
- **WHEN** a suite carries a binding warning and a score-reference warning, and a TSMD mutation resolves the score reference
- **THEN** the suite SHALL still carry the binding warning and remain `valid = false`

#### Scenario: Unaffected mutation does not touch the suite
- **WHEN** client creates a TSMD not referenced by the suite's score definitions and the suite's score warnings do not change
- **THEN** the suite's `updatedAt` SHALL remain unchanged

## Implementation notes

- `TestSuiteMetricDefinitionService` create/update/delete → `SuiteScoreWarningsRefresher` → `ScoreReferenceValidator`, persisted via `TestSuiteRepository.updateValidation`.
