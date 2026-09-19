## Purpose

Defines the deployments tool group of the Evaluation Framework MCP server: how an agent discovers the DIAL deployments it may evaluate and inspects a single deployment's invocation interfaces before creating a test suite. Cross-cutting behaviour (transport, authentication parity, result and error encoding, agent guidance) is specified by the `mcp-server` capability.

## ADDED Requirements

### Requirement: List deployments tool
The MCP server SHALL expose a tool named `list_deployments` that returns the deployments visible to the caller in DIAL Core. The tool SHALL accept two optional string inputs: `type`, accepting exactly `dial-model`, `dial-application`, `dial-toolset`, and `interfaceType`, accepting exactly `chat`, `embedding`, `mcp`, `custom_ui`, `openaiChatCompletions`, `openaiResponses`, `openaiEmbeddings`, `anthropicMessages`. Both filters SHALL be applied together when both are set. The result SHALL be a JSON object with `deployments` (array of deployment summaries as defined by the "MCP deployment summary" requirement) and `total` (number of returned deployments). The descriptions of both filter parameters SHALL list their accepted values. The tool description SHALL tell the agent to call `get_deployment` on a candidate to see its full detail, and SHALL recommend deployments exposing a chat or completions interface for `DEPLOYMENT` suites.
Status: **Implemented** — `mcp.tools.deployment.DeploymentTools.listDeployments`, mapped via `mcp.mapper.DeploymentMcpMapper.toList`; verified by `McpServerFoundationFunctionalTests` (unfiltered, `type`/`interfaceType` filters, invalid filter value, `tools/list` schema/description contract).

#### Scenario: Unfiltered listing
- **WHEN** an agent calls `list_deployments` with no inputs
- **THEN** the result SHALL contain every model, application and toolset deployment that DIAL Core returns for the caller, each with `deploymentId`, `type` and `interfaces`, and `total` equal to the array length

#### Scenario: Filter by type
- **WHEN** an agent calls `list_deployments` with `type = dial-toolset`
- **THEN** every returned deployment SHALL have `type = dial-toolset` and deployments of other types SHALL be absent

#### Scenario: Filter by interface
- **WHEN** an agent calls `list_deployments` with `interfaceType = mcp`
- **THEN** the result SHALL contain only deployments whose `interfaces` include `mcp`

#### Scenario: Invalid filter value
- **WHEN** an agent calls `list_deployments` with `type = model`
- **THEN** the result SHALL be a tool error in the structured error format with `code = VALIDATION_ERROR` whose message lists the accepted values, and no upstream call SHALL be made

#### Scenario: Filter schema and description advertise accepted values
- **WHEN** an agent reads the `list_deployments` tool from `tools/list`
- **THEN** the input schema SHALL declare `type` and `interfaceType` as optional string properties with non-empty descriptions, and each description SHALL contain every accepted value of that filter

### Requirement: Get deployment tool
The MCP server SHALL expose a tool named `get_deployment` that takes one required string input `deploymentId` and returns the deployment detail for that id as defined by the "MCP deployment detail" requirement, so the agent can decide which request template to use in `create_test_suite`. The tool SHALL resolve the deployment type itself; the caller does not supply it.
Status: **Implemented** — `mcp.tools.deployment.DeploymentTools.getDeployment`, mapped via `mcp.mapper.DeploymentMcpMapper.toDetail`; verified by `McpServerFoundationFunctionalTests` (model/toolset/application detail, unknown id → `NOT_FOUND`, blank id → `VALIDATION_ERROR`, `UPSTREAM_TIMEOUT`/upstream-unreachable cases).

#### Scenario: Existing model deployment
- **WHEN** an agent calls `get_deployment` with the id of a model deployment
- **THEN** the result SHALL have `isError = false` and contain a deployment detail with that `deploymentId`, `type = dial-model`, its `interfaces` and, when DIAL Core reports them, `model.limits` and `model.capabilities`

#### Scenario: Unknown deployment
- **WHEN** an agent calls `get_deployment` with an id DIAL Core does not know
- **THEN** the result SHALL be a tool error with `code = NOT_FOUND` whose message names the deployment id

#### Scenario: Blank identifier
- **WHEN** an agent calls `get_deployment` with an empty or whitespace-only `deploymentId`
- **THEN** the result SHALL be a tool error with `code = VALIDATION_ERROR` and no upstream call SHALL be made

#### Scenario: DIAL Core responds with a gateway timeout
- **WHEN** DIAL Core answers `get_deployment`'s upstream request with HTTP 504
- **THEN** the result SHALL be a tool error with `code = UPSTREAM_TIMEOUT`

#### Scenario: DIAL Core unreachable
- **WHEN** the upstream connection for `get_deployment` fails or times out at the socket level
- **THEN** the result SHALL be a tool error with `code` equal to `UPSTREAM_TIMEOUT` (socket timeout) or `UPSTREAM_ERROR` (connection failure) and a message that does not contain the DIAL Core URL

### Requirement: MCP deployment summary
Deployment summaries returned by `list_deployments` SHALL be MCP-owned and SHALL contain: `deploymentId`, `type` (`dial-model` | `dial-application` | `dial-toolset`), `displayName`, `description`, `interfaces` (array of interface wire values) and, for toolsets, `toolsetTransport`. Summaries SHALL NOT contain detail fields (limits, capabilities, allowed tools, routes, pricing, owner, version, timestamps); agents obtain those from `get_deployment`. Null-valued fields MAY be omitted from the JSON.
Status: **Implemented** — `mcp.model.DeploymentSummaryMcpDto`, built by `mcp.mapper.DeploymentMcpMapper.toSummary`; verified by `DeploymentMcpMapperTest` and `McpServerFoundationFunctionalTests`.

#### Scenario: Summary shape
- **WHEN** `list_deployments` returns a model deployment
- **THEN** the summary SHALL contain `deploymentId`, `type = dial-model`, `displayName` and `interfaces`, and SHALL NOT contain `model`, `owner`, `version`, `createdAt` or `updatedAt`

#### Scenario: Toolset summary carries its transport
- **WHEN** `list_deployments` returns a toolset deployment
- **THEN** the summary SHALL contain `type = dial-toolset` and `toolsetTransport`

### Requirement: MCP deployment detail
Deployment details returned by `get_deployment` SHALL be MCP-owned and SHALL contain: `deploymentId`, `type`, `displayName`, `version`, `description`, `owner`, `interfaces`, `features` (object, may be empty; explicit JSON nulls inside it are not preserved), `createdAt` and `updatedAt` (epoch milliseconds, nullable). Type-specific detail SHALL be nested and present only for the matching type: `model` (`limits.maxTotalTokens`, `limits.maxCompletionTokens`, `capabilities.chatCompletion`, `capabilities.completion`, `capabilities.embeddings`), `toolset` (`transport`, `allowedTools`), `application` (`applicationTypeSchemaId`). Application routes, pricing, icon URLs and attachment types SHALL NOT be included. Null-valued fields MAY be omitted.
Status: **Implemented** — `mcp.model.DeploymentMcpDto` (+ `ModelDetailsMcpDto`/`ToolsetDetailsMcpDto`/`ApplicationDetailsMcpDto`), built by `mcp.mapper.DeploymentMcpMapper.toDetail`; verified by `DeploymentMcpMapperTest` and `McpServerFoundationFunctionalTests`.

#### Scenario: Model detail shape
- **WHEN** `get_deployment` returns a model deployment
- **THEN** the detail SHALL contain `model` and SHALL NOT contain `toolset` or `application`

#### Scenario: Toolset detail shape
- **WHEN** `get_deployment` returns a toolset deployment that declares allowed tools
- **THEN** the detail SHALL contain `toolset.transport` and `toolset.allowedTools` and SHALL NOT contain `model` or `application`

#### Scenario: Operator-facing fields excluded
- **WHEN** `get_deployment` returns an application deployment that has routes and pricing information upstream
- **THEN** the detail SHALL contain `application.applicationTypeSchemaId` when present and SHALL NOT contain `routes`, `pricing`, `iconUrl` or `inputAttachmentTypes`
