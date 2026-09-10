## MODIFIED Requirements

### Requirement: Get deployment by ID across all types

The system SHALL provide an endpoint to get a single deployment by ID **without** the caller supplying its type, mapped as `GET /api/v1/deployments/all/**`. Everything after the `all` segment is the deployment ID and SHALL be resolved and decoded exactly once by the same web-layer wildcard resolution as the typed endpoint, so slash-containing and percent-encoded IDs behave identically on both endpoints. An empty ID SHALL be rejected with HTTP 400 `VALIDATION_ERROR` and message `Deployment ID must not be empty` without any upstream call.

For a non-empty ID the system SHALL issue exactly one call to DIAL Core's unified `GET /v1/deployments/{id}` endpoint, propagating the caller's JWT, and map the response to the same representation the typed endpoint returns for that deployment's resolved type, including the `$type` discriminator and, for an application, its resolved effective `routes`. The response SHALL additionally carry the `interfaces` field (see "Deployment interfaces exposed on list and by-id responses").

A DIAL Core 404 for this call SHALL be translated to a real HTTP 404 `NOT_FOUND` on this service's own resource. This 404 translation is scoped to this endpoint alone: it does not change the shared DIAL Core error mapping used by every other `DialCoreClient` consumer, including the by-type lookup ("Get deployment by type and ID"), which continues to map a 404 to HTTP 502 `UPSTREAM_NOT_FOUND`. Every other upstream status from this call (401, 403, other 4xx, 5xx) SHALL continue to flow through the existing shared error mapping unchanged (403 passthrough, 401/5xx → 502, etc.).

The literal `all` mapping SHALL take precedence over the sibling type-capturing wildcard mapping, so `/api/v1/deployments/all/{id}` SHALL never be interpreted as a deployment of type `all`.

Implementation notes:
- Endpoint: `web.controller.DeploymentController#getDeploymentById` (mapping `/all/**`); ID resolved by `web.path.WildcardPathResolver`, empty-ID guard shared with the by-type handler.
- Single call: `service.domain.DeploymentService#getDeployment(String)` calls `client.dialcore.DialCoreClient#getDeploymentById(String)`, backed by `GET /v1/deployments/{id}`.
- 404 scoping: the by-id call site catches the `DialCoreClientException` thrown for a 404 status and raises a dedicated not-found exception that resolves directly to HTTP 404, bypassing `runner.client.dialcore.DialCoreErrorMapper`. Every other status rethrows the original `DialCoreClientException` unchanged, through the normal `DefaultExceptionHandler.handleDialCoreClientException` path.
- The response is mapped by the same private mapping the by-type path uses (an application winner passes through `SchemaRouteExtractor.resolveRoutes`), plus an explicit `interfaces` assignment from the DIAL Core payload.
- The former 3-probe fan-out (`DeploymentProbe`/`DeploymentProbeCollapser`, concurrent calls to `/openai/models/{id}`, `/openai/applications/{id}`, `/openai/toolsets/{id}` with severity-ordered collapse) is removed; this endpoint no longer issues concurrent probes or needs `TokenPropagationHelper`.

Status: **Implemented**

#### Scenario: Model found by ID alone
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/gpt-5`
- **THEN** the system SHALL call DIAL Core `GET /v1/deployments/gpt-5` with the user's JWT token
- **AND** return HTTP 200 with the same body the typed model endpoint returns for `gpt-5`
- **AND** the body's `$type` SHALL be `dial-model`

#### Scenario: Application found by ID alone, with resolved routes
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/{id}` for an application that inherits routes from its application type schema
- **THEN** the system SHALL return HTTP 200 with `$type` `dial-application`
- **AND** the `routes` field SHALL be resolved exactly as on the typed application endpoint

#### Scenario: Toolset found by ID alone
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/{id}` for a toolset
- **THEN** the system SHALL return HTTP 200 with `$type` `dial-toolset`

#### Scenario: Single upstream request per lookup
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/{id}`
- **THEN** the system SHALL issue exactly one upstream request, `GET /v1/deployments/{id}`
- **AND** that request SHALL carry the caller's JWT, so the result reflects only a deployment DIAL Core grants that caller

#### Scenario: Deployment not found returns HTTP 404
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/{id}`
- **AND** DIAL Core returns HTTP 404 for `GET /v1/deployments/{id}`
- **THEN** the system SHALL return HTTP 404 with error code `NOT_FOUND` — not the generic `502 UPSTREAM_NOT_FOUND` other `DialCoreClient` consumers receive on a 404

#### Scenario: Access denied
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/{id}`
- **AND** DIAL Core returns HTTP 403 for `GET /v1/deployments/{id}`
- **THEN** the system SHALL return HTTP 403 with error code `ACCESS_DENIED` (via the unchanged shared DIAL Core error mapping)

#### Scenario: Upstream auth failure
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/{id}`
- **AND** DIAL Core returns HTTP 401 for `GET /v1/deployments/{id}`
- **THEN** the system SHALL return HTTP 502 with error code `UPSTREAM_AUTH_ERROR` (via the unchanged shared DIAL Core error mapping)

#### Scenario: Upstream server error
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/{id}`
- **AND** DIAL Core returns HTTP 5xx for `GET /v1/deployments/{id}` after all retries
- **THEN** the system SHALL return HTTP 502 with error code `UPSTREAM_ERROR` (via the unchanged shared DIAL Core error mapping)

#### Scenario: Slash-containing deployment ID
- **WHEN** an authenticated user sends GET request to `/api/v1/deployments/all/applications/public/my-app__0.0.1`
- **THEN** the resolved deployment ID SHALL be `applications/public/my-app__0.0.1` (all segments after `all` preserved)
- **AND** the system SHALL call `DialCoreClient.getDeploymentById("applications/public/my-app__0.0.1")`

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

## ADDED Requirements

### Requirement: Fetch single deployment by ID from DIAL Core's unified endpoint

`DialCoreClient` SHALL provide a `getDeploymentById(id)` method that calls DIAL Core's `GET /v1/deployments/{id}` endpoint, propagating the caller's JWT, and deserializes the response directly to `DialCoreDeploymentDto` — polymorphic on the `object` discriminator, via the same `@JsonTypeInfo`/`@JsonSubTypes` mechanism `getModel`/`getApplication`/`getToolset` already use for single-object bodies (no `JsonNode`-then-`convertValue` workaround, which is needed only for the generic-list-typed `getDeployments`).
Status: **Implemented**

#### Scenario: Successful deployment retrieval
- **WHEN** `DialCoreClient.getDeploymentById(id)` is called with a valid deployment ID
- **THEN** the client SHALL call `GET /v1/deployments/{id}` with the user's JWT
- **AND** return the deserialized `DialCoreDeploymentDto`, resolved to its concrete subtype via the `object` discriminator

#### Scenario: Deployment not found
- **WHEN** DIAL Core returns HTTP 404 for the deployment ID
- **THEN** the client SHALL throw `DialCoreClientException` carrying the raw HTTP 404 status (translation to a response status happens at the caller, not in the client)

#### Scenario: Other upstream errors
- **WHEN** DIAL Core returns HTTP 401, 403, another 4xx, or a 5xx after retries are exhausted
- **THEN** the client SHALL throw `DialCoreClientException` carrying the raw upstream status, following the same retry policy as its other single-item methods

---

### Requirement: Deployment interfaces exposed on list and by-id responses

`DeploymentInfoDto` SHALL expose an `interfaces` field (`List<InterfaceType>`, reusing the existing `client.dialcore.dto.InterfaceType` enum rather than a duplicate API-level type), populated from DIAL Core's `interfaces` field on the underlying deployment payload. This field SHALL be present on responses from `GET /api/v1/deployments` (list) and `GET /api/v1/deployments/all/**` (by-id). It SHALL be absent — null, and therefore omitted from JSON under the shared `ObjectMapper`'s `NON_NULL` inclusion — from responses of `GET /api/v1/deployments/{deploymentType}/**` (by-type).
Status: **Implemented**

#### Scenario: Interfaces present in list response
- **WHEN** authenticated user sends GET request to `/api/v1/deployments`
- **THEN** each entry's `interfaces` field SHALL reflect that deployment's DIAL Core `interfaces`

#### Scenario: Interfaces present in by-id response
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/all/{id}`
- **THEN** the response's `interfaces` field SHALL be populated from DIAL Core's `interfaces`

#### Scenario: Interfaces absent in by-type response
- **WHEN** authenticated user sends GET request to `/api/v1/deployments/{deploymentType}/{id}`
- **THEN** the response SHALL NOT contain an `interfaces` field
