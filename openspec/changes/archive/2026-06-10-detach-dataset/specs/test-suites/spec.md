## ADDED Requirements

### Requirement: Test suite supports detach-dataset action
The system SHALL expose a `detach-dataset` action endpoint on the test-suite resource at `POST /api/v1/test-suites/{id}/detach-dataset`. Full contract is defined in the `detach-dataset` capability spec.

#### Scenario: Detach action is available on the test-suite resource
- **WHEN** a client calls `POST /api/v1/test-suites/{id}/detach-dataset`
- **THEN** the system processes the request according to the `detach-dataset` capability spec and returns `TestSuiteResponseDto`
