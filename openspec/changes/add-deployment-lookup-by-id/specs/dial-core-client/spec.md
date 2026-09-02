# DIAL Core Client — delta for `add-deployment-lookup-by-id`

## ADDED Requirements

### Requirement: Get deployment by ID across all types

The system SHALL provide an endpoint to get a single deployment by ID **without** the caller supplying its type, mapped as `GET /api/v1/deployments/all/**`. Everything after the `all` segment is the deployment ID and SHALL be resolved and decoded exactly once by the same web-layer wildcard resolution as the typed endpoint, so slash-containing and percent-encoded IDs behave identically on both endpoints. An empty ID SHALL be rejected with HTTP 400 `VALIDATION_ERROR` and message `Deployment ID must not be empty` without any upstream call.

For a non-empty ID the system SHALL probe all three DIAL Core deployment endpoints — `/openai/models/{id}`, `/openai/applications/{id}`, `/openai/toolsets/{id}` — **concurrently**, propagating the caller's JWT to every probe, so a lookup costs one round-trip of latency rather than three. Each probe yields one of: a **hit** (2xx with a non-empty body), a **miss** (2xx with an empty body), or an **error** (non-2xx upstream status).

Outcome collapsing SHALL follow exactly two exit paths:

1. **At least one hit** — the system SHALL return that deployment with HTTP 200, in the same representation the typed endpoint returns for it, including the `$type` discriminator identifying which type was found and, for an application, its resolved effective `routes`. Errors on the other probes SHALL NOT affect the response beyond being logged. When more than one probe hits (an ID collision DIAL Core is not expected to produce), the winner SHALL be chosen by the fixed precedence `dial-model` > `dial-application` > `dial-toolset`, and the collision SHALL be logged at WARN naming the ID and the colliding types.
2. **No hit** — the probe failures SHALL be unified into a **single** upstream error whose status is the highest-severity status observed, ordered `401` > `403` > any other error status > `404`, and whose message names each probe's outcome. The unified error SHALL then map to the client response through the service's existing DIAL Core error mapping, introducing no new error codes. A no-hit lookup with no errors at all (all three probes returning empty bodies) SHALL be treated as an all-404 no-hit.

Severity ordering is normative, not cosmetic: an authentication failure or an upstream outage on one probe SHALL NOT be reported as a not-found result merely because the other probes returned 404.

The literal `all` mapping SHALL take precedence over the sibling type-capturing wildcard mapping, so `/api/v1/deployments/all/{id}` SHALL never be interpreted as a deployment of type `all`.

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

## MODIFIED Requirements

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
