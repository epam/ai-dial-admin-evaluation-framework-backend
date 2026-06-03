# Deployment Listing — MCP & Toolset Extension (Delta)

## Purpose

Extends the deployment listing to use DIAL Core's unified `GET /v1/deployments` endpoint, adds toolsets as a third deployment type, supports `type` and `interface` query parameter filtering, and provides a generalized tool discovery endpoint for MCP-capable deployments (toolsets and applications with MCP interface).

Status: **Planned**

## ADDED Requirements

### Requirement: ToolsetInfoDto in deployment hierarchy

The system SHALL define `ToolsetInfoDto extends DeploymentInfoDto` with discriminator value `dial-toolset` and toolset-specific fields.

#### Scenario: Toolset serialized with correct discriminator
- **WHEN** system returns a `ToolsetInfoDto`
- **THEN** JSON SHALL contain `"$type": "dial-toolset"`
- **AND** SHALL contain toolset-specific fields: `transport` (String, nullable), `allowedTools` (List<String>, nullable)

#### Scenario: Common deployment fields present on toolset
- **WHEN** system returns a `ToolsetInfoDto`
- **THEN** JSON SHALL contain common `DeploymentInfoDto` fields: `deploymentId`, `displayName`, `createdAt`, `updatedAt`, and nullable fields (`version`, `description`, `owner`, `descriptionKeywords`)

### Requirement: Deployment listing `interface` query parameter

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

### Requirement: Generalized tool discovery endpoint

The system SHALL provide `GET /api/v1/deployments/{type}/{id}/tools` to return the list of tools available on any MCP-capable deployment, including their schemas.

#### Scenario: List tools for a toolset
- **WHEN** authenticated user sends `GET /api/v1/deployments/dial-toolset/{id}/tools`
- **THEN** system SHALL call `McpToolInvoker.listTools(id, token)`
- **AND** return a list of `ToolDefinitionDto` objects containing `name`, `description`, `inputSchema` (Map), `outputSchema` (Map, nullable)

#### Scenario: List tools for an MCP-capable application
- **WHEN** authenticated user sends `GET /api/v1/deployments/dial-application/{id}/tools`
- **THEN** system SHALL call `McpToolInvoker.listTools(id, token)` (same MCP proxy path)
- **AND** return the tool list

#### Scenario: Tool discovery for non-MCP deployment type
- **WHEN** authenticated user sends `GET /api/v1/deployments/dial-model/{id}/tools`
- **THEN** system SHALL return HTTP 400 with error message indicating that models do not support tool discovery

#### Scenario: Deployment not found or inaccessible
- **WHEN** the deployment does not exist or user lacks access
- **THEN** DIAL Core's MCP proxy SHALL return an error
- **AND** the system SHALL return HTTP 502 with `UPSTREAM_ERROR`

#### Scenario: Tool discovery timeout
- **WHEN** the MCP `tools/list` call times out
- **THEN** the system SHALL return HTTP 504 with `UPSTREAM_TIMEOUT`

### Requirement: DeploymentType extended for toolsets

The `DeploymentType` enum SHALL include a `DIAL_TOOLSET` value with serialized form `"dial-toolset"`.

#### Scenario: Valid deployment types include toolset
- **WHEN** system validates deployment type
- **THEN** accepted values SHALL be: `dial-model`, `dial-application`, `dial-toolset`

#### Scenario: Get toolset by type and ID
- **WHEN** authenticated user sends `GET /api/v1/deployments/dial-toolset/{id}`
- **THEN** system SHALL call `GET /openai/toolsets/{id}` on DIAL Core and return the mapped `ToolsetInfoDto`

### Requirement: InterfaceType enum

The system SHALL define an `InterfaceType` enum for valid interface types used in query parameters.

#### Scenario: Valid interface types
- **WHEN** system validates interface type
- **THEN** accepted values SHALL be: `chat`, `embedding`, `mcp`, `custom_ui`

#### Scenario: Enum serialization
- **WHEN** `InterfaceType` is serialized to JSON or used in query parameter
- **THEN** value SHALL use snake_case (e.g., `"custom_ui"`)

## Implementation Notes

> **Note:** MODIFIED requirements for the deployment listing, detail endpoint, `DeploymentInfoDto` hierarchy, unified response mapping, and `DeploymentType` enum are defined in the `dial-core-client` delta spec. This spec covers only ADDED requirements (new toolset-specific functionality, interface/type query params, tool discovery, `InterfaceType` enum).


- New DTO: `ToolsetInfoDto extends DeploymentInfoDto` in `service.domain.dto.deployment`
- New DTO: `ToolDefinitionDto` in `service.domain.dto.deployment` — `name`, `description`, `inputSchema` (Map), `outputSchema` (Map, nullable)
- New enum: `InterfaceType` in `service.domain.dto.deployment` — `CHAT`, `EMBEDDING`, `MCP`, `CUSTOM_UI`
- New DIAL Core DTO: `DialCoreDeploymentDto` in `client.dialcore.dto` — unified response from `/v1/deployments` (replaces separate model/app/toolset list DTOs for listing)
- Modified: `DeploymentType` enum — add `DIAL_TOOLSET("dial-toolset")`
- Modified: `DialCoreClient` — add `getDeployments(interfaceType)` method calling `/v1/deployments`; keep `getToolset(id)` for single-item detail
- Modified: `DeploymentService` — replace 3 parallel calls with single `/v1/deployments` call; add type/interface filtering
- Modified: `DeploymentMapper` — add unified response mapping (type-discriminated), add toolset mapping
- Modified: `DeploymentController` — add `type` and `interface` query params, generalize tools endpoint to `/{type}/{id}/tools`
- OpenAPI: Update deployment endpoint docs with query params, toolset subtype, interface type enum, and tools endpoint
