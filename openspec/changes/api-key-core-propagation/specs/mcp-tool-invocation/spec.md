## MODIFIED Requirements

### Requirement: McpToolInvoker executes tool calls via DIAL Core MCP proxy

The system SHALL provide a `McpToolInvoker` component in the `runner.client.mcp` package that executes MCP `tools/call` requests against DIAL Core's MCP proxy endpoint. The invoker SHALL use the official Java MCP SDK (`io.modelcontextprotocol.sdk:mcp`) with the transport protocol specified by the `McpTransport` parameter. A new MCP client transport instance SHALL be created per invocation. The caller's credential SHALL be injected into the transport's HTTP headers in the header its kind requires — `Authorization: Bearer <token>` for a bearer caller, `Api-Key: <key>` for a DIAL API-key caller — and only that one header SHALL be set.

- **STREAMABLE_HTTP** transport: Uses `HttpClientStreamableHttpTransport` targeting `{dialCoreBaseUrl}` with endpoint path `/v1/toolset/{deploymentId}/mcp`.
- **SSE** transport: Uses `HttpClientSseClientTransport` targeting `{dialCoreBaseUrl}` with SSE endpoint path `/v1/toolset/{deploymentId}/sse`.

The deployment ID may contain slashes (e.g., `toolsets/public/my-tool`) and special characters (spaces, parentheses). The invoker SHALL decode any `%2F`-encoded slashes in the deployment ID to path separators and percent-encode other special characters (spaces → `%20`) in each path segment using `UriComponentsBuilder.encode()`.

Status: **Planned**

#### Scenario: Successful tool call
- **WHEN** `McpToolInvoker.callTool(...)` is called with valid parameters and the caller's credential
- **THEN** the invoker SHALL create an MCP client with the transport matching the `transport` parameter
- **AND** set the credential on the transport's HTTP headers as `Authorization: Bearer {token}` for a bearer credential or `Api-Key: {key}` for an API-key credential
- **AND** execute `tools/call` with the given tool name and arguments
- **AND** return the `CallToolResult` from the MCP SDK

#### Scenario: Deployment ID with slashes produces multi-segment path
- **WHEN** the deployment ID is `toolsets/public/my-tool`
- **THEN** the endpoint path SHALL be `/v1/toolset/toolsets/public/my-tool/mcp` (STREAMABLE_HTTP) or `/v1/toolset/toolsets/public/my-tool/sse` (SSE)

#### Scenario: Deployment ID with spaces is percent-encoded
- **WHEN** the deployment ID is `toolsets/public/27.03 deepwiki toolset__0.0.1`
- **THEN** the endpoint path SHALL be `/v1/toolset/toolsets/public/27.03%20deepwiki%20toolset__0.0.1/mcp` (spaces encoded, slashes preserved as separators)

#### Scenario: URL-encoded slash (%2F) in deployment ID decoded to path separator
- **WHEN** the deployment ID contains `%2F` (e.g., `my-org%2Fmy-toolset`)
- **THEN** the invoker SHALL decode `%2F` to `/` and produce `/v1/toolset/my-org/my-toolset/mcp`

#### Scenario: Tool call with no caller credential
- **WHEN** `callTool` or `listTools` is invoked with no caller credential (for example a run dispatched without one)
- **THEN** the transport SHALL carry neither an `Authorization` nor an `Api-Key` header, and SHALL NOT send a placeholder value such as `Bearer null`

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

### Requirement: MCP tool discovery via tools/list

The system SHALL provide a method on `McpToolInvoker` to call MCP `tools/list` for a given MCP-capable deployment (toolset or application), returning the list of available tools with their schemas. The request SHALL carry the caller's credential in the header its kind requires, exactly as `tools/call` does.

Status: **Planned**

#### Scenario: List tools for MCP deployment
- **WHEN** `McpToolInvoker.listTools(...)` is called with a deployment ID, the caller's credential, and a transport
- **THEN** the invoker SHALL create an MCP client using the specified transport and execute `tools/list` via the MCP proxy
- **AND** return a list of tool definitions including `name`, `description`, `inputSchema`, and optionally `outputSchema`

#### Scenario: tools/list for an API-key caller
- **WHEN** the caller authenticated with an `Api-Key` header
- **THEN** the `tools/list` request to the MCP proxy SHALL carry `Api-Key: <the caller's key>` and no `Authorization` header

#### Scenario: tools/list filtered by DIAL Core
- **WHEN** the deployment has `allowed_tools` configured in DIAL Core
- **THEN** DIAL Core's MCP proxy SHALL filter the `tools/list` response to only include allowed tools (EF receives the filtered list)

#### Scenario: tools/list failure
- **WHEN** `tools/list` fails (connection error, timeout, JSON-RPC error)
- **THEN** the invoker SHALL throw `McpInvocationException` with appropriate status code and error details

## Implementation notes

Planned touch points: `com.epam.aidial.evaluation.runner.client.mcp.McpToolInvoker` (both transport builders currently hardcode `Authorization: Bearer ` + token) and its callers `service.domain.DeploymentService#listTools`, `service.domain.TryItOutService`, and `runner.job.EvaluationWorker`, which pass the credential through.
