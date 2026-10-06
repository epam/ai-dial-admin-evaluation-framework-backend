## Why

`POST /api/v1/test-suites/{testSuiteId}/try-it-out` takes one flat `variables` map, and for a multi-request suite `TryItOutService.runChain` applies the converted bindings to **every** chain request (design D8 of the multi-request change). A variable name used by two requests can therefore only carry one value, and every request receives every other request's variables. Since `fix-template-variables-per-request` the Try out panel shows variables grouped per request (`{"0": [...], "1": [...]}`), so the FE can display two values for `user_message` but cannot send them. The input contract must mirror the template-variables output.

## What Changes

- **BREAKING**: `TryItOutWithVariablesRequestDto.variables` becomes a JSON object keyed by **request index** (`"0"` = suite's own request, `"n"` = `additionalRequests[n-1]` — same semantics as the template-variables response and `resolved-request?requestIndex`), each value a `{variableName: value}` map. Java type `Map<Integer, Map<String, Object>>`.
- Request `i` of a chain resolves with constant bindings built **only** from `variables["i"]`, wholesale-replacing request `i`'s own `inputBindings` (D8 replace semantics kept, now per request). No value leaks between requests.
- A missing index (or `"i": null`) means "no variables for request `i`": its placeholders fall to defaults, or to the existing `Unresolved required template variables` 400.
- An index outside `0..N` (N = `additionalRequests.size()`), or any index other than `0` for single-request `DEPLOYMENT` and `MCP_TOOL` suites, is rejected with HTTP 400 `VALIDATION_ERROR` **before** any invocation. A non-integer key is a 400 via the existing malformed-JSON handler.
- Single-request and MCP suites read `variables["0"]`; per-entry rules (null value skipped, blank key skipped) are unchanged.
- OpenAPI request examples, operation description and the `multi-request-suites.md` try-out section updated.

Non-goals:
- No backward-compatible acceptance of the legacy flat map (ambiguous with a variable named `"0"`, and keeps two code paths alive). FE ships in lockstep with the template-variables change on the same branch.
- No change to test-case try-out (`…/test-cases/{testCaseId}/try-it-out`) — it already uses each request's own bindings.
- No up-front validation of required variables across the chain (frame values from earlier responses are unknown until run time); an unresolved required variable in request `i>0` keeps today's behaviour.
- No merge of variables over a request's own `inputBindings`.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `try-it-out`: the variables-mode requirements (HTTP, MCP, request DTO structure, single-turn) change the request shape to a map by request index and add per-request scoping plus index validation for chains.

## Impact

- **Code**: `service.domain.dto.TryItOutWithVariablesRequestDto` (field type, `@Schema`), `service.domain.TryItOutService` (`tryWithVariables`, `runChain` override becomes per-request, index validation), `web.controller.TestSuiteTryOutController` (signature passthrough, OpenAPI description). New class `service.domain.TryItOutVariableBindings` (`@Component`, takes over `convertVariablesToBindings` plus index validation); no new packages, no DB, Flyway or configuration changes.
- **API**: breaking request-body change on one endpoint; examples `api-v1-test-suites-testSuiteId-try-it-out-POST-request-{minimal,full}.json` rewritten; a `chained` request example added.
- **Tests**: `TryItOutServiceTest` variables tests, `TryItOutFunctionalTests`, `McpTryItOutFunctionalTests`, `PolymorphicBodyFunctionalTests` move to the new shape; new tests for same-name-different-value across a chain, missing index, out-of-range index, non-zero index on single-request/MCP suites, `/v3/api-docs` request example presence.
- **Consumers**: admin FE Try out panel must send `{"variables": {"0": {...}, "1": {...}}}` — release together with the template-variables response change (same PR). No eval-cli / MCP-tool consumer of this endpoint.
- **Rollout**: same branch/PR as `fix-template-variables-per-request`; one FE handoff covering both.
- **Risks**: FE/BE skew breaks try-out entirely until both deploy (old FE flat map → keys like `"prompt"` fail integer parsing → 400). Mitigated by lockstep release.
- **Test plan**: unit — chain with `user_message` in requests 0 and 1 resolves different values; request without an entry gets no bindings; index `N+1`/`-1` → 400 with zero invocations; single-request suite with key `1` → 400; MCP reads `"0"`, rejects `"1"`; null inner map treated as empty. Functional (DIAL Core mocked via `@MockitoBean DialCoreDeploymentInvoker`) — chain repro asserting both requests' resolved bodies in `history`; legacy flat body and out-of-range key → 400 with `never()` invocations; existing chain variables tests re-keyed to the index that uses the variable; OpenAPI request examples present.
