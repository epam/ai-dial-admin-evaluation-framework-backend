# MCP Tool Invocation

## Purpose

Defines the MCP SDK client integration for invoking MCP tools via DIAL Core's MCP proxy endpoint (`POST /v1/toolset/{name}/mcp`). The proxy supports both toolsets and MCP-capable applications — the deployment name is used regardless of type. Covers tool call execution, argument resolution from templates, response serialization for the extraction pipeline, timeout handling, and error mapping.

Status: **Implemented**

## Key Terms

- **McpToolInvoker**: Client-layer component wrapping the MCP Java SDK to execute `tools/call` against DIAL Core's MCP proxy.
- **McpRequestResolver**: Service-layer component (stateless transformer) that resolves a pre-loaded argument template by merging input bindings with test case data, producing a `Map<String, Object>` of tool arguments. Does NOT perform DB I/O — receives all inputs as method parameters.
- **McpResponseSerializer**: Service-layer component that serializes MCP SDK's `CallToolResult` to a JSON string preserving the MCP envelope structure for JSONata extraction.
- **ArgumentTemplate**: JSONB structure defining tool call arguments with `${{variable}}` placeholders and constant values, stored on MCP test suites.
- **CallToolResult**: MCP SDK type representing the tool's response — contains `content` (list of typed content blocks), `structuredContent` (optional Map), and `isError` (boolean).

## Requirements

### Requirement: McpToolInvoker executes tool calls via DIAL Core MCP proxy

The system SHALL provide a `McpToolInvoker` component in the `client.mcp` package that executes MCP `tools/call` requests against DIAL Core's MCP proxy endpoint. The invoker SHALL use the official Java MCP SDK (`io.modelcontextprotocol.sdk:mcp`) with the transport protocol specified by the `McpTransport` parameter. A new MCP client transport instance SHALL be created per invocation. The user's JWT token SHALL be injected into the transport's HTTP headers.

- **STREAMABLE_HTTP** transport: Uses `HttpClientStreamableHttpTransport` targeting `{dialCoreBaseUrl}` with endpoint path `/v1/toolset/{deploymentId}/mcp`.
- **SSE** transport: Uses `HttpClientSseClientTransport` targeting `{dialCoreBaseUrl}` with SSE endpoint path `/v1/toolset/{deploymentId}/sse`.

The deployment ID may contain slashes (e.g., `toolsets/public/my-tool`) and special characters (spaces, parentheses). The invoker SHALL decode any `%2F`-encoded slashes in the deployment ID to path separators and percent-encode other special characters (spaces → `%20`) in each path segment using `UriComponentsBuilder.encode()`.

Status: **Implemented**

#### Scenario: Successful tool call
- **WHEN** `McpToolInvoker.callTool(deploymentId, toolName, arguments, token, transport)` is called with valid parameters
- **THEN** the invoker SHALL create an MCP client with the transport matching the `transport` parameter
- **AND** set `Authorization: Bearer {token}` on the transport's HTTP headers
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

Status: **Implemented**

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

Status: **Implemented**

#### Scenario: List tools for MCP deployment
- **WHEN** `McpToolInvoker.listTools(deploymentId, token, transport)` is called
- **THEN** the invoker SHALL create an MCP client using the specified transport and execute `tools/list` via the MCP proxy
- **AND** return a list of tool definitions including `name`, `description`, `inputSchema`, and optionally `outputSchema`

#### Scenario: tools/list filtered by DIAL Core
- **WHEN** the deployment has `allowed_tools` configured in DIAL Core
- **THEN** DIAL Core's MCP proxy SHALL filter the `tools/list` response to only include allowed tools (EF receives the filtered list)

#### Scenario: tools/list failure
- **WHEN** `tools/list` fails (connection error, timeout, JSON-RPC error)
- **THEN** the invoker SHALL throw `McpInvocationException` with appropriate status code and error details

### Requirement: McpRequestResolver resolves argument templates

The system SHALL provide a `McpRequestResolver` component in `service.domain` that resolves an MCP argument template by substituting `${{variable}}` placeholders with values from input bindings and test case data. The resolver SHALL produce a `Map<String, Object>` of resolved tool arguments. The resolver is a stateless transformer — it receives the argument template, input bindings, and test case data as method parameters and does NOT perform any DB I/O. The caller (worker or try-it-out service) is responsible for loading data before calling the resolver.

Method signatures:
- `resolve(ArgumentTemplateDto argumentTemplate, List<InputBindingDto> bindings, Map<String, Object> testCaseData)` — used by test-case try-it-out and evaluation worker
- `resolveWithVariables(ArgumentTemplateDto argumentTemplate, List<InputBindingDto> bindings, Map<String, Object> variables)` — used by variables try-it-out (delegates to `resolve`)

Resolution priority per variable:
1. Binding `constantValue` (if a binding exists for the variable and has `constantValue`)
2. Binding `dataField` lookup (if a binding exists and has `dataField`, look up `data[dataField]`)
3. Direct variable name lookup in data (fallback when no binding matches the variable)
4. Template default value (from `${{var:default}}` syntax)
5. `null` + `REQUIRED` validation warning (when no value can be resolved and no default exists)

When `bindings` is `null` or empty, the resolver falls back directly to step 3 (direct variable name lookup). Duplicate `templateVariable` entries in bindings are deduplicated first-wins.

**File reference resolution:** When a full-value placeholder carries a `|file` type hint (e.g., `${{document|file}}`) and the resolved value is a `String`, the resolver SHALL transform the value via `DialFileRefResolver.resolveToDialRef()` — converting short-format references (e.g., `@ef/suites/abc/data.csv`) to DIAL API paths (e.g., `files/real-bucket/suites/abc/data.csv`). This enables DIAL-aware MCP tools to receive properly resolved file references. File resolution applies only to full-value placeholders (entire argument value is one `${{var|file}}`); embedded placeholders in mixed text are string-concatenated without file resolution.

Status: **Implemented**

#### Scenario: Resolve arguments with binding dataField
- **WHEN** the argument template contains `{"query": "${{search_query}}", "limit": "${{max_results:10}}"}`
- **AND** input bindings map `search_query` to data field `question`
- **AND** test case data contains `{"question": "What is MCP?"}`
- **THEN** the resolver SHALL return `{"query": "What is MCP?", "limit": 10}` (binding dataField used for lookup, default value used for unbound variable)

#### Scenario: Resolve arguments with binding constantValue
- **WHEN** the argument template contains `{"query": "${{search_query}}"}`
- **AND** input bindings map `search_query` with `constantValue: "fixed-value"`
- **AND** test case data contains `{"search_query": "from-data"}`
- **THEN** the resolver SHALL return `{"query": "fixed-value"}` (constantValue overrides data lookup)

#### Scenario: Resolve arguments with no bindings (direct name lookup)
- **WHEN** the argument template contains `{"query": "${{searchQuery}}"}`
- **AND** bindings is `null` or empty
- **AND** test case data contains `{"searchQuery": "hello world"}`
- **THEN** the resolver SHALL return `{"query": "hello world"}` (direct variable name lookup in data)

#### Scenario: Binding dataField missing from data falls back to default
- **WHEN** the argument template contains `{"query": "${{searchQuery:fallback}}"}`
- **AND** a binding maps `searchQuery` to `dataField: "missing_key"`
- **AND** test case data does not contain `missing_key`
- **THEN** the resolver SHALL return `{"query": "fallback"}` (template default used as fallback)

#### Scenario: Constant values in argument template
- **WHEN** the argument template contains `{"format": "json", "query": "${{search_query}}"}`
- **THEN** `format` SHALL be included as-is (constant value), while `search_query` SHALL be resolved from bindings

#### Scenario: Type coercion from test case data
- **WHEN** a test case field value is a number (e.g., `10`) and the argument template placeholder expects it
- **THEN** the resolver SHALL preserve the original JSON type (number, boolean, object, array) — not convert to string

#### Scenario: Binding constantValue preserves non-string types
- **WHEN** a binding has `constantValue: 42` (integer)
- **AND** the argument template has `{"count": "${{num}}"}`
- **THEN** the resolver SHALL return `{"count": 42}` (type preserved via full-value resolution)

#### Scenario: Missing required variable
- **WHEN** a `${{variable}}` placeholder has no default value, no binding provides a value, and the variable name is not in test case data
- **THEN** the resolver SHALL produce a `REQUIRED` validation warning with `fieldName` = variable name, `path` = `$.argumentTemplate.arguments`, and set the resolved value to `null` (full-value mode) or empty string (embedded mode)

#### Scenario: |file type hint resolves to DIAL ref
- **WHEN** the argument template contains `{"document": "${{contract|file}}"}`
- **AND** the resolved value for `contract` is `"@ef/suites/abc/contract.pdf"` (from binding or data)
- **THEN** the resolver SHALL return `{"document": "files/real-bucket/suites/abc/contract.pdf"}` (resolved via `DialFileRefResolver.resolveToDialRef()`)

#### Scenario: |file type hint with constantValue binding
- **WHEN** the argument template contains `{"attachment": "${{doc|file}}"}`
- **AND** a binding for `doc` has `constantValue: "@ef/suites/abc/report.pdf"`
- **THEN** the resolver SHALL return `{"attachment": "files/real-bucket/suites/abc/report.pdf"}`

#### Scenario: |file type hint with null resolved value
- **WHEN** the argument template contains `{"document": "${{contract|file}}"}`
- **AND** no binding, data, or default provides a value for `contract`
- **THEN** the resolver SHALL produce a `REQUIRED` warning and set the value to `null` (no file resolution applied to null)

#### Scenario: |file in embedded placeholder (mixed text) not resolved
- **WHEN** the argument template contains `{"path": "prefix/${{doc|file}}/suffix"}`
- **AND** the resolved value for `doc` is `"@ef/suites/abc/data.csv"`
- **THEN** the resolver SHALL return `{"path": "prefix/@ef/suites/abc/data.csv/suffix"}` (string concatenation, NO file ref resolution — embedded mode)

#### Scenario: Non-string resolved value with |file hint not transformed
- **WHEN** the argument template contains `{"doc": "${{doc|file}}"}`
- **AND** the resolved value for `doc` is an integer or object (not a String)
- **THEN** the resolver SHALL return the resolved value as-is without file ref transformation

#### Scenario: |file resolution error propagates
- **WHEN** the argument template contains `{"document": "${{contract|file}}"}`
- **AND** the resolved value is a malformed file reference (e.g., `"invalid-no-prefix"`)
- **THEN** the resolver SHALL propagate the exception from `DialFileRefResolver.resolveToDialRef()` (fail-fast — the caller `EvaluationWorker`/`TryItOutService` handles errors at the job/request level via existing exception handling)

#### Scenario: |file with default value resolves the default as a file ref
- **WHEN** the argument template contains `{"document": "${{contract|file:@ef/suites/abc/default.pdf}}"}`
- **AND** no binding, data, or explicit value provides a value for `contract`
- **THEN** the resolver SHALL use the default value `"@ef/suites/abc/default.pdf"` as the resolved value
- **AND** SHALL transform it via `DialFileRefResolver.resolveToDialRef()` (the default string IS the resolved value — file resolution applies to it)

#### Scenario: |FILE uppercase type hint resolves as file ref (case-insensitive)
- **WHEN** the argument template contains `{"document": "${{contract|FILE}}"}`
- **AND** the resolved value for `contract` is `"@ef/suites/abc/contract.pdf"`
- **THEN** the resolver SHALL treat `FILE` the same as `file` (case-insensitive match via `SchemaFieldType.FILE.name().equalsIgnoreCase()`)
- **AND** return the value resolved via `DialFileRefResolver.resolveToDialRef()`

#### Scenario: Multiple |file variables in same argument template resolved independently
- **WHEN** the argument template contains `{"doc1": "${{a|file}}", "doc2": "${{b|file}}"}`
- **AND** the resolved value for `a` is `"@ef/suites/abc/first.pdf"` and for `b` is `"@ef/suites/abc/second.pdf"`
- **THEN** the resolver SHALL resolve each independently via `DialFileRefResolver.resolveToDialRef()`
- **AND** return `{"doc1": "files/real-bucket/suites/abc/first.pdf", "doc2": "files/real-bucket/suites/abc/second.pdf"}`

### Requirement: McpResponseSerializer converts CallToolResult to JSON

The system SHALL provide a `McpResponseSerializer` component in `service.domain` that serializes an MCP `CallToolResult` into a JSON string with a well-defined envelope structure for JSONata extraction.

Status: **Implemented**

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

Status: **Implemented**

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

Status: **Implemented**

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

### Requirement: McpTransport enum

A `McpTransport` enum SHALL exist in `service.domain.dto` with values `STREAMABLE_HTTP("streamable-http")` and `SSE("sse")`. `McpDeploymentReferenceDto.transport` and `ToolsetInfoDto.transport` SHALL use this enum type. The `@Pattern` regex validator on `McpDeploymentReferenceDto.transport` SHALL NOT be used — enum deserialization validation replaces it. Jackson SHALL use `@JsonCreator` on `McpTransport.fromValue()` and fail fast (throw `IllegalArgumentException`) on unrecognized values. `DeploymentMapper` SHALL convert `DialTransport.HTTP` → `McpTransport.STREAMABLE_HTTP` and `DialTransport.SSE` → `McpTransport.SSE` when mapping to `ToolsetInfoDto`.
Status: **Implemented**

#### Scenario: Known transport value accepted in request (streamable-http)
- **WHEN** a create/update request contains `"transport": "streamable-http"` in the MCP deployment reference
- **THEN** `McpDeploymentReferenceDto.transport` deserializes to `McpTransport.STREAMABLE_HTTP`

#### Scenario: Known transport value accepted in request (sse)
- **WHEN** a create/update request contains `"transport": "sse"` in the MCP deployment reference
- **THEN** `McpDeploymentReferenceDto.transport` deserializes to `McpTransport.SSE`

#### Scenario: Unknown transport value rejected
- **WHEN** a create/update request contains an unrecognized transport value in the MCP deployment reference
- **THEN** Jackson throws during deserialization and a 400 validation error is returned

#### Scenario: Transport value in toolset response
- **WHEN** `GET /api/v1/deployments?type=dial-toolset` returns a toolset with DIAL Core transport `HTTP`
- **THEN** `ToolsetInfoDto.transport` is `McpTransport.STREAMABLE_HTTP` (serialized as `"streamable-http"`)

#### Scenario: SSE transport value in toolset response
- **WHEN** `GET /api/v1/deployments?type=dial-toolset` returns a toolset with DIAL Core transport `SSE`
- **THEN** `ToolsetInfoDto.transport` is `McpTransport.SSE` (serialized as `"sse"`)

#### Scenario: Null transport defaults to STREAMABLE_HTTP
- **WHEN** a call site (TryItOutService, EvaluationWorker, DeploymentService) has `mcpDeploymentRef.transport` as null
- **THEN** the caller SHALL default to `McpTransport.STREAMABLE_HTTP` before passing to `McpToolInvoker`

---

## Implementation Notes
- `McpRequestResolver.PLACEHOLDER_PATTERN` captures the type hint: `(?:\|([^:}]+))?` (capturing group 2). Variable name is group 1, type hint is group 2, default value is group 3. Update all `matcher.group()` references when modifying the pattern.
- `DialFileRefResolver` is injected into `McpRequestResolver` via constructor injection. Both are in `service.domain` — no layering issue.
- File resolution check: `if (SchemaFieldType.FILE.name().equalsIgnoreCase(typeHint) && resolved instanceof String resolvedRef)` — same pattern as `ResolvedRequestService`.
- Only full-value placeholders trigger file resolution. Embedded placeholders use string concatenation and skip file ref transformation.
- MCP SDK dependency: `io.modelcontextprotocol.sdk:mcp` in `build.gradle`
- New package: `com.epam.aidial.evaluation.client.mcp`
- New classes: `McpToolInvoker`, `McpClientConfiguration`, `McpClientProperties`, `McpInvocationException`
- New service classes: `McpRequestResolver`, `McpResponseSerializer` in `service.domain`
- New enum: `McpTransport` in `service.domain.dto` — `STREAMABLE_HTTP("streamable-http")`, `SSE("sse")`, fail-fast via `@JsonCreator`
- New enum: `DialTransport` in `client.dialcore.dto` — `HTTP("HTTP")`, `SSE("SSE")` (maps to `McpTransport` via `DeploymentMapper`)
- Transport dispatch in `McpToolInvoker`: STREAMABLE_HTTP uses `HttpClientStreamableHttpTransport` (endpoint `/mcp`); SSE uses `HttpClientSseClientTransport` (endpoint `/sse`)
- Endpoint path construction: `buildMcpEndpoint(deploymentId)` and `buildSseEndpoint(deploymentId)` decode `%2F` to path separators and percent-encode special characters via `UriComponentsBuilder.encode()`
- No-op `JsonSchemaValidator` provided to MCP client builder to avoid `NoClassDefFoundError` from `networknt/json-schema-validator` 1.x/2.x version conflict (MCP SDK 1.1.0 compiles against 2.x, project uses 1.x). Safe because schema validation is only active when `enableCallToolSchemaCaching=true` (off by default).
- Config properties: `dial.mcp.connect-timeout-ms`, `dial.mcp.read-timeout-ms` in `application.yml`
- `docs/configuration.md` must be updated with new MCP properties
