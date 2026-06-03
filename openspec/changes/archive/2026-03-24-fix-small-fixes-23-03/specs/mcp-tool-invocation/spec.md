## ADDED Requirements

### Requirement: McpTransport enum
A `McpTransport` enum SHALL exist in `service.domain.dto` with at least `STREAMABLE_HTTP("streamable-http")`. `McpDeploymentReferenceDto.transport` and `ToolsetInfoDto.transport` SHALL use this enum type. The `@Pattern` regex validator on `McpDeploymentReferenceDto.transport` SHALL be removed (enum validation replaces it). Jackson SHALL use `@JsonCreator` on `McpTransport.fromValue()` and fail fast (throw `IllegalArgumentException`) on unrecognized values.

**Status**: Planned

#### Scenario: Known transport value accepted in request
- **WHEN** a create/update request contains `"transport": "streamable-http"` in the MCP deployment reference
- **THEN** `McpDeploymentReferenceDto.transport` deserializes to `McpTransport.STREAMABLE_HTTP`

#### Scenario: Unknown transport value rejected
- **WHEN** a create/update request contains `"transport": "grpc"` in the MCP deployment reference
- **THEN** Jackson throws during deserialization and a 400 validation error is returned

#### Scenario: Transport value in toolset response
- **WHEN** `GET /api/v1/deployments?type=dial-toolset` returns a toolset
- **THEN** `ToolsetInfoDto.transport` is `McpTransport.STREAMABLE_HTTP` (serialized as `"streamable-http"`)
