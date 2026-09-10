## ADDED Requirements

### Requirement: Execution engine rejects invalid resolved deployment models

The shared deployment execution engine SHALL perform resolved request model validation after request-body resolution and before serialization for invocation. On failure it SHALL skip the HTTP call, persist an `ERROR` result for the current request/turn with a `REQUEST_BODY_VALIDATION_ERROR` response envelope, preserve the resolved request body in the result for diagnostics, and apply the existing fail-fast request-chain semantics. Other test cases SHALL continue independently.

Status: **Planned**

#### Scenario: Invalid model produces an error row without invocation

- **WHEN** a backend run resolves an invalid `model` for a canonical fixed-path request
- **THEN** the current request/turn SHALL persist one `ERROR` row whose response body contains `REQUEST_BODY_VALIDATION_ERROR`
- **AND** its resolved request body SHALL be persisted in `requestBody`
- **AND** the deployment SHALL not be invoked for that request/turn

#### Scenario: Failure aborts the remaining chain for that case

- **WHEN** resolved model validation fails at a request/turn in a multi-request or multi-turn execution
- **THEN** later turns of that request and later requests in that test case's chain SHALL not execute
- **AND** earlier completed rows SHALL remain unchanged

#### Scenario: Non-target requests retain existing behavior

- **WHEN** the resolved URL is not one of the two canonical model-selecting paths
- **THEN** execution SHALL not require its JSON body to contain a matching `model`

#### Scenario: Backend run guard limits which static failures can execute

- **WHEN** a newly saved plain-content suite has a static model-validation warning and is therefore `isValid=false`
- **THEN** the existing backend run-creation guard SHALL reject the run before Phase 1 execution
- **AND** runtime model validation SHALL protect JSONata-resolved bodies and previously persisted suites that reach execution

## Implementation notes

Planned. `TurnLoopExecutor` (`evaluation-runner-core`, `com.epam.aidial.evaluation.runner.job`) calls the shared `RequestModelValidator` after `RequestResolver.resolveForRun` and before URL construction/serialization, catching `RequestBodyValidationException` and emitting the new `ExecutionErrorCodes.REQUEST_BODY_VALIDATION_ERROR` outcome. The `createRun` guard referenced above is guard #3 in `com.epam.aidial.evaluation.service.domain.TestSuiteRunService` (see `docs/patterns/suite-validity-and-run-guards.md`).
