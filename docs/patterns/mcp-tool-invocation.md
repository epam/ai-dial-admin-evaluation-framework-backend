# MCP Tool Invocation (McpToolInvoker)

MCP toolsets and MCP-capable applications are invoked via DIAL Core's MCP proxy at `POST /v1/toolset/{name}/mcp`. `McpToolInvoker` (`client.mcp`) creates a per-call `McpSyncClient` using the Java MCP SDK (`io.modelcontextprotocol.sdk:mcp-core`) with Streamable HTTP transport. Each call creates a new client instance (URL differs per toolset). Auth headers (user JWT) are injected via the transport's `customizeRequest` builder.

- `callTool(deploymentId, toolName, arguments, token)` → `CallToolResult`
- `listTools(deploymentId, token)` → `List<McpSchema.Tool>`
- Configuration: `McpClientProperties` (`dial.mcp.connect-timeout-ms`, `dial.mcp.read-timeout-ms`)
- Exceptions: `McpInvocationException` with status codes (502 connection error, 504 timeout)
- `McpRequestResolver` resolves `${{variable}}` placeholders in argument templates
- `McpResponseSerializer` serializes `CallToolResult` to JSON envelope for JSONata extraction
