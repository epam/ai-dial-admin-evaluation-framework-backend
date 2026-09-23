# MCP Tool Invocation (McpToolInvoker)

MCP toolsets and MCP-capable applications are invoked via DIAL Core's MCP proxy at `POST /v1/toolset/{name}/mcp`. `McpToolInvoker` (package `runner.client.mcp`, module `evaluation-runner-core`) creates a per-call `McpSyncClient` using the Java MCP SDK (`io.modelcontextprotocol.sdk:mcp-core`) with Streamable HTTP (or legacy SSE) transport. Each call creates a new client instance (URL differs per toolset). The caller's credential (bearer JWT or API key) is applied as exactly one header via `CallerCredential.headerName()`/`headerValue()` in the transport's `httpRequestCustomizer` builder hook.

- `callTool(String deploymentId, String toolName, Map<String, Object> arguments, CallerCredential credential, McpTransport transport)` → `CallToolResult`
- `listTools(String deploymentId, CallerCredential credential, McpTransport transport)` → `List<McpSchema.Tool>`
- Configuration: `McpClientProperties` (`dial.mcp.connect-timeout-ms`, `dial.mcp.read-timeout-ms`)
- Exceptions: `McpInvocationException` with status codes (502 connection error, 504 timeout)
- `McpRequestResolver` resolves `${{variable}}` placeholders in argument templates
- `McpResponseSerializer` serializes `CallToolResult` to JSON envelope for JSONata extraction
