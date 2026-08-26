## ADDED Requirements

### Requirement: One request template per request in the chain

A TestSuite SHALL carry one `requestTemplate` **per request in its chain**: the suite-level `requestTemplate` belongs to request #0, and each `additionalRequests[i]` carries its own `requestTemplate`. Each request's template SHALL have its own `endpointRef` and its own `inputBindings`, and SHALL be resolved independently of the others' — placeholder syntax (`${{variable}}` / `${{variable|type}}`), template-variable extraction, binding precedence, unresolved-variable warnings, JSONata body evaluation, and the object contract for the evaluated body SHALL apply to each request's template exactly as they apply to a single-request suite's template. There SHALL be no template inheritance, merging or defaulting between requests in a chain.

Endpoint-schema `$ref` inlining SHALL likewise be per request: at normalize time the service SHALL resolve the `endpointRef` of **every** request in the chain — the suite's own and each `additionalRequests[i]`'s — so an additional request whose endpoint schema is expressed by reference is inlined exactly as the suite's own request is. No request in a chain SHALL be left with unresolved schema references.

The frame supplied to a request's JSONata body evaluation SHALL additionally carry the accumulated response columns extracted earlier in the same test-case execution (see `multi-request-suite`), so request `i`'s template MAY reference values produced by requests `0..i-1` by column name.

Status: **Implemented**

#### Scenario: Each request resolves its own template
- **WHEN** a chain's two requests have different `urlTemplate`s and different bindings
- **THEN** each call SHALL be assembled from its own template and bindings, with no merging between them

#### Scenario: Additional request body is JSONata-evaluated the same way
- **WHEN** an additional request's JSON body uses `jsonataContent`
- **THEN** placeholder preprocessing followed by JSONata evaluation SHALL apply exactly as for the suite's own request, and a non-object evaluation result SHALL be a `REQUEST_BODY_EVALUATION_ERROR`

#### Scenario: Additional request template references a prior request's column
- **WHEN** an additional request's body expression references `$configId`, a response column extracted by request #0
- **THEN** the value SHALL be bound at resolution time from the accumulated frame

#### Scenario: Every request's endpoint schema references are inlined
- **WHEN** a suite is saved whose `additionalRequests[0].endpointRef` carries a schema expressed as a `$ref`
- **THEN** that reference SHALL be resolved and inlined at normalize time, exactly as the suite's own `endpointRef` is, and the persisted definition SHALL carry no unresolved reference

### Requirement: Template and binding limits apply per request

The configurable maximum serialized `requestTemplate` size and the configurable maximum `inputBindings` count SHALL be enforced **per request** in the chain, not against the concatenation of all requests. The duplicate-`templateVariable` rejection SHALL likewise be scoped to a single request's `inputBindings` — the same `templateVariable` name MAY appear in two different requests' binding lists, because each request resolves only its own bindings. Violations SHALL be rejected with HTTP 400 (`VALIDATION_ERROR`), with the error message identifying the offending request by its `additionalRequests` index.

Status: **Implemented**

#### Scenario: One oversized request template rejects the suite
- **WHEN** an additional request's serialized `requestTemplate` exceeds the configured maximum size
- **THEN** the system SHALL respond HTTP 400 identifying that request's index, and SHALL NOT persist the suite

#### Scenario: Bindings count is per request
- **WHEN** each of three requests declares 40 bindings and the configured maximum is 64
- **THEN** the suite SHALL be accepted, because no single request exceeds the limit

#### Scenario: Same templateVariable in two requests is allowed
- **WHEN** request #0 and request #1 each bind a variable named `prompt`
- **THEN** the suite SHALL be accepted, and each request SHALL resolve `prompt` from its own binding

#### Scenario: Duplicate templateVariable within one request is rejected
- **WHEN** a single additional request declares two bindings both named `prompt`
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) identifying that request's index

## MODIFIED Requirements

### Requirement: Resolved request preview for TestCase
The service SHALL provide `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request` to return the **resolved request** for that test case: URL, query parameters, headers, and body after applying the effective template, effective bindings, and test case `data`. This supports debugging and UI preview without executing the request.

The endpoint SHALL accept an optional `requestIndex` query parameter (integer, default `0`) selecting which request of the suite's chain to preview: `0` selects the suite's own request (`requestTemplate` + `inputBindings`), and `n > 0` selects `additionalRequests[n - 1]`'s template and bindings. A `requestIndex` that is negative or greater than `additionalRequests.size()` SHALL be rejected with HTTP 400 (`VALIDATION_ERROR`). Preview SHALL resolve with an **empty** JSONata frame regardless of `requestIndex` — no chain is executed to populate prior requests' response columns — so a chained request's references to earlier columns resolve as JSONata undefined and are reported as validation warnings, consistent with the existing unresolved-variable behavior.

Status: **Implemented**

#### Scenario: Get resolved request for test case
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request`
- **THEN** system SHALL resolve the effective template with effective bindings and test case `data` (per Runtime request assembly contract) and return a `ResolvedRequestDto`

#### Scenario: ResolvedRequestDto structure
- **WHEN** system returns the resolved request
- **THEN** the `ResolvedRequestDto` SHALL include: `url` (String — resolved URL path after placeholder substitution), `queryParams` (List of key-value pairs — resolved query parameters), `headers` (List of key-value pairs — resolved headers), `body` (`ResolvedBodyDto`, nullable — polymorphic resolved request body matching the template's content type), `warnings` (List of validation warning objects — unresolved placeholders, missing data, URL pattern mismatch, etc.)

#### Scenario: Resolved request uses suite template and bindings
- **WHEN** computing the resolved request for a test case
- **THEN** system SHALL use the suite's `requestTemplate` + `inputBindings` with the test case `data` (same resolution rules as assembly; per-test-case overrides no longer exist)

#### Scenario: Missing bindings or data produce warnings in response
- **WHEN** resolution encounters required variables with no binding or missing data
- **THEN** system SHALL still return a best-effort resolved request and SHALL include validation warnings (e.g. in response metadata or a `warnings` field) indicating unresolved placeholders or fallbacks used

#### Scenario: Non-existent TestCase or TestSuite
- **WHEN** client calls the endpoint with a non-existent testSuiteId or testCaseId
- **THEN** system SHALL respond with HTTP 404

#### Scenario: No effective template
- **WHEN** effective template is null (suite and test case have no template)
- **THEN** system SHALL respond with HTTP 400 or 404, or return a response that reflects "no template" (e.g. no resolvable path/body) per implementation choice

#### Scenario: requestIndex omitted previews the suite's own request
- **WHEN** client calls the endpoint without `requestIndex`
- **THEN** system SHALL preview the suite's `requestTemplate` + `inputBindings`, identical to the behavior before the parameter existed

#### Scenario: requestIndex selects an additional request
- **WHEN** client calls the endpoint with `requestIndex=2` on a suite with two `additionalRequests`
- **THEN** system SHALL preview `additionalRequests[1]`'s template and bindings

#### Scenario: Out-of-range requestIndex is rejected
- **WHEN** client calls the endpoint with `requestIndex=5` on a suite with two `additionalRequests`
- **THEN** system SHALL respond with HTTP 400 (`VALIDATION_ERROR`)

#### Scenario: Preview of a chained request warns about unresolved prior columns
- **WHEN** client previews an additional request whose body references a response column produced by an earlier request
- **THEN** system SHALL return a best-effort resolved request with that reference unresolved and a validation warning, and SHALL NOT execute any request
