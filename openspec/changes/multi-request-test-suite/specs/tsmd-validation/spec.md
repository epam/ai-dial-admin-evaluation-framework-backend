## MODIFIED Requirements

### Requirement: Response binding references resolve against the chain-union response column set
TSMD soft validation SHALL validate a `response` binding source's `columnName` against the suite's **chain-union** response column set — the union of request 0's flat `responseColumns` and every `additionalRequests` element's `responseColumns`, in chain order — rather than the flat list alone. This supersedes the baseline `TSMD soft validation on create and update` requirement's `Invalid — Response column reference unresolved` scenario, which checked a `Response` binding's `columnName` only against "the test suite's `responseColumns`" (the flat list): that check now resolves against the chain-union response-column set instead. A reference to a column declared by any chain request SHALL be considered resolved and SHALL NOT produce an `UNRESOLVED_REFERENCE` warning. A reference to a column declared by no chain request SHALL continue to produce `UNRESOLVED_REFERENCE`. For a single-request suite, the chain union degenerates to the flat `responseColumns` list, so this check's behavior there is unchanged; for a multi-request suite, the check now spans every chain element's declared response columns.
Status: **Planned**

#### Scenario: Reference to a later chain request's column resolves
- **WHEN** a TSMD binds an input to response column `answer`, declared by chain request 2
- **THEN** validation SHALL NOT produce an `UNRESOLVED_REFERENCE` warning for that binding

#### Scenario: Reference to an unknown column still warns
- **WHEN** a TSMD binds an input to a response column declared by no chain request
- **THEN** validation SHALL produce an `UNRESOLVED_REFERENCE` warning naming the column

#### Scenario: Single-request suite validation unchanged
- **WHEN** a single-request suite's TSMD bindings are validated
- **THEN** the chain union equals the flat `responseColumns` and validation behaves exactly as before

## ADDED Requirements

### Requirement: A metric's condition is not required in a multi-request suite
A TSMD in a multi-request suite SHALL NOT be required to declare a `condition`. An absent condition SHALL remain valid and SHALL NOT produce a validation warning, even though it causes the metric to run on every chain request's row. Suite and TSMD validity SHALL be unaffected by the absence of a condition.
Status: **Planned**

#### Scenario: Unconditioned metric in a multi-request suite validates
- **WHEN** a TSMD without a condition is saved against a multi-request suite
- **THEN** it SHALL be accepted with no condition-related validation warning and SHALL NOT be marked invalid

#### Scenario: Runtime failure is the feedback channel
- **WHEN** that unconditioned metric later runs on a chain request whose row lacks its bound response column
- **THEN** the failure surfaces at run time as a `FAILED` eval summary for that row, not as a save-time validation warning

## Implementation notes

`MetricDefinitionValidationService` — the response column name set used by the `UNRESOLVED_REFERENCE` check is sourced from the shared chain-union response-column helper instead of the suite's flat `responseColumns`. Auto-revalidation on suite schema update uses the same set, so editing a chain request's `responseColumns` revalidates dependent TSMDs correctly.
