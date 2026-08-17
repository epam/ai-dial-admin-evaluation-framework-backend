## MODIFIED Requirements

### Requirement: Try it out with test case data
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out` to resolve the effective request template using the test case's data and effective bindings, send the resolved request to the DIAL Core deployment referenced by the test suite, and return the deployment's response along with the resolved request details. When the test case is multi-turn, the system SHALL execute every turn of the sequence and return the result of the last executed turn.

Status: **Implemented**

#### Scenario: Successful try-it-out with test case
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`
- **AND** the test suite has a valid `deploymentRef`, `requestTemplate`, and `endpointRef`
- **AND** the test case exists and belongs to the test suite
- **THEN** the system SHALL load the suite (for `deploymentRef`/`endpointRef` precondition validation via `JsonbMapper` deserialization), then delegate turn planning and resolution to `ResolvedRequestService`, which handles test-case loading, effective template/bindings determination, turn planning, and resolution within its own `@Transactional(readOnly=true)` scope. Note: on the single-turn collapse path (no `multiTurnData`, or `multiTurnData` present but no per-turn binding) this results in the suite being loaded 3 times and the test case being loaded 2 times — once via `loadSuite`, once via `planTurns` (suite + test case), and once more via the separate `resolveRequest` call (suite + test case) that `tryWithTestCase` makes when the plan collapses to a single turn. On the true multi-turn path (N>1 turns), the suite is loaded only 2 times and the test case only 1 time — via `loadSuite` and `planTurns` alone, since `resolveRequest` is not called in that case. This is an accepted trade-off for clear pre-validation errors without modifying `ResolvedRequestService`.
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
- **AND** the response SHALL be a `TryItOutResponseDto` containing the last executed turn's `resolvedRequest`/`response`/`durationMs`/`traceId` — identical in shape to a single-turn response. Intermediate turns are not returned individually: when the request template accumulates history via `$append($history, [...])`, the last turn's `resolvedRequest` already carries every prior turn's messages.

#### Scenario: Multi-turn data present but no per-turn binding collapses to a single turn
- **WHEN** the test case has non-null `multiTurnData` with more than one entry, but no effective input binding references any `perTurn=true` schema field
- **THEN** the system SHALL treat the case as a single turn using only the shared `data` (identical to the `PerTurnBindingDetector` collapse behavior used by real runs)
- **AND** the response SHALL be identical in shape to a single-turn test case's response

#### Scenario: Turn failure stops the sequence
- **WHEN** executing a multi-turn test case and a turn's invocation resolves to a non-2xx DIAL Core status, or fails request-body JSONata evaluation (`RequestBodyEvaluationException`)
- **THEN** the system SHALL stop executing further turns (fail-fast)
- **AND** the failed turn's resolved request and error response SHALL be returned as the `resolvedRequest`/`response`
- **NOTE**: a transport-level failure during a turn's invocation (timeout, connection refused, unreachable deployment) is NOT caught by this mechanism — it propagates uncaught exactly as in the single-turn path, producing the pre-existing plain 502/504 error response.
- **NOTE**: `ValidationException` (null resolved URL) or `TryItOutValidationException` (unresolved REQUIRED template variables) thrown by `validateResolutionResult` for turns after the first are ALSO not caught by this mechanism — `runTurnSequence`'s catch clause only catches `RequestBodyEvaluationException`. These propagate uncaught exactly like transport-level failures.

#### Scenario: MCP suite rejects multi-turn test case
- **WHEN** the test suite has `suiteType = MCP_TOOL` and the test case has non-null `multiTurnData`
- **THEN** the system SHALL return HTTP 409 with error code `INVALID_OPERATION`, without invoking the MCP tool
- **AND** the error message SHALL indicate that MCP suites do not support multi-turn test cases (consistent with the existing rejection of MCP + multi-turn at run creation)
- **NOTE**: the check is a coarse presence check (`multiTurnData != null`), not a `PerTurnBindingDetector`-based collapse check — it rejects even when the data would collapse to a single turn, matching the existing run-creation guard's coarseness.

---

### Requirement: TryItOutResponseDto structure
The response SHALL be an envelope containing the resolved request, the DIAL Core response, timing information, and the OTel trace ID of the invocation. For a multi-turn test case, the envelope carries only the last executed turn's data — the same shape as a single-turn response.

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
  - `durationMs` (Long) — wall-clock time for the DIAL Core invocation in milliseconds
  - `traceId` (String, nullable) — the 32-char hex OTel trace ID; null when tracing disabled

#### Scenario: Response structure (SSE)
- **WHEN** DIAL Core returns an SSE response (Content-Type is `text/event-stream`)
- **THEN** `response.streaming` SHALL be `true`, `response.events` SHALL contain the list of parsed `SseEventDto` objects in order of receipt, `response.body` SHALL be the `{"events": [...]}` envelope (the document JSONata would operate on)

#### Scenario: DIAL Core returns error status
- **WHEN** DIAL Core returns 4xx or 5xx status code
- **THEN** the try-it-out endpoint SHALL still return HTTP 200
- **AND** the `response.statusCode` SHALL contain the actual upstream status code
- **AND** the `response.body` SHALL contain the upstream response body
- **AND** `traceId` SHALL still be populated

#### Scenario: Response body is valid JSON (non-SSE)
- **WHEN** DIAL Core returns a non-SSE response body that is valid JSON
- **THEN** `response.body` SHALL be the parsed JSON value (object, array, string, number, boolean, or null)

#### Scenario: Response body is not JSON (non-SSE)
- **WHEN** DIAL Core returns a non-SSE response body that is not valid JSON (e.g., HTML error page, plain text)
- **THEN** `response.body` SHALL be the raw response string

#### Scenario: Resolved request with multipart body
- **WHEN** the test suite uses a `multipart/form-data` request template
- **THEN** `resolvedRequest.body` SHALL be a `ResolvedMultipartBodyDto` showing the resolved form parts (text values and file blob UUIDs)

#### Scenario: Resolved request with URL-encoded body
- **WHEN** the test suite uses a `application/x-www-form-urlencoded` request template
- **THEN** `resolvedRequest.body` SHALL be a `ResolvedUrlEncodedBodyDto` showing the resolved `List<KeyValueTemplateDto>` entries

---

## ADDED Requirements

### Requirement: `tryWithVariables` remains single-turn
The variables-based try-it-out endpoint (`POST /api/v1/test-suites/{testSuiteId}/try-it-out`) SHALL remain single-turn. It has no bound test case and therefore no `multiTurnData` source.

#### Scenario: Variables-based try-it-out is unaffected by multi-turn support
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/try-it-out` with a `variables` map
- **THEN** the system SHALL resolve and invoke exactly one request, as before
