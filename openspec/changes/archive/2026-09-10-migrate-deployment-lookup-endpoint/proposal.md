## Why

The type-less deployment lookup (`GET /api/v1/deployments/all/**`) currently resolves a deployment by probing DIAL Core's `/openai/models/{id}`, `/openai/applications/{id}`, and `/openai/toolsets/{id}` concurrently and collapsing the three outcomes with type-precedence/severity-ordered rules — a workaround that exists only because no single-call alternative was available. DIAL Core now exposes a unified `GET /v1/deployments/{id}` endpoint that resolves the type itself, making the 3-probe fan-out and its collapse logic unnecessary overhead and complexity. Separately, DIAL Core's `interfaces` field (which invocation APIs — chat, mcp, openaiResponses, anthropicMessages, etc. — a deployment supports) is already deserialized internally but never exposed via the EF API, which blocks upcoming OpenAI Responses / Anthropic Messages API support work (GitHub issue #192).

## What Changes

- Replace the 3-probe fan-out in `DeploymentService.getDeployment(String)` with a single call to a new `DialCoreClient.getDeploymentById(String)`, backed by DIAL Core's `GET /v1/deployments/{id}`.
- Fix HTTP status propagation for the by-id lookup only: when `getDeploymentById` resolves to an upstream 404, the by-id endpoint returns a real `404 NOT_FOUND` instead of the generic `502 UPSTREAM_NOT_FOUND`. This is scoped to the by-id call site (a dedicated not-found exception raised from `DeploymentService`/`DeploymentController` that bypasses the shared `DialCoreErrorMapper`) — the shared mapper itself is unchanged, so every other `DialCoreClient` consumer, including the by-type deployment lookup, keeps today's `502` behavior on 404. Non-breaking.
- Expose a new `interfaces` field on `DeploymentInfoDto` (reusing the existing `client.dialcore.dto.InterfaceType` enum), populated on the list (`GET /api/v1/deployments`) and by-id (`GET /api/v1/deployments/all/**`) responses; explicitly absent on by-type (`GET /api/v1/deployments/{deploymentType}/**`) responses.
- Delete `DeploymentProbe` and `DeploymentProbeCollapser` and their dedicated unit tests; trim the token-propagation test that only existed to cover the removed fan-out.
- Update `DeploymentController` OpenAPI docs/examples and by-id/by-type `@ApiResponse`s to reflect single-request semantics and corrected 404 status.
- Add a documentation placeholder (no invented version number) noting a forthcoming minimum DIAL Core version requirement.

## Capabilities

### New Capabilities

(none — this extends existing deployment-lookup behavior rather than introducing a new capability)

### Modified Capabilities

- `dial-core-client`: the "Get deployment by ID across all types" requirement changes from probe/collapse semantics to a single unified-endpoint call, including a dedicated 404 response for that endpoint only; the by-type lookup's not-found scenario is unchanged (`502`); a new requirement is added for `interfaces` exposure (present on list/by-id, absent on by-type).

## Impact

- **Code**: `DialCoreClient` (new `getDeploymentById` method), `DeploymentService` (rewritten `getDeployment(String)`, deleted probe-fan-out helpers, new not-found translation for the by-id call site), `DeploymentProbe`/`DeploymentProbeCollapser` (deleted), `DeploymentMapper` (new `interfaces` mapping, explicit `ignore` on shared by-type mapping methods), `DeploymentInfoDto` (new field), `DeploymentController` (OpenAPI doc updates). `DialCoreErrorMapper` (in `evaluation-runner-core`) is **not** modified.
- **API**: `GET /api/v1/deployments/all/**` now issues exactly one upstream request per call and returns real `404`/`403`/`400` statuses instead of a unified `502` for not-found; `GET /api/v1/deployments` and `GET /api/v1/deployments/all/**` responses gain an `interfaces` array field. `GET /api/v1/deployments/{deploymentType}/**` is unaffected — its not-found response stays `502`.
- **OpenAPI examples**: `api-v1-deployments-all-GET-response-200-*.json` and `api-v1-deployments-GET-response-200-*.json` need `interfaces` added; by-type examples are unaffected.
- **Tests**: functional tests covering probe/collapse behavior are replaced with single-stub equivalents; mapper/DTO unit tests gain `interfaces` coverage; a new `DialCoreClientTest` validates single-object polymorphic deserialization for the new endpoint.
- **No DB schema changes.**
- **Docs**: `README.md` and `docs/configuration.md` §5.1 get a placeholder note for the minimum DIAL Core version (exact version TBD at implementation time).
