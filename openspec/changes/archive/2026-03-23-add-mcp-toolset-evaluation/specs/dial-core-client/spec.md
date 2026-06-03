# DIAL Core Client — MCP Extension (Delta)

## ADDED Requirements

### Requirement: Fetch deployments from DIAL Core unified endpoint

The `DialCoreClient` SHALL provide a `getDeployments(interfaceType)` method that calls DIAL Core's `GET /v1/deployments` endpoint with the user's JWT token. When `interfaceType` is provided, the request SHALL include `?interface_type={value}`.

#### Scenario: Fetch all deployments
- **WHEN** `DialCoreClient.getDeployments(null)` is called
- **THEN** the client SHALL call `GET /v1/deployments` with the user's JWT
- **AND** return the list of deployment entries (models, applications, toolsets)

#### Scenario: Fetch MCP-capable deployments
- **WHEN** `DialCoreClient.getDeployments("mcp")` is called
- **THEN** the client SHALL call `GET /v1/deployments?interface_type=mcp`
- **AND** return only MCP-capable deployments (toolsets + applications with MCP interface)

#### Scenario: Fetch chat deployments
- **WHEN** `DialCoreClient.getDeployments("chat")` is called
- **THEN** the client SHALL call `GET /v1/deployments?interface_type=chat`
- **AND** return only chat-capable deployments

#### Scenario: Empty deployment list
- **WHEN** DIAL Core returns an empty list
- **THEN** the client SHALL return an empty list

#### Scenario: Unified endpoint fails
- **WHEN** the call to `/v1/deployments` fails
- **THEN** the existing retry and error mapping logic SHALL apply (same as existing DIAL Core calls)

### Requirement: Fetch single toolset by ID

The `DialCoreClient` SHALL provide a `getToolset(id)` method that calls DIAL Core's `GET /openai/toolsets/{id}` with the user's JWT token.

#### Scenario: Successful toolset retrieval
- **WHEN** `DialCoreClient.getToolset(id)` is called with a valid toolset ID
- **THEN** the client SHALL call `GET /openai/toolsets/{id}` and return the deserialized `DialCoreToolsetDto`

#### Scenario: Toolset not found
- **WHEN** DIAL Core returns HTTP 404 for the toolset ID
- **THEN** the client SHALL throw `DialCoreClientException` mapped to `UPSTREAM_NOT_FOUND`

## MODIFIED Requirements

### Requirement: List all deployments

The system SHALL provide an endpoint to list all available deployments (models, applications, and toolsets) from DIAL Core. The system SHALL call DIAL Core's unified `GET /v1/deployments` endpoint (with optional `interface_type` parameter), transform responses to `DeploymentInfoDto` hierarchy, and return. When `type` query parameter is provided on EF's endpoint, the system SHALL filter the response client-side by deployment type. The `routes` field on every `DialApplicationInfoDto` in the list response SHALL always be `null`.

#### Scenario: Successful deployment listing
- **WHEN** authenticated user sends GET request to `/api/v1/deployments`
- **THEN** system calls DIAL Core `GET /v1/deployments` with user's JWT token
- **AND** transforms responses to `DialModelInfoDto`, `DialApplicationInfoDto`, and `ToolsetInfoDto` based on entry type
- **AND** sets `routes = null` on every `DialApplicationInfoDto`
- **AND** returns merged list with HTTP 200

#### Scenario: Deployment listing with interface filter
- **WHEN** authenticated user sends `GET /api/v1/deployments?interface=mcp`
- **THEN** system calls DIAL Core `GET /v1/deployments?interface_type=mcp`
- **AND** returns only MCP-capable deployments

#### Scenario: Deployment listing with type filter
- **WHEN** authenticated user sends `GET /api/v1/deployments?type=dial-toolset`
- **THEN** system calls DIAL Core `GET /v1/deployments` (without type parameter — DIAL Core does not support type filtering server-side)
- **AND** filters response client-side to include only toolset entries (accepted over-fetch trade-off — see design D7)

#### Scenario: Deployment listing with combined filters
- **WHEN** authenticated user sends `GET /api/v1/deployments?type=dial-application&interface=mcp`
- **THEN** system calls DIAL Core `GET /v1/deployments?interface_type=mcp`
- **AND** filters response to include only application entries

#### Scenario: Deployment listing without authentication
- **WHEN** unauthenticated user sends GET request to `/api/v1/deployments`
- **THEN** system returns HTTP 401 Unauthorized

#### Scenario: DIAL Core endpoint fails
- **WHEN** authenticated user sends GET request to `/api/v1/deployments`
- **AND** DIAL Core returns an error
- **THEN** system returns appropriate error status

> **Note:** This scenario supersedes the baseline's "One DIAL Core endpoint fails" scenario. The baseline called two separate endpoints (`/openai/models` + `/openai/applications`) and had a partial-failure scenario; now a single unified call is made, so failure semantics are straightforward — one call fails, one error returned.

#### Scenario: Unknown deployment type in unified response
- **WHEN** DIAL Core returns an entry with an unrecognized `type` value (neither `model`, `application`, nor `toolset`)
- **THEN** the system SHALL log a warning and skip the entry (not fail the entire listing)
- **AND** the remaining valid entries SHALL be returned normally

#### Scenario: Application with app-level routes in list
- **WHEN** DIAL Core returns an application with non-null `routes`
- **THEN** the list endpoint SHALL still return `routes: null` for that application

### Requirement: Get deployment by type and ID

The system SHALL provide an endpoint to get a single deployment by type and ID. The `deploymentType` path parameter determines which DIAL Core endpoint to call (`/openai/models/{id}` for `dial-model`, `/openai/applications/{id}` for `dial-application`, `/openai/toolsets/{id}` for `dial-toolset`). Path values use kebab-case. For applications with `applicationTypeSchemaId`, the system SHALL resolve effective routes by fetching the schema and merging schema-level routes with app-level routes.

#### Scenario: Successful model retrieval
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-model/{id}`
- **THEN** system calls DIAL Core `/openai/models/{id}` with user's JWT token
- **AND** transforms response to `DialModelInfoDto`
- **AND** returns with HTTP 200

#### Scenario: Successful toolset retrieval
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-toolset/{id}`
- **THEN** system calls DIAL Core `/openai/toolsets/{id}` with user's JWT token
- **AND** transforms response to `ToolsetInfoDto`
- **AND** returns with HTTP 200

#### Scenario: Successful application retrieval with app-level routes only
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-application/{id}`
- **AND** the application has non-null `routes` from DIAL Core
- **AND** the application has no `applicationTypeSchemaId` (or the schema has no routes)
- **THEN** system returns the application with its app-level routes intact

#### Scenario: Successful application retrieval with schema-inherited routes
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-application/{id}`
- **AND** the application has `applicationTypeSchemaId` set and `routes == null`
- **THEN** system SHALL resolve routes from the application type schema via `SchemaRouteExtractor`
- **AND** return the application with the resolved schema routes in the `routes` field

#### Scenario: Application retrieval merges app-level and schema routes
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-application/{id}`
- **AND** the application has `applicationTypeSchemaId` set and non-null `routes`
- **AND** the schema also has `dial:applicationTypeRoutes`
- **THEN** system SHALL return the application with merged routes (schema as base, app-level overrides on conflict)
- **AND** log a warning for each conflicting route key

#### Scenario: Invalid deployment type
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/invalid-type/{id}`
- **THEN** system returns HTTP 400 Bad Request with error message listing valid types: `dial-model`, `dial-application`, `dial-toolset`

#### Scenario: Deployment not found
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-toolset/{id}`
- **AND** DIAL Core returns HTTP 404
- **THEN** system returns HTTP 404 Not Found

### Requirement: DeploymentInfoDto hierarchy

The system SHALL define a `DeploymentInfoDto` abstract base class with polymorphic serialization using `$type` discriminator. Three concrete implementations SHALL be provided: `DialModelInfoDto`, `DialApplicationInfoDto`, and `ToolsetInfoDto`. Type discriminator values SHALL use kebab-case naming (consistent with URL paths).

#### Scenario: Toolset serialized with correct discriminator
- **WHEN** system returns a `ToolsetInfoDto`
- **THEN** JSON contains `"$type": "dial-toolset"`
- **AND** contains toolset-specific fields: `transport`, `allowedTools` (all nullable)

#### Scenario: Common fields present
- **WHEN** system returns any `DeploymentInfoDto`
- **THEN** JSON contains required fields: `deploymentId`, `displayName`, `createdAt`, `updatedAt`
- **AND** nullable fields present if available: `version`, `description`, `owner`, `descriptionKeywords`, `inputAttachmentTypes`

### Requirement: DeploymentType enum

The system SHALL define a `DeploymentType` enum for valid deployment types used in path parameters and JSON discriminators.

#### Scenario: Valid deployment types
- **WHEN** system validates deployment type
- **THEN** accepted values are: `dial-model`, `dial-application`, `dial-toolset` (kebab-case)

## Implementation Notes
- Modified: `DialCoreClient` — add `getDeployments(interfaceType)` method calling `/v1/deployments`; keep `getToolset(id)` for single-item detail
- Modified: `DeploymentService` — replace 3 parallel calls with single `/v1/deployments` call; add type/interface filtering
- Modified: `DeploymentMapper` — add unified response mapping, add toolset mapping methods
- Modified: `DeploymentController` — add `type` and `interface` query params, generalize tools endpoint to `/{type}/{id}/tools`
- New DTO: `DialCoreDeploymentDto` in `client.dialcore.dto` — unified response entry from `/v1/deployments`
- New DTO: `DialCoreToolsetDto` in `client.dialcore.dto` — for single toolset detail
