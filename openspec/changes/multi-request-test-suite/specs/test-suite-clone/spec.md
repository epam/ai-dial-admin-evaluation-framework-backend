## ADDED Requirements

### Requirement: Chain configuration is cloned verbatim and is not overridable
Cloning a suite SHALL copy `additionalRequests` and `requestLabel` verbatim from the source suite. Neither SHALL appear in the clone request's set of overridable fields; supplying either SHALL be rejected as an unknown field per the clone DTO's existing contract. Because the chain cannot be overridden, the cloned suite's chain-union response column set is necessarily identical to the source's, so cloning a chain SHALL NOT introduce any new TSMD revalidation trigger beyond the existing `datasetId` and `responseColumns` triggers.
Status: **Planned**

#### Scenario: Chain is copied to the clone
- **WHEN** a client clones a suite whose chain has three requests
- **THEN** the cloned suite SHALL carry the same `additionalRequests` array in the same order and the same `requestLabel`

#### Scenario: Chain override is not accepted
- **WHEN** a clone request supplies `additionalRequests` or `requestLabel`
- **THEN** the request SHALL be rejected, as for any field outside the documented overridable set

#### Scenario: Cloned chain preserves TSMD validity verbatim
- **WHEN** a suite with a chain and TSMDs is cloned with no `datasetId` or `responseColumns` override
- **THEN** every cloned TSMD's `isValid` and `validationWarnings` SHALL be copied verbatim from the source, because every input to TSMD validation — including the chain-union response column set — is identical

#### Scenario: Cloned chain is independently editable
- **WHEN** a cloned suite's `additionalRequests` is subsequently updated through the suite update endpoint
- **THEN** the update SHALL apply to the clone only and SHALL revalidate the clone's TSMDs against its new chain-union response column set

#### Scenario: Cloning a single-request suite is unchanged
- **WHEN** a client clones a suite with no `additionalRequests`
- **THEN** the clone SHALL also have no chain and clone behavior SHALL be exactly as before

### Requirement: responseColumns override validation accounts for the chain
When a clone request supplies a `responseColumns` override, the resulting suite's response column names SHALL still be unique across the whole chain — the overridden flat `responseColumns` unioned with the verbatim-copied `additionalRequests` columns. An override that collides with a name declared by a copied chain request SHALL be rejected with HTTP 400 `VALIDATION_ERROR`.
Status: **Planned**

#### Scenario: Override colliding with a chain column is rejected
- **WHEN** a clone request overrides `responseColumns` with a column named `answer`, and the source suite's chain request 1 already declares `answer`
- **THEN** the clone SHALL be rejected with HTTP 400 `VALIDATION_ERROR` naming the duplicated column

#### Scenario: Non-colliding override is accepted and triggers revalidation
- **WHEN** a clone request overrides `responseColumns` with names that do not collide with any copied chain request's columns
- **THEN** the clone SHALL succeed and its TSMDs' `isValid` and `validationWarnings` SHALL be recomputed against the resolved dataset schema and the cloned suite's chain-union response column set

## Implementation notes

`TestSuiteCloneRequestDto` is unchanged (no new overridable fields); the clone service copies `additional_requests` and `request_label` alongside the other suite columns. The `responseColumns`-override validation path and the TSMD revalidation path both source the response column set from the shared chain-union helper.
