## ADDED Requirements

### Requirement: Clone enforces hard write-time validation on the effective suite

`POST /api/v1/test-suites/{id}/clone` SHALL run the same **hard** write-time validation that `POST /api/v1/test-suites` and `PUT /api/v1/test-suites/{id}` run, evaluated against the **effective post-override suite** — the source suite's inherited configuration merged with whatever the clone request overrides. A violation SHALL be rejected with HTTP 400 (`VALIDATION_ERROR`) and the clone SHALL NOT be created; no partially-created suite, dataset or file copy SHALL remain.

The rules enforced SHALL be exactly those of create/update, notably: global response-column name uniqueness across the suite's whole request chain (the `responseColumns` override or inherited list, plus every `additionalRequests[i].responseColumns`); the suite-wide response-column union cap; reserved response-column names and JSONata expression syntax; request-template size and input-binding count limits per request; duplicate `templateVariable` within one request's bindings; endpoint-schema JSON-Schema validity; and the chain-length cap.

This is distinct from, and runs before, the existing synchronous **soft** validation that determines the clone's `isValid` / `validationWarnings`: a hard violation aborts the clone with a 400, whereas a soft finding creates the clone and records a warning. The invariant is that a clone can never be persisted in a configuration that a `PUT` of the same effective content would reject.

Status: **Implemented**

#### Scenario: Override collides with an inherited additional request's column
- **WHEN** client clones a suite whose inherited `additionalRequests[0]` declares response column `answer`, supplying a `responseColumns` override that also declares `answer`
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) naming the duplicate column, and SHALL NOT create the clone

#### Scenario: Effective union exceeds the response-column cap
- **WHEN** the clone's effective suite — `responseColumns` override plus the inherited chain's columns — exceeds the suite-wide response-column union cap
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) and SHALL NOT create the clone

#### Scenario: Inherited-only violation is still caught
- **WHEN** client clones with `{"name": "Copy"}` and no overrides
- **THEN** hard validation SHALL still run against the inherited configuration, so a source suite that somehow holds an invalid chain cannot be propagated by cloning

#### Scenario: Valid clone is unaffected
- **WHEN** client clones a suite whose effective configuration violates no hard rule
- **THEN** the clone SHALL be created exactly as before this requirement existed, with its `isValid` / `validationWarnings` still determined by the synchronous soft validation

#### Scenario: Rejected clone leaves no residue
- **WHEN** a clone is rejected by hard validation
- **THEN** no cloned suite row, no cloned dataset, no cloned test cases, no cloned TSMDs and no copied suite-scoped files SHALL exist
