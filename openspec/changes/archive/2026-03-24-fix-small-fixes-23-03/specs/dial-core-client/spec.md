## MODIFIED Requirements

### Requirement: Deployment list deserialization
The DIAL Core client SHALL deserialize the `GET /v1/deployments` response as a bare JSON array (`List<DialCoreDeploymentDto>`), not as a wrapped object. `DialCoreDeploymentListResponseDto` SHALL be removed. `DialCoreClient.getDeployments()` SHALL return `List<DialCoreDeploymentDto>` and `DeploymentService` SHALL be updated to consume this type directly without a `getData()` call.

**Status**: Planned

#### Scenario: Successful deployment list
- **WHEN** DIAL Core returns `[{"id":"x","object":"model",...}, {"id":"y","object":"toolset",...}]`
- **THEN** `DialCoreClient.getDeployments()` returns a `List<DialCoreDeploymentDto>` with two entries

#### Scenario: Empty list
- **WHEN** DIAL Core returns `[]`
- **THEN** `DialCoreClient.getDeployments()` returns an empty list (not null, no exception)

### Requirement: Deployment type discriminator
`DialCoreDeploymentDto` SHALL use the field `object` (not `type`) as the deployment type discriminator, reflecting the actual DIAL API response. The JSON field name is `object` (matching the DIAL Core API field) and the Java field name SHALL be `object` as well. Valid values are `"model"`, `"application"`, `"toolset"`. `DeploymentMapper.toDeploymentInfoDto()` SHALL branch on `source.getObject()`.

**Status**: Planned

#### Scenario: Model entry mapped correctly
- **WHEN** the unified list entry has `"object": "model"`
- **THEN** `toDeploymentInfoDto()` returns a `DialModelInfoDto`

#### Scenario: Toolset entry mapped correctly
- **WHEN** the unified list entry has `"object": "toolset"`
- **THEN** `toDeploymentInfoDto()` returns a `ToolsetInfoDto`

#### Scenario: Application entry mapped correctly
- **WHEN** the unified list entry has `"object": "application"`
- **THEN** `toDeploymentInfoDto()` returns a `DialApplicationInfoDto`

#### Scenario: Unknown object value skipped
- **WHEN** the unified list entry has an unrecognized `"object"` value
- **THEN** `toDeploymentInfoDto()` returns `null` and the caller logs a WARN and skips the entry

### Requirement: DialTransport enum
A `DialTransport` enum SHALL exist in `client.dialcore.dto` with at least `HTTP`. `DialCoreToolsetDto.transport` and `DialCoreDeploymentDto.transport` SHALL use this enum type. Jackson SHALL use `@JsonCreator` on `DialTransport.fromValue()` and fail fast (throw `IllegalArgumentException`) on unrecognized values.

**Status**: Planned

#### Scenario: Known transport value deserialized
- **WHEN** DIAL response contains `"transport": "HTTP"`
- **THEN** the field deserializes to `DialTransport.HTTP`

#### Scenario: Unknown transport value fails fast
- **WHEN** DIAL response contains `"transport": "GRPC"` (not in enum)
- **THEN** Jackson throws and the request fails with a 500 (not silently ignored)

### Requirement: InterfaceType typed list
`DialCoreDeploymentDto.interfaces` SHALL be `List<InterfaceType>` (using the existing `InterfaceType` enum). Jackson SHALL use `@JsonCreator` on `InterfaceType.fromValue()` and fail fast on unknown values.

**Status**: Planned

#### Scenario: Known interface values deserialized
- **WHEN** DIAL response contains `"interfaces": ["chat", "mcp"]`
- **THEN** the field deserializes to `[InterfaceType.CHAT, InterfaceType.MCP]`

#### Scenario: Unknown interface value fails fast
- **WHEN** DIAL response contains `"interfaces": ["unknown-type"]`
- **THEN** Jackson throws and the request fails (not silently skipped)

## ADDED Requirements

### Requirement: Transport field in unified deployment DTO
`DialCoreDeploymentDto` SHALL include a `transport` field of type `DialTransport` to capture the transport value present on toolset entries in the unified `/v1/deployments` response. The field SHALL be `null` for non-toolset entries (annotated with `@JsonIgnoreProperties(ignoreUnknown = true)` already handles absent fields).

**Status**: Planned

#### Scenario: Toolset transport captured from unified list
- **WHEN** the unified list entry has `"object": "toolset"` and `"transport": "HTTP"`
- **THEN** `DialCoreDeploymentDto.transport` is `DialTransport.HTTP`

#### Scenario: Model entry has null transport
- **WHEN** the unified list entry has `"object": "model"` (no transport field)
- **THEN** `DialCoreDeploymentDto.transport` is `null`
