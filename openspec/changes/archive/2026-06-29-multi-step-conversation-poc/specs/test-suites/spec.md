## ADDED Requirements

### Requirement: Multi-step suite configuration fields
A `DEPLOYMENT` `TestSuite` SHALL support a `multiStep` boolean (default `false`) and a `multistepInputBindings` field typed as an ordered list of input-binding lists (`List<List<InputBindingDto>>`), where element `i` configures the bindings for conversation step `i`. These fields SHALL be accepted on create/update, persisted, returned in the suite response, and captured in the suite snapshot. When `multiStep == false`, both fields behave as today and `multistepInputBindings` is ignored.
Status: **Planned**

#### Scenario: Create a multi-step suite
- **WHEN** a client creates a `DEPLOYMENT` suite with `multiStep: true` and a non-empty `multistepInputBindings`
- **THEN** the suite SHALL persist both fields
- **AND** the suite response SHALL include `multiStep` and `multistepInputBindings`

#### Scenario: multiStep defaults to false
- **WHEN** a client creates a suite without specifying `multiStep`
- **THEN** the stored `multiStep` SHALL be `false`
- **AND** the suite SHALL behave exactly as a single-step suite

### Requirement: Multi-step and single-step bindings are mutually exclusive
When `multiStep == true`, the engine and validation SHALL use only `multistepInputBindings` and SHALL ignore the single `inputBindings` field. When `multiStep == false`, the engine and validation SHALL use only `inputBindings` and SHALL ignore `multistepInputBindings`.
Status: **Planned**

#### Scenario: Single inputBindings ignored for multi-step
- **WHEN** a suite has `multiStep == true`
- **THEN** validation and execution SHALL source bindings exclusively from `multistepInputBindings`
- **AND** the `inputBindings` field SHALL NOT be validated or applied

### Requirement: Multi-step suite validation
For a suite with `multiStep == true`, suite soft-validation SHALL mark the suite invalid (adding a validation warning) when any of the following holds: the resolved `requestTemplate` body is not JSON with a top-level `messages` array; `multistepInputBindings` is null or empty; `multistepInputBindings` size exceeds the configured maximum of 10 steps; or any step's bindings fail the existing per-binding cross-validation (template-variable match and test-case-schema resolution). A suite with no violations SHALL be valid.
Status: **Planned**

#### Scenario: Empty multistepInputBindings is invalid
- **WHEN** a suite has `multiStep == true` and `multistepInputBindings` is empty or null
- **THEN** the suite SHALL be marked invalid with a validation warning

#### Scenario: Step count over the cap is invalid
- **WHEN** a suite has `multiStep == true` and `multistepInputBindings` has more than 10 entries
- **THEN** the suite SHALL be marked invalid with a validation warning

#### Scenario: Non-messages body is invalid for multi-step
- **WHEN** a suite has `multiStep == true` and its request body is multipart, url-encoded, or JSON without a top-level `messages` array
- **THEN** the suite SHALL be marked invalid with a validation warning

#### Scenario: Bad per-step binding is invalid
- **WHEN** a suite has `multiStep == true` and any step's binding references a missing template variable or an unknown test-case field
- **THEN** the suite SHALL be marked invalid with a validation warning

### Implementation notes
- New fields on `data.db.model.TestSuite`, `TestSuiteRequestDto`, `TestSuiteResponseDto`, `SuiteSnapshotDto`.
- `multistepInputBindings` serialized to/from a JSONB column via a new `JsonbMapper` `List<List<InputBindingDto>>` ser/deser pair.
- Validation extends `service.domain.SuiteValidationService.validateDeploymentSuite`; step cap is `ValidationConstants.MAX_CONVERSATION_STEPS = 10`; per-step checks reuse the existing `BindingValidator`.
