# Eval Execution Engine

## Purpose
This spec defines the in-process evaluation execution engine for current deployment, ROUTE and MCP execution. The engine resolves requests from templates, calls target deployment endpoints via DIAL Core, handles both streaming (SSE) and non-streaming HTTP responses, extracts response columns via JSONata, tracks retry attempts, and writes results to the analytics DB in batches. Supports configurable concurrency, retry policies, rate limiting (via Bucket4j), response size limits, and immediate cancellation.

Status: **Implemented**

## Key Terms
- **EvaluationExecutor**: Interface for execution strategies (`execute(EvaluationContext)`). Currently implemented by `InProcessEvaluationExecutor`.
- **EvaluationContext**: Immutable context carrier for a run — carries runId, suiteId, execution settings, retry policy, the run's shared `ExecutorService`, and the caller's `CallerCredential` (bearer JWT or API key, with kind).
- **EvaluationWorker**: Single test case execution logic — resolves request, calls endpoint, captures response, tracks retries, extracts columns, builds `TestCaseRunResult`.
- **StreamingResponseAccumulator**: Four-mode SSE accumulator — OpenAI chat-completions assembly, OpenAI Responses assembly, Anthropic Messages format assembly, or `{"events":[...]}` envelope for custom SSE formats. Delegates SSE wire format parsing to `SseEventParser`.
- **ResultBatchWriter**: Thread-safe result buffer that flushes to analytics DB at configurable batch size thresholds, with SSE progress reporting.

## Requirements

### Requirement: Evaluation executor interface
The system SHALL define an `EvaluationExecutor` interface with a single `execute(EvaluationContext)` method. The `EvaluationContext` SHALL carry: `runId`, `suiteId`, execution settings (concurrency, timeout, retry, rate limit), the run's worker executor (owned and shut down by the run owner, never by the executor implementation), the suite definition, frozen dataset schema, dataset ID, and a `credential` (`CallerCredential`, carrying either a bearer JWT or an API key — including a DIAL Core per-request key, which is simply an `API_KEY`-kind credential).
Status: **Implemented**

#### Scenario: In-process executor is the default
- **WHEN** the application starts with default configuration
- **THEN** the `InProcessEvaluationExecutor` bean SHALL be the active `EvaluationExecutor` implementation

#### Scenario: Executor receives fully populated context
- **WHEN** `TestSuiteEvaluationJob` dispatches a run
- **THEN** it SHALL construct an `EvaluationContext` from the run's `RunConfigDto` (with system defaults for omitted fields) carrying the run's registered worker executor, and pass it to the executor. The run's executor SHALL be registered before async dispatch so a cancel request can reach it even if the job thread has not started yet.

#### Scenario: EvaluationContext credential is kind-agnostic
- **WHEN** `dial-app-proxy.enabled=true` and a run is dispatched from `EvalExecuteInternalController` with a PRK
- **THEN** the `EvaluationContext` constructed for the run SHALL carry an `API_KEY`-kind `CallerCredential` (the PRK)
- **AND** deployment invocations SHALL use that credential's `headerName()`/`headerValue()` exactly as they already do for a `BEARER`-kind credential — no branching logic is added to `DialCoreDeploymentInvoker`, `EvaluationWorker`, `DeploymentTurnInvoker`, or `McpToolInvoker`

### Requirement: In-process evaluation execution
The `InProcessEvaluationExecutor` SHALL read test inputs from the `test_case_run_inputs` table (populated at snapshot phase) in pages, dispatch execution tasks (one per test case per run index) bounded by the configured concurrency level, collect results, and flush them to analytics DB in batches. When no input rows exist, the executor itself falls back to paginated valid cases from the snapshot-referenced dataset (ignoring `testCaseFilter`); this is a legacy path that `TestSuiteEvaluationJob` does not reach, because the snapshot phase always stores a snapshot and a snapshot without input rows fails with `SNAPSHOT_STATE_INCONSISTENT` before Phase 1. When `dial-app-proxy.enabled=true`, the executor is started from `EvalExecuteInternalController` (triggered by the DIAL Core Application Route, with an `API_KEY`-kind credential) via the existing `TestSuiteEvaluationJob.dispatch(runId, credential, skipDeploymentPhase)` method; otherwise (`dial-app-proxy.enabled=false`, or the flag is on but there is no caller credential or no `DialRouteTriggerClient`), the executor is started by direct dispatch from `TestSuiteRunService.dispatchEvaluation` with the initiating caller's credential (bearer or API key, or none). Both paths call the same `dispatch` method — only the caller and the credential's kind differ.
Status: **Implemented**

#### Scenario: Snapshot path — pages from inputs table
- **WHEN** `test_case_run_inputs` rows exist for the run (snapshot phase committed)
- **THEN** the executor SHALL page through `testCaseRunInputRepository.findByRunId()` instead of `testCaseRepository.findValidByDatasetId()`. The `testCaseRepository` SHALL NOT be called.

#### Scenario: No input rows — reads current dataset cases (legacy, not reached by the job)
- **WHEN** the executor is invoked for a run with no `test_case_run_inputs` rows
- **THEN** the executor SHALL fall back to paging through live test cases from `testCaseRepository.findValidByDatasetId()`, wrapping each `TestCase` as a `TestCaseRunInput` struct for uniform worker interface.

#### Scenario: DIAL App mode — executor started from internal endpoint
- **WHEN** `dial-app-proxy.enabled=true`
- **AND** `EvalExecuteInternalController` receives a valid, already-authenticated trigger for `runId`
- **THEN** `TestSuiteEvaluationJob.dispatch(runId, credential, false)` SHALL be called directly from the controller, with `credential` sourced from `AuthorizationTokenHolder.getCredential()`
- **AND** `DialRouteTriggerClient` (not `TokenPropagationHelper`/`AuthorizationTokenHolder`-based in-process dispatch) SHALL have been the mechanism that caused DIAL Core to call this endpoint

#### Scenario: Direct mode — executor started with caller credential propagation
- **WHEN** `dial-app-proxy.enabled=false`
- **THEN** eval execution SHALL be started via `TestSuiteRunService.dispatchEvaluation`'s existing direct call to `TestSuiteEvaluationJob.dispatch(runId, credential, skipDeploymentPhase)` with the initiating caller's credential

#### Scenario: Sequential execution (default)
- **WHEN** `concurrencyLevel` is 1 (default)
- **THEN** the executor SHALL process test case calls one at a time, in order (page by page, case by case, run index by run index)

#### Scenario: Parallel execution
- **WHEN** `concurrencyLevel` is greater than 1 (e.g., 10)
- **THEN** the executor SHALL process up to `concurrencyLevel` test case calls concurrently using a semaphore-bounded, thread-per-task worker executor supplied by the run owner — virtual threads when `spring.threads.virtual.enabled` is `true` (default), platform daemon threads when it is `false`; the executor SHALL NOT be created by the execution engine itself

#### Scenario: All selected valid test cases are executed
- **WHEN** the executor runs for a suite with N selected valid test cases and `numberOfRuns = M`
- **THEN** the executor SHALL dispatch exactly N * M evaluation tasks (one per case per run index 0..M-1)

#### Scenario: Invalid and filtered-out test cases are skipped
- **WHEN** the dataset contains invalid test cases or cases excluded by the suite's `testCaseFilter`
- **THEN** they SHALL be excluded when capturing run inputs; test cases have no enabled flag

#### Scenario: Test cases read in pages
- **WHEN** the suite has more selected valid test cases than fit in a single page
- **THEN** the executor SHALL read inputs using paginated queries

### Requirement: Single test case evaluation (worker)
The `EvaluationWorker` SHALL accept a `TestCaseRunInput` (carrying frozen test case data and optional overrides) plus an `EvaluationContext` (carrying snapshot fields). It SHALL resolve the request body using snapshot fields from context + test case data + per-case overrides (no DB read), call the target deployment endpoint, capture the response (including streaming), extract response columns, and build a `TestCaseRunResult`. HTTP resolution SHALL be performed by `RequestChainExecutor`/`TurnLoopExecutor` using `RequestResolver` (from `evaluation-runner-core`) — not `ResolvedRequestService` (which remains in the EF backend and carries DB dependencies for the Try-It-Out path). `EvaluationWorker` itself now resides in the shared module, under package `com.epam.aidial.evaluation.runner.job`.
Status: **Implemented**

#### Scenario: Snapshot-based request resolution via RequestResolver
- **WHEN** a test case is dispatched for execution
- **THEN** the worker SHALL resolve the full request using `requestResolver.resolveForRun(effectiveTemplate, effectiveBindings, turnData, accumulatedFrame, snapshotSchema)` where:
  - `effectiveTemplate` = `input.getRequestTemplateOverride()` if non-null, else the request's `RequestExecutionSpec.requestTemplate()` from the `HttpSuite` chain on `context.getSuite()`
  - `effectiveBindings` = `input.getInputBindingsOverride()` if non-null, else the request's `RequestExecutionSpec.inputBindings()`
  - `testCaseData` = deserialized from `input.getTestCaseData()`
  - `deploymentRef` and `endpointRef` are read from the `DeploymentTarget` ref in the `HttpTarget` of `context.getSuite()` and the request's `RequestExecutionSpec.endpointRef()`
  - The worker SHALL NOT call `resolveRequest(suiteId, tcId)` (no DB reads during execution) and SHALL NOT call `ResolvedRequestService.resolve(...)` — that method no longer exists; the equivalent logic lives on the injected `RequestResolver`

#### Scenario: HTTP execution delegates to the runner-owned resolver
- **WHEN** the shared module's `EvaluationWorker` bean is created
- **THEN** its HTTP path SHALL use `RequestChainExecutor` and `TurnLoopExecutor`, with `RequestResolver` (from `runner.service`) injected into the turn executor — not `ResolvedRequestService` (which is an EF backend class)


### Requirement: Multi-turn dispatch and per-turn result emission
The worker that executes one HTTP run input SHALL execute the request chain and return one result per executed request/turn. Readable multi-turn input expands a request only when that request binds a perTurn schema field; otherwise it collapses to shared data. MCP execution returns a singleton. Request/turn identity fields are stamped for multi-request/multi-turn plans; a plain single-turn/single-request path keeps the defaults `turnIndex=0`, `totalTurns=1`, `requestIndex=0`, `totalRequests=1` (the result fields are non-null and the analytics columns are `NOT NULL`).
Status: **Implemented**

#### Scenario: Multi-turn input yields per-turn results
- **WHEN** a run input has readable `multi_turn_data` and the request has an effective perTurn binding
- **THEN** execution runs the turn loop and returns one `TestCaseRunResult` per executed turn

#### Scenario: Single-turn input is unchanged
- **WHEN** a run input has no `multi_turn_data`
- **THEN** the existing single-turn path runs and returns one result per chain request, with turn identity at the defaults `turnIndex=0`, `totalTurns=1` for a single-turn request

### Requirement: One concurrency permit per test-case run
The execution unit SHALL be the whole test-case run: turns of one multi-turn case run sequentially under a single concurrency permit, and progress is counted one unit per test-case run regardless of how many turn rows it writes.
Status: **Implemented**

#### Scenario: Progress counts test-case runs, not turns
- **WHEN** a multi-turn case writes N turn rows
- **THEN** run progress advances by one unit for that case, and the runnable-case count treats the multi-turn case as one unit

### Requirement: Snapshot phase
Before transitioning a run to RUNNING, the system SHALL execute a snapshot phase that freezes the suite configuration and test case data.
Status: **Implemented**

#### Scenario: Snapshot phase runs before RUNNING state
- **WHEN** a run is dispatched
- **THEN** the snapshot phase SHALL complete (committing `suite_snapshot` and `test_case_run_inputs` rows) before `status` is set to RUNNING

#### Scenario: Snapshot phase builds SuiteSnapshotDto
- **WHEN** the snapshot phase executes
- **THEN** `SuiteSnapshotBuilder.build(liveSuite, liveDataset)` SHALL produce a `SuiteSnapshotDto` with `snapshotVersion = "2"`, `suiteType`, and all execution-relevant fields. DEPLOYMENT suites: `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema`. MCP_TOOL suites: `mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema`.

#### Scenario: Snapshot phase inserts test case inputs
- **WHEN** the snapshot phase executes
- **THEN** valid dataset cases selected by testCaseFilter SHALL be read in pages and inserted as rows in `test_case_run_inputs`, preserving `testCaseId`, `testCaseName`, `testCaseData`, `multiTurnData` and position; new snapshot rows carry no case overrides

#### Scenario: Snapshot failure marks run FAILED
- **WHEN** the snapshot phase throws an exception
- **THEN** the run SHALL be marked FAILED with an appropriate error code (`SNAPSHOT_FAILED`, `SNAPSHOT_SERIALIZATION_CONFLICT`, `SNAPSHOT_SUITE_MISSING`, or `SNAPSHOT_DATASET_MISSING`)

#### Scenario: Snapshot serialization conflict retried
- **WHEN** the snapshot phase transaction fails with PostgreSQL `40001` (serialization failure)
- **THEN** the system SHALL retry up to 2 times; each retry deletes prior `test_case_run_inputs` for idempotency; on final failure the run is marked FAILED with `SNAPSHOT_SERIALIZATION_CONFLICT`

#### Scenario: Inconsistent snapshot guard
- **WHEN** run transitions to RUNNING and exactly one of `suite_snapshot` / `test_case_run_inputs` rows is present
- **THEN** the run SHALL be immediately marked FAILED with `SNAPSHOT_STATE_INCONSISTENT`

#### Scenario: Missing stored snapshot — synthesized current snapshot
- **WHEN** a run with no stored snapshot is executed
- **THEN** `TestSuiteEvaluationJob.resolveSnapshot()` SHALL load the live suite and its dataset and call `SuiteSnapshotBuilder.build(liveSuite, liveDataset)` to synthesize a transient snapshot; if the live suite or dataset no longer exists, the run SHALL fail with `UNEXPECTED_ERROR` (the lazy resolution runs inside the job's generic failure handler). This path is legacy: the snapshot phase always stores a snapshot, and `SNAPSHOT_SUITE_MISSING`/`SNAPSHOT_DATASET_MISSING` are produced only by the snapshot phase

#### Scenario: Endpoint invocation (non-streaming)
- **WHEN** the resolved request is sent and the response `Content-Type` is NOT `text/event-stream`
- **THEN** the worker SHALL capture the full response body, HTTP status code, and timing (exec start, exec complete, duration)

#### Scenario: Endpoint invocation (streaming SSE)
- **WHEN** the resolved request is sent and the response `Content-Type` is `text/event-stream`
- **THEN** the worker SHALL accumulate SSE chunks via `StreamingResponseAccumulator`, assemble them into a complete response body (OpenAI chat-completions format), and record the assembled response

#### Scenario: Classified HTTP transport timeout
- **WHEN** the HTTP invocation throws an exception whose cause chain is classified as a timeout by `DeploymentInvocationSupport.isTimeoutException`
- **THEN** the worker SHALL set `executionStatus = TIMEOUT`, record `responseBody` as an `INVOCATION_ERROR` JSON envelope, `responseStatusCode = null`, and the elapsed time as `execDurationMs`
- **AND** `requestTimeoutMs` SHALL NOT imply an independently enforced end-to-end invocation deadline; checked SSE parser timeouts follow the streaming-duration contract

#### Scenario: Network error
- **WHEN** the endpoint call fails with a network-level error (connection refused, DNS failure, etc.)
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the error message in `responseBody` as a JSON error envelope, and set `responseStatusCode = null`

#### Scenario: HTTP error from target (4xx/5xx)
- **WHEN** the endpoint returns an HTTP 4xx or 5xx status
- **THEN** the worker SHALL set `executionStatus = FAILED` except 401/403, which map to ERROR, store the response body and status code as-is, and proceed (not retry unless retry is configured)

#### Scenario: Response column extraction
- **WHEN** a response body is captured (streaming or non-streaming)
- **THEN** the worker SHALL apply the suite's `responseColumns` definitions via `ResponseColumnExtractor`, storing extracted values in `extractedColumns` and any extraction failures in `extractionWarnings`

#### Scenario: Request body stored in results
- **WHEN** the worker builds a `TestCaseRunResult`
- **THEN** the worker SHALL serialize and store the resolved request body in `requestBody` (JSONB). On the HTTP path the body SHALL be serialized as JSON, falling back to its `toString()` form on a serialization failure (never failing the row); the MCP path serializes the resolved arguments via `EvaluationWorker.serializeBody()`. `requestBody` SHALL be null when the resolved body is absent.

#### Scenario: Retry count tracked in results
- **WHEN** the worker completes execution of a test case (with or without retries)
- **THEN** the result SHALL include `retryCount` set to the number of retry attempts made (0 if no retries occurred, N if N retries were attempted before the final outcome)

#### Scenario: Log details populated on retries
- **WHEN** the worker completes execution with `retryCount > 0`
- **THEN** the result SHALL include `logDetails` containing a structured log of retry attempts: `{"retryAttempts": [{"attemptIndex": 1, "statusCode": <int|null>, "errorType": "<HTTP_ERROR|TIMEOUT|NETWORK_ERROR>", "durationMs": <long>}, ...]}`. Each entry represents one failed attempt before the final result.

#### Scenario: Log details null when no retries
- **WHEN** the worker completes execution with `retryCount = 0`
- **THEN** `logDetails` SHALL be null (not an empty object or empty array)

### Requirement: Test case execution produces a traceable result
Each test case execution SHALL create an OpenTelemetry child span. The span's trace ID (32 lowercase hex characters) SHALL be stored as `TestCaseRunResult.traceId` and used as the correlation handle for the DIAL Core invocation. The span SHALL cover the entire execution including retries.
Status: **Implemented**

#### Scenario: traceId stored in result is OTel trace ID
- **WHEN** a test case executes successfully or fails
- **THEN** `TestCaseRunResult.traceId` SHALL be the 32-char lowercase hex OTel trace ID from the test case execution span (previously was a UUID with dashes)

#### Scenario: traceId stable across retries
- **WHEN** a test case execution is retried
- **THEN** `TestCaseRunResult.traceId` SHALL remain the same (the span wraps all retry attempts)

#### Scenario: Execution span attributes
- **WHEN** the test case execution span is created
- **THEN** it SHALL have span name `eval.testcase.execute`
- **AND** it SHALL have tags for `testcase.id` (UUID string) and `run.index` (int)

### Requirement: Streaming response accumulation
`StreamingResponseAccumulator` SHALL parse SSE event streams and assembles complete response bodies. It delegates SSE wire format parsing to `SseEventParser` and operates in four modes, evaluated in this order:

1. **OpenAI chat-completions mode** — auto-detected when the first event has no named `event:` type (type is `"message"`) AND its data contains a `choices[]` array. Extracts `choices[0].delta.content` from each chunk, concatenates, and assembles a complete non-streaming chat-completions response.
2. **OpenAI Responses mode** — auto-detected when the first event's data is a JSON object whose `type` is a string starting with `"response."`. Assembly is delegated to `ResponsesApiAccumulator` and yields exactly the document a non-streaming Responses call returns (see "Responses API streaming assembly" below).
3. **Anthropic mode** — auto-detected when the first event's named `event:` type is `message_start` AND its data contains a `message` field. Reassembles `content_block_start`/`content_block_delta`/`message_delta` events into a complete Anthropic Messages response: a `content` array (text and tool_use blocks merged from deltas, other block types passed through unchanged) plus the base message's `stop_reason`/`stop_sequence`/`usage` merged from `message_delta`.
4. **Structured SSE mode** — for all other streams. Wraps parsed events in a `{"events": [{"event": "<type>", "data": <payload>}, ...]}` envelope. Named events force this mode regardless of data payload structure, **except** when they match Anthropic mode's detection, and **except** for Responses events, which are detected from the payload and never fall through to this mode.

Status: **Implemented**

#### Scenario: OpenAI chat-completions SSE format
- **WHEN** response stream contains events with no named `event:` type and data in format `{"choices":[{"delta":{"content":"..."}}]}`
- **THEN** the accumulator SHALL extract content deltas from each chunk, concatenate them, and assemble a complete response in non-streaming chat-completions format (with `message.content` instead of `delta.content`)

#### Scenario: Stream termination
- **WHEN** the stream contains `data: [DONE]`
- **THEN** the accumulator SHALL finalize the assembled response

#### Scenario: Stream error mid-accumulation
- **WHEN** the SSE stream is interrupted (connection drop, timeout) after receiving partial data
- **THEN** the accumulator SHALL preserve the parser status (`TIMEOUT` for exceeded checked deadline, `ERROR` for read failure) and store partial text as a JSON string for chat/Responses/Anthropic modes, or partial events envelope for structured mode

#### Scenario: Non-OpenAI streaming format fallback
- **WHEN** the response has `Content-Type: text/event-stream` but events do NOT follow OpenAI format (either named `event:` types are present, or data lacks `choices[].delta.content` structure), AND the stream does NOT match Responses mode's detection (first event's data being an object whose `type` starts with `"response."`) or Anthropic mode's detection (first event named `message_start` carrying a `message` object)
- **THEN** the accumulator SHALL produce `responseBody` as a JSON object with `events` array: `{"events": [{"event": "<type>", "data": <payload>}, ...]}`. Each event's `event` field SHALL contain the SSE event type name (defaulting to `"message"` when absent). Each event's `data` field SHALL contain parsed JSON if the data payload is valid JSON, or a raw string if not.

#### Scenario: OpenAI mode detection with named events
- **WHEN** SSE stream has named `event:` types (e.g., `event: process_rules`) whose data is not a Responses event and that do not match Anthropic mode's `message_start`-with-`message` detection
- **THEN** accumulator SHALL use structured SSE mode regardless of data payload structure — named events are never treated as OpenAI chat-completions format

#### Scenario: JSON array fallback enables JSONata extraction
- **WHEN** non-OpenAI SSE response is stored as `{"events": [...]}` envelope
- **THEN** JSONata `responseColumns` expressions SHALL be able to filter by event type (e.g., `events[event="process_rules"].data.evaluated_rule.status`), access last event (e.g., `events[-1].data`), or iterate all events (e.g., `events.data.result`). `DashjoinJsonataEvaluationService` MUST accept generic JSON input (supporting both JSON objects AND arrays at top level).

#### Scenario: Empty SSE stream produces empty envelope
- **WHEN** SSE stream has `Content-Type: text/event-stream` but contains no events (immediate EOF or only comments/empty lines)
- **THEN** accumulator SHALL produce `responseBody` as `{"events": []}` (empty envelope) with `executionStatus = SUCCESS`

#### Scenario: All events have non-JSON data in structured mode
- **WHEN** non-OpenAI SSE stream contains events where all data payloads are plain text (not valid JSON)
- **THEN** accumulator SHALL produce `{"events": [{"event": "<type>", "data": "<raw string>"}, ...]}` — each event's `data` is a JSON string value, not a parsed object

### Requirement: Responses API streaming assembly

When the accumulator is in Responses mode, `ResponsesApiAccumulator` SHALL assemble the stream into the **same document a non-streaming Responses call would have returned**, so a suite's response columns (e.g. `output[0].content[0].text`, `usage.total_tokens`) resolve identically whether the deployment streamed or not.

The terminal event — the last event whose `type` is `response.completed`, `response.incomplete` or `response.failed` — carries the full response object under `response`; that object SHALL be used **verbatim**, so vendor extensions DIAL adds to it survive untouched. `response.created` also carries a `response` object, but an empty one, and SHALL NOT be treated as terminal.

`response.output_text.delta` events SHALL be concatenated (across every output/content index, in receipt order) and used **only** as a fallback when no terminal event arrived. There is no separate `custom_content` merge in this mode — the Responses format carries no `choices[].delta.custom_content`, and whatever the terminal object holds is preserved by the verbatim copy.

Status: **Implemented**

#### Scenario: Terminal event assembled verbatim
- **WHEN** the stream ends with `event: response.completed` whose data is `{"type":"response.completed","response":{…}}`
- **THEN** `responseBody` SHALL be that `response` object serialized as-is — no `{"events":[…]}` envelope, no re-derivation from deltas — with `executionStatus = SUCCESS`

#### Scenario: Detection reads the payload, not the event name
- **WHEN** a Responses stream is forwarded with no `event:` lines, so every event's type defaults to `"message"`, but the first event's data is a JSON object whose `type` starts with `"response."`
- **THEN** the accumulator SHALL still use Responses mode

#### Scenario: Stream cut before the terminal event
- **WHEN** a Responses stream ends (EOF, `[DONE]`, or a dropped connection) after `response.created` and some `response.output_text.delta` events but with no terminal event
- **THEN** the accumulator SHALL synthesize `{"status":"incomplete","output":[{"type":"message","role":"assistant","status":"incomplete","content":[{"type":"output_text","text":"<concatenated deltas>"}]}]}` — the empty `response` object from `response.created` SHALL NOT be used

#### Scenario: Non-Responses named events are unaffected
- **WHEN** the first event is a named non-Responses, non-Anthropic event (e.g. `event: process_rules`)
- **THEN** the accumulator SHALL use structured SSE mode as before

#### Scenario: Anthropic Messages SSE format detected
- **WHEN** the response stream's first event has a named `event:` type of `message_start` and its data contains a `message` object
- **THEN** the accumulator SHALL use Anthropic mode

#### Scenario: Anthropic mode text block assembly
- **WHEN** an Anthropic-mode stream contains a `content_block_start` event with `content_block.type = "text"` at some index, followed by one or more `content_block_delta` events with `delta.type = "text_delta"` at that index
- **THEN** the accumulator SHALL concatenate the `delta.text` values in arrival order and produce a final `content` entry at that index: `{"type":"text","text": "<concatenated text>"}`

#### Scenario: Anthropic mode tool_use block assembly
- **WHEN** an Anthropic-mode stream contains a `content_block_start` event with `content_block.type = "tool_use"` at some index, followed by one or more `content_block_delta` events with `delta.type = "input_json_delta"` at that index
- **THEN** the accumulator SHALL concatenate the `delta.partial_json` fragments in arrival order, parse the concatenated result as JSON, and produce a final `content` entry at that index: `{"type":"tool_use","id":<id>,"name":<name>,"input": <parsed JSON>}`. If the concatenated fragments do not parse as valid JSON, `input` SHALL instead be the raw concatenated string.

#### Scenario: Anthropic mode multiple content blocks assembled in order
- **WHEN** an Anthropic-mode stream contains `content_block_start`/`content_block_delta` events for more than one content block index
- **THEN** the accumulator SHALL produce the final `content` array with entries ordered by ascending block index

#### Scenario: Anthropic mode message_delta merged
- **WHEN** an Anthropic-mode stream contains a `message_delta` event with a `delta` object (e.g., `stop_reason`, `stop_sequence`) and/or a `usage` object
- **THEN** the accumulator SHALL merge `delta.stop_reason`/`delta.stop_sequence` into the base message object (taken from the `message_start` event's `message`) and merge `usage` into that base message's `usage` object

#### Scenario: Anthropic mode ignores lifecycle-only events
- **WHEN** an Anthropic-mode stream contains `content_block_stop`, `message_stop`, or `ping` events
- **THEN** the accumulator SHALL treat them as no-ops for content reconstruction

#### Scenario: Anthropic mode non-text/tool_use block passthrough
- **WHEN** a `content_block_start` event carries a `content_block.type` other than `text` or `tool_use` (e.g., `thinking`)
- **THEN** the accumulator SHALL pass that block through unchanged, exactly as captured from `content_block_start`, into the final `content` array at its index — without reconstructing it from subsequent delta events

### Requirement: Response size limiting
The system SHALL enforce a configurable maximum response body size. Responses exceeding the limit SHALL be truncated into JSONB-safe content and marked ERROR; dedicated truncation warnings are not propagated by the HTTP/MCP result path. Because `response_body` is a JSONB column, truncation MUST produce valid JSON — raw byte-level truncation is NOT allowed.
Status: **Implemented**

#### Scenario: Response within limit
- **WHEN** the response body is within `max-response-size-bytes` (default 50MB)
- **THEN** the full response body SHALL be stored

#### Scenario: Response exceeds limit (non-streaming)
- **WHEN** the non-streaming response body exceeds `max-response-size-bytes`
- **THEN** the worker SHALL store the response body as a JSON string containing the serialized response text truncated at the byte limit (e.g., `"<truncated text>"`). This guarantees JSONB validity since a JSON string is always valid JSONB. No dedicated truncation warning SHALL be appended by the HTTP invocation path; ordinary extraction warnings depend on the JSON-string document. The `executionStatus` SHALL be set to `ERROR` (truncated response is incomplete data). Note: JSONata extraction on truncated content may produce partial/incorrect results — the ERROR status identifies incomplete content.

#### Scenario: Streaming response exceeds limit
- **WHEN** accumulated SSE event data bytes exceed `max-response-size-bytes` during streaming (size tracked by `SseEventParser`)
- **THEN** the accumulator SHALL stop accumulating and set `executionStatus = ERROR`. For OpenAI chat-completions mode: store the accumulated content as a truncated JSON string. For Responses mode: store the text concatenated from `response.output_text.delta` events as a truncated JSON string. For Anthropic mode: store the concatenation of all `text`-block accumulated text (in ascending block-index order) as a truncated JSON string. For structured SSE mode: store the `{"events": [...]}` envelope with events accumulated before the limit was hit. The accumulator SHALL expose its truncation warning, but the HTTP TurnOutcome/result path SHALL not append it as a dedicated extraction warning.

### Requirement: Retry policy execution
When a `RetryPolicyDto` is configured with `maxRetries > 0`, the worker SHALL retry failed calls according to the policy.
Status: **Implemented**

#### Scenario: No retry configured (default)
- **WHEN** `retry` is null or `maxRetries = 0`
- **THEN** the worker SHALL NOT retry failed calls — a single failure is recorded immediately

#### Scenario: Retry on retryable failure
- **WHEN** a call fails with a retryable condition (timeout, network error, HTTP 429, HTTP 5xx) and `maxRetries > 0`
- **THEN** the worker SHALL retry up to `maxRetries` times with exponential backoff: delay = `min(retryDelayMs * retryBackoffMultiplier^(attemptIndex - 1), maxRetryDelayMs)` where `maxRetryDelayMs` is the system-configured cap on computed retry delay (default 60000ms from `test-suite-run.retry.max-retry-delay-ms`). This cap prevents exponential growth from producing unreasonable delays (e.g., multiplier=3, 10 retries → uncapped attempt 10 would be 5.5 hours).

#### Scenario: Non-retryable failure
- **WHEN** a call fails with HTTP 4xx (except 429 and 401/403)
- **THEN** the worker SHALL NOT retry — the result is recorded immediately with `executionStatus = FAILED`

#### Scenario: Authentication failure (401/403)
- **WHEN** a call fails with HTTP 401 or 403
- **THEN** the worker SHALL set `executionStatus = ERROR` (not FAILED — this is an infrastructure/auth issue, not a target endpoint error), store the response body and status code, and NOT retry (the JWT is likely expired and retries won't help). The run continues executing remaining cases per the continue-on-failure policy.

#### Scenario: All retries exhausted
- **WHEN** all retry attempts fail
- **THEN** the worker SHALL record the result from the last attempt (preserving the final HTTP status, response body, and execution timing from the last attempt)

#### Scenario: Retry respects cancellation
- **WHEN** the run is cancelled while a retry is pending (backoff sleep)
- **THEN** the worker thread SHALL be interrupted, the backoff sleep SHALL end immediately, the worker SHALL NOT issue another attempt, and the interrupted case SHALL NOT produce a result row (see "Immediate cancellation")

### Requirement: Rate limiting
The runner SHALL pace initial test-case-run submissions with a run-wide Bucket4j bucket when `rateLimitRps` is positive. It SHALL consume one token per test-case run, not per request, turn or retry. The bucket SHALL have capacity and greedy one-second refill of `max(1, round(rateLimitRps))` tokens; an initial burst up to capacity is possible.
Status: **Implemented**

#### Scenario: No rate limit
- **WHEN** rateLimitRps is null or non-positive
- **THEN** submissions SHALL not consume bucket tokens

#### Scenario: Configured rate paces submissions
- **WHEN** rateLimitRps is 2.4
- **THEN** the shared bucket SHALL have capacity 2 and refill 2 tokens per second
- **AND** each test-case-run submission SHALL consume one token

#### Scenario: Turns and retries share the submitted task
- **WHEN** one submitted case performs several turns, requests or retries
- **THEN** those calls SHALL not acquire additional bucket tokens

### Requirement: Batch result writing
The Postgres writer SHALL buffer rows per test-case run and flush them in an analytics transaction when the row threshold is reached. Normal execution SHALL await workers and then attempt a final flush. Dispatch-loop failure SHALL attempt a catch-path flush and rethrow, followed by another finally-path flush attempt. This code does not guarantee one flush attempt on every path or durable recovery of failed batches.
Status: **Implemented**

#### Scenario: Threshold flush
- **WHEN** adding a case's result rows brings the buffer to at least result-batch-size (default 100)
- **THEN** the writer SHALL detach and clear the buffer under its lock and save the detached rows in an analytics transaction

#### Scenario: Normal completion and cancellation
- **WHEN** submitted workers finish normally or after interruption
- **THEN** the executor SHALL attempt to flush remaining buffered rows in finally

#### Scenario: Worker-side batch failure
- **WHEN** addResults throws during a threshold flush
- **THEN** TestCaseRunner SHALL handle it as an unexpected per-case failure and attempt one synthetic ERROR row
- **AND** detached failed rows SHALL not be restored automatically

#### Scenario: Final flush failure
- **WHEN** the finally-path flush throws
- **THEN** the executor SHALL log the exception without rethrowing it
- **AND** this failure alone SHALL not guarantee a FAILED run

### Requirement: Immediate cancellation
When a run is cancelled, the run's worker executor SHALL be shut down immediately: no new test case tasks are accepted, every in-flight worker task is interrupted, and the executor SHALL wait for the interrupted tasks to terminate before its finally-path flush attempt. There is no grace period and no configurable drain window. Cancellation SHALL be deliverable before the async job thread has started (the run's executor is registered before dispatch — see test-suite-runs "Evaluation job orchestration"). The executor SHALL NOT synthesize result rows for cases interrupted by cancellation — see "Diagnostic logging for unfinished cases on cancel". A user cancellation SHALL interrupt worker tasks, not the orchestrating job thread. Application-context shutdown can interrupt the job thread separately; the job clears that stray interrupt before its terminal status write. Flush attempts retain the failure behavior documented under Batch result writing.
Status: **Implemented**

#### Scenario: Cancellation stops new dispatches
- **WHEN** the run's executor has been shut down
- **THEN** any attempt to submit a further test case task SHALL be rejected and the dispatch loop SHALL end; no further pages of inputs are fetched

#### Scenario: In-flight calls are interrupted immediately
- **WHEN** cancellation is requested while HTTP or MCP calls are in flight
- **THEN** the run executor SHALL interrupt its worker threads and the orchestrator SHALL await their termination before its normal finally-path flush attempt
- **AND** semaphore waits can proceed when workers release their permits, but the separate Bucket4j pacing wait resumes through timed token refill, not semaphore release; this can delay the next rejected submission because user cancellation does not interrupt the dispatching thread

#### Scenario: Long-running uncancelled run does NOT time out
- **WHEN** a run executes for a long time and is never cancelled
- **THEN** the executor SHALL wait for all dispatched futures without any overall timeout. This does not imply an end-to-end per-call deadline; HTTP transport settings and SSE checked durations apply separately.

#### Scenario: Worker wrapper suppresses results when interruption is observed
- **WHEN** the worker wrapper observes its interrupt flag after `EvaluationWorker.execute` returns and before `resultsWriter.addResults`, or handles an exception classified as interrupted
- **THEN** it SHALL omit the returned result rows and SHALL NOT synthesize an ERROR row for that interruption
- **AND** interruption arriving after the pre-write check does not retroactively remove rows already buffered or persisted; already persisted results SHALL survive cancellation

#### Scenario: Partial results preserved
- **WHEN** a run is cancelled at any point
- **THEN** all results that completed and were written to analytics DB before cancellation SHALL be preserved (not deleted), and results of workers that completed before the interrupt but were still buffered SHALL be included in the final flush attempt; persistence remains subject to batch-write failures

#### Scenario: Run registration cleanup
- **WHEN** the async run task completes (success, failure, or cancellation)
- **THEN** the run's executor SHALL be shut down (if not already) and its registration removed in a `finally` block

### Requirement: Synthetic ERROR result for worker exception
When a test case worker throws an unexpected exception (an exception that escapes the worker's own internal handling and reaches the executor's per-task `catch (Exception e)`), the executor SHALL emit a synthetic `TestCaseRunResult` with `executionStatus = ERROR` to the result buffer in best-effort fashion. This makes per-case worker bugs visible in the analytics surface — without it, a buggy worker silently drops cases. The synthesis is best-effort: if appending the synthetic row to the buffer itself fails, the executor SHALL log the secondary failure and continue without further fallback. The executor SHALL NOT chain additional retry / fallback layers beyond the single best-effort attempt — the JVM may be in any state, and the next safe action is to keep going so other cases can still finish.
Status: **Implemented**

#### Scenario: Worker throws unexpected exception
- **WHEN** the runnable inside `TestCaseRunner` catches an exception escaping `evaluationWorker.execute(...)`
- **THEN** the executor SHALL build a synthetic `TestCaseRunResult` via `TestCaseRunResultFactory.errorResult(...)` with `executionStatus = ERROR`, `responseBody` set to a JSON error envelope `{"error":{"type":"<exception class name>","message":"<exception message>","origin":"executor"}}`, `responseStatusCode = null`, `execStartedAtMs = execCompletedAtMs = clock.millis()`, `execDurationMs = 0`, `retryCount = 0`, `logDetails = null`
- **AND** the executor SHALL pass that result to `resultsWriter.addResults(List.of(synthetic))` so it is persisted alongside other results

#### Scenario: Per-case error does not fail the run
- **WHEN** one or more synthetic `ERROR` rows are produced for a run because workers threw unexpected exceptions
- **THEN** the run SHALL still reach `COMPLETED` status (per-case errors do NOT mark the whole run FAILED)

#### Scenario: TestCaseRunResultFactory must not throw
- **WHEN** the executor invokes `TestCaseRunResultFactory.errorResult(...)`
- **THEN** the factory SHALL build the synthetic row from the input, the run index, the caught exception, and the current clock millis via fixed-shape construction — no template resolution, no JSON parsing of test case data, no DB access — guaranteeing it cannot throw and double-drop the case

#### Scenario: Buffer append failure is logged, not retried
- **WHEN** the inner `resultsWriter.addResults(List.of(synthetic))` call itself throws (e.g., the buffer's downstream batch flush triggered by reaching `result-batch-size` threshold fails)
- **THEN** the executor SHALL log the secondary failure at `ERROR` level (with the exception as last SLF4J argument) and continue. It SHALL NOT raise, SHALL NOT retry, and SHALL NOT make a second synthesis attempt.

#### Scenario: Broad catch is intentional
- **WHEN** static analysis or code review questions the broad `catch (Exception e)` in the worker runnable
- **THEN** the catch IS intentional and documented as the deliberate exception to AGENTS.md's "catch specific exceptions" rule. The contract of this catch is "any unexpected runtime failure escaping the worker" — narrowing it would re-introduce the silent-drop bug that this requirement exists to prevent. The catch SHALL remain `catch (Exception e)`.

### Requirement: Diagnostic logging for unfinished cases on cancel
After all submitted futures terminate, `TestCaseRunner.awaitCompletion` SHALL emit one WARN log line when its `interruptedTasks` counter is positive. The counter SHALL count worker-wrapper observations of interruption, not incomplete futures or every case affected by cancellation. It SHALL exclude rejected/not-submitted tasks and interruption arriving after the wrapper's pre-write check. The wrapper SHALL NOT synthesize rows for observations classified as interrupted.
Status: **Implemented**

#### Scenario: Observed interruption count is logged after completion
- **WHEN** the wait on all submitted futures returns and `interruptedTasks` is positive
- **THEN** the runner SHALL emit one WARN line: `"Run {runId} cancelled with {interruptedTasks} test case(s) interrupted before completion"`
- **AND** a zero counter SHALL produce no such line

#### Scenario: No synthetic cancellation rows
- **WHEN** the worker wrapper classifies a returned result or exception as interrupted
- **THEN** it SHALL increment `interruptedTasks` and omit result/synthetic row writing for that observation
- **AND** the runner SHALL NOT iterate futures to synthesize cancellation rows

#### Scenario: Rejected submissions are not counted as interrupted workers
- **WHEN** cancellation prevents a case from being submitted or the executor rejects its submission
- **THEN** that case SHALL not increment the worker interruption counter
- **AND** the WARN counter SHALL not be treated as a complete missing-result count; row counts also vary with turns/requests and failed batch writes

### Requirement: Catastrophic executor failures are rethrown
An Exception escaping the dispatch/wait loop SHALL trigger a best-effort flush and be rethrown (runtime exceptions unchanged, checked exceptions wrapped), followed by the finally flush attempt. Errors are not caught by this Exception handler.
Status: **Implemented**

#### Scenario: Dispatch failure reaches the job
- **WHEN** page fetching or dispatch/wait throws RuntimeException
- **THEN** the executor SHALL log, attempt a catch-path flush and rethrow that exception
- **AND** the job SHALL mark an uncancelled run FAILED with INTERNAL/UNEXPECTED_ERROR

#### Scenario: Cancellation takes precedence
- **WHEN** an ordinary dispatch failure reaches the job after cancellation
- **THEN** the job SHALL attempt CANCELLED instead of FAILED
- **AND** an AnalyticsWriteException escaping a phase SHALL take the separate FAILED/ANALYTICS_WRITE_FAILED handler

### Requirement: Progress reporting
The executor SHALL report execution progress at batch-flush boundaries via the existing SSE infrastructure.
Status: **Implemented**

#### Scenario: Progress event on batch flush
- **WHEN** a batch of results is flushed to analytics DB
- **THEN** the executor SHALL emit a progress notification via `TestSuiteRunSseService` containing: `completedCases` (number of nonempty test-case-run additions to the writer so far, not row count or a durability guarantee), `totalCases` (total expected: numberOfTestCases * numberOfRuns)

#### Scenario: No progress events for empty batches
- **WHEN** a batch flush contains zero results (e.g., all calls pending)
- **THEN** no progress event SHALL be emitted

### Requirement: Caller credential propagation to workers
The executor SHALL propagate the initiating caller's credential (bearer JWT or API key, with kind) to all worker threads for DIAL Core deployment calls.
Status: **Implemented**

#### Scenario: Credential available in workers
- **WHEN** workers make HTTP calls to DIAL Core deployments
- **THEN** the caller's credential SHALL be available from EvaluationContext.getCredential() for MCP and via the propagated AuthorizationTokenHolder for HTTP

#### Scenario: Credential captured before async dispatch
- **WHEN** the run is dispatched to the `@Async` executor
- **THEN** the credential SHALL be captured in the calling thread (before `CompletableFuture.supplyAsync`) and propagated to the async thread and all worker threads spawned from it

### Requirement: Execution configuration system defaults and validation
The system SHALL define default and maximum values for all execution settings via `application.yml` properties. Per-run values in `RunConfigDto` SHALL be validated against these system maximums.
Status: **Implemented**

#### Scenario: System defaults applied when execution settings omitted
- **WHEN** `RunConfigDto.execution` is null
- **THEN** the executor SHALL use system defaults: `concurrencyLevel = 1`, `requestTimeoutMs = 600000`, `rateLimitRps = null`

#### Scenario: System defaults applied when retry omitted
- **WHEN** `RunConfigDto.retry` is null
- **THEN** the executor SHALL use system defaults: `maxRetries = 0` (no retry)

#### Scenario: Per-run value within system maximum
- **WHEN** `concurrencyLevel = 20` and system max is 50
- **THEN** the value SHALL be accepted

#### Scenario: Per-run value exceeds system maximum
- **WHEN** `concurrencyLevel = 100` and system max is 50
- **THEN** the service SHALL reject the run creation with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Configurable system properties
- **WHEN** the application starts
- **THEN** it SHALL read execution configuration from `test-suite-run.execution.*` and `test-suite-run.retry.*` property namespaces

### Requirement: URL and method resolution from endpoint contract
The worker SHALL construct the full request URL and HTTP method from the suite's HTTP target (the `deploymentRef` of a DEPLOYMENT suite, or the DIAL Core root for a ROUTE suite) and `endpointRef`, using `DialCoreUrlBuilder` for URL construction.
Status: **Implemented**

#### Scenario: Standard OpenAI endpoint
- **WHEN** `endpointRef.relativeUrlPattern` is `/chat/completions` and `deploymentRef.id` is `gpt-4`
- **THEN** the worker SHALL construct URL as `{dialCoreBaseUrl}/openai/deployments/gpt-4/chat/completions`

#### Scenario: Custom endpoint
- **WHEN** `endpointRef.relativeUrlPattern` is `/custom/predict`
- **THEN** the worker SHALL construct URL as `{dialCoreBaseUrl}/v1/deployments/{id}/route/custom/predict`

#### Scenario: HTTP method from endpointRef
- **WHEN** `endpointRef.method` is `POST`
- **THEN** the worker SHALL use POST as the HTTP method for the deployment call

#### Scenario: Anthropic Messages endpoint
- **WHEN** `endpointRef.relativeUrlPattern` is `/anthropic/v1/messages`
- **THEN** the worker SHALL construct URL as `{dialCoreBaseUrl}/anthropic/v1/messages` (passed through unchanged — it is DIAL Core's real, deployment-less route), with the deployment ID NOT included anywhere in the URL. The suite author is responsible for including a `model` field identifying the deployment inside the JSON request body — DIAL Core resolves the deployment from that field, not from the URL. This mapping applies regardless of the value of `deploymentRef.id`.

#### Scenario: Global route path is invoked from the DIAL Core root
- **WHEN** a run executes a ROUTE suite whose resolved `urlTemplate` is `/validate/items/42`
- **THEN** the worker SHALL construct URL as `{dialCoreBaseUrl}/validate/items/42`
- **AND** no `/openai/deployments/...` or `/v1/deployments/.../route` segment, and no deployment ID, SHALL appear anywhere in the URL

### Requirement: Request headers and query parameters
The worker SHALL include resolved headers and query parameters from the request template in the deployment call.
Status: **Implemented**

#### Scenario: Template headers included
- **WHEN** the request template defines headers (e.g., `Content-Type: application/json`)
- **THEN** the worker SHALL include all resolved headers in the HTTP request, **except** headers on the system blacklist (see header blacklist requirement)

#### Scenario: Blacklisted headers filtered at call time
- **WHEN** the resolved request template includes headers that are on the system header blacklist (e.g., `Authorization`, `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `traceparent`, `tracestate`)
- **THEN** the worker SHALL silently skip those headers and log a warning. The blacklist comparison SHALL be **case-insensitive** (HTTP headers are case-insensitive per RFC 7230 — e.g., `authorization`, `Authorization`, and `AUTHORIZATION` all match the `Authorization` blacklist entry). The blacklist is configurable via `test-suite-run.execution.header-blacklist`.

#### Scenario: Template query params included
- **WHEN** the request template defines query parameters
- **THEN** the worker SHALL append all resolved query parameters to the request URL

#### Scenario: Authorization header from caller credential
- **WHEN** the worker makes a deployment call
- **THEN** the header the credential's kind requires (`Authorization: Bearer <jwt>` or `Api-Key`) SHALL be set automatically via the token propagation mechanism (not from user-provided headers)

### Requirement: Evaluation job delegates execution
TestSuiteEvaluationJob SHALL build a frozen execution context and delegate Phase 1 to EvaluationExecutor. Request resolution SHALL remain DB-free in runner-core; the job SHALL orchestrate snapshot, deployment, metrics and score-statistics phases rather than fabricate results.
Status: **Implemented**

#### Scenario: Job delegates to executor
- **WHEN** dispatch runs with deployment execution enabled after a successful snapshot
- **THEN** it SHALL build EvaluationContext and call evaluationExecutor.execute(context)

#### Scenario: Request resolution remains runner-owned
- **WHEN** an HTTP worker executes a case
- **THEN** RequestChainExecutor and TurnLoopExecutor SHALL resolve through runner.service.RequestResolver

### Requirement: HTTP client factory for streaming support
The `DialCoreDeploymentInvokerConfiguration` SHALL use `JdkClientHttpRequestFactory` (backed by `java.net.http.HttpClient`) with HTTP/1.1 protocol version pinned, instead of relying on default HTTP/2 ALPN negotiation.
Status: **Implemented**

#### Scenario: JdkClientHttpRequestFactory used for deployment invoker
- **WHEN** the deployment invoker `RestClient` bean is created
- **THEN** it SHALL use `JdkClientHttpRequestFactory` as the request factory, enabling chunked/streaming response reading

#### Scenario: HTTP/1.1 protocol version pinned
- **WHEN** the `JdkClientHttpRequestFactory` is configured
- **THEN** the underlying `HttpClient` SHALL be built with `HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)` to avoid HTTP 505 errors from servers that do not support HTTP/2

#### Scenario: DialCoreClient unaffected
- **WHEN** the `DialCoreClient` (models/applications listing) bean is created
- **THEN** it MAY continue using `SimpleClientHttpRequestFactory` since it does not need streaming support

### Requirement: Streaming-aware deployment invocation
The `DialCoreDeploymentInvoker` SHALL expose an `invokeWithStreaming()` method that returns a `DeploymentInvocationResult` with streaming detection and raw `InputStream` access for SSE consumption. The method SHALL NOT accept a `Duration timeout` parameter (HTTP transport settings and SSE parse-duration checks apply separately).
Status: **Implemented**

#### Scenario: invokeWithStreaming returns non-streaming result
- **WHEN** the endpoint returns a response with `Content-Type` that is NOT `text/event-stream`
- **THEN** `DeploymentInvocationResult.streaming` SHALL be `false`, `body` SHALL contain the parsed response, and `eventStream` SHALL be `null`

#### Scenario: invokeWithStreaming returns streaming result
- **WHEN** the endpoint returns a response with `Content-Type: text/event-stream`
- **THEN** `DeploymentInvocationResult.streaming` SHALL be `true`, `eventStream` SHALL contain the raw SSE `InputStream` for the caller to consume, and `body` SHALL be `null`

#### Scenario: Streaming parser duration bounds
- **WHEN** the worker consumes SSE
- **THEN** it SHALL pass requestTimeoutMs as the parser's idle duration and sse-event-processing.max-total-duration-ms as the absolute parse duration
- **AND** parsing deadlines SHALL start at parse entry, not HTTP request start, and be checked after completed line reads
- **AND** no timed future SHALL independently bound the whole invocation; blocking reads depend on transport timeout/interruption

#### Scenario: DeploymentInvocationResult structure
- **WHEN** a deployment invocation completes
- **THEN** the result SHALL contain: `statusCode` (int), `streaming` (boolean), `body` (Object, nullable — parsed JSON for non-streaming), `eventStream` (InputStream, nullable — raw SSE for streaming), `responseHeaders` (HttpHeaders — response headers). This extends the existing `DeploymentInvocationResponse(statusCode, parsedBody)` pattern with streaming awareness.

#### Scenario: DeploymentInvocationResult resource lifecycle
- **WHEN** the worker receives a `DeploymentInvocationResult` with `streaming = true`
- **THEN** the worker SHALL use `DeploymentInvocationResult` in a try-with-resources block (the result implements `AutoCloseable`). The `close()` method SHALL close the underlying `eventStream`, releasing the HTTP connection back to the pool. This prevents connection leaks when the worker or accumulator throws before fully consuming the stream.

#### Scenario: Method signature without timeout
- **WHEN** `invokeWithStreaming()` is defined
- **THEN** it SHALL use parameters: `HttpMethod method`, `String path` (relative), `HttpHeaders headers`, `MultiValueMap<String, String> queryParams`, `Object body`. The `Duration timeout` parameter is removed — the invoker uses the configured dialCoreTryOutRestClient; the worker has no independent timed-future invocation bound.

### Requirement: X-Correlation-Id removed from eval execution header blacklist
The `X-Correlation-Id` header SHALL be removed from the evaluation run header blacklist. It is no longer generated by the eval worker; `traceparent` and `tracestate` SHALL be added to the blacklist instead.
Status: **Implemented**

#### Scenario: traceparent stripped from template headers
- **WHEN** a test suite's request template includes a `traceparent` header
- **THEN** `InProcessEvaluationExecutor` / `EvaluationWorker` SHALL strip it (via the header blacklist)
- **AND** the correct `traceparent` SHALL be injected by the `RestClient` interceptor

#### Scenario: X-Correlation-Id from template is forwarded
- **WHEN** a test suite's request template includes an `X-Correlation-Id` header
- **AND** `X-Correlation-Id` is not in the header blacklist
- **THEN** the template-provided value SHALL be included in the DIAL Core request as-is

### Requirement: Request resolution error handling
When the worker fails to resolve the request for a test case (e.g., binding variable missing, template parsing error), the worker SHALL record the failure and continue to the next test case.
Status: **Implemented**

#### Scenario: Resolution failure recorded as ERROR
- **WHEN** `RequestResolver.resolveForRun()` throws an exception for a specific test case
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the error message in `responseBody` as a JSON error envelope, set `responseStatusCode = null`, and continue to the next test case (consistent with "continue on failure" policy)

### Requirement: MCP tool evaluation in EvaluationWorker

The `EvaluationWorker` SHALL support MCP_TOOL suites by branching on suite type at the beginning of test case execution. For MCP suites, the worker SHALL resolve arguments, invoke the MCP tool, serialize the response, and feed the result into the shared extraction and result-building pipeline.
Status: **Implemented**

#### Scenario: MCP suite detected
- **WHEN** the `EvaluationContext` references an MCP_TOOL suite
- **THEN** the worker SHALL use the MCP execution flow: `McpRequestResolver` → `McpToolInvoker` → `McpResponseSerializer` → `ResponseColumnExtractor`

#### Scenario: DEPLOYMENT suite unchanged
- **WHEN** the `EvaluationContext` references a DEPLOYMENT suite
- **THEN** the worker SHALL use the existing HTTP execution flow: `RequestResolver` → `RequestBodySerializerRegistry` → `DialCoreDeploymentInvoker` → `StreamingResponseAccumulator` → `ResponseColumnExtractor`

### Requirement: MCP argument resolution in worker

The worker SHALL resolve MCP tool arguments from the suite's argument template and effective input bindings using `McpRequestResolver`.
Status: **Implemented**

#### Scenario: Full argument resolution with effective bindings
- **WHEN** an MCP test case is dispatched for execution
- **THEN** the worker SHALL determine effective bindings: test case `inputBindingsOverride` (if non-null) takes priority over suite-level `McpSuite.inputBindings()` of `context.getSuite()` (same override semantics as HTTP suites)
- **AND** resolve tool arguments using `McpRequestResolver.resolve(argumentTemplate, effectiveBindings, testCaseData, context.snapshotTestCaseSchema)` where `argumentTemplate` is pre-loaded from `EvaluationContext` and `testCaseData` is parsed from the current test case

#### Scenario: Argument resolution failure
- **WHEN** `McpRequestResolver` fails to resolve arguments for a test case
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the error message in `responseBody`, and continue to the next test case

### Requirement: MCP tool invocation in worker

The worker SHALL invoke the MCP tool via `McpToolInvoker` and handle the various outcomes. The worker SHALL determine the MCP transport from `mcpDeploymentRef.transport` (defaulting to `McpTransport.STREAMABLE_HTTP` when null) and pass it to `McpToolInvoker.callTool()`.
Status: **Implemented**

#### Scenario: Successful tool call (isError = false)
- **WHEN** the MCP tool call completes with `isError = false`
- **THEN** the worker SHALL serialize the `CallToolResult` via `McpResponseSerializer`
- **AND** set `executionStatus = SUCCESS`
- **AND** store the serialized JSON in `responseBody`
- **AND** set `responseStatusCode = null`

#### Scenario: Tool-level error (isError = true)
- **WHEN** the MCP tool call completes with `isError = true`
- **THEN** the worker SHALL serialize the `CallToolResult` via `McpResponseSerializer`
- **AND** set `executionStatus = FAILED`
- **AND** store the serialized JSON in `responseBody`
- **AND** set `responseStatusCode = null` (MCP rows do not carry an HTTP status)

#### Scenario: MCP transport timeout
- **WHEN** the MCP tool call times out (as classified from an SDK/transport exception)
- **THEN** the worker SHALL set `executionStatus = TIMEOUT`, `responseBody` as an `MCP_INVOCATION_ERROR` JSON envelope, `responseStatusCode = null`

#### Scenario: MCP transport error
- **WHEN** the MCP tool call fails with a network-level error
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the error message in `responseBody`

#### Scenario: JSON-RPC error from DIAL Core
- **WHEN** DIAL Core returns a JSON-RPC error (e.g., tool not found, invalid arguments)
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the mapped exception message in an `MCP_INVOCATION_ERROR` JSON envelope in `responseBody`; no separate numeric JSON-RPC code is retained

### Requirement: MCP request body stored in results

For MCP test cases, the resolved tool arguments SHALL be stored in `requestBody`.
Status: **Implemented**

#### Scenario: MCP requestBody content
- **WHEN** the worker builds a `TestCaseRunResult` for an MCP test case
- **THEN** `requestBody` SHALL contain a JSON object with the resolved tool arguments (e.g., `{"query": "What is MCP?", "limit": 10}`)
- **AND** `requestBody` SHALL retain serialized resolved arguments; errors before that resolution may leave it null

### Requirement: MCP retry policy

The existing retry policy SHALL apply to MCP tool calls with status-based retry conditions; JSON-RPC numeric error codes are not separately classified.
Status: **Implemented**

#### Scenario: Retry on MCP transport failure
- **WHEN** an MCP tool call fails with a transport-level error (timeout, connection failure) and `maxRetries > 0`
- **THEN** the worker SHALL retry up to `maxRetries` times with exponential backoff (same formula as HTTP retries)

#### Scenario: Retry on JSON-RPC server error
- **WHEN** an MCP tool call fails with a JSON-RPC server error (error code -32000 to -32099) and `maxRetries > 0`
- **THEN** the mapped ERROR outcome SHALL be retried while attempts remain, without a numeric-code-specific classification

#### Scenario: No retry on tool-level isError
- **WHEN** an MCP tool call returns `isError = true` (tool completed but reported an error)
- **THEN** the worker SHALL NOT retry (tool-level errors are semantic, not transient)

#### Scenario: JSON-RPC invalid params follows generic ERROR retry
- **WHEN** an MCP tool call fails with JSON-RPC `InvalidParams` error (-32602)
- **THEN** the SDK error SHALL map to ERROR and the worker SHALL retry when retries remain; the worker does not distinguish JSON-RPC numeric codes

### Requirement: MCP rate limiting

The existing Bucket4j rate limiting SHALL apply to MCP tool calls identically to HTTP calls.
Status: **Implemented**

#### Scenario: MCP calls throttled by rate limiter
- **WHEN** `rateLimitRps` is configured and the suite is MCP_TOOL
- **THEN** each test-case-run submission SHALL acquire one rate-limit token; tool retries within it SHALL not acquire another

### Requirement: MCP response column extraction

After serializing the MCP response to JSON, the worker SHALL apply the suite's `responseColumns` via `ResponseColumnExtractor` — identical to the HTTP path.
Status: **Implemented**

#### Scenario: JSONata on MCP response
- **WHEN** an MCP test case completes with a serialized response body
- **THEN** the worker SHALL evaluate all `responseColumns` JSONata expressions against the serialized JSON
- **AND** store results in `extractedColumns` and failures in `extractionWarnings`

#### Scenario: MCP-specific extraction paths
- **WHEN** response columns use MCP-specific JSONata paths (e.g., `$.isError`, `$.content[0].text`, `$.structuredContent.results`)
- **THEN** the extraction SHALL work correctly because the serialized JSON preserves the MCP envelope structure

### Requirement: EvaluationWorker resides in `evaluation-runner-core`
The `EvaluationWorker` class SHALL reside in the `evaluation-runner-core` module under package `com.epam.aidial.evaluation.runner.job`. The EF backend's `InProcessEvaluationExecutor` SHALL inject it from the shared module.

Status: **Implemented**

#### Scenario: EvaluationWorker is a shared-module bean
- **WHEN** the EF backend application context starts
- **THEN** the `EvaluationWorker` bean SHALL originate from `evaluation-runner-core` (via `EvaluationRunnerAutoConfiguration`) and be injectable into `InProcessEvaluationExecutor` without the EF backend declaring it as a bean

#### Scenario: EvaluationWorker has no EF backend import
- **WHEN** ArchUnit's `RunnerModuleConstraintsTest` is run in the shared module
- **THEN** `EvaluationWorker` SHALL have no import from `com.epam.aidial.evaluation` (the EF backend package)

### Requirement: TestCaseRunResult type used by EvaluationWorker comes from `evaluation-runner-core`
The `TestCaseRunResult` type produced by `EvaluationWorker.execute(...)` and consumed by `InProcessEvaluationExecutor` and the EF backend's `PostgresResultBatchWriter` (implementing the shared module's `ResultBatchWriter` interface) SHALL be `com.epam.aidial.evaluation.runner.model.TestCaseRunResult`. The EF backend's analytics `TestCaseRunResultRecordMapper` SHALL map this shared type to the jOOQ-generated `TestCaseRunResultsRecord`.

Status: **Implemented**

#### Scenario: EvaluationWorker returns shared TestCaseRunResult
- **WHEN** `EvaluationWorker.execute(input, context, runIndex, responseColumns)` completes
- **THEN** it SHALL return `List<com.epam.aidial.evaluation.runner.model.TestCaseRunResult>`

#### Scenario: InProcessEvaluationExecutor consumes shared TestCaseRunResult
- **WHEN** `InProcessEvaluationExecutor` collects results from `EvaluationWorker` (via `TestCaseRunner`)
- **THEN** it SHALL pass them to a `com.epam.aidial.evaluation.runner.job.ResultBatchWriter` instance (created per-run by `PostgresResultBatchWriterFactory`) whose `addResults(List<TestCaseRunResult>)` parameter is `List<com.epam.aidial.evaluation.runner.model.TestCaseRunResult>`

#### Scenario: Analytics RecordMapper maps from shared model
- **WHEN** `TestCaseRunResultRecordMapper.from(TestCaseRunResult result)` is called in the EF backend
- **THEN** it SHALL produce a `TestCaseRunResultsRecord` by reading fields from `com.epam.aidial.evaluation.runner.model.TestCaseRunResult` — no data conversion, only field mapping

### Requirement: Execution engine rejects invalid resolved deployment models

The shared deployment execution engine SHALL perform resolved request model validation after request-body resolution and before serialization for invocation. On failure it SHALL skip the HTTP call, persist an `ERROR` result for the current request/turn with a `REQUEST_BODY_VALIDATION_ERROR` response envelope, preserve the resolved request body in the result for diagnostics, and apply the existing fail-fast request-chain semantics. Other test cases SHALL continue independently.

Status: **Implemented**

#### Scenario: Invalid model produces an error row without invocation

- **WHEN** a backend run resolves an invalid `model` for a canonical fixed-path request
- **THEN** the current request/turn SHALL persist one `ERROR` row whose response body contains `REQUEST_BODY_VALIDATION_ERROR`
- **AND** its resolved request body SHALL be persisted in `requestBody`
- **AND** the deployment SHALL not be invoked for that request/turn

#### Scenario: Failure aborts the remaining chain for that case

- **WHEN** resolved model validation fails at a request/turn in a multi-request or multi-turn execution
- **THEN** later turns of that request and later requests in that test case's chain SHALL not execute
- **AND** earlier completed rows SHALL remain unchanged

#### Scenario: Non-target requests retain existing behavior

- **WHEN** the resolved URL is not one of the two canonical model-selecting paths
- **THEN** execution SHALL not require its JSON body to contain a matching `model`

#### Scenario: Backend run guard limits which static failures can execute

- **WHEN** a newly saved plain-content suite has a static model-validation warning and is therefore `isValid=false`
- **THEN** the existing backend run-creation guard SHALL reject the run before Phase 1 execution
- **AND** runtime model validation SHALL protect JSONata-resolved bodies and previously persisted suites that reach execution

### Requirement: ROUTE suite invocation path validation
For a ROUTE suite the resolved `urlTemplate` SHALL be the path sent to DIAL Core, appended to the DIAL Core base URL unchanged, and SHALL be a root-relative path. The `routesRef` of the suite is metadata only and SHALL NOT be used to build the URL. A resolved path that violates the rules below SHALL NOT be sent to DIAL Core. The rules SHALL apply identically to every request of a chain, to every turn, and to try-it-out.

Status: **Implemented**

#### Scenario: Path without a leading slash is rejected
- **WHEN** the resolved `urlTemplate` of a ROUTE suite is `validate/items`
- **THEN** URL construction SHALL fail with message `ROUTE urlTemplate must resolve to a root-relative DIAL Core path`
- **AND** no request SHALL be sent to DIAL Core

#### Scenario: Authority-form path is rejected
- **WHEN** the resolved `urlTemplate` of a ROUTE suite is `//host/path`
- **THEN** URL construction SHALL fail with message `ROUTE urlTemplate must resolve to a root-relative DIAL Core path`

#### Scenario: Blank path is rejected
- **WHEN** the resolved `urlTemplate` of a ROUTE suite is empty or blank
- **THEN** URL construction SHALL fail with message `ROUTE urlTemplate must resolve to a root-relative DIAL Core path`

#### Scenario: Query or fragment data in the path is rejected
- **WHEN** the resolved `urlTemplate` of a ROUTE suite contains `?` or `#` (e.g. `/validate/items?x=1`)
- **THEN** URL construction SHALL fail with message `ROUTE urlTemplate path must not contain query or fragment data; use queryParams instead`

#### Scenario: Backslash or control character in the path is rejected
- **WHEN** the resolved `urlTemplate` of a ROUTE suite contains `\` or an ISO control character
- **THEN** URL construction SHALL fail with message `ROUTE urlTemplate path contains an unsupported character`

#### Scenario: Valid root-relative path is passed through unchanged
- **WHEN** the resolved `urlTemplate` of a ROUTE suite is `/validate/items`
- **THEN** the path sent to DIAL Core SHALL be `/validate/items`

#### Scenario: Rejected path in a run records an ERROR result
- **WHEN** a run executes a test case of a ROUTE suite whose resolved `urlTemplate` violates the rules above
- **THEN** no request SHALL be sent to DIAL Core for that turn
- **AND** the turn SHALL record a result row with `executionStatus = ERROR` whose response body is the error envelope with code `REQUEST_RESOLUTION_ERROR` and the rejection message
- **AND** the remaining turns and chain requests of that test case SHALL NOT run (fail-fast)
- **AND** the run SHALL NOT be failed by this per-case error

### Requirement: ROUTE suites skip resolved deployment-model validation
The resolved deployment-model validation described in `Execution engine rejects invalid resolved deployment models` SHALL NOT be applied to a ROUTE suite, because a ROUTE suite has no deployment identifier to compare the body `model` against. For a ROUTE suite, a resolved body for `/openai/v1/responses` or `/anthropic/v1/messages` SHALL be sent as resolved regardless of its `model` value. The behaviour for DEPLOYMENT suites SHALL be unchanged.

Status: **Implemented**

#### Scenario: ROUTE suite with an arbitrary model in the body is invoked
- **WHEN** a ROUTE suite's resolved `urlTemplate` is `/openai/v1/responses` and its resolved JSON body is `{"model":"not-a-deployment"}`
- **THEN** the request SHALL be sent to DIAL Core at `/openai/v1/responses`
- **AND** the result row SHALL have `executionStatus = SUCCESS` when DIAL Core answers successfully
- **AND** no `REQUEST_BODY_VALIDATION_ERROR` SHALL be produced

#### Scenario: DEPLOYMENT suite validation is unchanged
- **WHEN** a DEPLOYMENT suite's resolved body for `/openai/v1/responses` carries a `model` that does not equal the effective deployment ID
- **THEN** the request SHALL NOT be sent and the turn SHALL record an `ERROR` result with code `REQUEST_BODY_VALIDATION_ERROR`, exactly as before

### Requirement: ROUTE suites share the HTTP execution pipeline
Apart from URL construction and the skipped deployment-model validation, a ROUTE suite SHALL execute exactly like a DEPLOYMENT suite: HTTP method from `endpointRef.method`, request headers and query parameters, request-body serialisation, streaming accumulation, response size limiting, retry policy, rate limiting, the multi-turn loop, the request chain, response-column extraction, result rows, chain-wide fail-fast and caller credential propagation.

Status: **Implemented**

#### Scenario: ROUTE run completes and persists results like a DEPLOYMENT run
- **WHEN** a run is created for a ROUTE suite with `urlTemplate` `/validate/${{path}}` and a test case whose `routePath` field is `items`
- **THEN** the run SHALL reach `COMPLETED`
- **AND** DIAL Core SHALL receive the request path `/validate/items`
- **AND** the run's result rows SHALL be persisted with the same shape as for a DEPLOYMENT run

#### Scenario: Caller credentials are propagated unchanged
- **WHEN** a ROUTE suite run invokes DIAL Core
- **THEN** the credential headers SHALL follow the same rules as for a DEPLOYMENT suite

## Implementation Notes

- Orchestration/snapshots/defaults: `src/main/java/com/epam/aidial/evaluation/service/domain/job/TestSuiteEvaluationJob.java`, `InProcessEvaluationExecutor.java`, `PostgresResultBatchWriter.java`; `src/main/resources/application.yml`.
- Shared engine: `evaluation-runner-core/src/main/java/com/epam/aidial/evaluation/runner/job/EvaluationWorker.java`, `TestCaseRunner.java`, `RequestChainExecutor.java`, `TurnLoopExecutor.java`, `DeploymentTurnInvoker.java`, `StreamingResponseAccumulator.java`, `SseEventParser.java`, `TestCaseRunResultFactory.java`.
- Shared transforms: runner-core `runner.service.RequestResolver`, `RequestModelValidator`, `DialCoreUrlBuilder`, `ResponseColumnExtractor`, `McpRequestResolver`; transport `runner.client.dialcore.DialCoreDeploymentInvoker` and `runner.client.mcp.McpToolInvoker`.
- Worker schemas come from the snapshot dataset schema. Override carrier fields remain supported in run inputs, but current dataset cases/new snapshots do not author them.
- Read/response timeout defaults are not end-to-end request deadlines: HTTP uses the shared try-out RestClient; SSE checks occur only after completed line reads. Byte limits gate event dispatch or already-read nonstreaming/MCP content, not all allocation.
- Tests: runner-core `EvaluationWorkerTest`, `TestCaseRunnerTest`, `TurnLoopExecutorTest`, `DeploymentTurnInvokerTest`, `StreamingResponseAccumulatorTest`, `SseEventParserTest`; backend `InProcessEvaluationExecutorTest`, `PostgresResultBatchWriterTest`, `TestSuiteEvaluationJobTest`.
