## Why

During long eval runs (minutes to hours), the user's JWT is captured at run-creation time and propagated to async worker threads — but JWTs expire, causing mid-run 401 failures on deployment invocations. Additionally, when evaluated deployments receive file attachments from the EF bucket (e.g., multi-modal test case inputs), they cannot access those files because no DIAL auto-sharing mechanism is in place.

Registering EF as a DIAL Application (via DIAL Application Routes) solves both problems: DIAL Core generates a per-request key (PRK) that lives for the duration of the eval run connection, carries user identity and cost attribution through the full call tree, and enables DIAL Core's auto-sharing mechanism to propagate EF bucket file access to evaluated deployments.

## What Changes

- **New: `PerRequestKeyStore`** — in-memory registry mapping `runId → per-request key`, populated when DIAL Core triggers EF's internal eval endpoint.
- **New: `DialRouteTriggerClient`** — fires eval execution via DIAL Core Application Route (`POST /v1/deployments/ef-eval/route/eval/runs/{runId}/execute`) using the user's JWT; keeps SSE connection open to maintain PRK liveness.
- **New: `EvalExecuteInternalController`** — internal Spring controller at `POST /internal/eval/runs/{runId}/execute`; receives PRK from DIAL Core, stores it, starts async eval, streams SSE heartbeat/progress back through DIAL Core.
- **Modified: `DialCoreDeploymentInvoker`** — uses PRK from `PerRequestKeyStore` (by `runId`) for deployment invocations when in DIAL App mode; falls back to JWT-based auth otherwise.
- **Modified: `AuthorResolver`** — resolves user identity via `GET /v1/user/info` with PRK when in DIAL App mode; falls back to JWT claim parsing.
- **New: `ef.dial-app.enabled` config flag** — when `false` (default), legacy JWT propagation is used; when `true`, DIAL Route trigger + PRK store is active. Allows gradual rollout per environment.
- **New: DIAL Core registration config** — EF registered as a DIAL deployment with an Application Route and bucket access; documented in `docs/configuration.md`.
- **No public API changes** — EF's REST API (`/api/v1/...`) is unchanged. The `/internal/...` endpoint is not publicly routable (only reachable via DIAL Core route proxy).
- **No DB schema changes.**

## Capabilities

### New Capabilities

- `dial-app-auth`: EF registered as a DIAL App via Application Routes. Covers: DIAL Core route registration, `PerRequestKeyStore`, `DialRouteTriggerClient`, `EvalExecuteInternalController`, SSE heartbeat/progress streaming to DIAL Core, PRK-based user info resolution, file auto-sharing to evaluated deployments via DIAL Core PRK chain, `ef.dial-app.enabled` feature flag, and per-request key validation.

### Modified Capabilities

- `eval-execution-engine`: `EvaluationContext` token propagation changes — when DIAL App mode is active, the PRK (not JWT) is used for deployment invocations; JWT capture and `TokenPropagationHelper` are bypassed on the execution path.
- `security`: JWT-based auth for outbound DIAL Core calls is now conditional; the `AuthorizationTokenHolder` / `TokenPropagationHelper` pattern is augmented with a PRK-first path controlled by `ef.dial-app.enabled`.

## Impact

- **New packages**: `client.dialcore` (DialRouteTriggerClient), `service.domain` (PerRequestKeyStore), `web.controller` (EvalExecuteInternalController)
- **Modified components**: `DialCoreDeploymentInvoker`, `AuthorResolver`, `TestSuiteRunService` (or eval dispatch site), `EvaluationContext`, `application.yml`
- **External dependency**: DIAL Core — EF must be registered as a deployment with an Application Route pointing to EF's internal endpoint and configured bucket access for file auto-sharing
- **Configuration**: new properties under `ef.dial-app.*`; `docs/configuration.md` must be updated
- **Security surface**: `/internal/eval/runs/{runId}/execute` must only be reachable through DIAL Core proxy (not via public ingress); PRK validation via `/v1/user/info` is configurable
- **No DB migrations**, no Flyway changes, no public REST API changes
