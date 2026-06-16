## Why

The Evaluation Framework currently supports evaluating HTTP-based DIAL deployments (models and applications) via REST endpoints (chat/completions, embeddings, custom routes). DIAL Core now supports **MCP (Model Context Protocol) toolsets** — registered MCP servers exposing tools via JSON-RPC 2.0 through `POST /v1/toolset/{name}/mcp`. Additionally, DIAL applications can expose MCP interfaces alongside their HTTP endpoints. To evaluate these MCP-capable deployments, EF needs native MCP support: listing toolsets, discovering tools, executing tool calls, and handling MCP's structured response format (content blocks + optional structuredContent + isError).

## What Changes

- **Deployment listing overhaul** — switch to DIAL Core's unified `GET /v1/deployments` endpoint with `type` and `interface` query param filtering; support all DIAL Core interface types (`chat`, `embedding`, `mcp`, `custom_ui`, `all`); toolsets become a third `DeploymentInfoDto` subtype alongside models and applications
- **MCP tool discovery** — call MCP `tools/list` via DIAL Core proxy to retrieve tool schemas (inputSchema, outputSchema, description) for any MCP-capable deployment (toolsets or applications with MCP interface)
- **MCP tool invocation** — integrate Java MCP SDK (`io.modelcontextprotocol.sdk:mcp`) with Streamable HTTP transport targeting DIAL Core's MCP proxy; execute `tools/call` with resolved arguments, handle `CallToolResult` response
- **Test suite extension** — add `suite_type` discriminator (`DEPLOYMENT` | `MCP_TOOL`) to the test_suites table; add nullable `mcp_deployment_ref`, `tool_ref`, `argument_template` JSONB columns for MCP suites
- **MCP argument resolution** — resolve argument templates by merging tool inputSchema bindings with test case data variables (parallel to HTTP request template resolution)
- **MCP response serialization** — serialize `CallToolResult` (content blocks + structuredContent + isError) to JSON string for JSONata-based response column extraction
- **MCP try-it-out** — extend try-it-out service to support single MCP tool call execution with tool-specific response rendering
- **MCP evaluation flow** — branch `EvaluationWorker` by suite type: existing HTTP flow for DEPLOYMENT suites, new MCP flow (argument resolution → tool call → response serialization → extraction) for MCP_TOOL suites
- **MCP response column defaults** — built-in extraction paths for MCP responses: `$.isError`, `$.content[0].text`, `$count($.content)`, plus `$.structuredContent.*` paths derived from tool's outputSchema

## Capabilities

### New Capabilities
- `mcp-tool-invocation`: MCP SDK client integration, Streamable HTTP transport via DIAL Core proxy (`POST /v1/toolset/{name}/mcp`), `tools/list` for tool schema discovery, `tools/call` execution with argument resolution, `CallToolResult` response handling (text/image/audio/resource content blocks + optional structuredContent + isError), response serialization to JSON for extraction pipeline
- `toolset-listing`: Switch to DIAL Core's unified `GET /v1/deployments` endpoint; add `type` and `interface` query params to EF deployment listing; fetch toolset metadata, map to `ToolsetInfoDto`; support MCP-capable applications alongside toolsets

### Modified Capabilities
- `test-suites`: Add `suite_type` discriminator column (default `DEPLOYMENT` for backward compat), nullable `mcp_deployment_ref`/`tool_ref`/`argument_template` JSONB columns, type-specific validation (DEPLOYMENT follows existing soft-validation pattern; MCP_TOOL hard-requires mcpDeploymentRef+toolRef, while argumentTemplate is recommended via soft validation — null produces a warning, mirroring the HTTP suite pattern where requestTemplate:null produces a warning)
- `dial-core-client`: Switch to unified `GET /v1/deployments` endpoint with `interface_type` and type filtering; add `DialCoreToolsetDto` mapping; support MCP-capable applications
- `try-it-out`: MCP tool call try-it-out mode — resolve arguments, invoke via MCP SDK, return MCP response with content blocks + structuredContent + isError; validate `mcpDeploymentRef` and `toolRef`
- `eval-execution-engine`: Suite type branching in `EvaluationWorker` — MCP suites use `McpRequestResolver` + `McpToolInvoker` + `McpResponseSerializer` instead of HTTP resolution + `DialCoreDeploymentInvoker` + `StreamingResponseAccumulator`; `EvaluationContext` carries `mcpDeploymentRef`, `toolRef`, `argumentTemplate`
- `response-columns`: MCP-specific default extraction path suggestions — built-in (isError, content[0].text, content count) + outputSchema-derived (structuredContent.* paths)

## Impact

### Code
- **New dependency**: `io.modelcontextprotocol.sdk:mcp` (Java MCP SDK with Streamable HTTP transport)
- **New packages/classes**:
  - `client.mcp` — MCP client configuration, `McpToolInvoker`
  - `service.domain.dto.deployment.ToolsetInfoDto` — new DeploymentInfoDto subtype
  - `service.domain.McpRequestResolver` — argument template resolution
  - `service.domain.McpResponseSerializer` — CallToolResult → JSON string
- **Modified classes**: `DeploymentService`, `DeploymentMapper`, `DeploymentType`, `EvaluationWorker`, `TryItOutService`, `TestSuiteService`, `TestSuiteRequestDto`/`ResponseDto`, `DialCoreClient`

### Database
- Flyway migration: add `suite_type VARCHAR(20) DEFAULT 'DEPLOYMENT'`, `mcp_deployment_ref JSONB`, `tool_ref JSONB`, `argument_template JSONB` to `test_suites` table
- Migration naming: `V{next}__{description}.sql` in `db/migration/meta/POSTGRES/`

### API
- `GET /api/v1/deployments` — response includes toolset entries with `$type: "dial-toolset"`
- `GET /api/v1/deployments` — supports `type` and `interface` query params; backed by DIAL Core's unified `GET /v1/deployments`. **Note:** `type` query param filtering is done client-side (not delegated to DIAL Core), per design decision D7
- `GET /api/v1/deployments/{type}/{id}/tools` — generalized tool discovery for any MCP-capable deployment type
- `POST/PUT /api/v1/test-suites` — request body extended with optional `suiteType`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate` fields; type-specific validation
- `GET /api/v1/test-suites/{id}` — response includes new MCP fields when suite type is MCP_TOOL
- `POST /api/v1/try-it-out/*` — extended to support MCP tool calls

### Configuration
- New properties under `dial.mcp.*` (timeouts, transport settings)
- `docs/configuration.md` must be updated

### Dependencies
- DIAL Core must support `GET /v1/deployments` (unified listing with `interface_type` filter), `GET /openai/toolsets/{id}` (single toolset detail), and `POST /v1/toolset/{name}/mcp` (MCP proxy) endpoints
