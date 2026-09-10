## MODIFIED Requirements

### Requirement: Per-request soft validation with indexed warning paths
Suite-level soft validation (`isValid` + `validationWarnings`) SHALL run the existing per-request checks — required `endpointRef` / `urlTemplate`, template variable extraction, binding validation against the dataset's `testCaseSchema`, file-reference ownership, content-type/multipart consistency, blacklisted headers, and (for requests whose `endpointRef.relativeUrlPattern` is exactly `/openai/v1/responses` or `/anthropic/v1/messages`) literal `model`-field consistency against the suite's `deploymentRef.id` — for **every** request in the chain, and SHALL aggregate all resulting warnings into the single suite-level `validationWarnings` list. A suite SHALL be `isValid = false` when any request in the chain produces a warning that affects validity.

For a request whose `endpointRef.relativeUrlPattern` is exactly `/openai/v1/responses` or `/anthropic/v1/messages` and whose JSON body is authored as plain `content`, fixed-path request-model validation SHALL require `content.model` to be a string literal, contain no substring matching the `${{...}}` placeholder grammar, and exactly equal the suite's `deploymentRef.id`. A missing, null, non-string, placeholder-containing, or mismatched value SHALL add a `REQUEST_BODY_VALIDATION_ERROR` warning and make the suite invalid. This remains soft validation: create and update SHALL persist the invalid suite rather than reject the operation. A `jsonataContent` body SHALL skip this static model check while retaining the existing JSONata syntax validation.

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

#### Scenario: Anthropic Messages model field mismatch produces a warning
- **WHEN** a request's `endpointRef.relativeUrlPattern` is `/anthropic/v1/messages` and its JSON body is a literal `content` template whose `model` field is a `String` that does not equal the suite's `deploymentRef.id`
- **THEN** a warning SHALL be added at the request's body path (`$.requestTemplate.body` for the suite's own request, or `$.additionalRequests[i].requestTemplate.body` for an additional request), and the suite SHALL be `isValid = false`
- **AND** the create/update operation SHALL still persist the suite rather than be rejected

#### Scenario: Matching literal model is valid
- **WHEN** a canonical fixed-path request has plain JSON `content.model` equal to `deploymentRef.id`
- **THEN** static model validation SHALL add no warning

#### Scenario: Invalid plain model shapes make the suite invalid
- **WHEN** a canonical fixed-path request's plain JSON `content.model` is missing, JSON null, non-string, contains a substring matching the `${{...}}` placeholder grammar, or differs from `deploymentRef.id`
- **THEN** a `REQUEST_BODY_VALIDATION_ERROR` warning SHALL be added at that request's body path (`$.requestTemplate.body` for the suite's own request, or `$.additionalRequests[i].requestTemplate.body` for an additional request) and the suite SHALL be `isValid=false`
- **AND** the create/update operation SHALL still persist the suite rather than be rejected

#### Scenario: Anthropic Messages model field that cannot be statically checked is skipped
- **WHEN** a canonical fixed-path request's body uses `jsonataContent` instead of a plain `content` map (JSONata model validation is deferred to run time)
- **THEN** static model validation SHALL add no model warning regardless of the expression's eventual `model` value
- **AND** the existing write-time JSONata syntax validation SHALL still apply
- **BUT** a missing, null, non-string, or `${{...}}`-placeholder `model` in a plain `content` body SHALL NOT be skipped — it produces the `REQUEST_BODY_VALIDATION_ERROR` warning described above, superseding the earlier no-op degradation

#### Scenario: A non-Anthropic request's literal model-like field is not checked
- **WHEN** a request's `endpointRef.relativeUrlPattern` is neither exactly `/openai/v1/responses` nor exactly `/anthropic/v1/messages` (e.g. `/chat/completions`, a subresource of the Responses path, or a case variant) and its literal body happens to contain a `model` field differing from `deploymentRef.id`
- **THEN** no warning SHALL be added for this check — exact endpoint matching limits the rule to the two canonical model-selecting paths
- **AND** `/openai/v1/responses` SHALL receive the same check as `/anthropic/v1/messages`, superseding the earlier Anthropic-only scoping

#### Scenario: Additional request uses indexed warning path
- **WHEN** `additionalRequests[i]` targets a canonical fixed-path API and has an invalid plain `model`
- **THEN** its warning path SHALL be `$.additionalRequests[i].requestTemplate.body`
- **AND** validation of other requests in the chain SHALL remain independent

## Implementation notes

Planned. Static rule lives in `com.epam.aidial.evaluation.service.domain.SuiteValidationService`, which replaces its Anthropic-only literal check with the shared `RequestModelValidator` (`evaluation-runner-core`, `com.epam.aidial.evaluation.runner.service`) and the canonical-path constants that `DialCoreUrlBuilder` also consumes. The new warning code `REQUEST_BODY_VALIDATION_ERROR` is added to `ValidationWarningCode`.
