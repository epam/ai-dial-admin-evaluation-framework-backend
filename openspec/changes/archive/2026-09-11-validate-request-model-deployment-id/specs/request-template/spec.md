## ADDED Requirements

### Requirement: Resolved request model matches the effective deployment

Before executing a request whose resolved URL is exactly `/openai/v1/responses` or `/anthropic/v1/messages`, the system SHALL validate the resolved JSON body. Its `model` field MUST be a string and MUST exactly equal the effective deployment ID selected for that execution. This runtime rule SHALL apply identically whether the template was authored as plain `content` or `jsonataContent`; it SHALL not apply to non-canonical URLs.

Status: **Implemented**

#### Scenario: Resolved model matches

- **WHEN** a canonical fixed-path request resolves to a JSON body whose string `model` equals the effective deployment ID
- **THEN** execution SHALL proceed normally

#### Scenario: Resolved body or model is invalid

- **WHEN** a canonical fixed-path request resolves without a JSON body, with a non-JSON body, without `model`, with JSON null, or with a non-string `model`
- **THEN** validation SHALL fail before any HTTP request is sent

#### Scenario: Resolved model differs

- **WHEN** a canonical fixed-path request resolves to a string `model` different from the effective deployment ID
- **THEN** validation SHALL fail before any HTTP request is sent

#### Scenario: JSONata is checked after evaluation

- **WHEN** a `jsonataContent` expression evaluates successfully to a JSON object for a canonical fixed-path request
- **THEN** the model rule SHALL validate that evaluated object rather than the source expression

### Requirement: Dedicated runtime model validation error

A resolved request model failure SHALL be represented by the execution error code `REQUEST_BODY_VALIDATION_ERROR`, distinct from JSONata evaluation failures and general request-resolution failures. The diagnostic message SHALL identify the invalid `model` condition and expected deployment ID without sending the request.

Status: **Implemented**

#### Scenario: Validation error remains distinct from evaluation error

- **WHEN** JSONata evaluation succeeds but its resulting `model` is invalid
- **THEN** the failure code SHALL be `REQUEST_BODY_VALIDATION_ERROR`
- **AND** it SHALL not be reported as `REQUEST_BODY_EVALUATION_ERROR`

#### Scenario: Resolved-request preview remains non-executing

- **WHEN** a client only retrieves the resolved-request preview without invoking Try-It-Out or a run
- **THEN** the new runtime execution failure SHALL not be raised by that preview operation

## Implementation notes

Implemented. `RequestModelValidator` and `RequestBodyValidationException` in `evaluation-runner-core` (`com.epam.aidial.evaluation.runner.service`), sharing canonical-path constants with `DialCoreUrlBuilder`. Consumers: `TurnLoopExecutor` (runs and CLI), `com.epam.aidial.evaluation.service.domain.TryItOutService`, and `SuiteValidationService` for the static plain-`content` variant. Error codes: `ExecutionErrorCodes.REQUEST_BODY_VALIDATION_ERROR` and the matching `ValidationWarningCode`.
