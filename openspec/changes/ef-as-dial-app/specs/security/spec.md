## ADDED Requirements

### Requirement: Internal endpoint excluded from JWT security filter chain
When `ef.dial-app.enabled=true`, the Spring Security configuration SHALL exclude the path `/internal/**` from JWT authentication. These paths are protected at the network level (only reachable via DIAL Core proxy) and via PRK validation (see `dial-app-auth` spec). No `Authorization` header is expected on internal endpoints.

Status: **Planned**

#### Scenario: Internal endpoint accessible without JWT
- **WHEN** a request arrives at `/internal/eval/runs/{runId}/execute`
- **AND** no `Authorization` header is present
- **THEN** Spring Security SHALL NOT return HTTP 401 due to missing JWT
- **AND** the request SHALL reach `EvalExecuteInternalController` for PRK-level validation

#### Scenario: Public endpoints unaffected
- **WHEN** a request arrives at `/api/v1/**`
- **THEN** the existing JWT/OIDC security rules SHALL apply unchanged

## MODIFIED Requirements

### Requirement: Outbound DIAL Core auth — dual-mode JWT and PRK
The system's outbound authentication to DIAL Core SHALL support two modes, selected per-invocation:
- **JWT mode** (`ef.dial-app.enabled=false` or no PRK in store): `Authorization: <jwt>` header, sourced from `AuthorizationTokenHolder.getToken()` (existing behavior)
- **PRK mode** (`ef.dial-app.enabled=true` and PRK present in `PerRequestKeyStore`): `Api-Key: <prk>` header, sourced from `PerRequestKeyStore.get(runId)`

Both modes are valid DIAL Core authentication mechanisms. The JWT is never forwarded to DIAL Core when PRK mode is active.

Status: **Implemented** (JWT mode), **Planned** (PRK mode)

#### Scenario: JWT mode used when DIAL App disabled
- **WHEN** `ef.dial-app.enabled=false`
- **THEN** all outbound DIAL Core calls (deployment invocations, file API, deployment listing) SHALL use `Authorization: <jwt>` from `AuthorizationTokenHolder` (existing behavior)

#### Scenario: PRK mode used for deployment invocations in DIAL App mode
- **WHEN** `ef.dial-app.enabled=true`
- **AND** `PerRequestKeyStore` contains a PRK for the current `runId`
- **THEN** `DialCoreDeploymentInvoker` SHALL use `Api-Key: <prk>` for deployment invocations
- **AND** SHALL NOT include an `Authorization` header

#### Scenario: Non-eval DIAL Core calls unaffected in DIAL App mode
- **WHEN** `ef.dial-app.enabled=true`
- **AND** a DIAL Core call is made outside of eval execution context (e.g., deployment listing, file management)
- **THEN** those calls SHALL continue to use `Authorization: <jwt>` from `AuthorizationTokenHolder`
