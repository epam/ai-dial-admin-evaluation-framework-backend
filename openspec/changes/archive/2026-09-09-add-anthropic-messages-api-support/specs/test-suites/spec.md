## MODIFIED Requirements

### Requirement: Per-request soft validation with indexed warning paths
Suite-level soft validation (`isValid` + `validationWarnings`) SHALL run the existing per-request checks — required `endpointRef` / `urlTemplate`, template variable extraction, binding validation against the dataset's `testCaseSchema`, file-reference ownership, content-type/multipart consistency, blacklisted headers, and (for requests whose `endpointRef.relativeUrlPattern` is `/anthropic/v1/messages`) literal `model`-field consistency against the suite's `deploymentRef.id` — for **every** request in the chain, and SHALL aggregate all resulting warnings into the single suite-level `validationWarnings` list. A suite SHALL be `isValid = false` when any request in the chain produces a blocking warning.

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
- **THEN** a non-blocking warning SHALL be added at the request's body path (`$.requestTemplate.body` for the suite's own request, or `$.additionalRequests[i].requestTemplate.body` for an additional request), and the suite SHALL be `isValid = false`

#### Scenario: Anthropic Messages model field that cannot be statically checked is skipped
- **WHEN** a request's `endpointRef.relativeUrlPattern` is `/anthropic/v1/messages`, and either its body uses `jsonataContent` instead of a literal `content` map, or its literal `content.model` value is a `${{...}}` placeholder, or the `model` key is absent from `content`
- **THEN** no warning SHALL be added for this check — the value cannot be determined without executing the template, so the check degrades to a no-op rather than a false positive

#### Scenario: A non-Anthropic request's literal model-like field is not checked
- **WHEN** a request's `endpointRef.relativeUrlPattern` is anything other than `/anthropic/v1/messages` (e.g. `/chat/completions`) and its literal body happens to contain a `model` field whose value differs from `deploymentRef.id`
- **THEN** no warning SHALL be added for this check — the model/deploymentRef consistency check is scoped to `/anthropic/v1/messages` requests only
