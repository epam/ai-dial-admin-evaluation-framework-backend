## MODIFIED Requirements

### Requirement: Multi-step suite configuration fields
A `DEPLOYMENT` `TestSuite` SHALL support a `multiTurn` boolean (default `false`) — renamed from the branch-only `multiStep`; the underlying column becomes `test_suites.multi_turn`. When `multiTurn == true`, the suite uses its regular single `inputBindings` and `requestTemplate`, exactly like a single-turn suite; per-turn variation comes from array-valued test-case columns at execution time (see multi-step-conversation). The `multiTurn` flag SHALL be accepted on create/update, persisted, returned in the suite response, and captured in the suite snapshot (`SuiteSnapshotDto.multiTurn`). There SHALL be no suite-level per-turn binding configuration.
Status: **Planned**

#### Scenario: Create a multi-turn suite
- **WHEN** a client creates a `DEPLOYMENT` suite with `multiTurn: true` and a single `inputBindings`
- **THEN** the suite SHALL persist the `multiTurn` flag and `inputBindings`
- **AND** the suite response SHALL include `multiTurn`

#### Scenario: multiTurn defaults to false
- **WHEN** a client creates a suite without specifying `multiTurn`
- **THEN** the stored `multiTurn` SHALL be `false`

### Requirement: Multi-step suite validation
For a suite with `multiTurn == true`, suite soft-validation SHALL mark the suite invalid (adding a validation warning) when either of the following holds: the resolved `requestTemplate` body is not JSON with a top-level `messages` array; or the single `inputBindings` fail the existing per-binding cross-validation (template-variable match and test-case-schema resolution). A suite with no violations SHALL be valid. Turn count and array-shape checks are per-test-case data concerns evaluated at execution time (capped at `ValidationConstants.MAX_CONVERSATION_TURNS`), not suite-validation concerns.
Status: **Planned**

#### Scenario: Non-messages body is invalid for multi-turn
- **WHEN** a suite has `multiTurn == true` and its request body is multipart, url-encoded, or JSON without a top-level `messages` array
- **THEN** suite soft-validation SHALL mark the suite invalid with a validation warning

#### Scenario: Unresolved binding is invalid for multi-turn
- **WHEN** a suite has `multiTurn == true` and a binding references a missing template variable or an unknown test-case field
- **THEN** suite soft-validation SHALL mark the suite invalid with a validation warning

#### Scenario: Valid multi-turn suite
- **WHEN** a suite has `multiTurn == true`, a JSON body with a top-level `messages` array, and `inputBindings` that pass cross-validation
- **THEN** the suite SHALL be valid
