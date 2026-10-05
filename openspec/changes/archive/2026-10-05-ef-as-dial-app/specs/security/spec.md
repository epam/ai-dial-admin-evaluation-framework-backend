## ADDED Requirements

### Requirement: Internal eval execute endpoint authenticated by the existing DIAL API-Key chain
When `dial-app-proxy.enabled=true`, the path `/api/internal/**` SHALL be added to the Spring Security configuration's authenticated path set, protected by the same filter chain as `/api/v1/**` (JWT resource server + `ApiKeyAuthenticationFilter`/`CoreApiKeyIntrospector`). This path SHALL NOT be excluded from authentication (no `permitAll`) — a DIAL Core per-request key presented in the `Api-Key` header is authenticated the same way any other DIAL API key is authenticated today. `dial-app-proxy.enabled=true` SHALL require `config.rest.security.api-key.enabled=true`; the application SHALL fail fast at startup otherwise.

Status: **Planned**

#### Scenario: Internal endpoint authenticated like any other API-Key caller
- **WHEN** a request arrives at `/api/internal/runs/{runId}/execute` with a valid `Api-Key` header
- **THEN** Spring Security SHALL authenticate it via the existing `ApiKeyAuthenticationFilter`/`CoreApiKeyIntrospector` chain
- **AND** the request SHALL reach `EvalExecuteInternalController` only once authenticated

#### Scenario: Internal endpoint rejects unauthenticated requests
- **WHEN** a request arrives at `/api/internal/runs/{runId}/execute` without a header the existing chain can authenticate
- **THEN** Spring Security SHALL return HTTP 401, the same as it would for `/api/v1/**`

#### Scenario: Public endpoints unaffected
- **WHEN** a request arrives at `/api/v1/**`
- **THEN** the existing JWT/OIDC security rules SHALL apply unchanged

#### Scenario: Startup fails fast on misconfiguration
- **WHEN** `dial-app-proxy.enabled=true` and `config.rest.security.api-key.enabled=false`
- **THEN** the application SHALL fail to start rather than exposing an endpoint with no viable authentication mechanism

## MODIFIED Requirements

### Requirement: Outbound DIAL Core auth — credential-kind-agnostic propagation
The system's outbound authentication to DIAL Core SHALL be governed entirely by the `CallerCredential` captured for the current execution context (via `AuthorizationTokenHolder` / `EvaluationContext.credential`), with no separate "JWT mode" vs. "PRK mode" branching anywhere in the invocation path. A `BEARER`-kind credential (a user's JWT, captured on the public `/api/v1/**` path) SHALL produce an `Authorization: Bearer <jwt>` header, as today. An `API_KEY`-kind credential (any API key, including a DIAL Core per-request key captured on `/api/internal/**`) SHALL produce an `Api-Key: <value>` header, as today — this was already true for any API-key caller before this change; a PRK requires no new code to flow through the same path.

Status: **Implemented**

#### Scenario: Bearer credential used for a JWT-authenticated run
- **WHEN** a run was dispatched with a `BEARER`-kind `CallerCredential`
- **THEN** all outbound DIAL Core deployment-invocation calls for that run SHALL use `Authorization: Bearer <jwt>`

#### Scenario: API-key credential used for a PRK-authenticated run
- **WHEN** `dial-app-proxy.enabled=true` and a run was dispatched (via `EvalExecuteInternalController`) with an `API_KEY`-kind `CallerCredential` (the PRK)
- **THEN** all outbound DIAL Core deployment-invocation calls for that run SHALL use `Api-Key: <prk>`
- **AND** no `Authorization` header SHALL be set for those calls

#### Scenario: Non-eval DIAL Core calls unaffected
- **WHEN** a DIAL Core call is made outside of eval execution context (e.g., deployment listing, file management via the service's own configured API key)
- **THEN** those calls SHALL continue to use whatever credential they already use today, unaffected by DIAL App mode
