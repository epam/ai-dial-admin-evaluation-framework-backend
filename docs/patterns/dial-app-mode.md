# DIAL App mode (Application Routes)

Feature-flagged (`dial-app-proxy.enabled`, default `false`) integration that lets EF receive and keep alive a
DIAL Core per-request key (PRK) for the whole duration of an eval run, instead of propagating the user's
JWT (which expires mid-run on long evals). Full rationale and alternatives considered:
`openspec/changes/ef-as-dial-app/design.md`.

## Why not a new PRK-specific stack

The design deliberately reuses EF's existing, credential-kind-agnostic infrastructure rather than inventing
parallel machinery:

- **`CallerCredential(value, kind)`** — a PRK is simply an `API_KEY`-kind `CallerCredential`. No new
  credential type. `EvaluationContext.credential`, `DialCoreDeploymentInvoker`, `DeploymentTurnInvoker`, and
  `McpToolInvoker` already read `credential.headerName()/headerValue()` agnostic of `BEARER` vs `API_KEY` —
  zero invoker changes were needed.
- **`ApiKeyAuthenticationFilter` + `CoreApiKeyIntrospector`** — already authenticate an inbound `Api-Key`
  header via DIAL Core's `GET /v1/user/info` (cached). The internal endpoint below is authenticated by this
  existing chain; there is no bespoke PRK-verification step or `permitAll` carve-out.
- **`ActiveRunRegistry`** — already tracks one `RunHandle` per in-flight run. Its
  `registerIfAbsent(UUID)` (returns `Optional<RunHandle>`, empty if already registered) gives the 409
  duplicate-trigger check for free, and `cancel(UUID)` gives the cancel-on-disconnect hook for free. No new
  PRK-keyed store.

## Flow

```
EF UI -> EF BE  POST /api/v1/test-suites/{id}/runs        [unchanged, returns 202]
  -> creates run, captures JWT once (AuthorizationTokenHolder)
  -> TestSuiteRunService.dispatchEvaluation: dial-app-proxy.enabled=true and credential != null
       -> virtual thread: DialRouteTriggerClient.triggerEvalRun(runId, jwt)
            -> POST {core}/v1/deployments/{deploymentName}/route/api/internal/runs/{runId}/execute
               Authorization: Bearer <jwt>   (used exactly once)
                   |
               DIAL Core validates JWT, mints a PRK, proxies to EF's internal endpoint
                   |
               EF: POST /api/internal/runs/{runId}/execute   Api-Key: <prk>
                   (authenticated by the EXISTING ApiKeyAuthenticationFilter chain)
               EvalExecuteInternalController:
                 - getRun(runId)                                   -> 404 if missing
                 - TestSuiteRunSseService.createEmitterWithHeartbeat(..., heartbeatIntervalMs, onDisconnect)
                   (emitter registered BEFORE dispatch — see "Emitter-before-dispatch ordering" below)
                 - TestSuiteEvaluationJob.dispatch(runId, credential=PRK, false)
                   -> RunAlreadyActiveException if already active  -> sseService.discardEmitter(emitter),
                      then rethrow -> 409 (DefaultExceptionHandler)
                   -> success -> 200 text/event-stream (the already-registered emitter)
            <- DialRouteTriggerClient consumes/discards the proxied SSE stream until it closes
  [connection open = PRK valid; eval can run for hours]
```

`importResultsAndEvaluate` (CSV result import + Phase 2/3 only) has no caller JWT — it calls
`dispatchEvaluation(runId, credential=null, skipDeploymentPhase=true, ...)`. `dispatchEvaluation` only takes
the DIAL-App branch when `credential != null`; with a null credential it falls back to the existing direct
`TestSuiteEvaluationJob.dispatch` path regardless of the flag, because DIAL App mode has no PRK-issuing leg
for a flow that never had a user JWT to begin with. This avoids an NPE inside `DialRouteTriggerClient` and
keeps that flow's behavior unchanged under either flag value.

## Components

| Component | Package | Role |
|---|---|---|
| `DialAppProperties` | `configuration.properties.dialapp` | `enabled`, `deploymentName`, `heartbeatIntervalMs`, `triggerReadTimeoutMs`. **Unconditional bean** (not `@ConditionalOnProperty`) so its `@PostConstruct validate()` always runs and fails fast when `enabled=true` but `config.rest.security.api-key.enabled=false` — the internal endpoint has no other auth mechanism. `ApiKeyProperties` is itself conditional on that property, so it is injected as `ObjectProvider<ApiKeyProperties>` (`getIfAvailable()`), not a plain constructor parameter — a direct dependency would fail bean creation with an opaque `UnsatisfiedDependencyException` instead of this class's clear message. |
| `DialRouteTriggerClient` | `client.dialcore` | `@ConditionalOnProperty(dial-app-proxy.enabled)`. Fires the Application Route call with the user's JWT, reads the proxied SSE stream line-by-line until EOF/IOException, runs on a virtual thread. A dedicated `RestClient` (long read timeout = `triggerReadTimeoutMs`) — never shared with `dialCoreRestClient`/`dialCoreTryOutRestClient`. |
| `EvalExecuteInternalController` | `web.controller` | `@ConditionalOnProperty(dial-app-proxy.enabled)`. `POST /api/internal/runs/{runId}/execute`. Does **not** pre-register an `ActiveRunRegistry` handle itself — that would always win the race against `TestSuiteEvaluationJob.dispatch`'s own `registerIfAbsent` and make every call throw. See "Emitter-before-dispatch ordering" below for why it creates the SSE emitter *before* calling `dispatch`, and discards it on a `dispatch` failure. |
| `ActiveRunRegistry.registerIfAbsent` / `RunAlreadyActiveException` | `service.domain.job` | Atomic `putIfAbsent`-based duplicate-trigger guard, shared by both the public and internal dispatch paths. |
| `TestSuiteRunSseService` heartbeat | `service.domain` | Emitters created via `createEmitterWithHeartbeat(..., heartbeatIntervalMs, onDisconnect)` get a heartbeat at `heartbeatIntervalMs` (default 30000ms, 10x margin under DIAL Core's 300s idle timeout) instead of the default `test-suite-run.sse.cleanup-interval-ms` (300000ms). A single `sendHeartbeats()` sweep (fixed 5s cadence) checks every tracked emitter against its own effective interval — custom or default — stored as a field on `SseEmitterWrapper`, so one scheduled method serves both cadences with no shared timestamp map to keep in sync. Stale-connection removal relies solely on a failed send (`IOException`); `SseEmitter`'s own construction-time timeout/`onTimeout` callback already handles elapsed-duration eviction. |
| `onDisconnect` callback | `service.domain.TestSuiteRunSseService` | Optional `Runnable` passed to `createEmitterWithHeartbeat`, invoked once from the emitter's `onError` handler (and from any send-failure path) — `EvalExecuteInternalController` passes `() -> activeRunRegistry.cancel(runId)`, mirroring `TestSuiteRunService.cancelRun`'s cancellation effect. |
| `TestSuiteRunSseService.discardEmitter` | `service.domain.TestSuiteRunSseService` | Removes a just-created emitter from tracking directly (bypassing `SseEmitter`'s own `onCompletion`/`onError` callbacks, which are only wired to a live servlet async context once the emitter is actually returned as a controller's response body). Used by `EvalExecuteInternalController` when `dispatch` throws after the emitter was pre-created. |

## Emitter-before-dispatch ordering

`EvalExecuteInternalController.executeRun` creates and registers the SSE emitter with
`TestSuiteRunSseService` **before** calling `TestSuiteEvaluationJob.dispatch`, not after. `dispatch`
submits the job onto a separate executor and returns immediately; the job runs asynchronously and can
reach a terminal state (e.g. an immediate snapshot-phase failure) and call its terminal
`notifySse`-equivalent within microseconds. If the emitter were created only after `dispatch` returned,
that terminal notification could iterate `TestSuiteRunSseService`'s currently-registered emitters and find
none for this run — the notification is silently missed, and the SSE stream returned to the caller then
sits open with no status event until `SseEmitter`'s own timeout (`test-suite-run.sse.timeout-minutes`, 30
minutes by default) elapses, wasting a virtual thread and holding the PRK alive for no benefit.

Registering the emitter first guarantees it is present before the job can possibly notify. The remaining
risk — `dispatch` itself throwing (`RunAlreadyActiveException`, or an `Error` from executor submission
failure) — is handled by discarding the already-created emitter via
`TestSuiteRunSseService.discardEmitter(emitter)` before rethrowing, so no orphaned emitter is left tracked
for a run that was never actually dispatched. `discardEmitter` cannot rely on `emitter.complete()` here
because Spring MVC has not yet attached this emitter to the request's async context (that only happens once
the emitter is returned as the response body), so the emitter's own `onCompletion` callback would never
fire; `discardEmitter` instead removes the emitter from `TestSuiteRunSseService`'s tracking maps directly.

This ordering is specific to the internal endpoint's dispatch-then-stream pattern. The public
`GET /api/v1/test-suite-runs/status-stream` endpoint (`TestSuiteRunSseController`, via
`TestSuiteRunSseService.createEmitter`) only ever subscribes to runs that already exist and are not being
dispatched by that same request, so it has no equivalent race and is unaffected by this change.

## Security

`/api/internal/**` is added to the same authenticated matcher set as `/api/v1/**` in
`SecurityConfiguration` — not `permitAll`. It is registered unconditionally (harmless when the flag is off,
since `EvalExecuteInternalController` itself doesn't exist then and the path 404s). Network isolation
(keeping `/api/internal/**` off public ingress) is documented as a recommended, non-required, additional
layer.

## Testing tips

- `DialAppProperties` validation: assert it throws when `enabled=true` and the `ApiKeyProperties` bean is
  absent (simulate by not enabling `config.rest.security.api-key.enabled` in the test context) or present
  but `isEnabled()==false`.
- `TestSuiteRunService` dispatch branch: cover all three cases — flag off; flag on with a non-null
  credential (route trigger called); flag on with a null credential (falls back to direct dispatch, import
  flow).
- `EvalExecuteInternalController`: assert `dispatch`'s `RunAlreadyActiveException` surfaces as HTTP 409 via
  `DefaultExceptionHandler` (not a local catch-and-map), and that on that path
  `TestSuiteRunSseService.discardEmitter` is called so the pre-created emitter is not left tracked. Also
  assert the emitter is created before `dispatch` is invoked (e.g. via invocation-order verification).
