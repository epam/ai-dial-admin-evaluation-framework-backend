## ADDED Requirements

### Requirement: Request chain fields on the suite API

The suite create, update and read endpoints SHALL accept and return two additional optional fields: `additionalRequests` (a list of `RequestDefinitionDto`, never null in responses, defaulting to `[]` when omitted) and `requestName` (`String`, max 255, nullable — the label for the suite's own request). Both SHALL be persisted on `test_suites` as `additional_requests JSONB NOT NULL DEFAULT '[]'` and `request_name VARCHAR(255)` (nullable). `PUT` SHALL replace `additionalRequests` wholesale, consistent with the existing replace semantics for `responseColumns` and `inputBindings`. Suites created before this capability SHALL read back `additionalRequests: []` and `requestName: null` with no migration of existing rows.

Hard write-time validation for these fields SHALL be performed alongside the existing suite validation and SHALL reject with HTTP 400 (`VALIDATION_ERROR`): more than `ValidationConstants.MAX_ADDITIONAL_REQUESTS` entries; a null element in `additionalRequests` (message naming the 0-based index); a response-column name duplicated anywhere across the chain; a chain-wide response-column union exceeding `ValidationConstants.MAX_RESPONSE_COLUMNS`; a non-empty `additionalRequests` on a `suiteType = MCP_TOOL` suite; and, per additional request, the same body/schema/template checks already applied to the suite's own request.

The identical hard-validation rule set SHALL also gate the clone endpoint, evaluated against the effective post-override suite — see the `test-suite-clone` capability, which owns that requirement — so no configuration reachable by cloning is one that a `PUT` would reject.

Status: **Implemented**

#### Scenario: Create with a chain
- **WHEN** client calls `POST /api/v1/test-suites` with `requestName` and two `additionalRequests`
- **THEN** the system SHALL persist both fields and return them in the 201 response in the submitted order

#### Scenario: Update replaces the chain wholesale
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a single-entry `additionalRequests` on a suite that had three
- **THEN** the stored chain SHALL be exactly the submitted single entry

#### Scenario: Update omitting the field clears it
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with `additionalRequests` omitted or null
- **THEN** the stored value SHALL be `[]`, matching the existing replace semantics of `responseColumns` / `inputBindings`

#### Scenario: Pre-existing suite reads back empty
- **WHEN** client calls `GET /api/v1/test-suites/{id}` for a suite created before this capability
- **THEN** the response SHALL include `additionalRequests: []` and `requestName: null`

#### Scenario: Chain fields appear on the list endpoint response items
- **WHEN** client calls `GET /api/v1/test-suites`
- **THEN** each item SHALL carry `additionalRequests` and `requestName` consistently with the other suite configuration fields

### Requirement: Per-request soft validation with indexed warning paths

Suite-level soft validation (`isValid` + `validationWarnings`) SHALL run the existing per-request checks — required `endpointRef` / `urlTemplate`, template variable extraction, binding validation against the dataset's `testCaseSchema`, file-reference ownership, content-type/multipart consistency, blacklisted headers — for **every** request in the chain, and SHALL aggregate all resulting warnings into the single suite-level `validationWarnings` list. A suite SHALL be `isValid = false` when any request in the chain produces a blocking warning.

Warning paths for the suite's own request SHALL remain byte-identical to today's values (e.g. `$.urlTemplate`, `$.requestTemplate.body`, `$.requestTemplate.headers`, `$.endpointRef`, `$.inputBindings`) so existing clients and stored `validation_warnings` blobs stay valid. Warnings for additional requests SHALL carry an indexed path rooted at the list element — `$.additionalRequests[i].requestTemplate.urlTemplate`, `$.additionalRequests[i].requestTemplate.body`, `$.additionalRequests[i].requestTemplate.headers`, `$.additionalRequests[i].endpointRef`, `$.additionalRequests[i].inputBindings` — where `i` is the 0-based index within `additionalRequests`. The configured maximum-warnings cap SHALL apply to the aggregated chain-wide list.

Both the DTO-based and the entity-based validation entry points SHALL iterate the chain, so manual revalidation and dataset-schema-change revalidation produce the same warnings as create/update.

Status: **Implemented**

#### Scenario: Additional request warning carries an indexed path
- **WHEN** the second entry of `additionalRequests` binds a template variable to a dataset field that does not exist
- **THEN** the suite SHALL carry a warning whose path is `$.additionalRequests[1].inputBindings` and SHALL be `isValid = false`

#### Scenario: Request #0 warning paths are unchanged
- **WHEN** the suite's own `requestTemplate` has no `urlTemplate`
- **THEN** the warning path SHALL still be `$.urlTemplate`, unchanged from before this capability

#### Scenario: Warnings from several requests aggregate
- **WHEN** both the suite's own request and one additional request have unresolvable bindings
- **THEN** `validationWarnings` SHALL contain warnings for both, distinguishable by path

#### Scenario: Revalidation after a dataset schema change covers the chain
- **WHEN** the bound dataset's `testCaseSchema` drops a field referenced only by an additional request's bindings
- **THEN** revalidation SHALL mark the suite invalid with a warning at `$.additionalRequests[i].inputBindings`

#### Scenario: A valid chain stays valid
- **WHEN** every request in the chain resolves all bindings against the dataset schema and has a complete template
- **THEN** the suite SHALL be `isValid = true` with no chain-related warnings
