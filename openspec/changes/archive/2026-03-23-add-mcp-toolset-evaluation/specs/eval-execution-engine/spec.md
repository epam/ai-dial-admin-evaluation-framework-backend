# Eval Execution Engine — MCP Extension (Delta)

## ADDED Requirements

### Requirement: MCP tool evaluation in EvaluationWorker

The `EvaluationWorker` SHALL support MCP_TOOL suites by branching on suite type at the beginning of test case execution. For MCP suites, the worker SHALL resolve arguments, invoke the MCP tool, serialize the response, and feed the result into the shared extraction and result-building pipeline.

#### Scenario: MCP suite detected
- **WHEN** the `EvaluationContext` references an MCP_TOOL suite
- **THEN** the worker SHALL use the MCP execution flow: `McpRequestResolver` → `McpToolInvoker` → `McpResponseSerializer` → `ResponseColumnExtractor`

#### Scenario: DEPLOYMENT suite unchanged
- **WHEN** the `EvaluationContext` references a DEPLOYMENT suite
- **THEN** the worker SHALL use the existing HTTP execution flow: `ResolvedRequestService` → `RequestBodySerializer` → `DialCoreDeploymentInvoker` → `StreamingResponseAccumulator` → `ResponseColumnExtractor`

### Requirement: MCP argument resolution in worker

The worker SHALL resolve MCP tool arguments from the suite's argument template using `McpRequestResolver`.

#### Scenario: Full argument resolution
- **WHEN** an MCP test case is dispatched for execution
- **THEN** the worker SHALL resolve tool arguments using `McpRequestResolver.resolve(argumentTemplate, inputBindings, testCaseData)` where all inputs are pre-loaded from the `EvaluationContext` and the current test case

#### Scenario: Argument resolution failure
- **WHEN** `McpRequestResolver` fails to resolve arguments for a test case
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the error message in `responseBody`, and continue to the next test case

### Requirement: MCP tool invocation in worker

The worker SHALL invoke the MCP tool via `McpToolInvoker` and handle the various outcomes.

#### Scenario: Successful tool call (isError = false)
- **WHEN** the MCP tool call completes with `isError = false`
- **THEN** the worker SHALL serialize the `CallToolResult` via `McpResponseSerializer`
- **AND** set `executionStatus = SUCCESS`
- **AND** store the serialized JSON in `responseBody`
- **AND** set `responseStatusCode = 200`

#### Scenario: Tool-level error (isError = true)
- **WHEN** the MCP tool call completes with `isError = true`
- **THEN** the worker SHALL serialize the `CallToolResult` via `McpResponseSerializer`
- **AND** set `executionStatus = FAILED`
- **AND** store the serialized JSON in `responseBody`
- **AND** set `responseStatusCode = 200` (transport succeeded, tool reported error)

#### Scenario: MCP transport timeout
- **WHEN** the MCP tool call times out (exceeds `requestTimeoutMs`)
- **THEN** the worker SHALL set `executionStatus = TIMEOUT`, `responseBody = null`, `responseStatusCode = null`

#### Scenario: MCP transport error
- **WHEN** the MCP tool call fails with a network-level error
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the error message in `responseBody`

#### Scenario: JSON-RPC error from DIAL Core
- **WHEN** DIAL Core returns a JSON-RPC error (e.g., tool not found, invalid arguments)
- **THEN** the worker SHALL set `executionStatus = ERROR`, store the JSON-RPC error details in `responseBody`

### Requirement: MCP request body stored in results

For MCP test cases, the resolved tool arguments SHALL be stored in `requestBody`.

#### Scenario: MCP requestBody content
- **WHEN** the worker builds a `TestCaseRunResult` for an MCP test case
- **THEN** `requestBody` SHALL contain a JSON object with the resolved tool arguments (e.g., `{"query": "What is MCP?", "limit": 10}`)
- **AND** `requestBody` SHALL be null only when argument resolution itself fails

### Requirement: MCP retry policy

The existing retry policy SHALL apply to MCP tool calls with MCP-specific retryable conditions.

#### Scenario: Retry on MCP transport failure
- **WHEN** an MCP tool call fails with a transport-level error (timeout, connection failure) and `maxRetries > 0`
- **THEN** the worker SHALL retry up to `maxRetries` times with exponential backoff (same formula as HTTP retries)

#### Scenario: Retry on JSON-RPC server error
- **WHEN** an MCP tool call fails with a JSON-RPC server error (error code -32000 to -32099) and `maxRetries > 0`
- **THEN** the worker SHALL retry (server errors are transient, similar to HTTP 5xx)

#### Scenario: No retry on tool-level isError
- **WHEN** an MCP tool call returns `isError = true` (tool completed but reported an error)
- **THEN** the worker SHALL NOT retry (tool-level errors are semantic, not transient)

#### Scenario: No retry on JSON-RPC invalid params
- **WHEN** an MCP tool call fails with JSON-RPC `InvalidParams` error (-32602)
- **THEN** the worker SHALL NOT retry (client error, similar to HTTP 4xx)

### Requirement: MCP rate limiting

The existing Bucket4j rate limiting SHALL apply to MCP tool calls identically to HTTP calls.

#### Scenario: MCP calls throttled by rate limiter
- **WHEN** `rateLimitRps` is configured and the suite is MCP_TOOL
- **THEN** each MCP tool call (including retries) SHALL acquire a rate limit token before execution

### Requirement: MCP response column extraction

After serializing the MCP response to JSON, the worker SHALL apply the suite's `responseColumns` via `ResponseColumnExtractor` — identical to the HTTP path.

#### Scenario: JSONata on MCP response
- **WHEN** an MCP test case completes with a serialized response body
- **THEN** the worker SHALL evaluate all `responseColumns` JSONata expressions against the serialized JSON
- **AND** store results in `extractedColumns` and failures in `extractionWarnings`

#### Scenario: MCP-specific extraction paths
- **WHEN** response columns use MCP-specific JSONata paths (e.g., `$.isError`, `$.content[0].text`, `$.structuredContent.results`)
- **THEN** the extraction SHALL work correctly because the serialized JSON preserves the MCP envelope structure

## Implementation Notes
- Modified: `EvaluationWorker` — add suite type branching at `execute()` entry point. MCP retry logic SHOULD reuse the existing `invokeWithRetries()` method by extracting the inner execution logic into a strategy function (e.g., `Supplier<InvocationResult>`) so that backoff calculation, attempt tracking, cancellation checks, and `logDetails` building are shared between HTTP and MCP paths. Avoid duplicating retry logic in `executeMcpToolCall()`.
- New private methods in worker: `executeMcpToolCall()`, `buildMcpResult()`
- Shared: `ResponseColumnExtractor`, result building, retry logic, rate limiting, batch writing, progress reporting
- Modified: `EvaluationContext` — SHALL carry `suiteType` and deserialized MCP-specific references (`mcpDeploymentRef`, `toolRef`, `argumentTemplate`) loaded from the suite at run initialization time. The worker does NOT re-query the DB for these per test case — they are immutable for the duration of the run. This parallels how `EvaluationContext` already carries `deploymentRef`, `endpointRef`, and `requestTemplate` for HTTP suites.
- **MCP field loading chain:** `InProcessEvaluationExecutor.execute()` already loads the suite (to read `responseColumns`, `inputBindings`, etc.) before constructing `EvaluationContext` and starting the worker loop. The MCP fields (`mcpDeploymentRef`, `toolRef`, `argumentTemplate`) SHALL be deserialized from the suite's JSONB strings at this point (using `JsonbMapper` / `ObjectMapper`) and passed into `EvaluationContext.builder()` as typed objects (`McpDeploymentReferenceDto`, `ToolReferenceDto`, `ArgumentTemplateDto`). This avoids per-test-case deserialization and keeps the context immutable. The deserialization code lives in `InProcessEvaluationExecutor` (or a helper it delegates to), NOT in the worker.
- **Data loading asymmetry between HTTP and MCP suites (intentional):**
  - For HTTP suites, `ResolvedRequestService` loads suite data per test case (existing pattern unchanged) because each test case may have different variable bindings that affect the full HTTP request (URL, headers, query params, body).
  - For MCP suites, the suite's `mcpDeploymentRef`, `toolRef`, and `argumentTemplate` are loaded once at run initialization into `EvaluationContext` because they are immutable for the run duration. `McpRequestResolver` is a stateless transformer that receives them as parameters and only resolves the argument template per test case (the deployment and tool targets are fixed for all cases in the run).
  - This asymmetry is intentional — HTTP suites resolve the full request template per test case (URL template, headers, query params, and body all participate in variable substitution), while MCP suites resolve only the argument values per test case (the tool name and deployment target never change within a run).
