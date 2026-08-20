## MODIFIED Requirements

### Requirement: Multi-turn dispatch and per-turn result emission
The worker that executes one run input SHALL return a list of results. Dispatch SHALL be independent of suite type: every input — `DEPLOYMENT` or `MCP_TOOL` — is delegated to the chain executor and its turn loop. When an input carries `multi_turn_data` and the executing request binds a per-turn field, the turn loop emits one result per executed turn; otherwise it emits a single result for that request. Each result carries `turn_index` and `total_turns` (single-turn = `0/1`) and, when the chain has more than one request, `request_index` and `total_requests`.
Status: **Planned**

#### Scenario: Multi-turn input yields per-turn results
- **WHEN** a run input has `multi_turn_data`
- **THEN** execution runs the turn loop and returns one `TestCaseRunResult` per executed turn

#### Scenario: Single-turn input is unchanged
- **WHEN** a run input has no `multi_turn_data`
- **THEN** the single-turn path runs and returns exactly one result with `turn_index=0, total_turns=1`

#### Scenario: MCP input follows the same dispatch
- **WHEN** a run input for an `MCP_TOOL` suite has `multi_turn_data` and the request binds a per-turn field
- **THEN** execution runs the same turn loop and returns one result per executed turn

### Requirement: MCP tool evaluation in EvaluationWorker

The worker SHALL execute `MCP_TOOL` suites through the same chain executor and turn loop as `DEPLOYMENT` suites, with no suite-type branch ahead of the chain executor. The suite type SHALL select only the per-turn resolve-and-invoke step, chosen once per chain: for an MCP suite that step resolves tool arguments, invokes the tool, and normalizes the reply into the same per-turn outcome shape the HTTP step produces. Retry and backoff, cancellation checks, oversize-response handling, execution-status mapping, response-column extraction, frame accumulation, and result-row construction SHALL be shared by both suite types rather than duplicated per type.
Status: **Planned**

#### Scenario: MCP suite detected
- **WHEN** the `EvaluationContext` references an MCP_TOOL suite
- **THEN** the worker SHALL delegate to the chain executor with the MCP invocation step selected, and the tool reply SHALL flow through the shared extraction and row-building pipeline

#### Scenario: DEPLOYMENT suite unchanged
- **WHEN** the `EvaluationContext` references a DEPLOYMENT suite
- **THEN** the worker SHALL delegate to the chain executor with the HTTP invocation step selected, producing rows identical to those produced before this capability existed

#### Scenario: Retry and oversize handling are shared
- **WHEN** an MCP tool call is retried, or its response exceeds the configured maximum response size
- **THEN** the behaviour SHALL be governed by the same retry/backoff and oversize handling the HTTP path uses, with the MCP-specific retry classification applied through it

### Requirement: MCP argument resolution in worker

The worker SHALL resolve each MCP request's tool arguments from that request's argument template and effective input bindings, evaluated with the accumulated frame for the turn being executed. Effective bindings SHALL be determined per request: a test case's `inputBindingsOverride` (when non-null) takes priority over the request's own bindings, matching the override semantics used for HTTP suites. The argument template SHALL come from the run snapshot — request #0's from the suite-level field, each later request's from its `additionalRequests` entry.
Status: **Planned**

#### Scenario: Full argument resolution with effective bindings
- **WHEN** an MCP test case is dispatched for execution
- **THEN** the worker SHALL determine effective bindings with test-case override priority, and resolve the executing request's arguments from its argument template, the turn's effective data, and the accumulated frame

#### Scenario: Argument resolution failure
- **WHEN** argument resolution or JSONata evaluation fails for a turn
- **THEN** the turn SHALL persist a row with `executionStatus = ERROR` carrying the failure envelope in `responseBody`, and the chain SHALL abort without invoking later turns or requests

#### Scenario: Each chain position resolves its own template
- **WHEN** an MCP chain's requests declare different argument templates
- **THEN** each request SHALL resolve its own template, and request `i` SHALL see every response column extracted by requests `0..i-1` bound by name

### Requirement: MCP tool invocation in worker

The MCP invocation step SHALL invoke the tool and map its outcomes to execution statuses. The transport SHALL be taken from the suite-level `mcpDeploymentRef.transport`, defaulting to `STREAMABLE_HTTP` when null. The tool name SHALL be taken from the executing request's `toolRef`. Outcome-to-status mapping SHALL be unchanged from single-shot MCP execution.
Status: **Planned**

#### Scenario: Successful tool call (isError = false)
- **WHEN** the MCP tool call completes with `isError = false`
- **THEN** the step SHALL serialize the `CallToolResult`
- **AND** set `executionStatus = SUCCESS`
- **AND** store the serialized JSON in `responseBody`
- **AND** set `responseStatusCode = 200`

#### Scenario: Tool-level error (isError = true)
- **WHEN** the MCP tool call completes with `isError = true`
- **THEN** the step SHALL serialize the `CallToolResult`
- **AND** set `executionStatus = FAILED`
- **AND** store the serialized JSON in `responseBody`
- **AND** set `responseStatusCode = 200` (transport succeeded, tool reported error)

#### Scenario: MCP transport timeout
- **WHEN** the MCP tool call times out (exceeds `requestTimeoutMs`)
- **THEN** the step SHALL set `executionStatus = TIMEOUT`, `responseBody = null`, `responseStatusCode = null`

#### Scenario: MCP transport error
- **WHEN** the MCP tool call fails with a network-level error
- **THEN** the step SHALL set `executionStatus = ERROR` and store the error message in `responseBody`

#### Scenario: JSON-RPC error from DIAL Core
- **WHEN** DIAL Core returns a JSON-RPC error (e.g., tool not found, invalid arguments)
- **THEN** the step SHALL set `executionStatus = ERROR` and store the JSON-RPC error details in `responseBody`

#### Scenario: Non-success status aborts the chain
- **WHEN** any turn of any MCP request ends in `FAILED`, `TIMEOUT`, or `ERROR`
- **THEN** that turn's row SHALL persist and no later turn of that request nor any later request SHALL be invoked

## Implementation Notes

- `EvaluationWorker`'s MCP branch and its duplicated retry loop, backoff computation, response truncation, and result-row builders are deleted; the worker reduces to span/baggage setup plus delegation to `RequestChainExecutor`.
- The MCP invocation step returns the same per-turn outcome record the HTTP step returns, so `TurnLoopExecutor` needs no MCP-specific code beyond selecting the step.
- MCP retry classification (retry on transport `ERROR`/`TIMEOUT`, never on a tool-level `FAILED`) is expressed through the shared retry helper rather than a parallel predicate.
