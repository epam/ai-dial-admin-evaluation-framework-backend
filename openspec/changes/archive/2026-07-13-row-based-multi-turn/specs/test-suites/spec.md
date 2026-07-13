## REMOVED Requirements

### Requirement: Multi-turn suite configuration fields
A `DEPLOYMENT` `TestSuite` SHALL support a `multiTurn` boolean (default `false`); the underlying column is `test_suites.multi_turn`. When `multiTurn == true`, the suite uses its regular single `inputBindings` and `requestTemplate`, exactly like a single-turn suite; per-turn variation comes from array-valued test-case columns at execution time (see multi-turn-conversation). The `multiTurn` flag SHALL be accepted on create/update, persisted, returned in the suite response, and captured in the suite snapshot (`SuiteSnapshotDto.multiTurn`). There SHALL be no suite-level per-turn binding configuration.

#### Scenario: Create a multi-turn suite
- **WHEN** a client creates a `DEPLOYMENT` suite with `multiTurn: true` and a single `inputBindings`
- **THEN** the suite SHALL persist the `multiTurn` flag and `inputBindings`
- **AND** the suite response SHALL include `multiTurn`

#### Scenario: multiTurn defaults to false
- **WHEN** a client creates a suite without specifying `multiTurn`
- **THEN** the stored `multiTurn` SHALL be `false`
- **AND** the suite SHALL behave exactly as a single-turn suite

**Reason**: Multi-turn is no longer declared on the suite. Under the row-based model a conversation is emergent from the presence of grouped conversation rows (`conversationId` + `turnIndex`) in the bound dataset; the runner auto-dispatches multi-turn execution when it sees grouped rows. A suite-level `multiTurn` flag (and the array-valued-column execution model it presupposed) is removed entirely, along with `SuiteSnapshotDto.multiTurn`.

**Migration**: Do not toggle a suite flag. Author conversation rows on the bound test cases instead — set `conversationId` (a client-supplied UUID) and a contiguous 0-based `turnIndex` on each turn row; single-turn cases leave both NULL. The runner groups rows by `conversationId`, orders by `turnIndex`, and executes multi-turn automatically. Existing suites that had `multiTurn == true` need no config change beyond restructuring their dataset into per-turn rows.

### Requirement: Multi-turn suite validation
For a suite with `multiTurn == true`, suite soft-validation SHALL mark the suite invalid (adding a validation warning) when either of the following holds: the resolved `requestTemplate` body is not JSON with a top-level `messages` array; or the single `inputBindings` fail the existing per-binding cross-validation (template-variable match and test-case-schema resolution). A suite with no violations SHALL be valid. Turn count and array-shape checks are per-test-case data concerns evaluated at execution time (capped at `ValidationConstants.MAX_CONVERSATION_TURNS`), not suite-validation concerns.

#### Scenario: Non-messages body is invalid for multi-turn
- **WHEN** a suite has `multiTurn == true` and its request body is multipart, url-encoded, or JSON without a top-level `messages` array
- **THEN** the suite SHALL be marked invalid with a validation warning

#### Scenario: Unresolved binding is invalid for multi-turn
- **WHEN** a suite has `multiTurn == true` and a binding references a missing template variable or an unknown test-case field
- **THEN** the suite SHALL be marked invalid with a validation warning

#### Scenario: Valid multi-turn suite
- **WHEN** a suite has `multiTurn == true`, a JSON body with a top-level `messages` array, and `inputBindings` that pass cross-validation
- **THEN** the suite SHALL be valid

**Reason**: With no suite-level `multiTurn` flag there is no multi-turn-specific config-time validation surface. `SuiteValidationService.validateMultiTurnBody` is removed. A template that lacks a top-level `messages` array is no longer a suite-validation concern — under the row-based model such a template fails per-conversation at run time as an ERROR row, not at config time. The normal `inputBindings` cross-validation continues to run via the existing single-turn deployment-suite validation path.

**Migration**: None required at the suite level. Ensure the bound `requestTemplate` resolves to a chat-completions body with a top-level `messages` array; otherwise conversations for that suite surface as ERROR result rows at run time instead of a config-time warning. Move conversation shape/contiguity concerns to the per-row write-time validation and the snapshot phase (see the row-based multi-turn test-cases capability).

## Implementation notes
- `TestSuiteRequestDto` / `TestSuiteResponseDto`: remove the `multiTurn` field.
- `TestSuiteMapper`: remove `multiTurn` mapping in `toEntity` / `update` / `toDto` / `toCloneEntity`.
- `data.db.model.TestSuite`: remove the `multiTurn` field; drop the `test_suites.multi_turn` column via a reshaped branch migration (isolated feature branch — reshape in place, do not stack a throwaway migration), then `./gradlew generateJooq`.
- `SuiteSnapshotDto`: remove the `multiTurn` field (snapshot version handling unchanged otherwise).
- `SuiteValidationService`: remove `validateMultiTurnBody`; `validateDeploymentSuite` reverts to the single-turn deployment path (normal `BindingValidator` cross-validation only).
- Also remove `docs/database-schema.md` and AGENTS.md references to the suite `multiTurn` flag as part of the same change.
