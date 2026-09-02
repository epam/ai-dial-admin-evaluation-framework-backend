# Look up a deployment by ID across all types

## Why

Every existing way to fetch a full deployment representation requires the caller to already know the deployment's type: `GET /api/v1/deployments/{deploymentType}/**` needs `dial-model` | `dial-application` | `dial-toolset` in the path. Consumers that hold only a persisted deployment ID — a suite's `deploymentRef`, an ID pasted by a user, a link from an external system — cannot use it. Their options today are both bad: guess a type and retry on failure (up to three sequential round-trips, each failure indistinguishable from a real error), or fetch the whole `GET /api/v1/deployments` catalog and scan it (which returns only the short projection, so a second typed call is still needed).

DIAL Core deployment IDs are globally unique across models, applications and toolsets, so the type is derivable — the API should derive it instead of demanding it.

## What Changes

- **New endpoint** `GET /api/v1/deployments/all/**` returns a single deployment by ID with no type in the request. Everything after `/all/` is the deployment ID, so slash-containing IDs work exactly as they do on the typed endpoint (same `WildcardPathResolver`, decoded exactly once). The response is the existing polymorphic `DeploymentInfoDto` — its `$type` discriminator tells the caller which type was found, and the payload is byte-identical to what the typed endpoint returns for the same deployment (including resolved `routes` for applications).
- **Service-level parallel probe**: `DeploymentService.getDeployment(String)` fans out three concurrent DIAL Core lookups — `/openai/models/{id}`, `/openai/applications/{id}`, `/openai/toolsets/{id}` — on virtual threads, so worst-case latency is one round-trip, not three. The user's JWT is captured before the fan-out and propagated via `TokenPropagationHelper` (ThreadLocal does not cross the thread boundary).
- **Outcome collapsing** — one exit path per outcome class:
  - At least one probe returns a non-empty body ⇒ that deployment is returned. If more than one does (an ID collision Core is not supposed to allow), a fixed precedence `dial-model > dial-application > dial-toolset` decides, and the collision is logged at WARN.
  - No probe returns a body ⇒ the three failures are **unified into one** `DialCoreClientException`, whose upstream status is chosen by severity `401 > 403 > other 5xx/4xx > 404`, with a message naming each leg's outcome (`models=404, applications=401, toolsets=404`). Severity ordering exists so an auth failure or a Core outage is never reported as "not found" merely because the other two legs 404'd. Mapping to the client response is the existing `DialCoreErrorMapper` + `DefaultExceptionHandler`; no new error codes.
  - An all-404 lookup therefore surfaces as **HTTP 502 / `UPSTREAM_NOT_FOUND`**, identical to the typed endpoint's behavior for a missing deployment. Chosen for consistency over a more literal 404; see design.
- **Routing**: the literal `/all/**` mapping wins over the sibling `/{deploymentType}/**` capture by PathPattern specificity — an implicit framework guarantee, so it gets a regression test (mirroring the existing `/tools` precedence test).
- **OpenAPI**: full `@Operation` + `@ApiResponse` set on the new handler (the working-tree stub carries none), plus the `$type`-per-outcome note that makes a type-less lookup readable in Swagger UI.
- **OpenAPI example wiring for wildcard mappings**: SpringDoc registers a trailing-wildcard mapping with the `/**` intact, so the example-file key for this endpoint would contain `*` — illegal in a Windows filename. `OpenApiExampleCustomizer#pathToKey` now drops a trailing `/**`. This also revealed that the sibling deployment endpoints' example files had been dead since PR #131 changed their mapping to `/{deploymentType}/**` (named for the pre-wildcard path shape, silently never injected); they are renamed to their real keys, and a test on `/v3/api-docs` now pins the wiring so it cannot rot unnoticed again.
- **Spec correction (no code change)**: `dial-core-client`'s existing *Get deployment by type and ID* requirement claims a Core 404 yields "HTTP 404 Not Found". The implementation and `DeploymentFunctionalTests.getDeploymentWhenNotFoundYields502AndUpstreamNotFoundCode` have long returned 502 / `UPSTREAM_NOT_FOUND`. That scenario is corrected to match reality so it does not contradict the new requirement sitting beside it.

Explicitly **not** changing:
- The typed `/{deploymentType}/**` endpoint, the listing endpoint, and `/tools` keep their current behavior and payloads.
- `DialCoreClient` gains no new methods — the probe composes the three existing `getModel`/`getApplication`/`getToolset` calls.
- No DTO changes, no new error codes, no DB schema, no Flyway migration, no configuration properties.
- No response caching or negative caching of misses.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `openapi-examples`: adds a requirement that example files resolve for trailing-wildcard endpoint mappings (key derived with the trailing `/**` dropped, no `*` in filenames, wiring covered by a test on the generated document).
- `dial-core-client`: adds the type-less by-ID lookup requirement (`GET /api/v1/deployments/all/**` — parallel three-way probe, hit precedence, unified error with severity ordering, routing precedence over the typed wildcard) and corrects the existing not-found scenario on *Get deployment by type and ID* from HTTP 404 to HTTP 502 / `UPSTREAM_NOT_FOUND` to match the shipped behavior.

## Impact

- **Code** (main app only; nothing in `evaluation-runner-core` or `eval-cli`):
  - `web/controller/DeploymentController.java` — new `/all/**` handler (already stubbed in the working tree as `/any/**`; renamed) with full OpenAPI annotations; the existing empty-ID check is shared with the typed handler.
  - `service/domain/DeploymentService.java` — implements the `getDeployment(String)` overload: parallel probe, outcome collapsing, winner mapped through the existing per-type logic so an application winner still gets `SchemaRouteExtractor.resolveRoutes` (and the losing legs never trigger a schema fetch).
  - `configuration/OpenApiExampleCustomizer.java` — `pathToKey` drops a trailing `/**`; six pre-existing `src/main/resources/openapi/examples/api-v1-deployments-*` files renamed to their real keys, plus two new files for this endpoint.
  - A new injectable component under `service/domain/` is expected for the collapse rules (hit precedence + error unification) rather than private methods in the service, per the layering principle; final shape decided in design.
- **API**: purely additive — one new endpoint. No existing request or response contract moves.
- **Security**: no new auth surface; the endpoint is authenticated like its siblings and the caller's JWT gates all three probes, so a user sees only deployments Core lets them see. A 403 on one leg is surfaced as 403 rather than folded into not-found (existing `DialCoreErrorMapper` pass-through) — noted in design as an existence-disclosure trade-off already made by the typed endpoint.
- **Load on DIAL Core**: 3 requests per lookup instead of 1, all authenticated as the caller. Misses are not retried (`DialCoreClient` retries only 408/429/5xx), so the common case costs three cheap calls. The alternative (list-then-fetch) is compared in design.
- **Risks**: the "globally unique ID" premise is an assumption about Core, not something this service enforces — if `/openai/models/{id}` also resolves application IDs, the precedence rule becomes load-bearing semantics rather than a defensive log line. Worth one probe against a dev Core before implementation; the precedence rule plus the WARN log is the mitigation either way.
- **Tests**: functional (`DeploymentFunctionalTests`, mocked `DialCoreClient` bean, registered via `PostgresFunctionalTests`) for each hit type, `$type` in the body, multi-hit precedence, every severity branch, all-404, slash-containing ID, percent-decode-once, empty ID → 400 with no client calls, and `/all/x` not falling through to the typed handler; unit coverage for token propagation across all three legs.
- **Docs**: no `docs/configuration.md` or `docs/database-schema.md` impact. `openspec/specs/README.md` needs no new row (no new spec folder). Delta syncs into `openspec/specs/dial-core-client/spec.md` at archive.
- **Rollout**: additive endpoint, no migration ordering, no feature flag; safe in a regular release.
