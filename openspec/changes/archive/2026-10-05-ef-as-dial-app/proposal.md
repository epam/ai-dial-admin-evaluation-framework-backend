## Why

During long eval runs (minutes to hours), the user's JWT is captured at run-creation time and propagated to async worker threads — but JWTs expire, causing mid-run 401 failures on deployment invocations. Additionally, when evaluated deployments receive file attachments from the EF bucket (e.g., multi-modal test case inputs), they cannot access those files because no DIAL auto-sharing mechanism is in place.

Registering EF as a DIAL Application (via DIAL Application Routes) solves both problems: DIAL Core generates a per-request key (PRK) that lives for the duration of the eval run connection, carries user identity and cost attribution through the full call tree, and enables DIAL Core's auto-sharing mechanism to propagate EF bucket file access to evaluated deployments.

A PRK is, to this codebase, just another `Api-Key` header value. EF already generalized its caller-credential handling (`CallerCredential`, `AuthorizationTokenHolder`, `TokenPropagationHelper`) to carry either a bearer JWT or an API key uniformly through the whole execution path (`EvaluationContext.credential` → `EvaluationWorker`/`DeploymentTurnInvoker`/`McpToolInvoker`), and already has a DIAL Core API-key introspection path (`ApiKeyAuthenticationFilter` + `CoreApiKeyIntrospector`, calling `GET /v1/user/info`) that authenticates an `Api-Key` header and resolves its caller's identity/roles, with caching. This change reuses both rather than building parallel PRK-specific machinery.

## What Changes

- **New: `DialAppProperties`** (`dial-app-proxy.*`) — `enabled` (default `false`), `deployment-name` (default `EF`), `heartbeat-interval-ms` (default `30000`), `trigger-read-timeout-ms` (default `43200000`, 12h). Fails fast at startup if `enabled=true` but `config.rest.security.api-key.enabled=false` (the internal endpoint has no other way to authenticate the PRK).
- **New: `DialRouteTriggerClient`** — fires eval execution via DIAL Core Application Route (`POST /v1/deployments/{deploymentName}/route/api/internal/runs/{runId}/execute`) using the user's JWT (exactly once); keeps the SSE connection open (reading and discarding the stream) to maintain PRK liveness for the run's duration; runs on a virtual thread.
- **New: `EvalExecuteInternalController`** — `POST /api/internal/runs/{runId}/execute`. Sits behind the **existing** Spring Security chain (same as `/api/v1/**`), authenticated by the existing `ApiKeyAuthenticationFilter`/`CoreApiKeyIntrospector` — no new PRK validation or security bypass needed. On a valid, already-authenticated request it reads the existing run (404 if absent, 409 if not in a dispatchable state), captures the request's `CallerCredential` (already an `API_KEY`-kind credential, populated by the existing `AuthorizationHeaderInterceptor`) and calls the **existing** `TestSuiteEvaluationJob.dispatch(runId, credential, false)` — the same dispatch method the public JWT path already uses. Returns an SSE stream (heartbeat + progress, reusing `TestSuiteRunSseService`'s existing emitter/notification infra with a faster, DIAL-Core-specific heartbeat) that stays open for the run's duration.
- **Modified: `TestSuiteRunService`/dispatch site** — when `dial-app-proxy.enabled=true`, after the run-creation transaction commits, EF calls `DialRouteTriggerClient.triggerEvalRun(runId, jwt)` on a virtual thread instead of dispatching the evaluation job in-process directly. When `false`, unchanged (existing in-process dispatch).
- **New: DIAL Core registration config** — EF registered as a DIAL deployment named `EF` with an Application Route and bucket access; documented in `docs/configuration.md`.
- **No public API changes** — EF's REST API (`/api/v1/...`) is unchanged. `/api/internal/runs/{runId}/execute` carries no request body (the run already exists) and is additionally recommended to be withheld from public ingress (defense in depth; authentication itself no longer depends on that isolation).
- **No DB schema changes.**
- **Not needed** (reusing existing generic infrastructure instead): a dedicated `PerRequestKeyStore`, `RunIdHolder`, `DialUserInfoClient`, or any "PRK mode" branching inside `DialCoreDeploymentInvoker`/`AuthorResolver` — `CallerCredential`'s existing `BEARER`/`API_KEY` kinds, `ActiveRunRegistry` (run lookup/cancellation), and `CoreApiKeyIntrospector` (introspection + identity) already cover these needs with zero new code in the execution/invocation path.

## Capabilities

### New Capabilities

- `dial-app-auth`: EF registered as a DIAL App via Application Routes. Covers: DIAL Core route registration, `DialRouteTriggerClient`, `EvalExecuteInternalController`, SSE heartbeat/progress streaming to DIAL Core, PRK-based user info resolution (via the existing DIAL API-Key auth chain), file auto-sharing to evaluated deployments via DIAL Core's PRK chain, and the `dial-app-proxy.enabled` feature flag.

### Modified Capabilities

- `eval-execution-engine`: dispatch-path branching only — when DIAL App mode is active, execution is started from `EvalExecuteInternalController` (PRK-authenticated) instead of the legacy in-process `CompletableFuture`/`TokenPropagationHelper` dispatch from the public endpoint. `EvaluationContext`/`CallerCredential` themselves are unchanged (already credential-kind-agnostic).
- `security`: `/api/internal/**` is added to the authenticated path set, protected by the existing DIAL API-Key auth chain (same as any other API-key-authenticated endpoint) rather than a new bypass or validation mechanism. `DialAppProperties` fails fast if API-Key auth isn't enabled.

## Impact

- **New packages**: `client.dialcore` (`DialRouteTriggerClient`), `web.controller` (`EvalExecuteInternalController`), `configuration.properties.dialapp` (`DialAppProperties`)
- **Modified components**: `TestSuiteRunService` (dispatch branching), `SecurityConfiguration` (new authenticated matcher), `TestSuiteRunSseService` (faster configurable heartbeat for the internal stream), `application.yml`
- **External dependency**: DIAL Core — EF must be registered as a deployment named `EF` with an Application Route pointing to EF's internal endpoint and configured bucket access for file auto-sharing
- **Configuration**: new properties under `dial-app-proxy.*`; `docs/configuration.md` must be updated; `config.rest.security.api-key.enabled=true` becomes a hard prerequisite for `dial-app-proxy.enabled=true`
- **Security surface**: `/api/internal/runs/{runId}/execute` is authenticated the same way as any DIAL API-Key caller; recommended (not required) to also be withheld from public ingress as defense in depth
- **No DB migrations**, no Flyway changes, no public REST API changes
