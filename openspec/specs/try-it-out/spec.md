# Try It Out Spec

## Purpose

Endpoints for sending a single resolved request to a DIAL Core deployment or MCP tool call and proxying the response. Covers test-case-based and variables-based modes for both HTTP and MCP suites, URL routing, timeout configuration, error proxying, type-aware validation rules, and SSE streaming response handling (`streaming=true`, `events` list, `{"events":[...]}` body envelope).

Status: **Implemented**

## Requirements

### Requirement: Try it out with test case data
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out` to resolve the effective request template using the test case's data and effective bindings, send the resolved request to the DIAL Core deployment referenced by the test suite, and return the deployment's response along with the resolved request details. When the test case is multi-turn, the system SHALL execute every turn of the sequence and return the result of the last executed turn, plus invocation `history` whenever the plan has more than one invocation, even when failure stops it after the first.

Status: **Implemented**

#### Scenario: Successful try-it-out with test case
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`
- **AND** the test suite has a valid `deploymentRef`, `requestTemplate`, and `endpointRef`
- **AND** the test case exists in the dataset referenced by the test suite
- **THEN** the system SHALL load and validate the suite, then use `ResolvedRequestService.planChain` to load the case from the suite's referenced dataset and plan each request's turns. On a single-invocation plan it SHALL use `resolveRequest`; otherwise it SHALL resolve in `runChain` using the request's suite-level template and bindings
- **AND** send the resolved request(s) to the DIAL Core deployment (after the transaction completes and the DB connection is released)
- **AND** return HTTP 200 with `TryItOutResponseDto` containing the resolved request, the deployment's response (status code + body), and execution duration in milliseconds

#### Scenario: Suite-owned templates and bindings
- **WHEN** the test case is resolved for try-it-out
- **THEN** resolution SHALL use the template and bindings of the suite's corresponding request; current dataset test cases have no template/binding override fields

#### Scenario: Test suite not found
- **WHEN** user sends try-it-out request with non-existent `testSuiteId`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

#### Scenario: Test case not found
- **WHEN** user sends try-it-out request with non-existent `testCaseId` or a test case that does not belong to the suite's referenced dataset
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

#### Scenario: Multi-turn test case executes every turn
- **WHEN** the test case has non-null `multiTurnData` and at least one effective input binding references a dataset schema field flagged `perTurn=true` (same turn-count decision as `PerTurnBindingDetector` uses for real runs)
- **THEN** the system SHALL resolve and invoke each turn sequentially, in order, from turn 0 to turn N-1, where N is the number of entries in `multiTurnData`
- **AND** each turn's effective data SHALL be the merge of the test case's shared `data` with that turn's entry (per-turn wins on key collision)
- **AND** each turn after the first SHALL be resolved using `frameBindings` derived from the response columns extracted from the previous turn's response (via the suite's `responseColumns` definitions), enabling `$history`-style JSONata expressions in the request template to accumulate across turns exactly as they do in a real suite run
- **AND** the first turn SHALL be resolved with empty `frameBindings`
- **AND** the response SHALL be a `TryItOutResponseDto` containing the last executed invocation's `resolvedRequest`/`response`/`durationMs`/`traceId` at the top level, plus a `history` array with one entry per executed invocation when the plan has more than one invocation (see "Multi-invocation plan includes executed history")

#### Scenario: Multi-turn data present but no per-turn binding collapses to a single turn
- **WHEN** the test case has non-null `multiTurnData` with more than one entry, but no effective input binding references any `perTurn=true` schema field
- **THEN** the system SHALL treat the case as a single turn using only the shared `data` (identical to the `PerTurnBindingDetector` collapse behavior used by real runs)
- **AND** for a single-request suite the response SHALL omit `history`; a multi-request suite SHALL still retain invocation history

#### Scenario: Turn failure stops the sequence
- **WHEN** executing a multi-turn test case and a turn's invocation resolves to a non-2xx DIAL Core status, has a non-success SSE parse result, fails request-body JSONata evaluation (`RequestBodyEvaluationException`), or fails resolved request-model validation (`RequestBodyValidationException`)
- **THEN** the system SHALL stop executing further turns (fail-fast)
- **AND** the failed turn's resolved request and error response SHALL be returned as the `resolvedRequest`/`response`, and as the last entry of `history`
- **NOTE**: a transport-level failure during a turn's invocation (timeout, connection refused, unreachable deployment) is NOT caught by this mechanism — it propagates uncaught exactly as in the single-turn path, producing the pre-existing plain 502/504 error response.
- **NOTE**: `ValidationException` (null resolved URL) or `TryItOutValidationException` (unresolved REQUIRED variables) thrown by `validateResolutionResult` for turns after the first remain uncaught. `runChain` catches only `RequestBodyEvaluationException` and `RequestBodyValidationException`; other validation failures propagate exactly like transport-level failures. A `REQUEST_BODY_EVALUATION_ERROR` warning never reaches `validateResolutionResult` in the chain: `runChain` resolves via `RequestResolver.resolveForRun`, which throws `RequestBodyEvaluationException` instead of downgrading to a warning, so a body evaluation failure is always caught there. The `TryItOutValidationException` for that warning fires only on single-invocation paths that use the preview resolver.

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
- **WHEN** user sends try-it-out request whose `variables` has a key that is not an integer (e.g., a flat shape `{ "variables": { "prompt": "Hello" } }`)
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

#### Scenario: JSON request body fails JSONata evaluation (DEPLOYMENT suite)
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
Status: **Implemented**


The system SHALL support try-it-out for MCP_TOOL suites via `POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`. For MCP suites, the system SHALL resolve tool arguments from the argument template using effective bindings and test case data, execute the MCP tool call via `McpToolInvoker`, and return the MCP response.

#### Scenario: Successful MCP try-it-out with test case
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`
- **AND** the test suite has `suiteType = MCP_TOOL` with valid `mcpDeploymentRef`, `toolRef`, and `argumentTemplate`
- **THEN** the system SHALL use the suite-level MCP input bindings
- **AND** resolve tool arguments via `McpRequestResolver.resolve(argumentTemplate, effectiveBindings, testCaseData, datasetSchema)`
- **AND** determine the MCP transport from `mcpDeploymentRef.transport` (defaulting to `STREAMABLE_HTTP` when null)
- **AND** execute `McpToolInvoker.callTool(mcpDeploymentRef.id, toolRef.name, resolvedArguments, credential, transport)`
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
Status: **Implemented**


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

The response SHALL be an envelope containing the resolved request, the DIAL Core response, timing information, and the OTel trace ID of the invocation. The top-level fields always reflect the **last executed turn** — identical in shape to a single-turn response. For a plan with more than one invocation (multiple turns, multiple requests, or both), the envelope additionally carries a `history` field: an ordered list of one entry per executed invocation (request-major, then turn-major), including the last; early failure retains only the executed prefix, each entry having the same `resolvedRequest`/`response`/`durationMs`/`traceId`/`grafanaTraceUrl` shape as the envelope itself. `history` is omitted (`null`, not serialized) for a single-request suite whose test case plans one turn (including a multi-turn case collapsed by absence of a per-turn binding). A multi-request suite uses history even when each request plans only one turn.

`TryItOutCoreResponseDto` SHALL include:
- `statusCode` (int) — HTTP status code from DIAL Core
- `body` (Object, nullable) — parsed JSON response body, or `{"events": [...]}` envelope for SSE responses
- `streaming` (Boolean, nullable) — `true` when response was SSE, `null` (omitted from JSON) for non-SSE responses. Uses `@JsonInclude(NON_NULL)`.
- `events` (List of `SseEventDto`, nullable) — parsed SSE events for frontend debugging, `null` (omitted from JSON) for non-SSE responses. Uses `@JsonInclude(NON_NULL)`.

`SseEventDto` SHALL be a DTO with:
- `event` (String) — SSE event type name (e.g., `"process_rules"`, `"message"`)
- `data` (Object) — parsed JSON payload if valid JSON, raw string otherwise

#### Scenario: Response structure (non-SSE)
- **WHEN** system returns a try-it-out response for a non-SSE invocation
- **THEN** `TryItOutResponseDto` SHALL include:
  - `resolvedRequest` (`ResolvedRequestDto`) — the resolved URL, query params, headers, and body
  - `response` (`TryItOutCoreResponseDto`) — `statusCode` (int), `body` (Object, nullable — parsed JSON or raw string), `streaming` = `null` (omitted), `events` = `null` (omitted)
  - `durationMs` (Long) — elapsed time through `invokeWithStreaming` return; SSE parsing occurs afterward and is excluded
  - `traceId` (String, nullable) — the 32-char hex OTel trace ID; null when tracing disabled

#### Scenario: Response structure (SSE)
- **WHEN** DIAL Core returns an SSE response (Content-Type is `text/event-stream`)
- **THEN** `response.streaming` SHALL be `true`, `response.events` SHALL contain the list of parsed `SseEventDto` objects in order of receipt, `response.body` SHALL be the `{"events": [...]}` envelope (the document JSONata would operate on)

#### Scenario: DIAL Core returns error status
- **WHEN** DIAL Core returns 4xx or 5xx status code
- **THEN** the try-it-out endpoint SHALL still return HTTP 200
- **AND** the `response.statusCode` SHALL contain the actual upstream status code
- **AND** the `response.body` SHALL contain the upstream response body
- **AND** `traceId` SHALL be populated when the invocation span is valid

#### Scenario: Response body is valid JSON (non-SSE)
- **WHEN** DIAL Core returns a non-SSE response body that is valid JSON
- **THEN** `response.body` SHALL be the parsed JSON value (object, array, string, number, boolean, or null)

#### Scenario: Response body is not JSON (non-SSE)
- **WHEN** DIAL Core returns a non-SSE response body that is not valid JSON (e.g., HTML error page, plain text)
- **THEN** `response.body` SHALL be the raw response string

#### Scenario: Resolved request with multipart body
- **WHEN** the test suite uses a `multipart/form-data` request template
- **THEN** `resolvedRequest.body` SHALL be a `ResolvedMultipartBodyDto` showing resolved text parts and DIAL file references

#### Scenario: Resolved request with URL-encoded body
- **WHEN** the test suite uses a `application/x-www-form-urlencoded` request template
- **THEN** `resolvedRequest.body` SHALL be a `ResolvedUrlEncodedBodyDto` showing the resolved `List<KeyValueTemplateDto>` entries

#### Scenario: Multi-invocation plan includes executed history
- **WHEN** a try-it-out plan contains more than one invocation (multiple turns or multiple requests)
- **THEN** `TryItOutResponseDto.history` SHALL be present, containing one entry per executed invocation in execution order, including a failing first invocation when the remaining planned calls are skipped
- **AND** each entry SHALL carry that turn's own `resolvedRequest`/`response`/`durationMs`/`traceId`/`grafanaTraceUrl`
- **AND** the last entry in `history` SHALL be identical to the envelope's own top-level `resolvedRequest`/`response`/`durationMs`/`traceId`/`grafanaTraceUrl`
- **AND** if the sequence stopped early due to a failing turn (fail-fast), `history` SHALL contain only the turns that actually ran, with the last entry being the failing turn

#### Scenario: Single-request single-turn response omits history
- **WHEN** a single-request suite plans exactly one turn, including a multi-turn test case collapsed by absence of a per-turn binding
- **THEN** `TryItOutResponseDto.history` SHALL be absent (`null`, omitted from JSON) — the response shape is unchanged from a plain single-turn response

---

### Requirement: Try-it-out invocation uses streaming-aware invoker
The try-it-out service SHALL use `DialCoreDeploymentInvoker.invokeWithStreaming()` for HTTP/DEPLOYMENT suite invocations. For non-SSE responses, behavior is unchanged — `DeploymentInvocationResult.body()` provides the parsed response. For SSE responses, the service SHALL parse events using `SseEventParser` and build the structured response.

Status: **Implemented**

#### Scenario: Non-SSE invocation (behavior preserved)
- **WHEN** try-it-out invokes deployment and response Content-Type is NOT `text/event-stream`
- **THEN** system SHALL read parsed body from `DeploymentInvocationResult.body()`, build `TryItOutCoreResponseDto` with `statusCode` and `body`, with `streaming` and `events` as `null`

#### Scenario: SSE invocation
- **WHEN** try-it-out invokes deployment and response Content-Type is `text/event-stream`
- **THEN** system SHALL parse SSE stream via `SseEventParser`, build `TryItOutCoreResponseDto` with `streaming=true`, `events` containing parsed `SseEventDto` list, and `body` containing `{"events": [...]}` envelope

#### Scenario: SSE invocation resource cleanup
- **WHEN** try-it-out processes an SSE response
- **THEN** system SHALL use `DeploymentInvocationResult` in try-with-resources to ensure `eventStream` is closed

---

### Requirement: Try-it-out SSE timeout enforcement
When processing SSE responses, try-it-out SHALL enforce an **idle (inactivity) timeout** plus an **absolute max-total cap** to bound progress at completed line-read boundaries. The idle timeout SHALL be the existing `dial.components.core.try-out.read-timeout-ms` configuration, passed to `SseEventParser.parse(...)` as `idleTimeoutMs` (reset on every received line). The absolute cap SHALL be the dedicated `sse-event-processing.max-total-duration-ms` configuration, passed as `maxTotalDurationMs`. Try-it-out SHALL NOT compute an absolute `clock.millis() + readTimeoutMs` deadline itself.

Status: **Implemented**

#### Scenario: SSE stream idle longer than read timeout
- **WHEN** an SSE stream sends no line for longer than `dial.components.core.try-out.read-timeout-ms`
- **THEN** on the next completed line read the parser SHALL return partial dispatched events with `streaming=true` and `streamingStatus=TIMEOUT`

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
- **THEN** system SHALL return events dispatched before the overflowing event, with `streamingStatus=ERROR` and `truncationWarning`

---

### Requirement: Try-it-out SSE parsing error handling
When SSE parsing encounters an error (IOException, malformed stream), try-it-out SHALL return a partial or error response rather than propagating the exception as an HTTP 500.

Status: **Implemented**

#### Scenario: SSE stream read error
- **WHEN** an `IOException` occurs while parsing the SSE stream (e.g., connection reset mid-stream)
- **THEN** system SHALL return partial events accumulated before the error with `streaming=true` and `streamingStatus=ERROR` and `body` as the `{"events": [...]}` envelope of the partial events

#### Scenario: Empty SSE stream
- **WHEN** DIAL Core returns `Content-Type: text/event-stream` but the stream body is empty (immediate EOF)
- **THEN** `response.streaming` SHALL be `true`, `response.events` SHALL be an empty list, `response.body` SHALL be `{"events": []}`

#### Scenario: SSE timeout returns partial response
- **WHEN** an SSE stream stalls past the idle timeout, or stays active beyond the absolute max-total cap, during try-it-out
- **THEN** system SHALL return partial events accumulated so far with `streaming=true`, `streamingStatus=TIMEOUT`, and `body` as the envelope of partial events. Parser deadlines are checked after `readLine`, not by a separately timed future; transport behavior governs a stalled read

---

### Requirement: Try-it-out invocation uses pluggable serializer
Status: **Implemented**

The try-it-out service SHALL use `RequestBodySerializerRegistry` to serialize the resolved body before invoking the DIAL Core deployment, instead of relying on the invoker's hardcoded JSON serialization.

#### Scenario: JSON body invocation (current behavior preserved)
- **WHEN** the resolved body is `ResolvedJsonBodyDto`
- **THEN** the system SHALL serialize as JSON and invoke DIAL Core with `Content-Type: application/json`

#### Scenario: Multipart body invocation
- **WHEN** the resolved body is `ResolvedMultipartBodyDto`
- **THEN** the system SHALL build a multipart request (downloading referenced DIAL files for file parts), and invoke DIAL Core with `Content-Type: multipart/form-data`

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

Known deployment-prefixed paths: `/chat/completions`, `/embeddings`. Model-selecting paths pass through without a deployment prefix and their body model selects the deployment: any resolved URL that starts with `/openai/v1/responses` (case-insensitive prefix match), and `/anthropic/v1/messages` on exact (case-sensitive) match only.

Status: **Implemented**

#### Scenario: Standard chat completions endpoint
- **WHEN** the resolved URL from the template equals `/chat/completions`
- **THEN** the full URL SHALL be `{coreBaseUrl}/openai/deployments/{deploymentRef.id}/chat/completions`

#### Scenario: Standard embeddings endpoint
- **WHEN** the resolved URL from the template equals `/embeddings`
- **THEN** the full URL SHALL be `{coreBaseUrl}/openai/deployments/{deploymentRef.id}/embeddings`

#### Scenario: Custom application route
- **WHEN** the resolved URL from the template does NOT match a deployment-prefixed or canonical fixed path (e.g., `/my-custom-endpoint`)
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
Status: **Implemented**

The system SHALL map DIAL Core connectivity and timeout failures to appropriate HTTP error responses.

#### Scenario: DIAL Core unreachable
- **WHEN** the DIAL Core deployment is unreachable (connection refused, DNS failure)
- **THEN** the system SHALL return HTTP 502 with error code `UPSTREAM_ERROR`

#### Scenario: DIAL Core timeout
- **WHEN** the DIAL Core deployment does not respond within the configured timeout
- **THEN** the system SHALL return HTTP 504 with error code `UPSTREAM_TIMEOUT`

---

### Requirement: Try-it-out timeout configuration
Status: **Implemented**

The system SHALL support a separate read timeout for try-it-out invocations, configurable via `dial.components.core.try-out.read-timeout-ms`.

#### Scenario: Default timeout
- **WHEN** no explicit try-it-out timeout is configured
- **THEN** the system SHALL use a default of 3600000 milliseconds (one hour)

#### Scenario: Custom timeout
- **WHEN** `dial.components.core.try-out.read-timeout-ms` is set to a custom value
- **THEN** the system SHALL use the configured value for the try-it-out RestClient's read timeout

---

### Requirement: Request headers for DIAL Core invocation
Status: **Implemented**

The system SHALL include resolved template headers and the caller's credential when invoking the DIAL Core deployment.

#### Scenario: Authorization token forwarded
- **WHEN** system invokes DIAL Core deployment
- **THEN** the caller credential SHALL be forwarded using its kind: `Authorization: Bearer <jwt>` or `Api-Key: <key>`

#### Scenario: Template headers included
- **WHEN** the resolved request has custom headers from the template (e.g., `X-Custom: value`)
- **THEN** the service layer SHALL convert `List<KeyValueTemplateDto>` from `ResolvedRequestDto` to `HttpHeaders` and pass them to the invoker (preserving duplicate header names)

#### Scenario: Query parameters included
- **WHEN** the resolved request has query parameters
- **THEN** the service layer SHALL convert `List<KeyValueTemplateDto>` from `ResolvedRequestDto` to `MultiValueMap<String, String>` and pass them to the invoker

---

### Requirement: OpenAPI documentation
Status: **Implemented**

The system SHALL expose OpenAPI annotations on both try-it-out endpoints with descriptions, request/response schemas, and error responses.

#### Scenario: Swagger UI shows endpoints
- **WHEN** user navigates to Swagger UI
- **THEN** both try-it-out endpoints are visible under appropriate tags with descriptions and response schemas

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

### Requirement: Try-it-out resolves skill placeholders to canonical references
Test-case try-out, variables try-out and the resolved-request preview SHALL resolve full-value skill placeholders exactly as a run does (see `request-template`, "Runtime skill reference resolution for full-value placeholders"): the resolved request shown/sent SHALL contain the canonical `skills/{bucket}/{path}` value. In variables mode, a user-supplied value for a variable whose `effectiveType` is `SKILL` SHALL be treated as that variable's constant and canonicalised. No DIAL Core skill route and no file download SHALL be invoked as part of resolving a skill value; only the deployment/tool invocation itself is performed.

Status: **Implemented**
Implementation notes: resolution shares `ResourceRefPlaceholderResolver`; variables mode keeps the column type via `TryItOutVariableBindings.bindToFileColumns` (now FILE or SKILL). Tests: `TryItOutVariableBindingsTest`, `SkillFieldFunctionalTests` (test-case try-out, both variables try-out cases).

#### Scenario: Test-case try-out sends the canonical ref
- **WHEN** a suite body carries `"custom_content": {"skills": [{"url": "${{s}}"}]}`, `s` is bound to a `SKILL` column, and the test case holds `"skills/b/x/"`
- **THEN** the resolved request SHALL contain `"skills": [{"url": "skills/b/x"}]`

#### Scenario: Variables try-out for a SKILL variable
- **WHEN** the client calls variables try-out with `{"variables":{"0":{"s":"skills/b/x"}}}` for a `${{s|skill}}` template
- **THEN** the resolved request SHALL contain `"skills/b/x"` and the variables listing SHALL report `effectiveType: SKILL`

#### Scenario: No skill download
- **WHEN** a try-out resolves a skill value
- **THEN** no file download/upload and no skill-route call SHALL occur

## Implementation Notes

- Invocation/chain/streaming/extraction: `src/main/java/com/epam/aidial/evaluation/service/domain/TryItOutService.java`.
- Planning: `src/main/java/com/epam/aidial/evaluation/service/domain/ResolvedRequestService.java`; indexed constant bindings: `TryItOutVariableBindings.java`.
- Shared resolution, serialization, URL construction and model validation live in runner-core `runner.service`; SSE parsing/assembly in `runner.job`.
- Tests: `TryItOutServiceTest`, `TryItOutVariableBindingsTest`; functional `TryItOutFunctionalTests`, `McpTryItOutFunctionalTests`, `SkillFieldFunctionalTests`.
