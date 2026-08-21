# Try It Out — delta for `add-multi-request-try-out`

## ADDED Requirements

### Requirement: Test-case try-out executes the suite's request chain
For a DEPLOYMENT suite with non-empty `additionalRequests`, `POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out` SHALL execute the suite's **whole request chain** — request #0 (the suite's own `endpointRef`/`requestTemplate`/`inputBindings`/`responseColumns`, labelled by the optional suite-level `requestName`) followed by every `additionalRequests` entry in order — strictly sequentially, mirroring the run engine's chain semantics so a try-out predicts what a real run would do:

- One JSONata frame SHALL accumulate monotonically across turns AND requests (later key wins): request `i`'s first turn SHALL resolve against every column extracted by requests `0..i-1`, and each successful turn folds its own extraction into the frame. Cross-request column-name collisions are impossible (suite-wide response-column union uniqueness is enforced at write time).
- Turn count SHALL be decided **per request** from that request's own effective `inputBindings` against the dataset schema (same per-turn-binding decision as real runs) — any subset of the chain may be multi-turn.
- Each request SHALL be invoked with its **own** `endpointRef` HTTP method (the suite-level method applies only to request #0).
- Preconditions SHALL be validated for **every** chain element before the first invocation: each element needs an `endpointRef` with an HTTP method and a request template; a violation returns HTTP 400 with error code `VALIDATION_ERROR` without invoking anything, with a message identifying the offending element by its `additionalRequests[i]` position (same prefix convention as write-time chain validation). `deploymentRef` remains suite-level and is validated once. Precondition validation SHALL happen before test-case planning, preserving today's error precedence: a misconfigured suite combined with a nonexistent `testCaseId` yields 400, not 404.
- Execution SHALL be fail-fast: the first turn whose invocation resolves to a non-2xx status, or whose request body fails JSONata evaluation, stops the remaining turns of its request AND every later request.

Status: **Planned**

#### Scenario: Two-request chain threads real extracted columns
- **WHEN** a suite has one `additionalRequests` entry whose request body references `${{configId}}`, a response column named `configId` is defined on request #0, and the user posts a test-case try-out
- **THEN** the system SHALL invoke request #0, extract `configId` from its real response, and resolve the additional request's body with `configId` bound to that extracted value before invoking it
- **AND** the response SHALL contain a `history` with one entry per executed invocation, in execution order

#### Scenario: Per-request HTTP method
- **WHEN** request #0's `endpointRef.method` is `POST` and the additional request's `endpointRef.method` is `GET`
- **THEN** request #0 SHALL be invoked with `POST` and the additional request with `GET`

#### Scenario: Mixed multi-turn and multi-request
- **WHEN** a two-request chain runs for a multi-turn test case where only request #0's bindings reference a `perTurn=true` schema field
- **THEN** request #0 SHALL execute one invocation per `multiTurnData` entry and the additional request SHALL execute exactly one invocation, with the additional request's frame carrying the columns accumulated across all of request #0's turns
- **AND** `history` SHALL contain the invocations in execution order: request #0 turn 0..N-1, then the additional request

#### Scenario: Fail-fast mid-chain
- **WHEN** request #0 of a three-request chain resolves to a non-2xx DIAL Core status
- **THEN** the system SHALL NOT invoke requests #1 and #2
- **AND** the failing invocation SHALL be the top-level `resolvedRequest`/`response` and the last entry of `history`
- **NOTE**: the transport-exception gap documented for multi-turn try-out applies unchanged to chain entries after the first — a transport-level failure (timeout, connection refused) and `ValidationException`/`TryItOutValidationException` from post-resolution validation propagate uncaught (plain 502/504/400 error responses) instead of becoming a `history` entry.

#### Scenario: Chain element missing endpoint method
- **WHEN** an `additionalRequests` entry has a null `endpointRef` or a null `endpointRef.method`
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` before invoking any request of the chain
- **AND** the error message SHALL identify the offending element by its `additionalRequests[i]` position

#### Scenario: Single-request suite keeps the existing fast path
- **WHEN** a suite has empty/absent `additionalRequests` and the test case plans a single turn
- **THEN** the invocation path and response SHALL be unchanged from today, except for the additive `extractedColumns`/`extractionWarnings` fields (omitted entirely when the suite defines no response columns)
- **AND** no `requestIndex`/`totalRequests`/`requestName`/`turnIndex`/`totalTurns` field SHALL be serialized

#### Scenario: MCP suites are out of chain scope
- **WHEN** a suite has `suiteType = MCP_TOOL`
- **THEN** no chain handling applies — `MCP_TOOL` with non-empty `additionalRequests` is already rejected at write time, so an MCP try-out never sees a chain

---

### Requirement: Variables try-out executes the suite's request chain
For a DEPLOYMENT suite with non-empty `additionalRequests`, `POST /api/v1/test-suites/{testSuiteId}/try-it-out` (variables mode) SHALL execute the whole request chain with **every request single-turn** (there is no bound test case, hence no `multiTurnData`). The user-provided `variables` map SHALL be converted to constant-value bindings once and applied to **every** request's template; chain resolution SHALL use an empty data map (no test case data exists); frame bindings SHALL come from prior requests' real extracted response columns, accumulated exactly as in test-case chain execution. Preconditions SHALL be validated for every chain element before the first invocation, identically to test-case chain execution (see "Test-case try-out executes the suite's request chain"). Chain execution SHALL resolve fail-fast (a request-body JSONata evaluation failure aborts the chain, becoming the failing `history` entry); a single-request suite SHALL keep today's lenient single-resolution behavior unchanged.

- **NOTE**: variables mode keeps its wholesale binding replacement — a chain element's own `inputBindings` are **ignored**; the converted `variables` are the effective bindings for every request. A template variable of any chain element that the user does not supply falls through to its default or produces a `REQUIRED` warning; on the chain path an unresolved `REQUIRED` variable in an element after the first aborts the whole try-out with a bare HTTP 400 (uncaught post-resolution validation — same gap as the transport-exception NOTE), after earlier requests have already fired real calls.

Status: **Planned**

#### Scenario: Variables chain resolves against real prior responses
- **WHEN** a user posts `{ "variables": { "prompt": "Hello" } }` to a suite with one `additionalRequests` entry referencing a response column of request #0
- **THEN** the system SHALL invoke request #0 with `prompt` bound as a constant, extract its response columns, and resolve + invoke the additional request with both `prompt` and the extracted columns bound
- **AND** return `history` with both invocations, stamped with `requestIndex`/`totalRequests`

#### Scenario: Variables chain is fail-fast
- **WHEN** request #0 of a variables-mode chain resolves to a non-2xx status or fails request-body JSONata evaluation
- **THEN** the system SHALL NOT invoke later requests, and the failing invocation SHALL be the top-level result and the last `history` entry

#### Scenario: Chain element precondition failure returns 400 before any invocation
- **WHEN** a user posts variables to a multi-request suite whose `additionalRequests[1]` has a null `endpointRef.method`
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` identifying `additionalRequests[1]`, without invoking any request

#### Scenario: Single-request variables try-out unchanged
- **WHEN** a user posts variables to a suite with empty/absent `additionalRequests`
- **THEN** the system SHALL resolve and invoke exactly one request with the pre-existing lenient resolution behavior, with no `history` and no identity stamps — unchanged except the additive `extractedColumns`/`extractionWarnings` fields (omitted entirely when the suite defines no response columns)

---

### Requirement: Try-out responses expose extracted columns
Every try-out invocation result — each `history` entry and the top level — SHALL expose `extractedColumns` (object; **that invocation's own** reconciled per-column extraction over that request's response-column definitions — not the accumulated frame — with an explicit JSON `null` for a column whose extraction failed) and `extractionWarnings` (list; that invocation's extraction warnings). Both SHALL be serialized only for DEPLOYMENT suite invocations that actually performed extraction, and omitted (`null`, not serialized) everywhere else: when the suite defines **no response columns** (the extractor is not invoked at all — the fields are never serialized as empty `{}`/`[]`), when the invocation failed before or during the HTTP call, and for MCP try-outs.

Explicit JSON nulls in `extractedColumns` SHALL be preserved on the wire. Because the shared response mapper applies NON_NULL inclusion to map **content** as well (a `Map<String, Object>` field would serialize `{"col": null}` as `{}`), the DTO SHALL carry these fields as JSON trees (`JsonNode`) parsed verbatim from the extractor's null-preserving JSON output — never as maps re-serialized by the shared mapper.

Status: **Planned**

#### Scenario: Extracted columns on a successful single-request single-turn try-out
- **WHEN** a single-request, single-turn test-case try-out succeeds and the suite defines response columns
- **THEN** the top-level `extractedColumns` SHALL contain each defined column's extracted value and `extractionWarnings` the extraction warnings — the only response-shape change for such suites

#### Scenario: Failed extraction preserves explicit null
- **WHEN** a response column named `col` has a JSONata expression that fails or matches nothing for an invocation
- **THEN** that invocation's `extractedColumns` SHALL contain the column key with an explicit JSON `null` value (not a missing key) — the serialized response body literally contains `"col":null` — and `extractionWarnings` SHALL describe the failure

#### Scenario: Suite without response columns omits extraction fields
- **WHEN** any try-out runs for a suite that defines no response columns
- **THEN** `extractedColumns` and `extractionWarnings` SHALL be absent (`null`, not serialized) on the top level and on every `history` entry — never serialized as empty `{}`/`[]` — so such suites' responses are byte-identical to today's

---

### Requirement: Streaming try-out extracts the run-equivalent assembled document
For a streaming (SSE) response, the document response-column expressions are evaluated against SHALL be the **same document the run path would produce** — the one `StreamingResponseAccumulator` assembles from the parsed events: an OpenAI-mode stream (first event unnamed with a `choices[]` array) becomes a non-streaming chat-completions document whose `choices[0].message.content` is the concatenation of every chunk's `choices[0].delta.content` (plus merged `custom_content`), while any other stream keeps the `{"events": [...]}` envelope. The response DTO's `body`/`events` remain the display view (always the events envelope plus the verbatim event list) — the two views are deliberately different, and the display contract is unchanged. The stream SHALL be consumed exactly once: try-out parses it, hands the parse result to the accumulator's assembly seam, and never re-reads it.

A streaming response whose parse did not complete — `TIMEOUT` (idle timeout or absolute cap) or `ERROR` (read failure, or accumulated bytes over the response-size limit) — SHALL count as a **failed invocation**, exactly as a non-2xx HTTP status does: no response-column extraction for it, and a request chain stops there (the entry stays last in `history`). The condition SHALL be surfaced on `response.streamingStatus` (the non-`SUCCESS` `ExecutionStatus`) and, when the stream was truncated, `response.truncationWarning`; the events received before the stream was cut off stay visible in `response.events`.

Status: **Planned**

#### Scenario: OpenAI-mode streaming response extracts the assembled reply
- **WHEN** a suite whose response column is `choices[0].message.content` is tried out and DIAL Core answers with an OpenAI-mode SSE stream whose `delta.content` chunks spell `"Hello"`
- **THEN** that column SHALL extract `"Hello"` — the same value a real run of the same suite extracts
- **AND** `response.body` SHALL still be the `{"events": [...]}` envelope and `response.events` the parsed chunk list

#### Scenario: Non-OpenAI-mode streaming response extracts the events envelope
- **WHEN** the stream's events are named (e.g. `event: process_rules`), so the accumulator's auto-detect selects structured-SSE mode
- **THEN** extraction SHALL run against the `{"events": [...]}` envelope — again the document the run path would see

#### Scenario: Streaming request in a chain threads its assembled reply onward
- **WHEN** request #0 of a chain answers with an OpenAI-mode stream and extracts a column from the assembled document
- **THEN** request #1's JSONata frame SHALL carry that extracted value (not `null`), exactly as for a non-streaming request

#### Scenario: Truncated or timed-out stream mid-chain stops the chain without extracting
- **WHEN** a chain request's stream is cut short (size limit exceeded ⇒ `ERROR`, or an idle/absolute timeout ⇒ `TIMEOUT`)
- **THEN** no response-column extraction SHALL run for that invocation (`extractedColumns`/`extractionWarnings` absent) and later requests SHALL NOT be invoked, that invocation being the last `history` entry
- **AND** `response.streamingStatus` SHALL carry `ERROR`/`TIMEOUT`, `response.truncationWarning` the truncation reason when present, and `response.events` the events received before the cut-off

---

## MODIFIED Requirements

### Requirement: Try it out with test case data
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out` to resolve the effective request template using the test case's data and effective bindings, send the resolved request to the DIAL Core deployment referenced by the test suite, and return the deployment's response along with the resolved request details. When the test case is multi-turn, the system SHALL execute every turn of the sequence. When the suite defines `additionalRequests`, the system SHALL execute the whole request chain (see "Test-case try-out executes the suite's request chain"). The response SHALL return the result of the last executed invocation at the top level, plus a per-invocation `history` when more than one invocation was planned (more than one turn and/or more than one request).

Status: **Planned**

#### Scenario: Successful try-it-out with test case
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`
- **AND** the test suite has a valid `deploymentRef`, `requestTemplate`, and `endpointRef`
- **AND** the test case exists and belongs to the test suite
- **THEN** the system SHALL load the suite (for `deploymentRef`/`endpointRef` precondition validation via `JsonbMapper` deserialization), then delegate chain/turn planning and resolution to `ResolvedRequestService`, which handles test-case loading, per-request effective template/bindings determination, per-request turn planning, and resolution within its own `@Transactional(readOnly=true)` scope. Note: on the single-request single-turn collapse path (no `multiTurnData`, or `multiTurnData` present but no per-turn binding) this results in the suite being loaded 3 times and the test case being loaded 2 times — once via `loadSuite`, once via the planning call (suite + test case), and once more via the separate `resolveRequest` call (suite + test case) that `tryWithTestCase` makes when the plan collapses to a single invocation. On the chain/multi-turn path (more than one planned invocation), the suite is loaded only 2 times and the test case only 1 time — via `loadSuite` and the planning call alone. This is an accepted trade-off for clear pre-validation errors.
- **AND** send the resolved request(s) to the DIAL Core deployment (after the transaction completes and the DB connection is released)
- **AND** return HTTP 200 with `TryItOutResponseDto` containing the resolved request, the deployment's response (status code + body), and execution duration in milliseconds

#### Scenario: Test case with template/bindings overrides
- **WHEN** resolving the effective template and bindings for any request of the chain
- **THEN** the system SHALL always use the suite-owned template/bindings for request #0 and each `additionalRequests` element's own template/bindings for chained requests — per-test-case `requestTemplateOverride`/`inputBindingsOverride` no longer exist (they were removed by the dataset refactor), so no override lookup happens
- **NOTE**: this rewrites the stale baseline scenario, which described override fields the `TestCase` model no longer carries; the tooling requires keeping the scenario in the MODIFIED block, so it is corrected in place rather than dropped.

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
- **AND** each turn SHALL be resolved using the **accumulated** frame: the frame carried into the turn (columns extracted by earlier requests of the chain, empty for request #0's turn 0) merged with every earlier turn's own extraction, later keys winning — the same accumulation the run engine applies, so a turn whose extraction fails to re-produce a column does NOT erase the previous turn's value
- **AND** the response SHALL be a `TryItOutResponseDto` containing the last executed turn's `resolvedRequest`/`response`/`durationMs`/`traceId` at the top level, plus a `history` array with one entry per executed invocation (see "Multi-turn response includes per-turn history")

#### Scenario: Frame accumulates instead of being replaced (behavioral fix)
- **WHEN** turn k's extraction produces column `A`, and turn k+1's extraction fails to re-produce `A` (its expression matches nothing on that turn's response)
- **THEN** turn k+2 SHALL resolve with `A` still bound to turn k's value — the frame is the accumulated merge of all earlier extractions (later keys win), matching the run engine
- **NOTE**: this corrects the previous multi-turn try-out behavior, which replaced the frame with each turn's own extraction, silently erasing `A` in this situation while a real run kept it.

#### Scenario: Multi-turn data present but no per-turn binding collapses to a single turn
- **WHEN** the test case has non-null `multiTurnData` with more than one entry, but no effective input binding of a given request references any `perTurn=true` schema field
- **THEN** the system SHALL treat that request as a single turn using only the shared `data` (identical to the `PerTurnBindingDetector` collapse behavior used by real runs)
- **AND** for a single-request suite the response SHALL be identical in shape to a single-turn test case's response (no `history`)

#### Scenario: Turn failure stops the sequence
- **WHEN** executing a multi-turn or multi-request try-out and an invocation resolves to a non-2xx DIAL Core status, or fails request-body JSONata evaluation (`RequestBodyEvaluationException`)
- **THEN** the system SHALL stop executing further turns AND further requests (fail-fast)
- **AND** the failed invocation's resolved request and error response SHALL be returned as the `resolvedRequest`/`response`, and as the last entry of `history`
- **NOTE**: a transport-level failure during an invocation (timeout, connection refused, unreachable deployment) is NOT caught by this mechanism — it propagates uncaught exactly as in the single-turn path, producing the pre-existing plain 502/504 error response.
- **NOTE**: `ValidationException` (null resolved URL) or `TryItOutValidationException` (unresolved REQUIRED template variables) thrown by `validateResolutionResult` for invocations after the first are ALSO not caught by this mechanism — the loop's catch clause only catches `RequestBodyEvaluationException`. These propagate uncaught exactly like transport-level failures.

#### Scenario: MCP suite rejects multi-turn test case
- **WHEN** the test suite has `suiteType = MCP_TOOL` and the test case has non-null `multiTurnData`
- **THEN** the system SHALL return HTTP 409 with error code `INVALID_OPERATION`, without invoking the MCP tool
- **AND** the error message SHALL indicate that MCP suites do not support multi-turn test cases (consistent with the existing rejection of MCP + multi-turn at run creation)
- **NOTE**: the check is a coarse presence check (`multiTurnData != null`), not a `PerTurnBindingDetector`-based collapse check — it rejects even when the data would collapse to a single turn, matching the existing run-creation guard's coarseness.

---

### Requirement: Try it out with variables
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/try-it-out` accepting a `variables` map (`Map<String, Object>`) in the request body. Each entry maps a template variable name to its constant value. For a suite with empty/absent `additionalRequests` (single-request suite), the system SHALL resolve the suite's request template by treating each variable as a constant-value binding, send the single resolved request to the DIAL Core deployment, and return the response — unchanged behavior. For a suite with non-empty `additionalRequests`, the system SHALL execute the whole request chain instead — see "Variables try-out executes the suite's request chain".

Status: **Planned**

#### Scenario: Successful try-it-out with variables
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/try-it-out` with body `{ "variables": { "prompt": "Hello", "model": "gpt-4" } }`
- **AND** the test suite has a valid `deploymentRef`, `requestTemplate`, and `endpointRef`, and empty/absent `additionalRequests`
- **THEN** the system SHALL load the suite, deserialize JSONB fields via `JsonbMapper` (`deploymentRef` → `DeploymentReferenceDto`, `endpointRef` → `EndpointContractDto`, `requestTemplate` → `RequestTemplateDto`). The suite's `inputBindings` are NOT deserialized — they are fully replaced by the user-provided variables.
- **AND** convert the variables map to constant-value `InputBindingDto` entries (each map entry becomes an `InputBindingDto` with `templateVariable` = key and `constantValue` = value)
- **AND** resolve the suite's request template with the lenient single-request resolution (`resolve(template, convertedBindings, emptyMap)`) — the fail-fast chain resolution applies only to multi-request suites
- **AND** send the resolved request to the DIAL Core deployment
- **AND** return HTTP 200 with `TryItOutResponseDto`

#### Scenario: Variables must not be null
- **WHEN** user sends try-it-out request with `variables` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Empty variables map is valid
- **WHEN** user sends try-it-out request with `variables` as an empty map `{}`
- **AND** the template has no `${{...}}` placeholders (fully static)
- **THEN** the system SHALL accept the request and proceed with resolution and invocation

#### Scenario: Variable with null value
- **WHEN** user sends try-it-out request with a variable mapped to null (e.g., `{ "variables": { "myVar": null } }`)
- **THEN** the system SHALL skip that entry when converting to `InputBindingDto` (treat it as if the variable was not provided)
- **AND** the template variable will fall through to its default value (if any) or produce a `REQUIRED` warning if no default exists

#### Scenario: Variable with blank key
- **WHEN** user sends try-it-out request with a blank key in the variables map (e.g., `{ "variables": { "": "value" } }`)
- **THEN** the system SHALL skip that entry when converting to `InputBindingDto` (a blank key cannot match any `${{var}}` placeholder)

#### Scenario: Test suite not found
- **WHEN** user sends try-it-out request with non-existent `testSuiteId`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND`

---

### Requirement: Validation before invocation
The system SHALL validate the test suite configuration before invoking the deployment or MCP tool. The request SHALL be rejected if preconditions are not met. Validation SHALL be type-aware: DEPLOYMENT suites validate HTTP preconditions, MCP_TOOL suites validate MCP preconditions. For a DEPLOYMENT suite with non-empty `additionalRequests`, preconditions SHALL additionally be validated for every chain element before the first invocation — see "Test-case try-out executes the suite's request chain" for the per-element rules and error-message convention.

Status: **Planned**

#### Scenario: Missing deployment reference (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `deploymentRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that deployment reference is required

#### Scenario: Missing request template (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `requestTemplate` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that request template is required

#### Scenario: Missing endpoint reference (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `endpointRef` as null or `endpointRef.method` is null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that endpoint reference with HTTP method is required

#### Scenario: Missing preconditions on a chain element
- **WHEN** a DEPLOYMENT suite's `additionalRequests` entry lacks an `endpointRef`, an `endpointRef.method`, or a request template
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` before any invocation, identifying the element by its `additionalRequests[i]` position

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

#### Scenario: Unresolvable required template variables
- **WHEN** template/argument resolution produces warnings with `REQUIRED` code
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`
- **AND** the error message SHALL list the unresolved variable names
- **AND** the `resolvedRequest` SHALL be included in the error response details field

> **Note:** This scenario applies to both suite types: DEPLOYMENT suites check URL template variables, MCP_TOOL suites check argument template variables.

#### Scenario: JSON request body fails JSONata evaluation (DEPLOYMENT suite only)
- **WHEN** the resolved request produces a warning with `REQUEST_BODY_EVALUATION_ERROR` code (the JSON body's JSONata evaluation failed and the resolved body content was downgraded to `null`)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`, the same way as an unresolvable `REQUIRED` warning
- **AND** the deployment SHALL NOT be invoked

> **Note:** This is distinct from the preview/GET resolved-request endpoint, which keeps its existing graceful degradation (a `null` body content with a warning, no abort) — only the try-it-out invocation paths (test case and variables) abort before calling the live deployment.

---

### Requirement: TryItOutResponseDto structure
The response SHALL be an envelope containing the resolved request, the DIAL Core response, timing information, and the OTel trace ID of the invocation. The top-level fields always reflect the **last executed invocation** — identical in shape to a single-invocation response. For a genuine multi-invocation sequence (more than one turn and/or more than one request planned), the envelope additionally carries a `history` field: an ordered list of one entry per executed invocation in execution order (all turns of request #0, then all turns of request #1, …, including the last), each entry having the same shape as the envelope itself.

Each entry (and the top level) SHALL additionally carry identity stamps and extraction results, all `@JsonInclude(NON_NULL)` (omitted when not stamped):
- `requestIndex` (Integer) and `totalRequests` (Integer) — stamped only when the suite's chain has more than one request; a single-request suite never serializes them
- `requestName` (String) — the invocation's request label (suite-level `requestName` for request #0, the `RequestDefinitionDto.name` for an additional request); stamped only when the chain has more than one request AND the request is labelled. This field is a try-out-only convenience with no run analogue — persisted run rows carry no request name
- `turnIndex` (Integer) and `totalTurns` (Integer) — stamped only when that invocation's request planned more than one turn; single-turn invocations (including the no-per-turn-binding collapse) never serialize them
- `extractedColumns` (object) and `extractionWarnings` (list) — see "Try-out responses expose extracted columns"

The four **numeric** stamps mirror the run engine's row-identity stamping guards exactly; `requestName` has no run counterpart. Net compatibility: an existing single-request **single-turn** try-out response stays byte-identical apart from the additive `extractedColumns`/`extractionWarnings` (and fully byte-identical when the suite defines no response columns). An existing single-request **multi-turn** response newly gains `turnIndex`/`totalTurns` on its `history` entries and top level, plus the extraction fields — additive only. `history` is omitted (`null`, not serialized) when exactly one invocation was planned — a real single-turn test case on a single-request suite, or a multi-turn test case that collapses to a single turn on a single-request suite.

- **NOTE**: the "more than one invocation **planned**" wording corrects the previous spec's "more than one turn actually executed" — the implementation has always entered the loop based on the plan's size and built `history` for every executed invocation, so a plan of N>1 that fails on its first invocation already yields a one-entry `history`. Wording fix only; no behavior change.

`TryItOutCoreResponseDto` SHALL include:
- `statusCode` (int) — HTTP status code from DIAL Core
- `body` (Object, nullable) — parsed JSON response body, or `{"events": [...]}` envelope for SSE responses
- `streaming` (Boolean, nullable) — `true` when response was SSE, `null` (omitted from JSON) for non-SSE responses. Uses `@JsonInclude(NON_NULL)`.
- `events` (List of `SseEventDto`, nullable) — parsed SSE events for frontend debugging, `null` (omitted from JSON) for non-SSE responses. Uses `@JsonInclude(NON_NULL)`.
- `streamingStatus` (`ExecutionStatus`, nullable) — the SSE stream's terminal parse status when it was NOT `SUCCESS` (`TIMEOUT`/`ERROR`); `null` (omitted) for non-SSE responses and for streams that completed normally. Uses `@JsonInclude(NON_NULL)`.
- `truncationWarning` (String, nullable) — why the stream was cut short (currently: the response-size limit was reached); `null` (omitted) otherwise. Uses `@JsonInclude(NON_NULL)`.

`SseEventDto` SHALL be a DTO with:
- `event` (String) — SSE event type name (e.g., `"process_rules"`, `"message"`)
- `data` (Object) — parsed JSON payload if valid JSON, raw string otherwise

Status: **Planned**

#### Scenario: Response structure (non-SSE)
- **WHEN** system returns a try-it-out response for a non-SSE invocation
- **THEN** `TryItOutResponseDto` SHALL include:
  - `resolvedRequest` (`ResolvedRequestDto`) — the resolved URL, query params, headers, and body
  - `response` (`TryItOutCoreResponseDto`) — `statusCode` (int), `body` (Object, nullable — parsed JSON or raw string), `streaming` = `null` (omitted), `events` = `null` (omitted)
  - `durationMs` (Long) — wall-clock time for the DIAL Core invocation in milliseconds
  - `traceId` (String, nullable) — the 32-char hex OTel trace ID; null when tracing disabled

#### Scenario: Response structure (SSE)
- **WHEN** DIAL Core returns an SSE response (Content-Type is `text/event-stream`)
- **THEN** `response.streaming` SHALL be `true`, `response.events` SHALL contain the list of parsed `SseEventDto` objects in order of receipt, `response.body` SHALL be the `{"events": [...]}` envelope (the **display** view of the stream)
- **AND** response-column extraction SHALL NOT use that envelope for an OpenAI-mode stream but the run-equivalent assembled document — see "Streaming try-out extracts the run-equivalent assembled document"

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

#### Scenario: Multi-turn response includes per-turn history
- **WHEN** a try-it-out invocation plans more than one invocation (multi-turn and/or multi-request)
- **THEN** `TryItOutResponseDto.history` SHALL be present, containing one entry per executed invocation, in execution order (request-major, turn-minor)
- **AND** each entry SHALL carry that invocation's own `resolvedRequest`/`response`/`durationMs`/`traceId`/`grafanaTraceUrl`, its identity stamps, and its `extractedColumns`/`extractionWarnings`
- **AND** the last entry in `history` SHALL be identical to the envelope's own top-level fields
- **AND** if the sequence stopped early due to a failing invocation (fail-fast), `history` SHALL contain only the invocations that actually ran, with the last entry being the failing one

#### Scenario: Identity stamps on a two-request chain
- **WHEN** a two-request chain (request #0 unlabelled and single-turn; additional request named `"followup"` and single-turn) executes fully
- **THEN** `history[0]` SHALL carry `requestIndex=0`, `totalRequests=2`, no `requestName`, and no `turnIndex`/`totalTurns`
- **AND** `history[1]` SHALL carry `requestIndex=1`, `totalRequests=2`, `requestName="followup"`, and no `turnIndex`/`totalTurns`
- **AND** the top level SHALL carry the same stamps as `history[1]`

#### Scenario: Single-turn response omits history
- **WHEN** a try-it-out plans exactly one invocation (single-request suite with a real single-turn test case, or a multi-turn case that collapses to a single turn)
- **THEN** `TryItOutResponseDto.history` SHALL be absent (`null`, omitted from JSON) and no identity stamp SHALL be serialized — the response shape is unchanged from today apart from the additive `extractedColumns`/`extractionWarnings`

---

## REMOVED Requirements

### Requirement: `tryWithVariables` remains single-turn
**Reason**: Superseded by "Variables try-out executes the suite's request chain" — the variables endpoint now executes the whole request chain for multi-request suites. Each individual request remains single-turn (there is still no test case, hence no `multiTurnData` source), and a single-request suite's behavior is unchanged.
**Migration**: No client action needed — single-request suites keep the exact previous behavior (plus additive `extractedColumns`/`extractionWarnings`); multi-request suites gain `history` + identity stamps.
