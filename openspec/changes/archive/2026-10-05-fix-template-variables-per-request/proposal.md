## Why

Both template-variable endpoints extract variables only from the suite's own `requestTemplate` (request #0) and never read `additionalRequests`, so for a multi-request suite every request after the first reports "No Variables" in the Try out panel ([GH #217](https://github.com/epam/ai-dial-admin-evaluation-framework-backend/issues/217)). The runtime already treats each chain request independently (own `endpointRef`/`requestTemplate`/`inputBindings`), so the convenience API is simply out of step with execution.

## What Changes

- **BREAKING**: `GET /api/v1/test-suites/{id}/template-variables` and `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables` return a JSON object keyed by **request index** (`"0"` = the suite's own request, `"n"` = `additionalRequests[n-1]`) whose values are that request's `List<TemplateVariableDto>`, instead of a flat JSON array.
- Every chain index `0..N` is always present, in chain order; a request without placeholders (or with a null template) maps to `[]`.
- Each additional request's variables are extracted from **its own** `requestTemplate`, bindings resolved from **its own** `inputBindings`, and `effectiveType` inferred from **its own** `endpointRef` — no inheritance from request #0, mirroring `RequestChainExecutor`. The dataset test-case schema and (test-case endpoint) the test case's `data` are shared across the chain.
- Single-request `DEPLOYMENT` suites and `MCP_TOOL` suites return a single-entry map `{"0": [...]}`.
- `TemplateVariableDto` itself is unchanged; OpenAPI response schema and the four example files are updated to the map shape.

Non-goals:
- No `requestIndex` query parameter / single-request selection (rejected: per-tab calls; the map gives the FE the whole chain in one call).
- No per-turn (`multiTurnData`) resolution of `resolvedValue` — test-case endpoint keeps resolving from shared `data` only, as today.
- No change to binding validation (`BindingValidator` / `SuiteValidationService`), which already validates each request.
- No MCP chaining (still rejected at write time).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `request-template`: the two template-variable convenience API requirements change response shape (map by request index) and gain per-request extraction for `additionalRequests`; the MCP template-variables requirement's empty-result scenario becomes `{"0": []}`.

## Impact

- **Code**: `service.domain.TemplateVariableService` (chain iteration, return type), `web.controller.TemplateVariableController` (return type, OpenAPI annotations). No new packages or classes; no DB, Flyway, or configuration changes.
- **API**: breaking response-shape change on two endpoints; OpenAPI examples `src/main/resources/openapi/examples/api-v1-test-suites-testSuiteId{,-test-cases-testCaseId}-template-variables-GET-response-200-{minimal,full}.json` rewritten and renamed to match the registered path keys (old names never attached).
- **Tests**: `TemplateVariableServiceTest` plus functional tests asserting the array shape (`TemplateVariableFunctionalTests`, `McpTryItOutFunctionalTests` — the only endpoint callers) updated; stale "endpoint was removed" comments in `TestCaseConvenienceApiFunctionalTests` / `McpTryItOutFunctionalTests` corrected; new chain repro test and `/v3/api-docs` schema/example assertion.
- **Consumers**: admin FE (Try out panel, suite editor) must read `response[requestIndex]` — release in lockstep; flag in PR and release notes. No backend/MCP-tool/eval-cli consumer of these endpoints.
- **Rollout**: single PR; FE handoff via `fe-api-handoff` after implementation.
- **Test plan**: unit — keys `0..N` in order, request 1 uses own bindings and endpoint types, empty request → `[]`, single-request and MCP → `{0: …}`, test-case-level `resolvedValue` for request 1 from test case `data`; functional — GH #217 repro (GET `/settings` then POST `/chat/completions` with `user_message`, `temperature`) on both endpoints; OpenAPI example presence in `/v3/api-docs`.
