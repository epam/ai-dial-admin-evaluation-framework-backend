# Security

## Purpose
This spec describes authentication/authorization behavior for the REST API.

Status: **Implemented** (OIDC/JWT modes, DIAL API-Key authentication, caller-credential capture and forwarding to DIAL Core, author attribution with opt-in display-name resolution), **Planned** (fine-grained permissions per resource).

## Key Terms
- **Security mode**: operational mode controlling whether auth is enforced.
- **Provider**: a JWT issuer configuration (multi-issuer).
- **DIAL API-Key authentication**: an alternative to OIDC/JWT bearer tokens where a caller
  authenticates via an `Api-Key` header, validated by delegating to DIAL Core's
  `GET /v1/user/info`, active only in `oidc` security mode.
- **Caller credential**: the credential the current caller presented — a bearer JWT or a DIAL
  API key — captured request-scoped together with its *kind*, so downstream calls made on the
  caller's behalf can present it in the header that kind requires.

## Requirements

### Requirement: Support OIDC/JWT authentication
The service SHALL support OIDC/JWT authentication in `oidc` security mode.
Status: **Implemented**

#### Scenario: OIDC mode enabled
- **WHEN** `config.rest.security.mode=oidc`
- **THEN** protected endpoints SHALL require a valid JWT

#### Scenario: Multiple issuers
- **WHEN** multiple providers are configured
- **THEN** system SHALL accept JWTs from any configured issuer and validate them using the matching JWK set

### Requirement: Allow disabling security for local/test
The service SHALL support disabling authentication/authorization via configuration for local/test usage.
Status: **Implemented**

#### Scenario: No-security mode
- **WHEN** `config.rest.security.mode=none`
- **THEN** the service SHALL allow requests without authentication (intended for local/test)

### Requirement: Role-based access control
The service SHALL support role-based access control for protected endpoints.
Status: **Implemented** (baseline), **Planned** (resource-level policy)

#### Scenario: Default allowed roles
- **WHEN** a request is authenticated
- **THEN** access decisions SHALL consider `config.rest.security.default.allowedRoles` (or provider-specific roles)

### Requirement: Support DIAL API-Key authentication
The service SHALL support authenticating requests via a `Api-Key` header,
as an alternative to OIDC/JWT bearer tokens, when
`config.rest.security.mode=oidc` and `config.rest.security.api-key.enabled=true`.
The service SHALL delegate key validation to DIAL Core's `GET /v1/user/info`
endpoint and SHALL NOT store or validate API keys locally.
Status: **Implemented**

#### Scenario: Api-Key header present and Authorization absent
- **WHEN** a request carries a non-blank `Api-Key` header and no `Authorization` header
- **THEN** the service SHALL introspect the key against DIAL Core's `GET {core-url}/v1/user/info` (header `Api-Key: <key>`) and authenticate the request based on the result

#### Scenario: Authorization header takes precedence
- **WHEN** a request carries both an `Authorization` header and an `Api-Key` header
- **THEN** the service SHALL ignore the `Api-Key` header and proceed with OIDC/JWT authentication for that request

#### Scenario: Blank Api-Key header
- **WHEN** a request has no `Authorization` header and a blank or absent `Api-Key` header
- **THEN** the service SHALL treat the request as unauthenticated and let existing authorization rules apply

#### Scenario: Feature disabled
- **WHEN** `config.rest.security.api-key.enabled=false` (the default) or `config.rest.security.mode=none`
- **THEN** the service SHALL NOT attempt Api-Key introspection, regardless of any `Api-Key` header present

### Requirement: DIAL Core introspection response handling
The service SHALL parse two DIAL Core `/v1/user/info` response shapes — a
project-key shape (`{roles, project}`) and a JWT-rooted per-request-key
shape (`{roles, userClaims}`) — and SHALL map both a non-2xx response and an
unreachable Core to a distinct, non-cached failure outcome.
Status: **Implemented**

#### Scenario: Project-key response shape
- **WHEN** DIAL Core responds `200 OK` with a body containing a non-blank `project` field
- **THEN** the service SHALL authenticate the caller using `project` as principal and `roles` as the caller's raw Core roles

#### Scenario: JWT-rooted per-request-key response shape
- **WHEN** DIAL Core responds `200 OK` with a body containing a non-empty `userClaims` object and no `project` field
- **THEN** the service SHALL authenticate the caller using the configured user-identity claim from `userClaims` as principal and `roles` as the caller's raw Core roles

#### Scenario: Malformed introspection response
- **WHEN** DIAL Core responds `200 OK` with a body containing neither a non-blank `project` field nor a non-empty `userClaims` object
- **THEN** the service SHALL reject the request with `401 Unauthorized`

#### Scenario: Core rejects the key
- **WHEN** DIAL Core responds with a non-2xx HTTP status to the introspection call
- **THEN** the service SHALL reject the request with `401 Unauthorized` and SHALL NOT cache the failure

#### Scenario: Core is unreachable
- **WHEN** the introspection call to DIAL Core fails due to a connection error or timeout
- **THEN** the service SHALL reject the request with `503 Service Unavailable` and SHALL NOT cache the failure

### Requirement: Cache successful Api-Key introspections
The service SHALL cache successful DIAL Core introspection results, keyed by
a one-way hash of the raw API key (never the plaintext key), with
configurable time-to-live and maximum size, and SHALL NOT cache failed
introspections.
Status: **Implemented**

#### Scenario: Cache hit avoids re-introspection
- **WHEN** two requests within the configured cache TTL carry the same `Api-Key` value
- **THEN** the service SHALL call DIAL Core's introspection endpoint at most once and reuse the cached result for subsequent requests

#### Scenario: Failed introspection is never cached
- **WHEN** an introspection attempt for a given `Api-Key` value fails (invalid key or Core unreachable)
- **THEN** the service SHALL re-attempt introspection against DIAL Core on the next request carrying that same key, rather than reusing a cached failure

### Requirement: Map DIAL Core roles to service authorities
The service SHALL map DIAL Core's raw role names returned by introspection
to this service's `GrantedAuthority` strings via configurable mappings, and
SHALL use an independent mapping for the project-key response shape versus
the JWT-rooted per-request-key response shape.
Status: **Implemented**

#### Scenario: Project-key roles mapped independently
- **WHEN** an introspection result is a project-key shape with raw Core roles
- **THEN** the service SHALL resolve authorities using `config.rest.security.api-key.roles-mapping` only

#### Scenario: JWT-rooted roles mapped independently
- **WHEN** an introspection result is a JWT-rooted per-request-key shape with raw Core roles
- **THEN** the service SHALL resolve authorities using `config.rest.security.api-key.default-roles-mapping` only

#### Scenario: Unmapped role name
- **WHEN** a raw Core role name has no entry in the applicable mapping
- **THEN** the service SHALL drop that role name without rejecting the request

#### Scenario: Authenticated caller with no resolved authorities
- **WHEN** an introspected caller's raw roles resolve to zero mapped authorities
- **THEN** the service SHALL log a warning identifying the principal and SHALL mark the resulting authentication as unauthenticated, so downstream authorization denies the request

### Requirement: Fail fast on invalid Api-Key configuration
The service SHALL fail application startup when Api-Key authentication is
enabled with configuration that would make every caller unauthenticatable
or unauthorizable.
Status: **Implemented**

#### Scenario: Enabled without a Core URL
- **WHEN** `config.rest.security.api-key.enabled=true` and `config.rest.security.api-key.core-url` is blank
- **THEN** the service SHALL fail to start

#### Scenario: Enabled with both role mappings empty
- **WHEN** `config.rest.security.api-key.enabled=true` and both `config.rest.security.api-key.roles-mapping` and `config.rest.security.api-key.default-roles-mapping` parse to empty maps
- **THEN** the service SHALL fail to start, since every Api-Key caller would otherwise resolve to zero authorities

#### Scenario: Enabled with invalid mapping JSON
- **WHEN** `config.rest.security.api-key.enabled=true` and either roles-mapping property is not valid JSON
- **THEN** the service SHALL fail to start, naming the offending property

### Requirement: Capture the caller's credential with its kind
The service SHALL capture the credential presented by the current caller together with its kind — bearer JWT or DIAL API key — and make it available for the duration of that caller's request, so that calls made downstream on the caller's behalf can present the same credential in its native header. An `Api-Key` credential SHALL be captured only when the request's `Authorization` header is absent or blank — any non-blank `Authorization` header suppresses it, matching exactly the precedence already applied when authenticating an API-key caller. The captured credential SHALL be discarded when the request completes, SHALL never be written to logs, and SHALL be visible to MCP tool code exactly as it is to REST handlers.
Status: **Implemented**

#### Scenario: Bearer caller
- **WHEN** a request carries `Authorization: Bearer <jwt>`
- **THEN** the service SHALL capture `<jwt>` with kind *bearer*, regardless of whether an `Api-Key` header is also present

#### Scenario: Non-bearer Authorization header alongside an Api-Key header
- **WHEN** a request carries a non-blank `Authorization` header that is not a `Bearer` token (for example `Authorization: Basic …`) together with a non-blank `Api-Key` header
- **THEN** the service SHALL capture no credential, and SHALL forward neither header upstream — the same request is not API-key-authenticated either

#### Scenario: API-key caller
- **WHEN** a request carries a non-blank `Api-Key` header and no `Authorization: Bearer` header
- **THEN** the service SHALL capture the key with kind *API key*

#### Scenario: No caller credential
- **WHEN** a request carries neither header (for example security mode `none`)
- **THEN** no credential SHALL be captured, and downstream calls SHALL be made without a caller credential, as today

#### Scenario: Credential is request-scoped
- **WHEN** a request completes, successfully or with an error
- **THEN** the captured credential SHALL no longer be readable on that execution thread, so it cannot leak into an unrelated request served by the same pooled thread

#### Scenario: Request handling goes async
- **WHEN** handling of a request is handed off to an asynchronous dispatch, releasing the original request thread back to the pool before the request completes
- **THEN** the captured credential SHALL be cleared from that thread at hand-off, not only at final completion

#### Scenario: MCP caller parity
- **WHEN** an MCP client authenticates with either credential kind and a tool body executes
- **THEN** the tool body SHALL observe the same captured credential and kind that a REST handler would observe for an equivalently authenticated request

### Requirement: Forward the caller's credential to DIAL Core in its native header
Every call the service makes on behalf of a caller to DIAL Core, or to a service reached with the caller's own credential, SHALL present that caller's captured credential in the header that credential kind requires: `Authorization: Bearer <jwt>` for a bearer caller, `Api-Key: <key>` for an API-key caller. This SHALL hold for deployment and toolset metadata reads, user-info lookups, try-out and run-execution deployment invocations, and MCP `tools/list` / `tools/call` requests routed through DIAL Core's MCP proxy, whether the call originates from a REST request, an MCP tool call, or a run's worker threads. Calls the service makes for its own account (for example file-storage access authenticated with the configured service-account key) SHALL be unaffected.
Status: **Implemented**

#### Scenario: API-key caller reaching DIAL Core
- **WHEN** an API-key-authenticated caller triggers any DIAL Core call listed above
- **THEN** the outgoing request SHALL carry `Api-Key: <the caller's key>` and SHALL NOT carry an `Authorization` header

#### Scenario: Bearer caller reaching DIAL Core
- **WHEN** a bearer-authenticated caller triggers any DIAL Core call listed above
- **THEN** the outgoing request SHALL carry `Authorization: Bearer <the caller's token>` and SHALL NOT carry an `Api-Key` header

#### Scenario: Run execution on worker threads
- **WHEN** a run dispatched by an API-key caller invokes deployments or MCP tools from its own worker threads
- **THEN** those requests SHALL carry the dispatching caller's `Api-Key` credential

#### Scenario: Structured-query service call
- **WHEN** an API-key-authenticated caller triggers a structured-query (DSL) call to the dial-adas service, which is reached with the caller's own credential
- **THEN** that request SHALL carry `Api-Key: <the caller's key>`, instead of today's request with no credential at all

#### Scenario: Absent credential
- **WHEN** no caller credential was captured for the current request or run
- **THEN** the outgoing request SHALL carry neither an `Authorization` nor an `Api-Key` header, and SHALL NOT carry a placeholder value

#### Scenario: Service-account calls unchanged
- **WHEN** the service accesses DIAL Core file storage
- **THEN** it SHALL continue to use the configured service-account key, independent of the caller's credential

### Requirement: Author attribution for created entities
The service SHALL derive the `createdBy` value stored on newly created or cloned entities (test suites, datasets) from the caller's identity. For a bearer caller the value is by default the caller's configured JWT claim (`security.jwt.user-claim`, default `sub`); when `security.jwt.resolve-user-name` is `true` the service SHALL instead attempt to resolve a human-readable display name from DIAL Core `GET /v1/user/info`, using the caller's own bearer token, and SHALL fall back to the claim value whenever a display name cannot be obtained. For an API-key caller the value SHALL be the principal established by API-key introspection — the DIAL Core project name for a project key, or the configured user-identity claim for a JWT-rooted per-request key — and display-name resolution SHALL NOT be attempted, since that principal is not necessarily a user. Attribution SHALL be resolved identically for REST requests and MCP tool calls, and attribution failures SHALL never fail the calling request.
Status: **Implemented**

#### Scenario: No JWT present
- **WHEN** an entity is created with no authenticated caller at all (security mode `none`)
- **THEN** the service SHALL store `createdBy = "anonymous"` and SHALL NOT call DIAL Core, regardless of `security.jwt.resolve-user-name`

#### Scenario: API-key caller
- **WHEN** an entity is created by a caller authenticated with an `Api-Key` whose introspected principal is `my-project`
- **THEN** the service SHALL store `createdBy = "my-project"` and SHALL NOT attempt display-name resolution, regardless of `security.jwt.resolve-user-name`

#### Scenario: Configured claim missing from JWT
- **WHEN** an authenticated JWT does not carry the claim named by `security.jwt.user-claim`
- **THEN** the service SHALL store `createdBy = "anonymous"` and SHALL NOT call DIAL Core

#### Scenario: Display-name resolution disabled
- **WHEN** `security.jwt.resolve-user-name=false` (default) and an authenticated JWT carries the configured claim
- **THEN** the service SHALL store the raw claim value as `createdBy` and SHALL NOT call DIAL Core

#### Scenario: Display name resolved from DIAL Core
- **WHEN** `security.jwt.resolve-user-name=true`, the JWT carries the configured claim, and DIAL Core `GET /v1/user/info` (called with the caller's bearer token) responds with a non-blank string `userDisplayName`
- **THEN** the service SHALL store that `userDisplayName` as `createdBy`

#### Scenario: Display name missing or blank
- **WHEN** `security.jwt.resolve-user-name=true` and DIAL Core responds successfully but `userDisplayName` is absent, `null`, not a string, or blank — or the response body is empty
- **THEN** the service SHALL store the raw claim value as `createdBy` and SHALL log the fallback at debug level

#### Scenario: DIAL Core error or unreachable
- **WHEN** `security.jwt.resolve-user-name=true` and DIAL Core responds with a non-2xx status, or the connection fails or times out
- **THEN** the service SHALL store the raw claim value as `createdBy`, SHALL log a warning including the cause, and SHALL complete the create/clone request normally

#### Scenario: Stored value is a snapshot
- **WHEN** a display name has been stored as `createdBy` and the user's display name later changes in the identity provider
- **THEN** the previously stored `createdBy` SHALL remain unchanged (no synchronisation or back-fill)

## Implementation Notes
- Configuration entrypoints:
  - `com.epam.aidial.evaluation.configuration.security.SecurityConfiguration`
  - `com.epam.aidial.evaluation.configuration.security.NoSecurityConfiguration`
- Multi-issuer decoding:
  - `com.epam.aidial.evaluation.web.security.MultiIssuerJwtDecoder`
  - `com.epam.aidial.evaluation.configuration.properties.security.JwtProvidersProperties`
- DIAL API-Key authentication (wired into `SecurityConfiguration`'s `securityFilterChain`,
  `addFilterBefore(..., BearerTokenAuthenticationFilter.class)`, gated by
  `config.rest.security.api-key.enabled`):
  - `com.epam.aidial.evaluation.web.security.apikey.ApiKeyAuthenticationFilter`
  - `com.epam.aidial.evaluation.web.security.apikey.ApiKeyAuthenticationToken`
  - `com.epam.aidial.evaluation.web.security.apikey.CoreApiKeyIntrospector` (DIAL Core `GET /v1/user/info` introspection)
  - `com.epam.aidial.evaluation.web.security.apikey.ApiKeyAuthorityResolver`
  - `com.epam.aidial.evaluation.web.security.apikey.ApiKeyCache` (Caffeine, SHA-256-hashed cache key)
  - `com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties`
  - `com.epam.aidial.evaluation.client.apikey.ApiKeyIntrospectionClientConfiguration` (dedicated `RestClient` bean)
- Caller-credential capture (request-scoped, mirrors the `ApiKeyAuthenticationFilter` precedence rule;
  registered in `configuration.logging.WebMvcConfig` ahead of the MCP transport router function,
  and clearing in both `afterCompletion` and `afterConcurrentHandlingStarted`):
  - `com.epam.aidial.evaluation.configuration.security.AuthorizationHeaderInterceptor` (unit coverage `AuthorizationHeaderInterceptorTest`)
  - `com.epam.aidial.evaluation.runner.util.CallerCredential` (`evaluation-runner-core`; `headerName()`/`headerValue()` per kind, redacting `toString()`, `API_KEY_HEADER` constant) and `runner.util.CredentialKind`
  - `com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder` (single `ThreadLocal<CallerCredential>`) and `runner.util.TokenPropagationHelper` (`withCredential`/`withCredentialCallable`/`withCredentialRunnable`)
  - `com.epam.aidial.evaluation.mcp.support.McpCallerContext#callerCredential()` exposes it to MCP tool code (unit coverage `McpCallerContextTest`)
- Caller-credential forwarding (one header per call, chosen from the kind):
  - `com.epam.aidial.evaluation.client.dialcore.DialCoreClientConfiguration#callerCredentialInterceptor()` — shared by `dialCoreRestClient`, `client.dialcore.DialCoreDeploymentInvokerConfiguration`'s `dialCoreTryOutRestClient` and `client.dialadas.DialAdasClientConfiguration`'s `dialAdasRestClient` (unit coverage `DialCoreClientConfigurationTest`)
  - `com.epam.aidial.evaluation.runner.client.mcp.McpToolInvoker#applyCredentialHeader` (`evaluation-runner-core`; unit coverage `McpToolInvokerTest`)
  - `com.epam.aidial.evaluation.client.dialcore.DialFileClientConfiguration` is deliberately excluded — it keeps sending the configured service-account key
  - `eval-cli` `cli.client.target.TargetDialCoreClientConfiguration` emits `Api-Key` only for an `API_KEY` credential (unit coverage `TargetDialCoreClientConfigurationTest`)
  - Functional coverage: `functional.tests.ApiKeyAuthenticationFunctionalTests`, `functional.tests.McpServerSecurityFunctionalTests`
- See `docs/configuration.md` (Security Configuration section) for property surface.
- Author attribution (`createdBy`), incl. opt-in display-name resolution via DIAL Core `GET /v1/user/info`
  (gated by `security.jwt.resolve-user-name`, default `false`):
  - `com.epam.aidial.evaluation.service.domain.AuthorResolver` (resolution + fallback logic; unit coverage `AuthorResolverTest`).
    With no `Jwt`, it keys the fallback on the current `Authentication`'s shape — an authenticated,
    non-anonymous token whose principal is not a `Jwt` (the introspected API-key principal) yields
    `authentication.getName()`, skipping display-name resolution; otherwise `anonymous`. Keying on the
    `Authentication` rather than on the captured credential avoids a `.web.security.apikey` import that
    the layering test forbids.
  - MCP attribution is exercised through the test-only `probe_caller_identity` tool
    (`functional.support.CallerIdentityProbeTools`, `McpServerSecurityFunctionalTests`) until a mutating
    MCP tool exists; the REST path is asserted against a created entity's stored `createdBy`
    (`ApiKeyAuthenticationFunctionalTests`).
  - `com.epam.aidial.evaluation.configuration.properties.security.JwtSecurityProperties` (`userClaim`, `resolveUserName`)
  - `com.epam.aidial.evaluation.client.dialcore.DialCoreClient#getUserInfo()` (see dial-core-client spec)

## Open Questions / TODO
- Define which endpoints are public vs protected beyond health and swagger (document exact path patterns).
- Clarify how roles are mapped (claim names, authorities conversion) and default behavior for missing roles.

