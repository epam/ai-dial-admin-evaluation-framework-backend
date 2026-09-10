## ADDED Requirements

### Requirement: CLI execution validates against the effective target deployment

The CLI `run` and `evaluate` commands SHALL apply the shared resolved request model validation using the effective target deployment ID. When `--deployment-id` is supplied, that override SHALL be the expected `model`; otherwise the fetched suite's recorded deployment ID SHALL be expected. A validation failure SHALL produce the normal execution-result row with `ERROR` status and a `REQUEST_BODY_VALIDATION_ERROR` response envelope without invoking the target for that request.

The CLI's `SuiteContractValidator` SHALL retain its structural preflight scope and SHALL NOT duplicate static request-model validation. The shared execution-time check is authoritative because it sees both the resolved body and the effective target override.

**NOTE**: this behavior is inherited from the shared `evaluation-runner-core` execution engine rather than implemented in CLI-local code. The only CLI-local contribution is placing the effective target (the `--deployment-id` override, else the fetched suite's recorded deployment) into `EvaluationContext.snapshotDeploymentRef`; the validation, the `ERROR` row, and the `REQUEST_BODY_VALIDATION_ERROR` envelope all come from the shared engine, so CLI-side coverage is a target-plumbing assertion plus the engine's own tests.

Status: **Planned**

#### Scenario: Override deployment requires a matching resolved model

- **WHEN** `--deployment-id target-model` overrides a fetched suite and a canonical fixed-path request resolves `model` to a different value
- **THEN** the CLI SHALL write an `ERROR` result containing `REQUEST_BODY_VALIDATION_ERROR`
- **AND** it SHALL not invoke the target for that request

#### Scenario: Override deployment matches

- **WHEN** `--deployment-id target-model` is effective and the resolved body contains `"model": "target-model"`
- **THEN** the CLI SHALL invoke the target normally

#### Scenario: Recorded deployment is used without an override

- **WHEN** no override is supplied for a canonical fixed-path request
- **THEN** the resolved `model` SHALL be validated against the fetched suite's recorded deployment ID

## Implementation notes

Planned. Target selection: `com.epam.aidial.evaluation.cli.service.EvaluationContextFactory` (sets `snapshotDeploymentRef` from `--deployment-id` or the fetched suite). Enforcement is inherited from `evaluation-runner-core`: `RequestModelValidator` + `RequestBodyValidationException` in `com.epam.aidial.evaluation.runner.service`, applied by `TurnLoopExecutor`. `SuiteContractValidator` is unchanged.
