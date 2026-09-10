## Context

`GET /api/v1/deployments/all/**` (type-less deployment lookup) resolves a deployment by fanning out 3 concurrent probes to DIAL Core (`GET /openai/models/{id}`, `/openai/applications/{id}`, `/openai/toolsets/{id}`) on virtual threads, then collapsing the 3 outcomes via `DeploymentProbeCollapser` (type-precedence for hits; severity-ordered status unification for misses). DIAL Core now exposes `GET /v1/deployments/{id}`, which resolves the type itself, making the fan-out and collapse logic obsolete. Separately, `DialCoreDeploymentDto.interfaces` is already deserialized from DIAL Core responses but dropped during mapping to the API DTOs, blocking future OpenAI Responses / Anthropic Messages API support work.

The client's `RestClient`-based `DialCoreClient` already deserializes polymorphic single-object DTOs directly (`getModel`, `getApplication`, `getToolset`) via `@JsonTypeInfo`/`@JsonSubTypes` on `DialCoreDeploymentDto` and its subtypes; only the *list* endpoint (`getDeployments`) needs a `JsonNode`-then-`convertValue` workaround, because Jackson can't resolve subtype polymorphism through a generic `List<T>` target via the RestClient message converter.

`DialCoreClientException` (thrown by `DialCoreClient.withRetry`) always carries the *raw* upstream `HttpStatusCode` — the generic-to-app-status translation happens later, once, in `DefaultExceptionHandler.handleDialCoreClientException` via `DialCoreErrorMapper`, which is shared by every `DialCoreClient` consumer across the app. `DialCoreErrorCode` already has an unused `NOT_FOUND` constant (distinct from `UPSTREAM_NOT_FOUND`) with existing handling in `DefaultExceptionHandler.toErrorCode`, but nothing currently produces it.

## Goals / Non-Goals

**Goals:**
- Single upstream request per `GET /api/v1/deployments/all/{id}` call, resolved via `GET /v1/deployments/{id}`.
- The by-id endpoint returns real `403`/`404`/`400` statuses matching the upstream response, instead of the collapsed `502` outcomes the probe fan-out could produce.
- `interfaces` exposed on list and by-id responses; absent on by-type responses.
- Full removal of `DeploymentProbe`/`DeploymentProbeCollapser` and their dedicated tests.

**Non-Goals:**
- Changing the by-type lookup's (`GET /api/v1/deployments/{deploymentType}/**`) behavior or status codes — it keeps mapping upstream 404 → `502 UPSTREAM_NOT_FOUND` via the untouched shared `DialCoreErrorMapper`.
- Changing `DialCoreErrorMapper` or any other `DialCoreClient` consumer's error semantics.
- Introducing a duplicate API-level enum for `interfaces` — this design reuses the existing `client.dialcore.dto.InterfaceType`.
- Determining the exact minimum DIAL Core version (left as a doc placeholder).

## Decisions

**1. New `DialCoreClient.getDeploymentById(String id)` returns `DialCoreDeploymentDto` directly, no `JsonNode` workaround.**
Mirrors the existing `getModel`/`getApplication`/`getToolset` pattern: `withRetry(path, () -> get(path, DialCoreDeploymentDto.class))`. A single-object body doesn't hit the generic-list polymorphism limitation that forces `getDeployments` to go through `JsonNode` + `objectMapper.convertValue`. This assumption will be verified by a new `DialCoreClientTest` (via `MockRestServiceServer`) written before/alongside the implementation; if direct deserialization doesn't resolve the subtype correctly, fall back to the same `JsonNode`-convert workaround `getDeployments` uses.
*Alternative considered*: always route single-item fetches through the `JsonNode` workaround for consistency with `getDeployments`. Rejected — adds unnecessary indirection when the simpler direct-deserialization path already works for three sibling methods on the same DTO family.

**2. `DeploymentService.getDeployment(String)` becomes one synchronous call; probe/collapse machinery is deleted outright, not deprecated.**
```java
public DeploymentInfoDto getDeployment(String deploymentId) {
    DialCoreDeploymentDto deployment = dialCoreClient.getDeploymentById(deploymentId);
    DeploymentInfoDto dto = toDeploymentInfoDto(deployment); // existing private method, reused as-is
    dto.setInterfaces(deployment.getInterfaces());
    return dto;
}
```
This reuses the existing private `toDeploymentInfoDto(DialCoreDeploymentDto)` switch (shared with the old probe path), so the model/application(+route-resolution)/toolset mapping logic is unchanged. `DeploymentProbe`, `DeploymentProbeCollapser`, and the now-dead private helpers (`probeAsync`, `probe`, `logProbeFailure`, `awaitProbe`, `asDialCoreClientException`) are deleted rather than kept dormant — there is no future caller for probe-based resolution once Core's unified endpoint exists, and dead fan-out/collapse code carrying non-obvious severity ordering is a maintenance liability.
*Consequence*: the by-id path no longer needs `TokenPropagationHelper` or a virtual-thread executor — a single synchronous call on the request thread keeps `AuthorizationTokenHolder` (a `ThreadLocal`) trivially in scope with no thread hop.

**3. By-id 404 status is fixed locally, not via the shared `DialCoreErrorMapper`.**
`DeploymentService.getDeployment(String)` (or `DeploymentController`, whichever keeps the translation closest to the single call site) catches the `DialCoreClientException` thrown by `getDeploymentById`; when `getStatusCode()` is 404, it throws a distinct exception that resolves straight to `404 NOT_FOUND` — bypassing `DialCoreErrorMapper` for this one call site — and rethrows the original `DialCoreClientException` unchanged for every other status, which continues through the normal shared `DefaultExceptionHandler.handleDialCoreClientException` path (403 passthrough, 401/5xx → 502, etc.).
*Alternative considered*: fix `DialCoreErrorMapper`'s 404 branch globally (it already has an unused `NOT_FOUND` `DialCoreErrorCode`/`toErrorCode` mapping seemingly intended for this). Rejected for this change — it would also flip the by-type lookup's 404 response from `502` to `404`, a behavior change beyond this issue's scope and blast radius the user chose not to take on here.
*Trade-off accepted*: the not-found special-case exists only for the by-id path; the same 404-mapped-to-502 quirk remains for the by-type lookup and any other `DialCoreClient` consumer.

**4. `interfaces` reuses `client.dialcore.dto.InterfaceType`; no duplicate API-level enum.**
`LayeredArchitectureTest` does not constrain the `client` package, and `InterfaceType` already crosses into `service`/`web` today (`DeploymentController`'s `interface` query param, `DeploymentService.getAllDeployments(DeploymentType, InterfaceType)`). Adding a mirror enum would add mapping code with no layering benefit.

**5. `interfaces` presence is split by MapStruct method, not by a runtime flag.**
- `DeploymentMapper.toDeploymentInfoShortDto` (list-only, hand-written default method) maps `interfaces` directly in each builder chain.
- `toDialModelInfoDto`/`toDialApplicationInfoDto`/`toToolsetInfoDto` (shared by both by-type and by-id) get an explicit `@Mapping(target = "interfaces", ignore = true)` — without it, MapStruct's implicit same-name matching would auto-map the field from `DialCoreModelDto`/etc.'s inherited `getInterfaces()`.
- `DeploymentService.getDeployment(String)` is the single place that calls `dto.setInterfaces(...)` after the shared mapping, so by-id responses carry it and by-type responses (which never call `setInterfaces`) don't.
*Alternative considered*: a mapper parameter/context flag toggling `interfaces` inclusion. Rejected — needlessly dynamic for a static, call-site-determined distinction; the `ignore = true` + explicit `setInterfaces` split is simpler to read and test.

## Risks / Trade-offs

- **[Risk]** Single-object polymorphic deserialization for `/v1/deployments/{id}` might not work identically to the existing single-item methods if Core's response envelope differs subtly. → **Mitigation**: write the `DialCoreClientTest` for `getDeploymentById` first; fall back to the `JsonNode`-convert pattern used by `getDeployments` if direct deserialization fails.
- **[Risk]** The by-type lookup keeps returning `502` for not-found, which is arguably the same underlying bug the by-id fix addresses — a future reader may find the inconsistency between by-id (`404`) and by-type (`502`) confusing. → **Mitigation**: the design decision (§3) and this doc make the scoping explicit; a follow-up issue can revisit the shared mapper later if desired.
- **[Risk]** Deleting `DeploymentProbe`/`DeploymentProbeCollapser` removes any residual capability to resolve IDs when a future Core deployment type isn't covered by the unified endpoint. → **Mitigation**: none needed today — Core's unified endpoint is authoritative for all current and foreseeable deployment types; reintroducing probing would require reintroducing this exact code, which is preserved in git history.

## Migration Plan

No deployment/rollback complexity: this is a backend-only behavior change with no schema or data migration. Roll out as a normal PR/merge; no feature flag needed since the by-id endpoint's client-visible contract only *improves* (real error statuses, extra `interfaces` field) and its 200-response shape is otherwise compatible with existing consumers (`interfaces` is an additive field, `@JsonInclude` conventions already tolerate new fields for forward compatibility). Rollback is a plain revert if issues surface.

## Open Questions

- Exact minimum DIAL Core version required for `/v1/deployments/{id}` — left as a documentation placeholder per the proposal; to be filled in once confirmed against DIAL Core's release notes or by direct verification against a deployed Core instance.
