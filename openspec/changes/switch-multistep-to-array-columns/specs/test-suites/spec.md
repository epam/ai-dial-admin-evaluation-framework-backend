## MODIFIED Requirements

### Requirement: Multi-step suite configuration fields
A `DEPLOYMENT` `TestSuite` SHALL support a `multiStep` boolean (default `false`). When `multiStep == true`, the suite uses its regular single `inputBindings` and `requestTemplate`, exactly like a single-step suite; per-turn variation comes from array-valued test-case columns at execution time (see multi-step-conversation). The `multiStep` flag SHALL be accepted on create/update, persisted, returned in the suite response, and captured in the suite snapshot. There SHALL be no suite-level per-turn binding configuration.
Status: **Planned**

#### Scenario: Create a multi-step suite
- **WHEN** a client creates a `DEPLOYMENT` suite with `multiStep: true` and a single `inputBindings`
- **THEN** the suite SHALL persist the `multiStep` flag and `inputBindings`
- **AND** the suite response SHALL include `multiStep`

#### Scenario: multiStep defaults to false
- **WHEN** a client creates a suite without specifying `multiStep`
- **THEN** the stored `multiStep` SHALL be `false`
- **AND** the suite SHALL behave exactly as a single-step suite

### Requirement: Multi-step suite validation
For a suite with `multiStep == true`, suite soft-validation SHALL mark the suite invalid (adding a validation warning) when either of the following holds: the resolved `requestTemplate` body is not JSON with a top-level `messages` array; or the single `inputBindings` fail the existing per-binding cross-validation (template-variable match and test-case-schema resolution). A suite with no violations SHALL be valid. Turn count and array-shape checks are per-test-case data concerns evaluated at execution time, not suite-validation concerns.
Status: **Planned**

#### Scenario: Non-messages body is invalid for multi-step
- **WHEN** a suite has `multiStep == true` and its request body is multipart, url-encoded, or JSON without a top-level `messages` array
- **THEN** the suite SHALL be marked invalid with a validation warning

#### Scenario: Bad binding is invalid
- **WHEN** a suite has `multiStep == true` and a binding references a missing template variable or an unknown test-case field
- **THEN** the suite SHALL be marked invalid with a validation warning

#### Scenario: Valid multi-step suite
- **WHEN** a suite has `multiStep == true`, a JSON body with a top-level `messages` array, and `inputBindings` that pass cross-validation
- **THEN** the suite SHALL be valid

## REMOVED Requirements

### Requirement: Multi-step and single-step bindings are mutually exclusive
**Reason**: Suite-level per-turn `multistepInputBindings` is removed. Multi-step suites now use the same single `inputBindings` as single-step suites; there is no second binding field to be mutually exclusive with.
**Migration**: Configure the suite's single `inputBindings` and provide array-valued columns in the dataset for the bound `dataField`s. The `multistepInputBindings` field and its DB column are dropped.
