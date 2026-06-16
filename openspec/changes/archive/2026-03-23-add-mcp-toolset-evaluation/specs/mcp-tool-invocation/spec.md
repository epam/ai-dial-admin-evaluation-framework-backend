# MCP Tool Invocation

## Purpose

Defines the MCP SDK client integration for invoking MCP tools via DIAL Core's MCP proxy endpoint (`POST /v1/toolset/{name}/mcp`). The proxy supports both toolsets and MCP-capable applications — the deployment name is used regardless of type. Covers tool call execution, argument resolution from templates, response serialization for the extraction pipeline, timeout handling, and error mapping.

Status: **Planned**

## Key Terms

- **McpToolInvoker**: Client-layer component wrapping the MCP Java SDK to execute `tools/call` against DIAL Core's MCP proxy.
- **McpRequestResolver**: Service-layer component (stateless transformer) that resolves a pre-loaded argument template by merging input bindings with test case data, producing a `Map<String, Object>` of tool arguments. Does NOT perform DB I/O — receives all inputs as method parameters.
- **McpResponseSerializer**: Service-layer component that serializes MCP SDK's `CallToolResult` to a JSON string preserving the MCP envelope structure for JSONata extraction.
- **ArgumentTemplate**: JSONB structure defining tool call arguments with `${{variable}}` placeholders and constant values, stored on MCP test suites.
- **CallToolResult**: MCP SDK type representing the tool's response — contains `content` (list of typed content blocks), `structuredContent` (optional Map), and `isError` (boolean).

## ADDED Requirements

### Requirement: McpToolInvoker executes tool calls via DIAL Core MCP proxy

The system SHALL provide a `McpToolInvoker` component in the `client.mcp` package that executes MCP `tools/call` requests against DIAL Core's MCP proxy endpoint. The invoker SHALL use the official Java MCP SDK (`io.modelcontextprotocol.sdk:mcp`) with Streamable HTTP transport. A new MCP client transport instance SHALL be created per invocation, targeting `{dialCoreBaseUrl}/v1/toolset/{deploymentId}/mcp` (where `deploymentId` is the name of a toolset or MCP-capable application). The user's JWT token SHALL be injected into the transport's HTTP headers.

#### Scenario: Successful tool call
- **WHEN** `McpToolInvoker.callTool(deploymentId, toolName, arguments, token)` is called with valid parameters
- **THEN** the invoker SHALL create an MCP client with Streamable HTTP transport targeting `{dialCoreBaseUrl}/v1/toolset/{deploymentId}/mcp`
- **AND** set `Authorization: Bearer {token}` on the transport's HTTP headers
- **AND** execute `tools/call` with the given tool name and arguments
- **AND** return the `CallToolResult` from the MCP SDK

#### Scenario: Tool call returns isError
- **WHEN** the MCP server returns a `CallToolResult` with `isError = true`
- **THEN** the invoker SHALL return the result as-is (not throw an exception) — `isError` is a tool-level semantic, not a transport-level failure

#### Scenario: Connection failure to DIAL Core MCP proxy
- **WHEN** the connection to DIAL Core fails (refused, DNS error)
- **THEN** the invoker SHALL throw `McpInvocationException` with status `BAD_GATEWAY` (502)

#### Scenario: Read timeout
- **WHEN** DIAL Core does not respond within the configured timeout
- **THEN** the invoker SHALL throw `McpInvocationException` with status `GATEWAY_TIMEOUT` (504)

#### Scenario: DIAL Core returns JSON-RPC error
- **WHEN** DIAL Core returns a JSON-RPC error response (e.g., tool not found, invalid arguments)
- **THEN** the invoker SHALL throw `McpInvocationException` with the error code and message from the JSON-RPC error

### Requirement: McpToolInvoker configuration

The system SHALL support configuration of MCP client settings via application properties under `dial.mcp.*`.

#### Scenario: Configure read timeout
- **WHEN** `dial.mcp.read-timeout-ms` is set
- **THEN** the MCP client transport SHALL use the configured read timeout

#### Scenario: Configure connect timeout
- **WHEN** `dial.mcp.connect-timeout-ms` is set
- **THEN** the MCP client transport SHALL use the configured connect timeout

#### Scenario: Default configuration
- **WHEN** no explicit MCP configuration is provided
- **THEN** the system SHALL use defaults: connect-timeout 5000ms, read-timeout 120000ms

### Requirement: MCP tool discovery via tools/list

The system SHALL provide a method on `McpToolInvoker` to call MCP `tools/list` for a given MCP-capable deployment (toolset or application), returning the list of available tools with their schemas.

#### Scenario: List tools for MCP deployment
- **WHEN** `McpToolInvoker.listTools(deploymentId, token)` is called
- **THEN** the invoker SHALL execute `tools/list` via the MCP proxy
- **AND** return a list of tool definitions including `name`, `description`, `inputSchema`, and optionally `outputSchema`

#### Scenario: tools/list filtered by DIAL Core
- **WHEN** the deployment has `allowed_tools` configured in DIAL Core
- **THEN** DIAL Core's MCP proxy SHALL filter the `tools/list` response to only include allowed tools (EF receives the filtered list)

#### Scenario: tools/list failure
- **WHEN** `tools/list` fails (connection error, timeout, JSON-RPC error)
- **THEN** the invoker SHALL throw `McpInvocationException` with appropriate status code and error details

### Requirement: McpRequestResolver resolves argument templates

The system SHALL provide a `McpRequestResolver` component in `service.domain` that resolves an MCP argument template by substituting `${{variable}}` placeholders with values from input bindings and test case data. The resolver SHALL produce a `Map<String, Object>` of resolved tool arguments. The resolver is a stateless transformer — it receives the argument template, input bindings, and test case data as method parameters and does NOT perform any DB I/O. The caller (worker or try-it-out service) is responsible for loading data before calling the resolver.

#### Scenario: Resolve arguments with test case bindings
- **WHEN** the argument template contains `{"query": "${{search_query}}", "limit": "${{max_results:10}}"}`
- **AND** input bindings map `search_query` to test case field `question`
- **AND** test case data contains `{"question": "What is MCP?"}`
- **THEN** the resolver SHALL return `{"query": "What is MCP?", "limit": 10}` (default value used for unbound variable)

#### Scenario: Constant values in argument template
- **WHEN** the argument template contains `{"format": "json", "query": "${{search_query}}"}`
- **THEN** `format` SHALL be included as-is (constant value), while `search_query` SHALL be resolved from bindings

#### Scenario: Type coercion from test case data
- **WHEN** a test case field value is a number (e.g., `10`) and the argument template placeholder expects it
- **THEN** the resolver SHALL preserve the original JSON type (number, boolean, object, array) — not convert to string

#### Scenario: Missing required variable
- **WHEN** a `${{variable}}` placeholder has no default value and no binding provides a value
- **THEN** the resolver SHALL produce a resolution warning (same pattern as HTTP template resolution warnings)

### Requirement: McpResponseSerializer converts CallToolResult to JSON

The system SHALL provide a `McpResponseSerializer` component in `service.domain` that serializes an MCP `CallToolResult` into a JSON string with a well-defined envelope structure for JSONata extraction.

#### Scenario: Serialize result with text content
- **WHEN** `CallToolResult` has `content = [TextContent("Hello")]`, `isError = false`, no `structuredContent`
- **THEN** the serialized JSON SHALL be: `{"content": [{"type": "text", "text": "Hello"}], "isError": false}`

#### Scenario: Serialize result with structuredContent
- **WHEN** `CallToolResult` has `structuredContent = {"score": 0.95, "label": "positive"}` and text content
- **THEN** the serialized JSON SHALL include both: `{"content": [...], "structuredContent": {"score": 0.95, "label": "positive"}, "isError": false}`

#### Scenario: Serialize result with mixed content types
- **WHEN** `CallToolResult` has content blocks of different types (text, image, resource_link)
- **THEN** the serialized JSON SHALL preserve each content block's type discriminator and type-specific fields

#### Scenario: Serialize isError result
- **WHEN** `CallToolResult` has `isError = true`
- **THEN** the serialized JSON SHALL include `"isError": true`

#### Scenario: Null structuredContent omitted
- **WHEN** `CallToolResult` has no `structuredContent`
- **THEN** the serialized JSON SHALL omit the `structuredContent` field (not include `"structuredContent": null`)

### Requirement: MCP response size limiting

The system SHALL enforce the existing `max-response-size-bytes` limit on the serialized MCP response JSON.

#### Scenario: Response within limit
- **WHEN** the serialized MCP response is within `max-response-size-bytes`
- **THEN** the full serialized response SHALL be stored in `responseBody`

#### Scenario: Response exceeds limit
- **WHEN** the serialized MCP response exceeds `max-response-size-bytes` (e.g., large base64-encoded image in content block)
- **THEN** the system SHALL truncate the serialized JSON string at the byte limit (same behavior as HTTP response truncation)
- **AND** set `executionStatus = ERROR` regardless of the MCP-level `isError` value (truncation overrides tool-level status because the response is incomplete)
- **AND** add a truncation warning to `extractionWarnings`
- **AND** store the truncated string in `responseBody` (note: this is NOT a valid MCP JSON envelope — JSONata expressions targeting `$.isError`, `$.content`, or `$.structuredContent` will produce null or extraction errors, which is expected and correct since the data is incomplete)

### Requirement: MCP execution status mapping

The system SHALL map MCP outcomes to `ExecutionStatus` values consistent with the HTTP execution model.

#### Scenario: Successful tool call (isError = false)
- **WHEN** tool call completes and `isError = false`
- **THEN** `executionStatus` SHALL be `SUCCESS`

#### Scenario: Tool-level error (isError = true)
- **WHEN** tool call completes and `isError = true`
- **THEN** `executionStatus` SHALL be `FAILED` (tool reported an error, similar to HTTP 4xx)

#### Scenario: Transport timeout
- **WHEN** the MCP tool call times out
- **THEN** `executionStatus` SHALL be `TIMEOUT`

#### Scenario: Transport/network error
- **WHEN** the MCP tool call fails due to network/connection error
- **THEN** `executionStatus` SHALL be `ERROR`

#### Scenario: JSON-RPC protocol error
- **WHEN** DIAL Core returns a JSON-RPC error (e.g., tool not found)
- **THEN** `executionStatus` SHALL be `ERROR` and the error message SHALL be stored in `responseBody`

## Implementation Notes
- MCP SDK dependency: `io.modelcontextprotocol.sdk:mcp` in `build.gradle`
- New package: `com.epam.aidial.evaluation.client.mcp`
- New classes: `McpToolInvoker`, `McpClientConfiguration`, `McpClientProperties`, `McpInvocationException`
- New service classes: `McpRequestResolver`, `McpResponseSerializer` in `service.domain`
- Config properties: `dial.mcp.connect-timeout-ms`, `dial.mcp.read-timeout-ms` in `application.yml`
- `docs/configuration.md` must be updated with new MCP properties
