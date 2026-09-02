# DIAL Core Client Spec

## Purpose

The DIAL Core Client provides integration with the DIAL Core API, allowing the system to list and retrieve deployments (models and applications) while propagating user authentication and handling upstream errors.

## Requirements

### Requirement: List all deployments

The system SHALL provide an endpoint to list all available deployments (models, applications, and toolsets) from DIAL Core. The system SHALL call DIAL Core's unified `GET /v1/deployments` endpoint (with optional `interface_type` parameter), transform responses to `DeploymentInfoDto` hierarchy, and return. When `type` query parameter is provided on EF's endpoint, the system SHALL filter the response client-side by deployment type.

List entries SHALL be mapped to a **short projection** of the `DeploymentInfoDto` hierarchy — deliberately *not* the full per-type mapping used by the single-entity endpoints — so that the listing payload stays small. `DeploymentMapper.toDeploymentInfoShortDto` SHALL populate only:

- `deploymentId`, `displayName`, `description` — for every subtype (in addition to the `$type` discriminator);
- `transport` — additionally for `ToolsetInfoDto`, because callers pick an MCP transport straight off the list.

Every other field SHALL be left null and therefore SHALL be absent from the JSON (the shared `ObjectMapper` uses `NON_NULL` inclusion): `version`, `owner`, `createdAt`, `updatedAt`, `descriptionKeywords`, `inputAttachmentTypes`, model `capabilities`/`limits`/`pricing`, application `applicationProperties`/`applicationTypeSchemaId`/`routes`, toolset `allowedTools`. Clients needing any of those SHALL fetch the deployment individually via `GET /api/v1/deployments/{deploymentType}/**`, which keeps the full per-type mapping. Schema-route resolution via `SchemaRouteExtractor` likewise remains exclusive to the single-application GET.

#### Scenario: Successful deployment listing
- **WHEN** authenticated user sends GET request to `/api/v1/deployments`
- **THEN** system calls DIAL Core `GET /v1/deployments` with user's JWT token
- **AND** transforms responses to `DialModelInfoDto`, `DialApplicationInfoDto`, and `ToolsetInfoDto` based on entry type
- **AND** maps each entry to the short projection (`deploymentId`, `displayName`, `description`, plus `transport` for toolsets)
- **AND** returns merged list with HTTP 200

#### Scenario: Detail fields omitted from the listing
- **WHEN** DIAL Core returns entries carrying `display_version`, `owner`, timestamps, `descriptionKeywords`, `inputAttachmentTypes`, model `capabilities`/`limits`/`pricing`, application `applicationProperties`/`applicationTypeSchemaId`/`routes`, or toolset `allowedTools`
- **THEN** the listing response SHALL NOT contain those properties for any entry
- **AND** the same deployment fetched via `GET /api/v1/deployments/{deploymentType}/**` SHALL still contain them

#### Scenario: Deployment listing with interface filter
- **WHEN** authenticated user sends `GET /api/v1/deployments?interface=mcp`
- **THEN** system calls DIAL Core `GET /v1/deployments?interface_type=mcp`
- **AND** returns only MCP-capable deployments

#### Scenario: Deployment listing with type filter
- **WHEN** authenticated user sends `GET /api/v1/deployments?type=dial-toolset`
- **THEN** system calls DIAL Core `GET /v1/deployments` (without type parameter — DIAL Core does not support type filtering server-side)
- **AND** filters response client-side to include only toolset entries

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

#### Scenario: Unknown deployment type in unified response
- **WHEN** DIAL Core returns an entry with an unrecognized `object` value (neither `"model"`, `"application"`, nor `"toolset"`)
- **THEN** the system SHALL log a warning and skip the entry (not fail the entire listing)
- **AND** the remaining valid entries SHALL be returned normally

#### Scenario: Application with app-level routes in list
- **WHEN** DIAL Core returns an application with non-null `routes` in the unified list
- **THEN** the list endpoint SHALL omit `routes` (routes are a detail-endpoint concern; neither pass-through nor schema-route resolution happens in the list path)

---

### Requirement: Get deployment by type and ID

The system SHALL provide an endpoint to get a single deployment by type and ID, mapped as `GET /api/v1/deployments/{deploymentType}/**`. The `deploymentType` path parameter determines which DIAL Core endpoint to call (`/openai/models/{id}` for `dial-model`, `/openai/applications/{id}` for `dial-application`, `/openai/toolsets/{id}` for `dial-toolset`). Path values use kebab-case. For applications with `applicationTypeSchemaId`, the system SHALL resolve effective routes by fetching the schema and merging schema-level routes with app-level routes.

Everything after the `{deploymentType}` segment is the deployment ID, so DIAL Core IDs that contain slashes (e.g. `applications/public/Quick App with RAG__0.0.1`) SHALL be accepted verbatim as path segments — a plain `@PathVariable` cannot capture them. The ID SHALL be resolved from the wildcard tail by `web.path.WildcardPathResolver` and decoded **exactly once** there, in the web layer: `%20` becomes a space, an intra-segment `%2F` becomes a slash, and `+` is preserved literally (a plus sign in a URL path component is not a space — hence `UriUtils.decode`, not `java.net.URLDecoder`). `DialCoreClient` therefore receives an already-decoded ID and its `RestClient` performs the single wire encoding; the client itself SHALL NOT decode or pre-encode deployment IDs.

Implementation notes:
- The resolver reads `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` + `PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE` and extracts the wildcard tail via `AntPathMatcher.extractPathWithinPattern`, falling back to the request URI minus the servlet context path. It is a generic web-layer component, not deployment-specific.
- `WildcardPathResolver` returns an empty string (never `null`) when the request carries no tail; the emptiness check and its 400 belong to the controller.
- Encoded slashes (`%2F`) are supported by the resolver, but Tomcat rejects them in request paths by default (`ALLOW_ENCODED_SLASH=false`), so they are not reachable end-to-end without changing that connector setting.
Status: **Implemented**

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

#### Scenario: Slash-containing deployment ID
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-application/applications/public/my-app__0.0.1`
- **THEN** the resolved deployment ID SHALL be `applications/public/my-app__0.0.1` (all segments after the type preserved)
- **AND** system SHALL call `DialCoreClient.getApplication("applications/public/my-app__0.0.1")`

#### Scenario: Percent-encoded deployment ID decoded once
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-application/applications/public/Quick%20App%20with%20RAG__0.0.1`
- **THEN** the resolved deployment ID SHALL be `applications/public/Quick App with RAG__0.0.1`
- **AND** a double-encoded input (`Quick%2520App__0.0.1`) SHALL resolve to the single-encoded value `Quick%20App__0.0.1`, never to the raw one

#### Scenario: Plus sign in deployment ID preserved
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-model/gpt-5+preview`
- **THEN** the resolved deployment ID SHALL be `gpt-5+preview` (the `+` is NOT decoded to a space)

#### Scenario: Empty deployment ID
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-model/` (or `/api/v1/deployments/dial-model` with no tail)
- **THEN** system SHALL return HTTP 400 with `VALIDATION_ERROR` and message `Deployment ID must not be empty`
- **AND** SHALL NOT call DIAL Core

#### Scenario: Malformed percent-encoding in deployment ID
- **WHEN** the wildcard tail contains an invalid escape sequence (e.g. `broken%2`)
- **THEN** system SHALL return HTTP 400 with `VALIDATION_ERROR` rather than a 500

#### Scenario: Sibling `/tools` mapping takes precedence over the wildcard
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/tools?deploymentId=…`
- **THEN** the exact `/tools` mapping SHALL handle the request
- **AND** the request SHALL NOT be routed to the by-ID wildcard handler (which would reject `tools` as an invalid deployment type)

#### Scenario: Invalid deployment type
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/invalid-type/{id}`
- **THEN** system returns HTTP 400 Bad Request with error message listing valid types: `dial-model`, `dial-application`, `dial-toolset`

#### Scenario: Deployment not found
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/dial-toolset/{id}`
- **AND** DIAL Core returns HTTP 404
- **THEN** system returns HTTP 502 with error code `UPSTREAM_NOT_FOUND` (per the DIAL Core error mapping — a missing upstream deployment is reported as an upstream condition, not as a 404 on this service's own resource)

---

### Requirement: Get deployment by ID across all types

The system SHALL provide an endpoint to get a single deployment by ID **without** the caller supplying its type, mapped as `GET /api/v1/deployments/all/**`. Everything after the `all` segment is the deployment ID and SHALL be resolved and decoded exactly once by the same web-layer wildcard resolution as the typed endpoint, so slash-containing and percent-encoded IDs behave identically on both endpoints. An empty ID SHALL be rejected with HTTP 400 `VALIDATION_ERROR` and message `Deployment ID must not be empty` without any upstream call.

For a non-empty ID the system SHALL probe all three DIAL Core deployment endpoints — `/openai/models/{id}`, `/openai/applications/{id}`, `/openai/toolsets/{id}` — **concurrently**, propagating the caller's JWT to every probe, so a lookup costs one round-trip of latency rather than three. Each probe yields one of: a **hit** (2xx with a non-empty body), a **miss** (2xx with an empty body), or an **error** (a non-2xx upstream status, or an unreachable endpoint — a transport or response-conversion failure, which carries no upstream status and SHALL be recorded as a 502-class error). A failing probe SHALL NOT prevent a sibling probe's hit from being returned, whatever the failure's type.

Outcome collapsing SHALL follow exactly two exit paths:

1. **At least one hit** — the system SHALL return that deployment with HTTP 200, in the same representation the typed endpoint returns for it, including the `$type` discriminator identifying which type was found and, for an application, its resolved effective `routes`. Errors on the other probes SHALL NOT affect the response beyond being logged. When more than one probe hits (an ID collision DIAL Core is not expected to produce), the winner SHALL be chosen by the fixed precedence `dial-model` > `dial-application` > `dial-toolset`, and the collision SHALL be logged at WARN naming the ID and the colliding types.
2. **No hit** — the probe failures SHALL be unified into a **single** upstream error whose status is the highest-severity status observed, ordered `401` > `403` > any other error status > `404`, and whose message names each probe's outcome. The unified error SHALL then map to the client response through the service's existing DIAL Core error mapping, introducing no new error codes. A no-hit lookup with no errors at all (all three probes returning empty bodies) SHALL be treated as an all-404 no-hit.

Severity ordering is normative, not cosmetic: an authentication failure or an upstream outage on one probe SHALL NOT be reported as a not-found result merely because the other probes returned 404.

The literal `all` mapping SHALL take precedence over the sibling type-capturing wildcard mapping, so `/api/v1/deployments/all/{id}` SHALL never be interpreted as a deployment of type `all`.

Implementation notes:
- Endpoint: `web.controller.DeploymentController#getDeploymentById` (mapping `/all/**`); ID resolved by `web.path.WildcardPathResolver`, empty-ID guard shared with the by-type handler.
- Fan-out: `service.domain.DeploymentService#getDeployment(String)` — three `DialCoreClient` probes on `Context.taskWrapping(Executors.newVirtualThreadPerTaskExecutor())`, each wrapped in `runner.util.TokenPropagationHelper.withToken` with the token captured on the request thread.
- Collapse rules: `service.domain.DeploymentProbeCollapser` over `service.domain.DeploymentProbe` outcomes; the unified failure is a `DialCoreClientException` mapped by `runner.client.dialcore.DialCoreErrorMapper` + `web.handler.DefaultExceptionHandler`.
- The winning payload is mapped by the same private mapping the by-type path uses, so an application winner passes through `SchemaRouteExtractor.resolveRoutes` and a losing one never does.

Status: **Implemented**

#### Scenario: Model found by ID alone
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/gpt-5`
- **AND** only the models probe returns a deployment
- **THEN** the system SHALL return HTTP 200 with the same body the typed model endpoint returns for `gpt-5`
- **AND** the body's `$type` SHALL be `dial-model`

#### Scenario: Application found by ID alone, with resolved routes
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/{id}` for an application that inherits routes from its application type schema
- **AND** only the applications probe returns a deployment
- **THEN** the system SHALL return HTTP 200 with `$type` `dial-application`
- **AND** the `routes` field SHALL be resolved exactly as on the typed application endpoint

#### Scenario: Toolset found by ID alone
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/{id}` for a toolset
- **AND** only the toolsets probe returns a deployment
- **THEN** the system SHALL return HTTP 200 with `$type` `dial-toolset`

#### Scenario: Probes run concurrently with the caller's token
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/{id}`
- **THEN** all three upstream lookups SHALL be issued concurrently
- **AND** every one of them SHALL carry the caller's JWT, so the result reflects only deployments DIAL Core grants that caller

#### Scenario: One probe errors while another hits
- **WHEN** the applications probe returns a deployment
- **AND** the models probe fails with HTTP 500
- **THEN** the system SHALL return HTTP 200 with the application
- **AND** the failed probe SHALL be logged without affecting the response

#### Scenario: A probe that cannot reach DIAL Core does not discard a hit
- **WHEN** one probe fails with a transport failure (e.g. a read timeout, carrying no upstream status)
- **AND** another probe returns a deployment
- **THEN** the system SHALL return HTTP 200 with that deployment
- **AND** when no probe returns a deployment, the transport failure SHALL outrank the other probes' 404s and yield HTTP 502 with error code `UPSTREAM_ERROR`

#### Scenario: Two probes hit — precedence decides
- **WHEN** both the models probe and the applications probe return a deployment for the same ID
- **THEN** the system SHALL return the model
- **AND** SHALL log a WARN naming the ID and the colliding types

#### Scenario: Not found anywhere
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/{id}`
- **AND** all three probes return HTTP 404
- **THEN** the system SHALL return HTTP 502 with error code `UPSTREAM_NOT_FOUND`
- **AND** the message SHALL name each probe's outcome

#### Scenario: Auth failure outranks not-found
- **WHEN** the models probe fails with HTTP 401
- **AND** the applications and toolsets probes return HTTP 404
- **THEN** the system SHALL return HTTP 502 with error code `UPSTREAM_AUTH_ERROR`, never `UPSTREAM_NOT_FOUND`

#### Scenario: Access denied outranks not-found
- **WHEN** the applications probe fails with HTTP 403
- **AND** the other probes return HTTP 404
- **THEN** the system SHALL return HTTP 403 with error code `ACCESS_DENIED`

#### Scenario: Upstream failure outranks not-found
- **WHEN** the toolsets probe fails with HTTP 500
- **AND** the other probes return HTTP 404
- **THEN** the system SHALL return HTTP 502 with error code `UPSTREAM_ERROR`

#### Scenario: Slash-containing deployment ID
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/applications/public/my-app__0.0.1`
- **THEN** the resolved deployment ID SHALL be `applications/public/my-app__0.0.1` (all segments after `all` preserved)
- **AND** every probe SHALL be issued with that exact ID

#### Scenario: Percent-encoded deployment ID decoded once
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/applications/public/Quick%20App%20with%20RAG__0.0.1`
- **THEN** the resolved deployment ID SHALL be `applications/public/Quick App with RAG__0.0.1`

#### Scenario: Empty deployment ID
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/` (or `/api/v1/deployments/all` with no tail)
- **THEN** the system SHALL return HTTP 400 with `VALIDATION_ERROR` and message `Deployment ID must not be empty`
- **AND** SHALL NOT issue any upstream call

#### Scenario: `all` is not treated as a deployment type
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/{id}`
- **THEN** the type-less by-ID handler SHALL handle the request
- **AND** the request SHALL NOT be routed to the by-type wildcard handler (which would reject `all` as an invalid deployment type)
---

### Requirement: DeploymentInfoDto hierarchy

The system SHALL define a `DeploymentInfoDto` abstract base class with polymorphic serialization using `$type` discriminator. Three concrete implementations SHALL be provided: `DialModelInfoDto`, `DialApplicationInfoDto`, and `ToolsetInfoDto`. Type discriminator values SHALL use kebab-case naming (consistent with URL paths).

#### Scenario: Model serialized with correct discriminator
- **WHEN** system returns a `DialModelInfoDto`
- **THEN** JSON contains `"$type": "dial-model"`
- **AND** contains model-specific fields: `capabilities`, `limits`, `pricing` (all nullable)

#### Scenario: Application serialized with correct discriminator
- **WHEN** system returns a `DialApplicationInfoDto`
- **THEN** JSON contains `"$type": "dial-application"`
- **AND** contains application-specific fields: `applicationTypeSchemaId`, `applicationProperties`, `routes` (all nullable)

#### Scenario: Toolset serialized with correct discriminator
- **WHEN** system returns a `ToolsetInfoDto`
- **THEN** JSON contains `"$type": "dial-toolset"`
- **AND** contains toolset-specific fields: `transport`, `allowedTools` (all nullable)

#### Scenario: Common fields present
- **WHEN** system returns any `DeploymentInfoDto` from a single-deployment endpoint
- **THEN** JSON contains required fields: `deploymentId`, `displayName`, `createdAt`, `updatedAt`
- **AND** nullable fields present if available: `version`, `description`, `owner`, `descriptionKeywords`, `inputAttachmentTypes`

#### Scenario: Common fields on a listing entry
- **WHEN** system returns a `DeploymentInfoDto` as an entry of `GET /api/v1/deployments`
- **THEN** JSON contains only `$type`, `deploymentId`, `displayName`, `description` (and `transport` for `dial-toolset`)
- **AND** `createdAt`/`updatedAt` are absent — they are not guaranteed on listing entries

---

### Requirement: Typed routes in DialApplicationInfoDto

The system SHALL represent `routes` in `DialApplicationInfoDto` as `Map<String, ApplicationRouteDto>` with fully typed nested DTOs.

#### Scenario: Routes serialized with full structure

- **WHEN** system returns a `DialApplicationInfoDto` with routes
- **THEN** each route contains typed fields: `name`, `paths`, `methods`, `upstreams`, `maxRetryAttempts`, `order`, `permissions`
- **AND** `upstreams` is a list of `RouteUpstreamDto` with: `endpoint`, `extraData`, `weight`, `tier`
- **AND** `attachmentPaths` contains `requestBody` and `responseBody` lists
- **AND** `response` (if present) contains `status` and `body`

#### Scenario: Route example structure

- **WHEN** system returns application with route "v1"
- **THEN** route structure matches:
```json
{
  "name": "v1",
  "userRoles": null,
  "response": null,
  "rewritePath": true,
  "paths": ["/v1/.*"],
  "methods": ["DELETE", "GET"],
  "upstreams": [{
    "endpoint": "http://my-endpoint.svc.cluster.local",
    "extraData": null,
    "weight": 1,
    "tier": 0
  }],
  "maxRetryAttempts": 1,
  "order": 2147483647,
  "permissions": [],
  "attachmentPaths": {
    "requestBody": [],
    "responseBody": []
  }
}
```

---

### Requirement: Fetch deployments from DIAL Core unified endpoint

The `DialCoreClient` SHALL provide a `getDeployments(interfaceType)` method that calls DIAL Core's `GET /v1/deployments` endpoint with the user's JWT token. When `interfaceType` is provided, the request SHALL include `?interface_type={value}`. The response SHALL be deserialized as a bare JSON array (`List<DialCoreDeploymentDto>`) — DIAL Core returns a top-level array, not a wrapped object. `DialCoreDeploymentListResponseDto` SHALL NOT exist; `DeploymentService` SHALL consume `List<DialCoreDeploymentDto>` directly without a `getData()` call.
Status: **Implemented**

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

---

### Requirement: Fetch single toolset by ID

The `DialCoreClient` SHALL provide a `getToolset(id)` method that calls DIAL Core's `GET /openai/toolsets/{id}` with the user's JWT token.
Status: **Implemented**

#### Scenario: Successful toolset retrieval
- **WHEN** `DialCoreClient.getToolset(id)` is called with a valid toolset ID
- **THEN** the client SHALL call `GET /openai/toolsets/{id}` and return the deserialized `DialCoreToolsetDto`

#### Scenario: Toolset not found
- **WHEN** DIAL Core returns HTTP 404 for the toolset ID
- **THEN** the client SHALL throw `DialCoreClientException` mapped to `UPSTREAM_NOT_FOUND`

---

### Requirement: Token propagation

The system SHALL propagate the user's JWT token from incoming requests to DIAL Core. The token MUST be extracted from the `Authorization: Bearer` header and forwarded to DIAL Core in the same format. This ensures DIAL Core filters deployments based on the user's access rights - each user sees only deployments they are authorized to access.

#### Scenario: Token is propagated to DIAL Core

- **WHEN** user sends request with `Authorization: Bearer <token>` header
- **THEN** system forwards the same token to DIAL Core in `Authorization: Bearer <token>` header
- **AND** DIAL Core returns only deployments the user is authorized to access

#### Scenario: Missing authorization header

- **WHEN** user sends request without `Authorization` header
- **AND** security is enabled
- **THEN** system rejects request with HTTP 401 before calling DIAL Core

#### Scenario: User with limited access

- **WHEN** user with restricted permissions requests deployments
- **THEN** DIAL Core filters response to include only authorized deployments
- **AND** system returns this filtered list without modification

---

### Requirement: TokenPropagationHelper for async operations

The system SHALL provide a `TokenPropagationHelper` utility class for propagating the user's authorization token to new threads. When code executes asynchronously (e.g., via `CompletableFuture.supplyAsync()`), the new thread does not have access to the request thread's ThreadLocal token. This helper MUST be used whenever spawning async tasks that need user context.

#### Scenario: Async code needs user token

- **WHEN** service spawns async tasks (e.g., `CompletableFuture.supplyAsync()`)
- **AND** async code needs to make authenticated calls (e.g., to DIAL Core)
- **THEN** service MUST capture token before spawning: `String token = AuthorizationTokenHolder.getToken()`
- **AND** wrap async supplier with: `TokenPropagationHelper.withToken(token, () -> { ... })`

#### Scenario: Token cleanup after async execution

- **WHEN** wrapped async task completes (success or failure)
- **THEN** `TokenPropagationHelper` clears the token from the async thread's ThreadLocal
- **AND** prevents token leakage to subsequent tasks on pooled threads

#### Scenario: Usage pattern

- **WHEN** implementing parallel async operations with user context
- **THEN** follow this pattern:
```java
// Capture token in request thread before spawning async tasks
String token = AuthorizationTokenHolder.getToken();

CompletableFuture.supplyAsync(TokenPropagationHelper.withToken(token, () -> {
    // Token is available here via AuthorizationTokenHolder.getToken()
    return dialCoreClient.getModels();
}));
```

#### Scenario: Helper variants

- **WHEN** different async patterns are needed
- **THEN** `TokenPropagationHelper` provides:
  - `withToken(token, Supplier<T>)` - for `CompletableFuture.supplyAsync()`
  - `withTokenCallable(token, Callable<T>)` - for `ExecutorService.submit(Callable)`
  - `withTokenRunnable(token, Runnable)` - for `ExecutorService.submit(Runnable)`

---

### Requirement: Retry on transient failures

The system SHALL retry requests to DIAL Core when transient failures occur. Retryable failures include HTTP status codes 408 (Request Timeout), 429 (Too Many Requests), 500, 502, 503, and 504. The system SHALL use exponential backoff between retries.

#### Scenario: Successful retry after transient failure

- **WHEN** request to DIAL Core fails with HTTP 503
- **AND** retry attempt succeeds
- **THEN** system returns successful response to client

#### Scenario: All retries exhausted

- **WHEN** request to DIAL Core fails with HTTP 503
- **AND** all retry attempts (default: 3) fail
- **THEN** system returns HTTP 502 Bad Gateway with error details

#### Scenario: Non-retryable error

- **WHEN** request to DIAL Core fails with HTTP 401 or 403
- **THEN** system does NOT retry and returns the error immediately

---

### Requirement: Error mapping

The system SHALL map DIAL Core errors to appropriate HTTP responses for clients. Upstream errors that indicate a service-to-service or upstream configuration issue SHALL be returned as HTTP 502 Bad Gateway with a specific error code so clients do not misinterpret them as client-side failures (e.g., invalid credentials or invalid endpoint).

#### Scenario: DIAL Core returns 401

- **WHEN** DIAL Core returns HTTP 401 (e.g., token rejected by Core after our service accepted it)
- **THEN** system returns HTTP 502 Bad Gateway with error code `UPSTREAM_AUTH_ERROR`
- **AND** response body indicates the failure originated from the upstream service

#### Scenario: DIAL Core returns 403

- **WHEN** DIAL Core returns HTTP 403
- **THEN** system returns HTTP 403 Forbidden with error code `ACCESS_DENIED`
- **AND** client may interpret this as resource-level access denial (e.g., no permission to use this deployment in Core)

#### Scenario: DIAL Core not found error

- **WHEN** DIAL Core returns HTTP 404 (resource not found in Core)
- **THEN** system returns HTTP 502 Bad Gateway with error code `UPSTREAM_NOT_FOUND`
- **AND** response body indicates the failure originated from the upstream service

#### Scenario: DIAL Core client error

- **WHEN** DIAL Core returns HTTP 4xx (other than 401, 403, 404)
- **THEN** system returns HTTP 400 Bad Request with error details

#### Scenario: DIAL Core server error

- **WHEN** DIAL Core returns HTTP 5xx (other than 504) after all retries
- **THEN** system returns HTTP 502 Bad Gateway with error code `UPSTREAM_ERROR` and error details

#### Scenario: DIAL Core timeout or 504

- **WHEN** DIAL Core returns HTTP 504 or connection/read to DIAL Core times out
- **THEN** system returns HTTP 504 Gateway Timeout with error code `UPSTREAM_TIMEOUT`

#### Scenario: Upstream error codes clarify failure source

- **WHEN** system returns an error due to DIAL Core (upstream) failure
- **THEN** response uses one of: `UPSTREAM_AUTH_ERROR` (502), `UPSTREAM_NOT_FOUND` (502), `UPSTREAM_ERROR` (502), or `UPSTREAM_TIMEOUT` (504) so clients understand the failure is on the upstream side, not this service

---

### Requirement: Configuration

The system SHALL support configuration of DIAL Core client settings via application properties.

#### Scenario: Configure base URL

- **WHEN** `dial.components.core.base-url` is set
- **THEN** system uses configured URL for DIAL Core requests

#### Scenario: Configure timeouts

- **WHEN** `dial.components.core.connect-timeout-ms` and `dial.components.core.read-timeout-ms` are set
- **THEN** system uses configured timeout values

#### Scenario: Configure retry settings

- **WHEN** retry properties are configured (`max-attempts`, `delay-ms`, `multiplier`)
- **THEN** system uses configured retry behavior

#### Scenario: Default configuration

- **WHEN** no explicit configuration is provided
- **THEN** system uses sensible defaults (base-url: localhost:8080, connect-timeout: 5s, read-timeout: 30s, max-attempts: 3)

---

### Requirement: OpenAPI documentation

The system SHALL expose OpenAPI documentation for the deployment endpoints, including polymorphic response schemas.

#### Scenario: Swagger UI shows endpoints

- **WHEN** user navigates to Swagger UI
- **THEN** deployment endpoints are visible with descriptions and response examples

#### Scenario: Response schema shows polymorphic types

- **WHEN** user views endpoint documentation
- **THEN** response schema shows `DeploymentInfoDto` with `$type` discriminator and both subtypes documented

#### Scenario: Wildcard deployment ID documented on the by-ID operation

- **WHEN** user views the `GET /api/v1/deployments/{deploymentType}/**` operation
- **THEN** its description SHALL state that everything after the type segment is the deployment ID, that slash-containing IDs are supported as-is, and that percent-encoded characters are decoded once
- **AND** the 400 response description SHALL cover invalid deployment type, empty deployment ID, and malformed percent-encoding

**Implementation note:** the deployment ID is no longer a `@PathVariable` (it is resolved from the wildcard tail), so the operation description is the only place it is documented for clients.

---

### Requirement: Response field mapping

The system SHALL map DIAL Core response fields to `DeploymentInfoDto` fields as follows:

| DIAL Core Field | DeploymentInfoDto Field | Nullable |
|-----------------|-------------------------|----------|
| `id` | `deploymentId` | No |
| `display_name` | `displayName` | No |
| `display_version` | `version` | Yes |
| `description` | `description` | Yes |
| `owner` | `owner` | Yes |
| `created_at` | `createdAt` | No |
| `updated_at` | `updatedAt` | No |
| `description_keywords` | `descriptionKeywords` | Yes |
| `input_attachment_types` | `inputAttachmentTypes` | Yes |

#### Scenario: Fields correctly mapped

- **WHEN** DIAL Core returns model/application with fields
- **THEN** system maps to corresponding `DeploymentInfoDto` fields using camelCase naming

#### Scenario: Nullable field absent in response

- **WHEN** DIAL Core response does not include optional field (e.g., `description`)
- **THEN** corresponding field is null in response (not empty string or empty array)

---

### Requirement: DeploymentType enum

The system SHALL define a `DeploymentType` enum for valid deployment types used in path parameters and JSON discriminators.

#### Scenario: Valid deployment types
- **WHEN** system validates deployment type
- **THEN** accepted values are: `dial-model`, `dial-application`, `dial-toolset` (kebab-case)

#### Scenario: Enum serialization
- **WHEN** `DeploymentType` is serialized to JSON or used in URL
- **THEN** value is kebab-case (e.g., `"dial-model"`)

#### Scenario: Consistency between URL and JSON
- **WHEN** client uses deployment type in URL path
- **AND** receives response with `$type` discriminator
- **THEN** both values use the same format (kebab-case)

---

### Requirement: Deployment invocation via DialCoreDeploymentInvoker
The system SHALL provide a `DialCoreDeploymentInvoker` component in the `client.dialcore` package for invoking DIAL Core deployment endpoints. This component is separate from the existing `DialCoreClient` (which handles metadata retrieval only). The invoker SHALL have its own `RestClient` bean with a configurable read timeout (default 120s) and no retry logic.

The invoker SHALL return a `DeploymentInvocationResponse` record (defined in `client.dialcore`) containing `int statusCode` and `Object body` (nullable). This keeps the client-layer return type within the client package, preserving the layering rule that `client.*` does not depend on `service.*`. The service layer maps this to `TryItOutCoreResponseDto` when building the final response.

The invoker's `invoke()` method SHALL accept Spring/JDK types only: `HttpHeaders` for headers and `MultiValueMap<String, String>` for query params. This avoids importing `KeyValueTemplateDto` (a service-layer DTO in `service.domain.dto`) into the client package — consistent with the existing `DialCoreClient` which has zero imports from `service.*`. The service layer (`TryItOutService`) is responsible for converting `List<KeyValueTemplateDto>` from `ResolvedRequestDto` into `HttpHeaders`/`MultiValueMap<String, String>` before calling the invoker.

#### Scenario: Invoke deployment with POST
- **WHEN** service calls `DialCoreDeploymentInvoker.invoke()` with HTTP method POST, a relative path, headers (`HttpHeaders`), query params (`MultiValueMap<String, String>`), and a JSON body
- **THEN** the invoker SHALL send the request to `{coreBaseUrl}{path}` using its dedicated `RestClient`
- **AND** set `Content-Type: application/json` on the outgoing request when a body is present
- **AND** return `DeploymentInvocationResponse` with the response status code and body

**Note:** Headers use Spring `HttpHeaders` and query params use `MultiValueMap<String, String>` — both support duplicate keys natively (important for HTTP headers like `Set-Cookie`). The service layer converts `List<KeyValueTemplateDto>` from `ResolvedRequestDto` into these types before calling the invoker.

#### Scenario: Invoke deployment with GET
- **WHEN** service calls `DialCoreDeploymentInvoker.invoke()` with HTTP method GET and a relative path
- **THEN** the invoker SHALL send a GET request without a body to `{coreBaseUrl}{path}`
- **AND** return `DeploymentInvocationResponse` with the response status code and body

#### Scenario: Non-POST methods with request body
- **WHEN** service calls `DialCoreDeploymentInvoker.invoke()` with HTTP method GET (or DELETE, HEAD, OPTIONS) and a non-null `body` parameter
- **THEN** the invoker SHALL ignore the body and send the request without a body
- **AND** only include a request body for methods POST, PUT, and PATCH

#### Scenario: Authorization token propagated
- **WHEN** invoker sends a request to DIAL Core
- **THEN** the user's JWT token from `AuthorizationTokenHolder` SHALL be included as `Authorization: Bearer` header (via RestClient interceptor)

#### Scenario: Content-Type for request body
- **WHEN** invoker sends a request with a body (POST, PUT, PATCH)
- **THEN** the invoker SHALL set `Content-Type: application/json` on the outgoing request

#### Scenario: Custom headers from template
- **WHEN** invoker receives custom headers via `HttpHeaders` (e.g., resolved from template by the service layer)
- **THEN** those headers SHALL be added to the outgoing request alongside the authorization header

#### Scenario: Query parameters appended
- **WHEN** invoker receives query parameters via `MultiValueMap<String, String>`
- **THEN** those parameters SHALL be appended to the request URL

#### Scenario: DIAL Core returns error
- **WHEN** DIAL Core returns HTTP 4xx or 5xx
- **THEN** the invoker SHALL NOT throw an exception
- **AND** SHALL return the response status code and body as-is (for proxy behavior)

#### Scenario: Connection failure
- **WHEN** the connection to DIAL Core fails (refused, DNS error)
- **THEN** the invoker SHALL catch `ResourceAccessException`, inspect the cause chain, and if the root cause is NOT a `SocketTimeoutException` (e.g., `ConnectException`, `UnknownHostException`, or other I/O errors), throw `DialCoreClientException` with `HttpStatus.BAD_GATEWAY` (502)

#### Scenario: Read timeout
- **WHEN** DIAL Core does not respond within the configured read timeout
- **THEN** the invoker SHALL catch `ResourceAccessException`, inspect the cause chain, and if the root cause IS a `SocketTimeoutException`, throw `DialCoreClientException` with `HttpStatus.GATEWAY_TIMEOUT` (504)

#### Scenario: Response body parsing
- **WHEN** DIAL Core returns a response body
- **THEN** the invoker SHALL read the response body as a raw `String`
- **AND** attempt to parse it as JSON using `ObjectMapper.readValue(body, Object.class)`
- **AND** if JSON parsing succeeds, return the parsed object (Map, List, String, Number, Boolean, or null) as the `body` field
- **AND** if JSON parsing fails (e.g., HTML error page, plain text), return the raw string as the `body` field

---

### Requirement: DialCoreDeploymentInvoker configuration
The invoker SHALL use a separate configuration from the metadata client, allowing independent timeout tuning.

#### Scenario: Separate RestClient bean
- **WHEN** the application starts
- **THEN** a dedicated `RestClient` bean (e.g., `dialCoreTryOutRestClient`) SHALL be created with the try-it-out read timeout
- **AND** it SHALL share the same base URL and authorization interceptor as the metadata client

**Implementation note:** The authorization token interceptor logic (reading from `AuthorizationTokenHolder` and setting `Authorization: Bearer` header) is currently a package-private static method in `DialCoreClientConfiguration`. Both configuration classes are in `client.dialcore` and share this interceptor.

#### Scenario: Default timeout
- **WHEN** `dial.components.core.try-out.read-timeout-ms` is not set
- **THEN** the invoker's RestClient SHALL use 120000ms as the read timeout

#### Scenario: Connect timeout shared
- **WHEN** the invoker makes a connection
- **THEN** it SHALL use the same `dial.components.core.connect-timeout-ms` as the metadata client

---

### Requirement: Deployment invocation paths encoded exactly once on the wire

The DIAL Core deployment client SHALL ensure that the path component of every deployment invocation request (`GET`/`POST`/`PUT`/`PATCH`/`DELETE` to `/openai/deployments/{id}/…` and `/v1/deployments/{id}/route/…`) is URL-encoded **exactly once** on the wire, regardless of whether the `deploymentRef.id` arrives pre-encoded (the form DIAL Core's `GET /v1/deployments` returns) or as a raw literal value.

Concretely, for an application id whose canonical DIAL Core resource URL is `applications/public/Quick App with RAG__0.0.1`, the client SHALL produce the wire path `/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/{relativePath}` — never `Quick%2520App…` (double-encoded) and never `Quick App…` (unencoded, which the underlying HTTP client would reject as illegal URI).

The behaviour SHALL be idempotent under repeated decoding: feeding either `Quick App with RAG__0.0.1` or `Quick%20App%20with%20RAG__0.0.1` as the segment to the client MUST result in the identical wire path. Inner `/` characters within a single id segment, if any, SHALL be encoded as `%2F` (per-segment encoding), consistent with DIAL Core's `UrlUtil.encodePathSegment` contract.

Status: Implemented.

Implementation notes:
- Encoding is centralized in `client.dialcore.DialCoreDeploymentInvoker` so that every caller of the invoker (e.g. `service.domain.TryItOutService`, `service.domain.job.EvaluationWorker`) benefits without per-caller awareness of the encoding contract.
- Mirrors the pattern already used by `client.mcp.McpToolInvoker.buildMcpEndpoint` / `buildSseEndpoint` — decode each segment once, then use `UriComponentsBuilder.pathSegment(...).build().encode()`.
- The **metadata** client (`DialCoreClient`, `/openai/{models|applications|toolsets}/{id}`) is deliberately NOT part of this contract: it receives an already-decoded deployment ID (decoded once in the web layer by `WildcardPathResolver`, see "Get deployment by type and ID") and passes it to its `RestClient`, which applies the single wire encoding. `DialCoreClient` SHALL NOT decode or pre-encode paths itself — doing so at the client level would also corrupt the query strings it builds (`?interface_type=`, `?id=<schema URL>`).

#### Scenario: Public application id with spaces in display name

- **GIVEN** DIAL Core returns a deployment with `id = "applications/public/Quick%20App%20with%20RAG__0.0.1"` from `GET /v1/deployments`
- **AND** a test suite stores this id verbatim in `deployment_ref.id`
- **WHEN** Try Out or an evaluation run invokes the deployment via `POST /v1/deployments/{id}/route/chat/completions`
- **THEN** the wire request path SHALL be `/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/chat/completions`
- **AND** the wire path SHALL NOT contain `%2520`

#### Scenario: Public application id with parentheses or other reserved characters

- **GIVEN** DIAL Core returns a deployment with `id = "applications/public/Quick%20App%20(v2)__0.0.1"`
- **WHEN** the client invokes the deployment
- **THEN** the wire request path SHALL contain `Quick%20App%20(v2)__0.0.1` — the space is single-encoded as `%20`, and `(`/`)` are preserved literally because RFC 3986 lists them as `sub-delims` and therefore as valid `pchar` characters in a path segment (Spring's `HierarchicalUriComponents$Type.PATH_SEGMENT` rule honors this and does not percent-encode sub-delims)
- **AND** the wire path SHALL NOT contain any double-encoded form — i.e., no `%2520` (double-encoded space) and no `%2528`/`%2529` (double-encoded parentheses)

#### Scenario: Metadata fetch for a slash-containing application id

- **GIVEN** the web layer resolved the deployment ID `applications/public/Quick App with RAG__0.0.1` (already decoded)
- **WHEN** `DialCoreClient.getApplication(id)` is called with that value
- **THEN** the client SHALL pass the decoded value straight to its `RestClient`
- **AND** the wire path SHALL be `/openai/applications/applications/public/Quick%20App%20with%20RAG__0.0.1` — single-encoded, no `%2520`

#### Scenario: Model id with no special characters (regression guard)

- **GIVEN** a model deployment with `id = "gpt-4"`
- **WHEN** the client invokes `POST /openai/deployments/gpt-4/chat/completions`
- **THEN** the wire request path SHALL be `/openai/deployments/gpt-4/chat/completions` (unchanged from prior behaviour)

#### Scenario: Idempotency under raw-vs-encoded input

- **GIVEN** the input deployment id is `"applications/public/Quick App with RAG__0.0.1"` (raw, with literal space)
- **AND** a second input deployment id is `"applications/public/Quick%20App%20with%20RAG__0.0.1"` (already URL-encoded)
- **WHEN** the client builds the wire path for each
- **THEN** both inputs SHALL produce the identical wire path `/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/{relativePath}`

#### Scenario: Query parameters remain single-encoded

- **GIVEN** the invocation includes query parameters such as `?model=gpt-4&temperature=0.7`
- **WHEN** the client builds the request URI
- **THEN** query parameter values SHALL be encoded exactly once
- **AND** path and query encoding SHALL be composed in a single `UriComponentsBuilder` so encoding is consistent between components

---

### Requirement: Deployment type discriminator field

`DialCoreDeploymentDto` SHALL be an abstract polymorphic base using Jackson `@JsonTypeInfo(use = Id.NAME, include = As.EXISTING_PROPERTY, property = "object", visible = true, defaultImpl = DialCoreUnknownDeploymentDto.class)` with `@JsonSubTypes` mapping `"model"` → `DialCoreModelDto`, `"application"` → `DialCoreApplicationDto`, `"toolset"` → `DialCoreToolsetDto`. The `object` field remains a visible `String` on the base (reflecting the actual DIAL API response field name). `DeploymentMapper.toDeploymentInfoDto()` SHALL dispatch via a pattern-matching switch on the concrete subtype (no string comparison on `getObject()`). Unknown or missing `object` values deserialize to `DialCoreUnknownDeploymentDto`; the mapper returns `null` for it so `DeploymentService` logs a warning (with the raw `object` value) and skips the entry.
Status: **Implemented**

#### Scenario: Model entry mapped correctly
- **WHEN** the unified list entry has `"object": "model"`
- **THEN** it deserializes to `DialCoreModelDto`
- **AND** `toDeploymentInfoDto()` returns a `DialModelInfoDto`

#### Scenario: Toolset entry mapped correctly
- **WHEN** the unified list entry has `"object": "toolset"`
- **THEN** it deserializes to `DialCoreToolsetDto`
- **AND** `toDeploymentInfoDto()` returns a `ToolsetInfoDto`

#### Scenario: Application entry mapped correctly
- **WHEN** the unified list entry has `"object": "application"`
- **THEN** it deserializes to `DialCoreApplicationDto`
- **AND** `toDeploymentInfoDto()` returns a `DialApplicationInfoDto`

#### Scenario: Unknown object value falls back and is skipped
- **WHEN** the unified list entry has an `object` value that is missing or not among `"model"`, `"application"`, `"toolset"`
- **THEN** it deserializes to `DialCoreUnknownDeploymentDto`
- **AND** `toDeploymentInfoDto()` returns `null`
- **AND** `DeploymentService` logs a warning with the raw `object` value and the entry's `id`, then skips it

---

### Requirement: DialTransport enum

A `DialTransport` enum SHALL exist in `client.dialcore.dto` with values `HTTP("HTTP")` and `SSE("SSE")`. `DialCoreToolsetDto.transport` SHALL use this enum type. Jackson SHALL use `@JsonCreator` on `DialTransport.fromValue()` and fail fast (throw `IllegalArgumentException`) on unrecognized values.
Status: **Implemented**

#### Scenario: Known HTTP transport value deserialized
- **WHEN** DIAL response contains `"transport": "HTTP"`
- **THEN** the field deserializes to `DialTransport.HTTP`

#### Scenario: Known SSE transport value deserialized
- **WHEN** DIAL response contains `"transport": "SSE"`
- **THEN** the field deserializes to `DialTransport.SSE`

#### Scenario: Unknown transport value fails fast
- **WHEN** DIAL response contains an unrecognized transport value
- **THEN** Jackson throws and the request fails (not silently ignored)

---

### Requirement: InterfaceType typed list

`DialCoreDeploymentDto.interfaces` SHALL be `List<InterfaceType>` (using the existing `InterfaceType` enum). Jackson SHALL use `@JsonCreator` on `InterfaceType.fromValue()` and fail fast on unknown values.
Status: **Implemented**

#### Scenario: Known interface values deserialized
- **WHEN** DIAL response contains `"interfaces": ["chat", "mcp"]`
- **THEN** the field deserializes to `[InterfaceType.CHAT, InterfaceType.MCP]`

#### Scenario: Unknown interface value fails fast
- **WHEN** DIAL response contains an unrecognized interface value
- **THEN** Jackson throws and the request fails (not silently skipped)

---

### Requirement: Transport field on toolset entries

`transport` (type `DialTransport`) SHALL live on the `DialCoreToolsetDto` subtype, not on the `DialCoreDeploymentDto` base — unified list toolset entries deserialize to `DialCoreToolsetDto` (via the `object` discriminator), so `transport` is captured there. Non-toolset subtypes (`DialCoreModelDto`, `DialCoreApplicationDto`) SHALL have no `transport` field at all, which is stronger than the field merely being `null`.
Status: **Implemented**

#### Scenario: Toolset HTTP transport captured from unified list
- **WHEN** the unified list entry has `"object": "toolset"` and `"transport": "HTTP"`
- **THEN** it deserializes to `DialCoreToolsetDto` with `transport = DialTransport.HTTP`

#### Scenario: Toolset SSE transport captured from unified list
- **WHEN** the unified list entry has `"object": "toolset"` and `"transport": "SSE"`
- **THEN** it deserializes to `DialCoreToolsetDto` with `transport = DialTransport.SSE`

#### Scenario: Model entry has no transport field
- **WHEN** the unified list entry has `"object": "model"` (no transport field)
- **THEN** it deserializes to `DialCoreModelDto`, which has no `transport` field

---

## Implementation Notes (MCP Extension)
- Modified: `DialCoreClient` — add `getDeployments(interfaceType)` method calling `/v1/deployments`; keep `getToolset(id)` for single-item detail; deserializes response as bare `List<DialCoreDeploymentDto>` (not a wrapped object)
- Modified: `DeploymentService` — replace 3 parallel calls with single `/v1/deployments` call; add type/interface filtering; consumes `List<DialCoreDeploymentDto>` directly
- Modified: `DeploymentMapper` — `toDeploymentInfoDto()` dispatches via a pattern-matching switch on the concrete subtype (`DialCoreModelDto`/`DialCoreApplicationDto`/`DialCoreToolsetDto`), not `source.getObject()` string branching; add `dialTransportToMcp()` converter (`HTTP` → `STREAMABLE_HTTP`, `SSE` → `SSE`)
- Modified: `DeploymentController` — add `type` and `interface` query params; tools endpoint at `GET /deployments/tools?deploymentId=&transport=` (query params, not path variables); by-ID endpoint mapped as `/{deploymentType}/**` with the ID resolved from the wildcard tail via the injected `WildcardPathResolver` and rejected with 400 when blank
- New: `web.path.WildcardPathResolver` — generic web-layer `@Component` returning the decoded `/**` tail of the matched mapping pattern; the single place a slash-containing path value is extracted and percent-decoded (exactly once, `+` preserved)
- New: `DialCoreDeploymentDto` in `client.dialcore.dto` is now the abstract polymorphic base of the deployment DTO hierarchy — carries common fields (`object`, `id`, `displayName`, `displayVersion`, `description`, `descriptionKeywords`, `iconUrl`, `reference`, `owner`, `status`, `createdAt`, `updatedAt`, `defaults`, `maxRetryAttempts`, `inputAttachmentTypes`, `interfaces` (List<InterfaceType>), `features`); `transport` moved to the `DialCoreToolsetDto` subtype
- New enum: `DialTransport` in `client.dialcore.dto` — `HTTP("HTTP")`, `SSE("SSE")`, fail-fast on unknown values via `@JsonCreator`
- New DTO: `DialCoreToolsetDto` in `client.dialcore.dto` — extends the base; used for both single toolset detail and the toolset variant of unified list entries; adds `transport` (DialTransport) and `allowedTools`
- New DTO: `DialCoreUnknownDeploymentDto` in `client.dialcore.dto` — empty fallback subtype registered as `@JsonTypeInfo` `defaultImpl`; deserialization target when `object` is missing or unrecognized, so the entry is skipped (logged) instead of failing the whole list fetch
- Deleted: `DialCoreFeaturesDto` — dead any-setter container with no consumers; the base's `Map<String, Object> features` replaces it
- Deleted: `DialCoreDeploymentListResponseDto` — removed; DIAL returns a bare array
- Single-entity `/openai/{models|applications}/{id}` payloads rely on the `object` discriminator being present on the wire — DIAL Core always sends it, so polymorphic deserialization to the concrete subtype succeeds even when the static Java type at the call site is already the subtype
