## ADDED Requirements

### Requirement: Capture the caller's credential with its kind
The service SHALL capture the credential presented by the current caller together with its kind — bearer JWT or DIAL API key — and make it available for the duration of that caller's request, so that calls made downstream on the caller's behalf can present the same credential in its native header. An `Api-Key` credential SHALL be captured only when the request's `Authorization` header is absent or blank — any non-blank `Authorization` header suppresses it, matching exactly the precedence already applied when authenticating an API-key caller. The captured credential SHALL be discarded when the request completes, SHALL never be written to logs, and SHALL be visible to MCP tool code exactly as it is to REST handlers.
Status: **Planned**

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
Status: **Planned**

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

## MODIFIED Requirements

### Requirement: Author attribution for created entities
The service SHALL derive the `createdBy` value stored on newly created or cloned entities (test suites, datasets) from the caller's identity. For a bearer caller the value is by default the caller's configured JWT claim (`security.jwt.user-claim`, default `sub`); when `security.jwt.resolve-user-name` is `true` the service SHALL instead attempt to resolve a human-readable display name from DIAL Core `GET /v1/user/info`, using the caller's own bearer token, and SHALL fall back to the claim value whenever a display name cannot be obtained. For an API-key caller the value SHALL be the principal established by API-key introspection — the DIAL Core project name for a project key, or the configured user-identity claim for a JWT-rooted per-request key — and display-name resolution SHALL NOT be attempted, since that principal is not necessarily a user. Attribution SHALL be resolved identically for REST requests and MCP tool calls, and attribution failures SHALL never fail the calling request.
Status: **Planned**

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

## Implementation notes

Planned touch points (see `design.md` for the chosen shape):

- Credential capture: `com.epam.aidial.evaluation.configuration.security.AuthorizationHeaderInterceptor` (registered in `configuration.logging.WebMvcConfig`), alongside the existing `web.security.apikey.ApiKeyAuthenticationFilter` precedence rule.
- Request-scoped storage and async propagation: `com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder` and `runner.util.TokenPropagationHelper` (`evaluation-runner-core`).
- Outbound headers: `com.epam.aidial.evaluation.client.dialcore.DialCoreClientConfiguration` (interceptor factory shared with `DialCoreDeploymentInvokerConfiguration` and `client.dialadas.DialAdasClientConfiguration`) and `com.epam.aidial.evaluation.runner.client.mcp.McpToolInvoker`.
- Attribution: `com.epam.aidial.evaluation.service.domain.AuthorResolver` (fallback keyed on the `Authentication` shape, so no `.web` import is needed); `mcp.support.McpCallerContext#callerCredential()` replaces `bearerToken()` so tool code can never format an API key as a bearer token.
- Verification scope: the MCP attribution path is exercised through the test-only `probe_caller_identity` tool until a mutating MCP tool exists; the REST path is asserted against a created entity's stored `createdBy`.
