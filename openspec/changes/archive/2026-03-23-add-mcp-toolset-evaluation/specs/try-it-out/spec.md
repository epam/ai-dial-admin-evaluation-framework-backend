# Try It Out — MCP Extension (Delta)

## ADDED Requirements

### Requirement: Try it out with MCP tool call (test case)

The system SHALL support try-it-out for MCP_TOOL suites via `POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`. For MCP suites, the system SHALL resolve tool arguments from the argument template using test case data and bindings, execute the MCP tool call via `McpToolInvoker`, and return the MCP response.

#### Scenario: Successful MCP try-it-out with test case
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`
- **AND** the test suite has `suiteType = MCP_TOOL` with valid `mcpDeploymentRef`, `toolRef`, and `argumentTemplate`
- **THEN** the system SHALL resolve tool arguments via `McpRequestResolver`
- **AND** execute `McpToolInvoker.callTool(mcpDeploymentRef.id, toolRef.name, resolvedArguments, token)`
- **AND** serialize the `CallToolResult` via `McpResponseSerializer`
- **AND** return HTTP 200 with `TryItOutResponseDto` containing the resolved arguments, the serialized MCP response, and execution duration

#### Scenario: MCP suite missing mcpDeploymentRef
- **WHEN** the MCP_TOOL suite has `mcpDeploymentRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: MCP suite missing toolRef
- **WHEN** the MCP_TOOL suite has `toolRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: MCP tool call returns isError
- **WHEN** the MCP tool call returns `isError = true`
- **THEN** the try-it-out endpoint SHALL still return HTTP 200
- **AND** `response.body` SHALL contain the serialized MCP response with `isError: true`

#### Scenario: MCP tool call timeout
- **WHEN** the MCP tool call times out
- **THEN** the system SHALL return HTTP 504 with error code `UPSTREAM_TIMEOUT`

### Requirement: Try it out with MCP tool call (variables)

The system SHALL support try-it-out with variables for MCP_TOOL suites via `POST /api/v1/test-suites/{testSuiteId}/try-it-out`. Each variable entry maps an argument template variable name to its constant value.

#### Scenario: Successful MCP try-it-out with variables
- **WHEN** authenticated user sends POST to `/api/v1/test-suites/{testSuiteId}/try-it-out` with `{ "variables": { "search_query": "MCP protocol" } }`
- **AND** the test suite has `suiteType = MCP_TOOL`
- **THEN** the system SHALL convert variables to constant-value bindings
- **AND** resolve tool arguments using the suite's argument template and the variable bindings
- **AND** execute the MCP tool call and return the response

### Requirement: MCP try-it-out response structure

The `TryItOutResponseDto` SHALL accommodate MCP responses alongside HTTP responses.

#### Scenario: MCP response in TryItOutResponseDto
- **WHEN** an MCP try-it-out completes
- **THEN** `resolvedRequest` SHALL contain the resolved arguments (as the "body" component — reuse `ResolvedJsonBodyDto` with the arguments map)
- **AND** `response.statusCode` SHALL be `200` when the tool call succeeds (even if `isError = true` — that's tool-level, not transport-level)
- **AND** `response.body` SHALL be the parsed JSON object from `McpResponseSerializer` output (content blocks + structuredContent + isError)

#### Scenario: MCP transport error response
- **WHEN** the MCP tool call fails at the transport level (connection refused, timeout)
- **THEN** the system SHALL return the standard error response (HTTP 502/504) — same pattern as HTTP try-it-out

## MODIFIED Requirements

### Requirement: Validation before invocation
The system SHALL validate the test suite configuration before invoking the deployment or MCP tool. The request SHALL be rejected if preconditions are not met. Validation SHALL be type-aware: DEPLOYMENT suites validate HTTP preconditions, MCP_TOOL suites validate MCP preconditions.

#### Scenario: Missing deployment reference (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `deploymentRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that deployment reference is required

#### Scenario: Missing request template (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `requestTemplate` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that request template is required

#### Scenario: Missing endpoint reference (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `endpointRef` as null or `endpointRef.method` is null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that endpoint reference with HTTP method is required

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

## Implementation Notes
- Modified: `TryItOutService` — branch by `suiteType`: HTTP flow (existing) or MCP flow (new)
- MCP flow: `McpRequestResolver.resolve()` → `McpToolInvoker.callTool()` → `McpResponseSerializer.serialize()` → build `TryItOutResponseDto`
- Reuse `TryItOutResponseDto` structure — `resolvedRequest.body` contains arguments as JSON, `response.body` contains serialized MCP response
