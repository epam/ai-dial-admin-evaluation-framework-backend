# Try It Out Spec

## Purpose

Endpoints for sending resolved HTTP requests or MCP tool calls using test-case data or supplied variables and returning invocation previews, including multi-turn/multi-request history. Covers URL routing, timeout configuration, error proxying, validation and SSE responses with evaluation-equivalent assembled `body`, independently parsed `events`, and structured fallback when assembly is unavailable.

Status: **Implemented**

## Requirements

### Requirement: Try it out with test case data
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out` to resolve the effective request template using the test case's data and effective bindings, send the resolved request to the DIAL Core deployment referenced by the test suite, and return the deployment's response along with the resolved request details. When the test case is multi-turn, the system SHALL execute every turn of the sequence and return the result of the last executed turn, plus a per-turn `history` when more than one turn actually ran.

Status: **Implemented**

#### Scenario: Successful try-it-out with test case
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`
- **AND** the test suite has a valid `deploymentRef`, `requestTemplate`, and `endpointRef`
- **AND** the test case exists and belongs to the test suite
- **THEN** the system SHALL load the suite (for `deploymentRef`/`endpointRef` precondition validation via `SuiteDefinitionResolver.fromEntity`), then delegate turn planning and resolution to `ResolvedRequestService`, which handles test-case loading, effective template/bindings determination, turn planning, and resolution within its own `@Transactional(readOnly=true)` scope. Note: on the single-turn collapse path (no `multiTurnData`, or `multiTurnData` present but no per-turn binding) this results in the suite being loaded 3 times and the test case being loaded 2 times — once via `loadSuite`, once via `planTurns` (suite + test case), and once more via the separate `resolveRequest` call (suite + test case) that `tryWithTestCase` makes when the plan collapses to a single turn. On the true multi-turn path (N>1 turns), the suite is loaded only 2 times and the test case only 1 time — via `loadSuite` and `planTurns` alone, since `resolveRequest` is not called in that case. This is an accepted trade-off for clear pre-validation errors without modifying `ResolvedRequestService`.
- **AND** send the resolved request(s) to the DIAL Core deployment (after the transaction completes and the DB connection is released)
- **AND** return HTTP 200 with `TryItOutResponseDto` containing the resolved request, the deployment's response (status code + body), and execution duration in milliseconds

#### Scenario: Test case with template/bindings overrides
- **WHEN** the test case has `requestTemplateOverride` and/or `inputBindingsOverride`
- **THEN** the system SHALL use the overrides instead of suite-level template/bindings for resolution

#### Scenario: Test suite not found
- **WHEN** user sends try-it-out request with non-existent `testSuiteId`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

#### Scenario: Test case not found
- **WHEN** user sends try-it-out request with non-existent `testCaseId` or a test case that does not belong to the test suite
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

#### Scenario: Multi-turn test case executes every turn
- **WHEN** the test case has non-null `multiTurnData` and at least one effective input binding references a dataset schema field flagged `perTurn=true` (same turn-count decision as `PerTurnBindingDetector` uses for real runs)
- **THEN** the system SHALL resolve and invoke each turn sequentially, in order, from turn 0 to turn N-1, where N is the number of entries in `multiTurnData`
- **AND** each turn's effective data SHALL be the merge of the test case's shared `data` with that turn's entry (per-turn wins on key collision)
- **AND** each turn after the first SHALL be resolved using `frameBindings` derived from the response columns extracted from the previous turn's response (via the suite's `responseColumns` definitions), enabling `$history`-style JSONata expressions in the request template to accumulate across turns exactly as they do in a real suite run
- **AND** the first turn SHALL be resolved with empty `frameBindings`
- **AND** the response SHALL be a `TryItOutResponseDto` containing the last executed turn's `resolvedRequest`/`response`/`durationMs`/`traceId` at the top level, plus a `history` array with one entry per executed turn (see "Multi-turn response includes per-turn history")

#### Scenario: Multi-turn data present but no per-turn binding collapses to a single turn
- **WHEN** the test case has non-null `multiTurnData` with more than one entry, but no effective input binding references any `perTurn=true` schema field
- **THEN** the system SHALL treat the case as a single turn using only the shared `data` (identical to the `PerTurnBindingDetector` collapse behavior used by real runs)
- **AND** the response SHALL be identical in shape to a single-turn test case's response (no `history`)

#### Scenario: Turn failure stops the sequence
- **WHEN** executing a multi-turn test case and a turn's invocation resolves to a non-2xx DIAL Core status, fails request-body JSONata evaluation (`RequestBodyEvaluationException`), or fails resolved request-model validation (`RequestBodyValidationException`)
- **THEN** the system SHALL stop executing further turns (fail-fast)
- **AND** the failed turn's resolved request and error response SHALL be returned as the `resolvedRequest`/`response`, and as the last entry of `history`
- **NOTE**: a transport-level failure during a turn's invocation (timeout, connection refused, unreachable deployment) is NOT caught by this mechanism — it propagates uncaught exactly as in the single-turn path, producing the pre-existing plain 502/504 error response.
- **NOTE**: `ValidationException` (null resolved URL) or `TryItOutValidationException` (unresolved REQUIRED template variables) thrown by `validateResolutionResult` for turns after the first remain uncaught. `runChain` catches only `RequestBodyEvaluationException` and the new `RequestBodyValidationException`; other validation failures propagate exactly like transport-level failures.

#### Scenario: MCP suite rejects multi-turn test case
- **WHEN** the test suite has `suiteType = MCP_TOOL` and the test case has non-null `multiTurnData`
- **THEN** the system SHALL return HTTP 409 with error code `INVALID_OPERATION`, without invoking the MCP tool
- **AND** the error message SHALL indicate that MCP suites do not support multi-turn test cases (consistent with the existing rejection of MCP + multi-turn at run creation)
- **NOTE**: the check is a coarse presence check (`multiTurnData != null`), not a `PerTurnBindingDetector`-based collapse check — it rejects even when the data would collapse to a single turn, matching the existing run-creation guard's coarseness.

### Requirement: Try it out with variables
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/try-it-out` accepting a `variables` object in the request body, keyed by **request index** (`"0"` = the suite's own request, `"n"` = `additionalRequests[n-1]`; the same index semantics as the template-variables response and `resolved-request?requestIndex`). Each value maps template variable names to constant values for that request. The system SHALL resolve each request's template by treating each of that request's variables as a constant-value binding, except that a variable the suite binds via `dataField` to a `FILE` or `SKILL` schema column SHALL keep its constant value but additionally carry that column as its `dataField` (via `TryItOutVariableBindings.bindToFileColumns`; the resolver takes the value from the constant, the FILE and SKILL detectors read the column type) so a hintless `${{var}}` resolves to a DIAL file ref (FILE column) or the canonical `skills/{bucket}/{path}` ref (SKILL column) as in a real run, and any number of variables MAY share one FILE or SKILL column, each keeping its own value, send the resolved request(s) to the DIAL Core deployment, and return the response.

Status: **Implemented**

#### Scenario: Successful try-it-out with variables
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/try-it-out` with body `{ "variables": { "0": { "prompt": "Hello", "model": "gpt-4" } } }`
- **AND** the test suite has a valid `deploymentRef`, `requestTemplate`, and `endpointRef` and no `additionalRequests`
- **THEN** the system SHALL resolve the suite's request template with `prompt` and `model` as constant-value bindings; the suite's own `inputBindings` SHALL NOT be used — they are fully replaced by the user-provided variables of index `0`
- **AND** send the resolved request to the DIAL Core deployment
- **AND** return HTTP 200 with `TryItOutResponseDto`

#### Scenario: Variables must not be null
- **WHEN** user sends try-it-out request with `variables` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Empty variables map is valid
- **WHEN** user sends try-it-out request with `variables` as an empty object `{}`
- **AND** the template has no `${{...}}` placeholders (fully static)
- **THEN** the system SHALL accept the request and proceed with resolution and invocation

#### Scenario: Missing or null request entry means no variables for that request
- **WHEN** user sends try-it-out request whose `variables` has no key `"i"`, or maps `"i"` to null, for a request index `i` of the suite
- **THEN** request `i` SHALL be resolved with no constant bindings: its placeholders fall through to their default values, or produce a `REQUIRED` warning and the existing `Unresolved required template variables` rejection

#### Scenario: Variable with null value
- **WHEN** user sends try-it-out request with a variable mapped to null (e.g., `{ "variables": { "0": { "myVar": null } } }`)
- **THEN** the system SHALL skip that entry when building bindings (treat it as if the variable was not provided)
- **AND** the template variable will fall through to its default value (if any) or produce a `REQUIRED` warning if no default exists

#### Scenario: Variable with blank key
- **WHEN** user sends try-it-out request with a blank variable name (e.g., `{ "variables": { "0": { "": "value" } } }`)
- **THEN** the system SHALL skip that entry when building bindings (a blank key cannot match any `${{var}}` placeholder)

#### Scenario: Non-integer request index
- **WHEN** user sends try-it-out request whose `variables` has a key that is not an integer (e.g., the legacy flat shape `{ "variables": { "prompt": "Hello" } }`)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` without invoking the deployment
- **AND** the rejection SHALL happen while reading the request body, before the suite is looked up (so it takes precedence over a 404 for a non-existent suite)

#### Scenario: Request index out of range
- **WHEN** user sends try-it-out request whose `variables` has a key `i` with `i < 0` or `i > N`, where `N` is the number of the suite's `additionalRequests`
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message `variables: request index <i> is out of range (chain length <N+1>)`, naming the first offending key in iteration order
- **AND** SHALL NOT invoke the deployment for any request
- **AND** suite-configuration preconditions (missing deployment reference, request template, endpoint reference, or a misconfigured `additionalRequests[i]`) SHALL be checked first and take precedence over the index rejection

#### Scenario: Non-zero request index on a single-request suite
- **WHEN** user sends try-it-out request with key `"1"` for a `DEPLOYMENT` suite without `additionalRequests`
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message `variables: request index 1 is out of range (chain length 1)` without invoking the deployment

#### Scenario: Test suite not found
- **WHEN** user sends try-it-out request with non-existent `testSuiteId`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

### Requirement: Validation before invocation
The system SHALL validate the test suite configuration before invoking the deployment, the global route or the MCP tool. The request SHALL be rejected if preconditions are not met. Validation SHALL be type-aware: DEPLOYMENT suites validate HTTP preconditions including `deploymentRef`, ROUTE suites validate the same HTTP preconditions with `routesRef` in place of `deploymentRef`, and MCP_TOOL suites validate MCP preconditions.

Status: **Implemented**

#### Scenario: Missing deployment reference (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `deploymentRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that deployment reference is required

#### Scenario: Missing route reference (ROUTE suite)
- **WHEN** a ROUTE suite has no stored `routesRef` (null or blank)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message `Route reference is required for try-it-out`
- **AND** DIAL Core SHALL NOT be invoked

#### Scenario: ROUTE suite does not require deploymentRef
- **WHEN** a ROUTE suite has a `routesRef` and no `deploymentRef`
- **THEN** the "Deployment reference is required" check SHALL NOT be applied and validation SHALL continue with the endpoint reference and request template checks

#### Scenario: Missing request template (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `requestTemplate` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that request template is required

#### Scenario: Missing request template (ROUTE suite)
- **WHEN** a ROUTE suite has `requestTemplate` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that request template is required

#### Scenario: Missing endpoint reference (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `endpointRef` as null or `endpointRef.method` is null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that endpoint reference with HTTP method is required

#### Scenario: Missing endpoint reference (ROUTE suite)
- **WHEN** a ROUTE suite has `endpointRef` as null or `endpointRef.method` is null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that endpoint reference with HTTP method is required

#### Scenario: Missing MCP deployment reference (MCP_TOOL suite)
- **WHEN** an MCP_TOOL suite has `mcpDeploymentRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that MCP deployment reference is required

#### Scenario: Missing tool reference (MCP_TOOL suite)
- **WHEN** an MCP_TOOL suite has `toolRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that tool reference is required

#### Scenario: Null resolved URL (DEPLOYMENT suite only)
- **WHEN** the resolved URL is null after template resolution
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

> **Note:** This scenario applies only to DEPLOYMENT suites. MCP_TOOL suites do not resolve URLs.

#### Scenario: Null resolved URL (ROUTE suite)
- **WHEN** the resolved URL of a ROUTE suite is null after template resolution
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Unresolvable required template variables
- **WHEN** template/argument resolution produces warnings with `REQUIRED` code
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`
- **AND** the error message SHALL list the unresolved variable names
- **AND** the `resolvedRequest` SHALL be included in the error response details field

> **Note:** This scenario applies to all suite types: DEPLOYMENT and ROUTE suites check URL template variables, MCP_TOOL suites check argument template variables.

#### Scenario: JSON request body fails JSONata evaluation (DEPLOYMENT suite only)
- **WHEN** the resolved request produces a warning with `REQUEST_BODY_EVALUATION_ERROR` code (the JSON body's JSONata evaluation failed and the resolved body content was downgraded to `null`)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`, the same way as an unresolvable `REQUIRED` warning
- **AND** the deployment SHALL NOT be invoked

> **Note:** This is distinct from the preview/GET resolved-request endpoint, which keeps its existing graceful degradation (a `null` body content with a warning, no abort) — only the try-it-out invocation paths (test case and variables) abort before calling the live deployment or global route.

#### Scenario: JSON request body fails JSONata evaluation (ROUTE suite)
- **WHEN** the resolved request of a ROUTE suite produces a warning with `REQUEST_BODY_EVALUATION_ERROR` code
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`, the same way as for a DEPLOYMENT suite
- **AND** the global route SHALL NOT be invoked

---

### Requirement: Try it out with MCP tool call (test case)

The system SHALL support try-it-out for MCP_TOOL suites via `POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`. For MCP suites, the system SHALL resolve tool arguments from the argument template using effective bindings and test case data, execute the MCP tool call via `McpToolInvoker`, and return the MCP response.

#### Scenario: Successful MCP try-it-out with test case
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`
- **AND** the test suite has `suiteType = MCP_TOOL` with valid `mcpDeploymentRef`, `toolRef`, and `argumentTemplate`
- **THEN** the system SHALL determine effective bindings: test case `inputBindingsOverride` (if non-null) takes priority over suite-level `inputBindings` (same override semantics as HTTP suites)
- **AND** resolve tool arguments via `McpRequestResolver.resolve(argumentTemplate, effectiveBindings, testCaseData)`
- **AND** determine the MCP transport from `mcpDeploymentRef.transport` (defaulting to `STREAMABLE_HTTP` when null)
- **AND** execute `McpToolInvoker.callTool(mcpDeploymentRef.id, toolRef.name, resolvedArguments, token, transport)`
- **AND** serialize the `CallToolResult` via `McpResponseSerializer`
- **AND** return HTTP 200 with `TryItOutResponseDto` containing the resolved arguments, the serialized MCP response, and execution duration

#### Scenario: MCP suite missing mcpDeploymentRef
- **WHEN** the MCP_TOOL suite has `mcpDeploymentRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: MCP suite missing toolRef
- **WHEN** the MCP_TOOL suite has `toolRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: MCP tool call returns isError
- **WHEN** the MCP tool call returns `isError = true`
- **THEN** the try-it-out endpoint SHALL still return HTTP 200
- **AND** `response.body` SHALL contain the serialized MCP response with `isError: true`

#### Scenario: MCP tool call timeout
- **WHEN** the MCP tool call times out
- **THEN** the system SHALL return HTTP 504 with error code `UPSTREAM_TIMEOUT`

---

### Requirement: Try it out with MCP tool call (variables)

The system SHALL support try-it-out with variables for MCP_TOOL suites via `POST /api/v1/test-suites/{testSuiteId}/try-it-out`. MCP suites have a single request, so only index `"0"` is meaningful; its entries map argument template variable names to constant values.

Status: **Implemented**

#### Scenario: Successful MCP try-it-out with variables
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/try-it-out` with `{ "variables": { "0": { "search_query": "MCP protocol" } } }`
- **AND** the test suite has `suiteType = MCP_TOOL`
- **THEN** the system SHALL resolve the tool arguments using the index-`0` variables as constant-value bindings (same per-entry rules as HTTP try-it-out with variables)
- **AND** execute the MCP tool call and return the response

#### Scenario: MCP try-it-out rejects a non-zero request index
- **WHEN** user sends try-it-out request with `variables` containing any key other than `"0"` for an `MCP_TOOL` suite
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message `variables: request index <i> is out of range (chain length 1)` without invoking the MCP tool
- **AND** MCP preconditions (missing MCP deployment or tool reference) SHALL be checked first

---

### Requirement: MCP try-it-out response structure

The `TryItOutResponseDto` SHALL accommodate MCP responses alongside HTTP responses.

#### Scenario: MCP response in TryItOutResponseDto
- **WHEN** an MCP try-it-out completes
- **THEN** `resolvedRequest` SHALL contain the resolved arguments (as the "body" component — reuse `ResolvedJsonBodyDto` with the arguments map)
- **AND** `response.statusCode` SHALL be `200` when the tool call succeeds (even if `isError = true` — that's tool-level, not transport-level)
- **AND** `response.body` SHALL be the parsed JSON object from `McpResponseSerializer` output (content blocks + structuredContent + isError)

#### Scenario: MCP transport error response
- **WHEN** the MCP tool call fails at the transport level (connection refused, timeout)
- **THEN** the system SHALL return the standard error response (HTTP 502/504) — same pattern as HTTP try-it-out

---

### Requirement: TryItOutResponseDto structure

Status: **Implemented**

The response SHALL be an envelope containing the resolved request, upstream response, timing information and invocation trace ID. Top-level invocation fields SHALL reflect the last executed invocation. For a plan with more than one invocation (multiple turns, multiple requests, or both), `history` SHALL contain one entry per executed invocation in request-major, turn-minor order, including the last and any failing invocation. Each entry SHALL have its own `resolvedRequest`, `response`, `durationMs`, `traceId` and `grafanaTraceUrl`. A single-request single-turn plan, including a multi-turn case collapsed by absence of per-turn bindings, SHALL omit `history`. A multi-request plan SHALL retain history even when each request has only one turn.

The nested response SHALL contain:
- `statusCode` (integer): the upstream HTTP status.
- `body` (nullable JSON value): the existing upstream body for non-SSE HTTP responses, raw string when that body is not JSON, or the evaluation-equivalent assembled document for SSE responses. An assembled SSE JSON string SHALL be exposed as a string value, not JSON-encoded text. Assembled SSE object, array, scalar and explicit JSON null values SHALL retain their JSON types. Non-SSE responses SHALL retain existing null omission: a Java-null body is omitted and null-valued object/map properties are omitted by response serialization.
- `streaming` (nullable boolean): `true` for HTTP SSE responses, omitted for non-SSE responses.
- `events` (nullable array): parsed SSE events in receipt order for HTTP SSE responses, including an empty array when no events were dispatched; omitted for non-SSE responses. Each event SHALL carry its event type and data as parsed JSON when valid, or a raw string otherwise. This array SHALL NOT claim to be byte-verbatim SSE transport data.
- `streamingStatus`: the existing non-success stream status, omitted for successful/non-SSE responses.
- `truncationWarning`: the existing truncation reason when present, omitted otherwise.

SSE `body` SHALL NOT be an unconditional event envelope. When available, it SHALL be the same assembled JSON document that evaluation uses for the same events and stream status, including provider-specific success documents and existing partial/fallback results. When assembly produces no document, it SHALL retain the parsed events in a diagnostic events-envelope body while preserving the assembly error status. SSE event diagnostics SHALL remain available in `events` independently of that assembled body. This contract SHALL apply to both variables and test-case endpoints and both DEPLOYMENT and ROUTE suites.

#### Scenario: Response structure (non-SSE)
- **WHEN** the system returns a try-it-out response for a non-SSE invocation
- **THEN** it SHALL include the resolved request (URL, query parameters, headers and body), nested response with upstream status and parsed JSON or raw-string body, elapsed duration in milliseconds and the invocation trace ID when tracing is enabled
- **AND** `streaming` and `events` SHALL be omitted
- **AND** duration SHALL retain its existing measurement boundary through the HTTP invocation return, excluding subsequent SSE parsing

#### Scenario: Response structure (SSE)
- **WHEN** DIAL Core returns `Content-Type: text/event-stream` to either try-it-out endpoint
- **THEN** `response.streaming` SHALL be `true`
- **AND** `response.events` SHALL contain parsed events in receipt order
- **AND** `response.body` SHALL contain the evaluation-equivalent assembled JSON value, not JSON-encoded text or an unconditional events envelope

#### Scenario: DIAL Core returns error status
- **WHEN** DIAL Core returns a 4xx or 5xx status code
- **THEN** the try-it-out endpoint SHALL still return HTTP 200 with the actual upstream status in `response.statusCode`
- **AND** `response.body` SHALL contain the upstream non-SSE body or evaluation-equivalent assembled SSE document, according to the response content type
- **AND** the trace ID SHALL be populated when the invocation span is valid

#### Scenario: Response body is valid JSON (non-SSE)
- **WHEN** DIAL Core returns a non-SSE body containing valid JSON
- **THEN** `response.body` SHALL retain the existing parsed JSON response behaviour for objects, arrays, strings, numbers and booleans
- **AND** a root JSON null SHALL retain the existing omitted-body behaviour, and null-valued object/map properties SHALL remain omitted by response serialization

#### Scenario: Response body is not JSON (non-SSE)
- **WHEN** DIAL Core returns a non-SSE body that is not valid JSON, such as HTML or plain text
- **THEN** `response.body` SHALL be the raw response string

#### Scenario: Resolved request with multipart body
- **WHEN** the suite uses a `multipart/form-data` request template
- **THEN** the resolved request body SHALL show the resolved text parts and DIAL file references

#### Scenario: Resolved request with URL-encoded body
- **WHEN** the suite uses an `application/x-www-form-urlencoded` request template
- **THEN** the resolved request body SHALL show the resolved key/value entries

#### Scenario: Multi-invocation plan includes executed history
- **WHEN** a try-it-out plan contains multiple turns, multiple requests, or both
- **THEN** `history` SHALL contain one entry per executed invocation in execution order, including a failing first invocation when later calls are skipped
- **AND** each entry SHALL carry its own `resolvedRequest`, `response`, `durationMs`, `traceId` and `grafanaTraceUrl`
- **AND** the final entry's `resolvedRequest`, `response`, `durationMs`, `traceId` and `grafanaTraceUrl` SHALL match their top-level counterparts
- **AND** fail-fast SHALL retain only the executed prefix, with the failing invocation last
- **AND** each SSE history response SHALL contain that invocation's assembled body and its own parsed events separately

#### Scenario: Single-request single-turn response omits history
- **WHEN** a single-request suite plans exactly one turn, including a multi-turn case collapsed by absence of a per-turn binding
- **THEN** `history` SHALL be omitted

#### Scenario: Recognized streaming providers expose assembled responses
- **WHEN** an HTTP try-out receives a successful chat-completions, Responses API or Anthropic Messages stream recognized by evaluation
- **THEN** `response.body` SHALL equal evaluation's assembled document for that stream
- **AND** chat-completions extensions, including `custom_content` and `custom_fields`, SHALL appear according to evaluation's existing merge semantics
- **AND** the original parsed event payloads SHALL remain in `response.events`

#### Scenario: Structured SSE fallback retains its actual assembled envelope
- **WHEN** evaluation does not recognize a stream's provider mode
- **THEN** `response.body` SHALL retain evaluation's structured events-envelope fallback
- **AND** `response.events` SHALL also contain the parsed event list; this fallback SHALL NOT force recognized provider bodies into envelopes

#### Scenario: Response-column extraction agrees with the exposed body
- **WHEN** a successful SSE try-out performs response-column extraction
- **THEN** extraction SHALL operate on the same assembled JSON document exposed in `response.body`
- **AND** existing extracted columns, warnings and frame propagation across turns/requests SHALL remain unchanged

#### Scenario: MCP response fields remain unchanged
- **WHEN** either try-it-out endpoint completes an MCP tool invocation
- **THEN** `response.body` SHALL remain the parsed final tool-result document
- **AND** `streaming` and `events` SHALL remain omitted, regardless of the SDK's underlying transport

---

### Requirement: Try-it-out invocation uses streaming-aware invoker

Status: **Implemented**

HTTP try-out invocations for DEPLOYMENT and ROUTE suites SHALL distinguish non-SSE bodies from SSE streams. The system SHALL consume an SSE stream once and expose both its evaluation-equivalent assembled body and parsed event list. Existing resource cleanup SHALL be preserved.

#### Scenario: Non-SSE invocation (behavior preserved)
- **WHEN** an HTTP try-out receives a response whose Content-Type is not `text/event-stream`
- **THEN** the nested response SHALL contain the upstream status and existing parsed body, with `streaming` and `events` omitted

#### Scenario: SSE invocation
- **WHEN** an HTTP try-out receives `Content-Type: text/event-stream`
- **THEN** the system SHALL parse the stream once and assemble the same parsed events using evaluation-equivalent response semantics
- **AND** return `streaming=true`, parsed events in `events` and the parsed assembled document in `body`
- **AND** it SHALL NOT reread the consumed stream to construct either view

#### Scenario: SSE invocation resource cleanup
- **WHEN** try-it-out processes an SSE response
- **THEN** the event stream SHALL be closed after processing, including partial/error processing

---

### Requirement: Try-it-out SSE timeout enforcement
When processing SSE responses, try-it-out SHALL enforce an **idle (inactivity) timeout** plus an **absolute max-total cap** to prevent indefinite blocking on stalled or endlessly-heartbeating streams. The idle timeout SHALL be the existing `dial.components.core.try-out.read-timeout-ms` configuration, passed to `SseEventParser.parse(...)` as `idleTimeoutMs` (reset on every received line). The absolute cap SHALL be the dedicated `sse-event-processing.max-total-duration-ms` configuration, passed as `maxTotalDurationMs`. Try-it-out SHALL NOT compute an absolute `clock.millis() + readTimeoutMs` deadline itself.

Status: **Implemented**

#### Scenario: SSE stream idle longer than read timeout
- **WHEN** an SSE stream sends no line for longer than `dial.components.core.try-out.read-timeout-ms`
- **THEN** system SHALL stop reading, return partial events accumulated so far, and include `streaming=true` in response

#### Scenario: Active SSE stream is not killed by total elapsed time
- **WHEN** an SSE stream keeps sending lines (data or keep-alive heartbeats) with gaps shorter than the read timeout, for total elapsed time exceeding the read timeout but within `sse-event-processing.max-total-duration-ms`
- **THEN** system SHALL continue reading and SHALL NOT time out on the idle timeout

#### Scenario: SSE stream exceeds the absolute max-total cap
- **WHEN** an SSE stream stays active beyond `sse-event-processing.max-total-duration-ms` total elapsed time
- **THEN** system SHALL stop reading, return partial events accumulated so far, and include `streaming=true` in response

---

### Requirement: Try-it-out SSE size limit enforcement
When processing SSE responses, try-it-out SHALL enforce the same `max-response-size-bytes` limit used by evaluation to prevent OOM on pathological streams.

Status: **Implemented**

#### Scenario: SSE stream exceeds size limit
- **WHEN** accumulated SSE event data exceeds `max-response-size-bytes`
- **THEN** system SHALL stop reading and return partial events accumulated so far

---

### Requirement: Try-it-out SSE parsing error handling

Status: **Implemented**

When SSE parsing encounters a read error, try-it-out SHALL return the dispatched event prefix and evaluation-equivalent partial/fallback body rather than propagating the read failure as HTTP 500. Existing timeout, status, truncation and fail-fast semantics SHALL remain unchanged.

#### Scenario: SSE stream read error
- **WHEN** a read error such as a connection reset occurs during SSE parsing
- **THEN** the response SHALL contain `streaming=true`, `streamingStatus=ERROR` and dispatched events in `events`
- **AND** `body` SHALL be the assembled result evaluation produces from those events and the error status

#### Scenario: Empty SSE stream
- **WHEN** DIAL Core returns `Content-Type: text/event-stream` with immediate EOF
- **THEN** `response.streaming` SHALL be `true`, `response.events` SHALL be an empty array and `response.body` SHALL be `{"events":[]}`, matching evaluation's empty structured-stream fallback

#### Scenario: SSE timeout returns partial response
- **WHEN** an SSE stream exceeds the idle timeout or absolute max-total cap
- **THEN** the response SHALL contain the dispatched event prefix in `events`, `streaming=true`, `streamingStatus=TIMEOUT` and evaluation's assembled partial/fallback result in `body`
- **AND** parser deadlines SHALL retain their existing completed-line-read boundary; transport behaviour governs a stalled read

#### Scenario: Recognized interrupted stream exposes plain partial text
- **WHEN** a recognized provider stream contains accumulated text and ends with a non-success parsing status
- **THEN** `response.body` SHALL contain evaluation's partial text as a JSON string value, without an additional layer of JSON quoting
- **AND** the dispatched events SHALL remain available in `response.events`
- **AND** the invocation SHALL remain failed: extraction SHALL be skipped and later chain invocations SHALL not run

#### Scenario: Response size limit keeps diagnostics and assembled prefix
- **WHEN** the SSE response exceeds the configured response-size limit
- **THEN** `events` SHALL contain only events dispatched before the overflow
- **AND** `streamingStatus=ERROR` and the existing truncation warning SHALL be retained
- **AND** `body` SHALL equal evaluation's assembled result from that same dispatched prefix and status

#### Scenario: Missing assembled output retains event diagnostics in body
- **WHEN** an HTTP SSE invocation cannot produce an assembled document and reports an assembly error
- **THEN** `response.body` SHALL fall back to `{"events":[...]}` containing the already-parsed events, including an empty array when no events were dispatched
- **AND** `response.events` SHALL contain the same parsed event list
- **AND** the error status, skipped extraction and fail-fast semantics SHALL remain unchanged
- **AND** a valid assembled JSON null or empty partial string SHALL NOT be replaced by this fallback

---

### Requirement: Try-it-out invocation uses pluggable serializer
The try-it-out service SHALL use `RequestBodySerializerRegistry` to serialize the resolved body before invoking the DIAL Core deployment, instead of relying on the invoker's hardcoded JSON serialization.

#### Scenario: JSON body invocation (current behavior preserved)
- **WHEN** the resolved body is `ResolvedJsonBodyDto`
- **THEN** the system SHALL serialize as JSON and invoke DIAL Core with `Content-Type: application/json`

#### Scenario: Multipart body invocation
- **WHEN** the resolved body is `ResolvedMultipartBodyDto`
- **THEN** the system SHALL build a multipart request (reading file bytes from BlobStorage for file parts), and invoke DIAL Core with `Content-Type: multipart/form-data`

#### Scenario: URL-encoded body invocation
- **WHEN** the resolved body is `ResolvedUrlEncodedBodyDto`
- **THEN** the system SHALL build a URL-encoded form body and invoke DIAL Core with `Content-Type: application/x-www-form-urlencoded`

---

### Requirement: Try-it-out invocation span
Each try-it-out invocation SHALL create an OTel child span that wraps the DIAL Core HTTP call. The span's trace ID SHALL be returned in the response and propagated to DIAL Core via `traceparent`.
Status: **Implemented**

#### Scenario: Invocation span created
- **WHEN** `TryItOutService` calls `invokeAndBuildResponse()`
- **THEN** an OTel span named `try-it-out.invoke` SHALL be started as a child of the current HTTP request span
- **AND** the span SHALL be ended after the DIAL Core response is received (or on failure)

#### Scenario: traceId returned in response
- **WHEN** try-it-out completes (success or DIAL Core error)
- **THEN** `TryItOutResponseDto.traceId` SHALL contain the 32-char hex trace ID of the invocation span

#### Scenario: traceId null when tracing disabled
- **WHEN** Micrometer Tracing is disabled (`management.tracing.enabled=false`)
- **THEN** `TryItOutResponseDto.traceId` SHALL be null (Micrometer returns a no-op span; its trace ID is all-zeros, which SHALL be treated as absent and serialized as null via `@JsonInclude(NON_NULL)`)

---

### Requirement: TryItOutWithVariablesRequestDto structure
The request body for the suite-level try-it-out endpoint SHALL carry a request-index-keyed `variables` object.

Status: **Implemented**

#### Scenario: Request structure
- **WHEN** client sends a try-it-out with variables request
- **THEN** `TryItOutWithVariablesRequestDto` SHALL include:
  - `variables` (JSON object, required, not null, may be empty) — keys are request indices serialized as JSON strings (`"0"`, `"1"`, …); each value is an object (or null) mapping template variable names to constant values for that request. An empty object is valid when no request has placeholders.

#### Scenario: OpenAPI documents the indexed shape
- **WHEN** a client reads `/v3/api-docs`
- **THEN** the try-it-out-with-variables operation SHALL expose request examples `minimal`, `full` and `chained` in the indexed shape, `chained` carrying entries for indices `0` and `1`

---

### Requirement: URL construction for DIAL Core invocation
The system SHALL construct the full DIAL Core URL by combining the base URL, a deployment prefix (DEPLOYMENT suites only), and the resolved URL template. `DialCoreUrlBuilder` SHALL maintain a `Set<String>` of known OpenAI-standard paths to determine the routing prefix.

Known standard paths (V1): `/chat/completions`, `/embeddings`

Status: **Implemented**

#### Scenario: Standard chat completions endpoint
- **WHEN** the resolved URL from the template equals `/chat/completions`
- **THEN** the full URL SHALL be `{coreBaseUrl}/openai/deployments/{deploymentRef.id}/chat/completions`

#### Scenario: Standard embeddings endpoint
- **WHEN** the resolved URL from the template equals `/embeddings`
- **THEN** the full URL SHALL be `{coreBaseUrl}/openai/deployments/{deploymentRef.id}/embeddings`

#### Scenario: Custom application route
- **WHEN** the resolved URL from the template does NOT match any known OpenAI-standard path (e.g., `/my-custom-endpoint`)
- **THEN** the full URL SHALL be `{coreBaseUrl}/v1/deployments/{deploymentRef.id}/route{resolvedUrl}`

#### Scenario: HTTP method from endpoint reference
- **WHEN** constructing the DIAL Core request
- **THEN** the system SHALL use `endpointRef.method` as the HTTP method

#### Scenario: Global route (ROUTE suite)
- **WHEN** the suite is a ROUTE suite and the resolved URL from the template is `/validate/items`
- **THEN** the full URL SHALL be `{coreBaseUrl}/validate/items`, with no deployment prefix
- **AND** the root-relative path rules of the engine requirement `ROUTE suite invocation path validation` SHALL apply

#### Scenario: Invalid global route path is rejected
- **WHEN** the suite is a ROUTE suite and the resolved URL violates the root-relative path rules (e.g. `validate/items`)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and the message defined by the engine requirement `ROUTE suite invocation path validation` (e.g. `ROUTE urlTemplate must resolve to a root-relative DIAL Core path`)
- **AND** DIAL Core SHALL NOT be invoked for that request

---

### Requirement: Error handling for infrastructure failures
The system SHALL map DIAL Core connectivity and timeout failures to appropriate HTTP error responses.

#### Scenario: DIAL Core unreachable
- **WHEN** the DIAL Core deployment is unreachable (connection refused, DNS failure)
- **THEN** the system SHALL return HTTP 502 with error code `UPSTREAM_ERROR`

#### Scenario: DIAL Core timeout
- **WHEN** the DIAL Core deployment does not respond within the configured timeout
- **THEN** the system SHALL return HTTP 504 with error code `UPSTREAM_TIMEOUT`

---

### Requirement: Try-it-out timeout configuration
The system SHALL support a separate read timeout for try-it-out invocations, configurable via `dial.components.core.try-out.read-timeout-ms`.

#### Scenario: Default timeout
- **WHEN** no explicit try-it-out timeout is configured
- **THEN** the system SHALL use a default of 120000 milliseconds (120 seconds)

#### Scenario: Custom timeout
- **WHEN** `dial.components.core.try-out.read-timeout-ms` is set to a custom value
- **THEN** the system SHALL use the configured value for the try-it-out RestClient's read timeout

---

### Requirement: Request headers for DIAL Core invocation
The system SHALL include resolved template headers and the user's authorization token when invoking the DIAL Core deployment.

#### Scenario: Authorization token forwarded
- **WHEN** system invokes DIAL Core deployment
- **THEN** the user's JWT token from the incoming request SHALL be forwarded as `Authorization: Bearer` header

#### Scenario: Template headers included
- **WHEN** the resolved request has custom headers from the template (e.g., `X-Custom: value`)
- **THEN** the service layer SHALL convert `List<KeyValueTemplateDto>` from `ResolvedRequestDto` to `HttpHeaders` and pass them to the invoker (preserving duplicate header names)

#### Scenario: Query parameters included
- **WHEN** the resolved request has query parameters
- **THEN** the service layer SHALL convert `List<KeyValueTemplateDto>` from `ResolvedRequestDto` to `MultiValueMap<String, String>` and pass them to the invoker

---

### Requirement: OpenAPI documentation

Status: **Implemented**

Both try-it-out endpoints SHALL expose OpenAPI descriptions, request/response schemas, examples and error responses that describe the assembled-body/parsed-events contract.

#### Scenario: Swagger UI shows endpoints
- **WHEN** a user navigates to Swagger UI
- **THEN** both try-it-out endpoints SHALL be visible under their appropriate tags with descriptions and response schemas

#### Scenario: SSE examples separate body and events
- **WHEN** a client reads either endpoint's SSE response example in `/v3/api-docs`
- **THEN** the example SHALL show a recognized provider's assembled document in `response.body` and its parsed stream chunks in `response.events`
- **AND** response schema descriptions SHALL explain partial-string and structured-envelope fallback bodies without claiming every SSE body is an events envelope

---

### Requirement: `tryWithVariables` remains single-turn
The variables-based try-it-out endpoint (`POST /api/v1/test-suites/{testSuiteId}/try-it-out`) SHALL remain single-turn per request. It has no bound test case and therefore no `multiTurnData` source.

Status: **Implemented**

#### Scenario: Variables-based try-it-out is unaffected by multi-turn support
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/try-it-out` with a `variables` object
- **THEN** the system SHALL resolve and invoke exactly one turn per chain request (exactly one request for a suite without `additionalRequests`)

---

### Requirement: Try-It-Out validates the resolved deployment model

All deployment Try-It-Out modes SHALL validate the resolved body for canonical OpenAI Responses and Anthropic Messages requests against the suite's deployment ID immediately before invocation. A failure SHALL expose `REQUEST_BODY_VALIDATION_ERROR`, include the resolved request for diagnosis, and SHALL not call DIAL Core.

Status: **Implemented**

#### Scenario: Single-invocation Try-It-Out rejects an invalid model

- **WHEN** a single-invocation test-case or variables Try-It-Out resolves an invalid `model`
- **THEN** the endpoint SHALL return HTTP 400 with outer error code `VALIDATION_ERROR`
- **AND** the error details' resolved request SHALL carry a `REQUEST_BODY_VALIDATION_ERROR` warning
- **AND** DIAL Core SHALL not be invoked

#### Scenario: Chained Try-It-Out reports an invalid model as the failed invocation

- **WHEN** model validation fails during a multi-request or multi-turn Try-It-Out
- **THEN** the returned failed invocation SHALL contain a status-code-zero error response with code `REQUEST_BODY_VALIDATION_ERROR`
- **AND** its resolved request SHALL be retained in the top-level response and history
- **AND** no later invocation in the chain SHALL run

#### Scenario: Matching model invokes normally

- **WHEN** the resolved `model` matches the suite's deployment ID
- **THEN** Try-It-Out SHALL invoke DIAL Core using its existing response contract

---

### Requirement: Variables are scoped per chain request
For a suite with `additionalRequests`, variables-mode try-it-out SHALL resolve request `i` using constant bindings built only from `variables["i"]`, wholesale-replacing that request's own `inputBindings`. Variables given for one request SHALL NOT be visible to any other request. Values extracted into the accumulated frame by earlier requests' response columns SHALL remain available to later requests as in a run.

Status: **Implemented**

#### Scenario: Same variable name carries different values per request
- **WHEN** a suite's request `0` and `additionalRequests[0]` both use `${{user_message}}`
- **AND** user sends `{ "variables": { "0": { "user_message": "first" }, "1": { "user_message": "second" } } }`
- **THEN** request `0` SHALL be invoked with `user_message` resolved to `"first"`
- **AND** request `1` SHALL be invoked with `user_message` resolved to `"second"`

#### Scenario: A request's variables do not leak into another request
- **WHEN** user sends `{ "variables": { "0": { "temperature": 0.2 } } }` for a two-request suite whose request `1` uses `${{temperature}}` with a default value
- **THEN** request `1` SHALL resolve `temperature` to its default value, not `0.2`

#### Scenario: Frame values from earlier requests still resolve
- **WHEN** request `0` extracts response column `configId` and request `1`'s JSONata body (`jsonataContent`) references `$configId`
- **AND** `variables` has no entry for index `1`
- **THEN** request `1`'s body SHALL resolve `$configId` from the accumulated frame (frame values are visible to JSONata bodies only, not to `${{…}}` placeholders, as in a run)

### Requirement: Try it out for ROUTE suites
Both try-it-out endpoints (`POST /api/v1/test-suites/{id}/test-cases/{tcId}/try-it-out` and `POST /api/v1/test-suites/{id}/try-it-out`) SHALL support ROUTE suites. The invocation SHALL go to the DIAL Core root path given by the resolved `urlTemplate`, and the resolved deployment-model validation described in `Try-It-Out validates the resolved deployment model` SHALL NOT be applied to a ROUTE suite. The response shape, request-chain and multi-turn semantics, response-column extraction, SSE handling and invocation span SHALL be unchanged from DEPLOYMENT suites.

Status: **Implemented**

#### Scenario: Test-case try-it-out invokes the Core root path
- **WHEN** an authenticated user sends POST to `/api/v1/test-suites/{id}/test-cases/{tcId}/try-it-out` for a ROUTE suite whose `urlTemplate` is `/validate/items`
- **THEN** DIAL Core SHALL be invoked at path `/validate/items`
- **AND** the system SHALL return HTTP 200 with the standard try-it-out response

#### Scenario: Variables-based try-it-out invokes the Core root path
- **WHEN** an authenticated user sends POST to `/api/v1/test-suites/{id}/try-it-out` with a `variables` object for a ROUTE suite
- **THEN** DIAL Core SHALL be invoked at the resolved root-relative path, with no deployment prefix

#### Scenario: Model mismatch on a ROUTE suite is not rejected
- **WHEN** a ROUTE suite's resolved `urlTemplate` is `/openai/v1/responses` and its resolved body carries `{"model":"not-a-deployment"}`
- **THEN** Try-It-Out SHALL invoke DIAL Core at `/openai/v1/responses`
- **AND** the system SHALL NOT return a `REQUEST_BODY_VALIDATION_ERROR`

## Implementation Notes

- Streamed preview bodies reuse runner-core `StreamingResponseAccumulator`, with typed event-envelope diagnostics when no assembled document is available. The parsed assembled document is also used for response-column extraction. Body/events descriptions live in `TryItOutCoreResponseDto`; coverage includes `TryItOutServiceTest`, `TryItOutFunctionalTests`, `McpTryItOutFunctionalTests`, and `OpenApiExamplesFunctionalTests`.


- `TryItOutService` owns type-aware ROUTE preconditions and invocation; `DialCoreUrlBuilder` builds the root-relative URL. URL-builder rejection is mapped to HTTP 400 `VALIDATION_ERROR`, while run execution records `REQUEST_RESOLUTION_ERROR` per case.
- Modified: `TryItOutService` — branch by `suiteType`: HTTP flow (existing) or MCP flow (new)
- MCP test-case flow: loads suite-level `inputBindings`, determines effective bindings (test case `inputBindingsOverride` > suite bindings) → `McpRequestResolver.resolve(argumentTemplate, effectiveBindings, testCaseData)` → `McpToolInvoker.callTool(id, tool, args, token, transport)` → `McpResponseSerializer.serialize()` → build `TryItOutResponseDto`
- MCP variables flow: `TryItOutVariableBindings.toBindingsByRequest(variables, 1).get(0)` → `McpRequestResolver.resolveWithVariables(argumentTemplate, bindings, variables[0] or {})` → same invocation chain
- HTTP variables flow: `TryItOutVariableBindings.toBindingsByRequest(variables, 1 + additionalRequests.size())` validates indices (after suite preconditions, before any invocation) and returns request index → constant bindings; the single-request path resolves with entry `0`, the chain path passes the map to `runChain`, which looks bindings up by `RequestExecutionSpec.requestIndex()` (absent → empty list)
- MCP transport propagation: `TryItOutService` reads `mcpDeploymentRef.transport`, defaults to `McpTransport.STREAMABLE_HTTP` when null, passes to `McpToolInvoker`
- Reuse `TryItOutResponseDto` structure — `resolvedRequest.body` contains arguments as JSON, `response.body` contains serialized MCP response
- Resolved request-model validation: `TryItOutService.invokeTurn` calls `RequestModelValidator.validateForExecution` before building the URL — deliberately not inside `validateResolutionResult`, which the single-invocation path shares. `invokeAndBuildResponse` converts the resulting `RequestBodyValidationException` into the established HTTP 400 via `toTryItOutValidationException`, which appends a `ValidationWarningCode.REQUEST_BODY_VALIDATION_ERROR` warning to the returned `resolvedRequest`; `runChain` catches it separately from `RequestBodyEvaluationException` and builds the status-code-zero `buildModelValidationFailureResult` envelope that retains the resolved request in both the top-level response and `history`, stopping the chain.

### Requirement: Try-it-out resolves skill placeholders to canonical references
Test-case try-out, variables try-out and the resolved-request preview SHALL resolve full-value skill placeholders exactly as a run does (see `request-template`, "Runtime skill reference resolution for full-value placeholders"): the resolved request shown/sent SHALL contain the canonical `skills/{bucket}/{path}` value. In variables mode, a user-supplied value for a variable whose `effectiveType` is `SKILL` SHALL be treated as that variable's constant and canonicalised. No DIAL Core skill route and no file download SHALL be invoked as part of resolving a skill value; only the deployment/tool invocation itself is performed.

Status: **Implemented**
Implementation notes: resolution shares `ResourceRefPlaceholderResolver`; variables mode keeps the column type via `TryItOutVariableBindings.bindToFileColumns` (now FILE or SKILL). Tests: `TryItOutVariableBindingsTest`, `SkillFieldFunctionalTests` (test-case try-out, both variables try-out cases).

#### Scenario: Test-case try-out sends the canonical ref
- **WHEN** a suite body carries `"custom_content": {"skills": [{"url": "${{s}}"}]}`, `s` is bound to a `SKILL` column, and the test case holds `"skills/b/x/"`
- **THEN** the resolved request SHALL contain `"skills": [{"url": "skills/b/x"}]`

#### Scenario: Variables try-out for a SKILL variable
- **WHEN** the client calls variables try-out with `{"s": "skills/b/x"}` for a `${{s|skill}}` template
- **THEN** the resolved request SHALL contain `"skills/b/x"` and the variables listing SHALL report `effectiveType: SKILL`

#### Scenario: No skill download
- **WHEN** a try-out resolves a skill value
- **THEN** no file download/upload and no skill-route call SHALL occur
