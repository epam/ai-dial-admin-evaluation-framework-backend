# Toolset Listing

## Purpose

Extends the deployment listing to use DIAL Core's unified `GET /v1/deployments` endpoint, adds toolsets as a third deployment type, supports `type` and `interface` query parameter filtering, and provides a generalized tool discovery endpoint for MCP-capable deployments (toolsets and applications with MCP interface).

Status: **Implemented**

## Requirements

### Requirement: ToolsetInfoDto in deployment hierarchy

Status: **Implemented**

The system SHALL define `ToolsetInfoDto extends DeploymentInfoDto` with discriminator value `dial-toolset` and toolset-specific fields.

#### Scenario: Toolset serialized with correct discriminator
- **WHEN** system returns a `ToolsetInfoDto`
- **THEN** JSON SHALL contain `"$type": "dial-toolset"`
- **AND** SHALL contain toolset-specific fields: `transport` (String, nullable), `allowedTools` (List<String>, nullable)

#### Scenario: Common deployment fields present on toolset
- **WHEN** system returns a `ToolsetInfoDto`
- **THEN** JSON SHALL contain common `DeploymentInfoDto` fields: `deploymentId`, `displayName`, `createdAt`, `updatedAt`, and nullable fields (`version`, `description`, `owner`, `descriptionKeywords`)

### Requirement: Deployment listing `interface` query parameter

Status: **Implemented**

The system SHALL support an `interface` query parameter on `GET /api/v1/deployments` to filter deployments by supported interface type.

#### Scenario: Filter by MCP interface
- **WHEN** authenticated user sends `GET /api/v1/deployments?interface=mcp`
- **THEN** system SHALL return only MCP-capable deployments (toolsets + applications with MCP interface)

#### Scenario: Filter by chat interface
- **WHEN** authenticated user sends `GET /api/v1/deployments?interface=chat`
- **THEN** system SHALL return only chat-capable deployments (chat models + applications with chat completion endpoint)

#### Scenario: Filter by embedding interface
- **WHEN** authenticated user sends `GET /api/v1/deployments?interface=embedding`
- **THEN** system SHALL return only embedding models

#### Scenario: Filter by custom_ui interface
- **WHEN** authenticated user sends `GET /api/v1/deployments?interface=custom_ui`
- **THEN** system SHALL return only applications with viewer URL (custom UI interface)

#### Scenario: Invalid interface value
- **WHEN** authenticated user sends `GET /api/v1/deployments?interface=invalid`
- **THEN** system SHALL return HTTP 400 with error message listing valid interface values (`chat`, `embedding`, `mcp`, `custom_ui`)

### Requirement: Deployment listing `type` query parameter

Status: **Implemented**

The system SHALL support a `type` query parameter on `GET /api/v1/deployments` to filter deployments by deployment type.

#### Scenario: Filter by model type
- **WHEN** authenticated user sends `GET /api/v1/deployments?type=dial-model`
- **THEN** system SHALL return only models

#### Scenario: Filter by application type
- **WHEN** authenticated user sends `GET /api/v1/deployments?type=dial-application`
- **THEN** system SHALL return only applications

#### Scenario: Filter by toolset type
- **WHEN** authenticated user sends `GET /api/v1/deployments?type=dial-toolset`
- **THEN** system SHALL return only toolsets

#### Scenario: Combined type and interface filter
- **WHEN** authenticated user sends `GET /api/v1/deployments?type=dial-application&interface=mcp`
- **THEN** system SHALL return only applications with MCP interface

#### Scenario: Invalid type value
- **WHEN** authenticated user sends `GET /api/v1/deployments?type=invalid`
- **THEN** system SHALL return HTTP 400 with error message listing valid type values (`dial-model`, `dial-application`, `dial-toolset`)

### Requirement: Tool discovery endpoint

Status: **Implemented**

The system SHALL provide `GET /api/v1/deployments/tools?deploymentId={id}` to return the list of tools available on any MCP-capable deployment, including their schemas. The deployment ID is passed as a query parameter (not a path variable) because deployment IDs may contain slashes (e.g., `toolsets/public/3DMolVisualizer_(copy)__0.0.2`). An optional `transport` query parameter selects the MCP transport protocol; defaults to `STREAMABLE_HTTP` when omitted.

#### Scenario: List tools for a deployment
- **WHEN** authenticated user sends `GET /api/v1/deployments/tools?deploymentId=my-toolset`
- **THEN** system SHALL call `McpToolInvoker.listTools(id, token, transport)` with the effective transport
- **AND** return a list of `ToolDefinitionDto` objects containing `name`, `description`, `inputSchema` (Map), `outputSchema` (Map, nullable)

#### Scenario: List tools with slash-containing deployment ID
- **WHEN** authenticated user sends `GET /api/v1/deployments/tools?deploymentId=toolsets/public/my-tool`
- **THEN** the deployment ID SHALL be received intact (no encoding issues) because it is a query parameter, not a path segment
- **AND** system SHALL invoke `McpToolInvoker.listTools` with the full ID

#### Scenario: List tools with explicit SSE transport
- **WHEN** authenticated user sends `GET /api/v1/deployments/tools?deploymentId=my-toolset&transport=sse`
- **THEN** system SHALL pass `McpTransport.SSE` to `McpToolInvoker.listTools`

#### Scenario: List tools with default transport
- **WHEN** authenticated user sends `GET /api/v1/deployments/tools?deploymentId=my-toolset` (no `transport` param)
- **THEN** system SHALL default to `McpTransport.STREAMABLE_HTTP`

#### Scenario: Deployment not found or inaccessible
- **WHEN** the deployment does not exist or user lacks access
- **THEN** DIAL Core's MCP proxy SHALL return an error
- **AND** the system SHALL return HTTP 502 with `UPSTREAM_ERROR`

#### Scenario: Tool discovery timeout
- **WHEN** the MCP `tools/list` call times out
- **THEN** the system SHALL return HTTP 504 with `UPSTREAM_TIMEOUT`

### Requirement: DeploymentType extended for toolsets

Status: **Implemented**

The `DeploymentType` enum SHALL include a `DIAL_TOOLSET` value with serialized form `"dial-toolset"`.

#### Scenario: Valid deployment types include toolset
- **WHEN** system validates deployment type
- **THEN** accepted values SHALL be: `dial-model`, `dial-application`, `dial-toolset`

#### Scenario: Get toolset by type and ID
- **WHEN** authenticated user sends `GET /api/v1/deployments/dial-toolset/{id}`
- **THEN** system SHALL call `GET /openai/toolsets/{id}` on DIAL Core and return the mapped `ToolsetInfoDto`

### Requirement: InterfaceType enum

Status: **Implemented**

The system SHALL define an `InterfaceType` enum for valid interface types used in query parameters.

#### Scenario: Valid interface types
- **WHEN** system validates interface type
- **THEN** accepted values SHALL be: `chat`, `embedding`, `mcp`, `custom_ui`

#### Scenario: Enum serialization
- **WHEN** `InterfaceType` is serialized to JSON or used in query parameter
- **THEN** value SHALL use snake_case (e.g., `"custom_ui"`)

## Implementation Notes

- New DTO: `ToolsetInfoDto extends DeploymentInfoDto` in `service.domain.dto.deployment`
- New DTO: `ToolDefinitionDto` in `service.domain.dto.deployment` — `name`, `description`, `inputSchema` (Map), `outputSchema` (Map, nullable)
- New enum: `InterfaceType` in `service.domain.dto.deployment` — `CHAT`, `EMBEDDING`, `MCP`, `CUSTOM_UI`
- New DIAL Core DTO: `DialCoreDeploymentDto` in `client.dialcore.dto` — unified response from `/v1/deployments` (replaces separate model/app/toolset list DTOs for listing)
- Modified: `DeploymentType` enum — add `DIAL_TOOLSET("dial-toolset")`
- Modified: `DialCoreClient` — add `getDeployments(interfaceType)` method calling `/v1/deployments`; keep `getToolset(id)` for single-item detail
- Modified: `DeploymentService` — replace 3 parallel calls with single `/v1/deployments` call; add type/interface filtering
- Modified: `DeploymentMapper` — add unified response mapping (type-discriminated), add toolset mapping, add `DialTransport.SSE` → `McpTransport.SSE` in `dialTransportToMcp()`
- Modified: `DeploymentController` — add `type` and `interface` query params; tools endpoint at `GET /deployments/tools?deploymentId=&transport=` (query params, not path variables)
- Modified: `DeploymentService.listTools(deploymentId, transport)` — accepts `McpTransport`, defaults to `STREAMABLE_HTTP` when null
- OpenAPI: Update deployment endpoint docs with query params, toolset subtype, interface type enum, and tools endpoint
