## Context

- `TestSuiteTryOutController.tryWithVariables` → `TryItOutService.tryWithVariables(UUID, Map<String, Object>)`.
- `TryItOutService` converts the flat map once (`convertVariablesToBindings`) and uses it on three paths:
  - MCP: `McpRequestResolver.resolveWithVariables(argumentTemplate, bindings, variables)`;
  - single-request HTTP: `requestResolver.resolve(template, bindings, Map.of())`;
  - chain: `runChain(plan, …, bindingsOverride)`, where `bindingsOverride` replaces **every** spec's `inputBindings` (`TryItOutService.java:201`).
- `RequestExecutionSpec.requestIndex()` is 0-based chain position (0 = suite request) — the same index the template-variables response is keyed by.
- `HttpMessageNotReadableException` is already mapped to 400 `VALIDATION_ERROR` (`DefaultExceptionHandler.handleWrongJsonError`), so a non-integer map key needs no new code.
- Previous change `fix-template-variables-per-request` (archived, same branch) made the response side `Map<Integer, List<TemplateVariableDto>>`.

## Goals / Non-Goals

**Goals:**
- Request body mirrors the template-variables response: `variables` keyed by request index.
- Per-request binding isolation in the chain; same name, different values.
- Reject impossible indices before any outbound call.

**Non-Goals:**
- Legacy flat-map compatibility.
- Pre-flight required-variable validation across the chain (request `i>0` failing on an unresolved required variable still surfaces mid-chain, as today).
- Changes to test-case try-out, `resolved-request`, or run execution.

## Decisions

### D1 — DTO type `Map<Integer, Map<String, Object>>`
Jackson deserializes string keys `"0"`, `"1"` into `Integer`; a non-numeric key (e.g. legacy `"prompt"`) fails deserialization → existing 400 handler. `@NotNull` stays on the outer map; inner values may be null (treated as empty). `@Schema` on the field documents the shape with an example.
*Alternative rejected:* `List<Map<String,Object>>` indexed positionally — forces FE to send placeholders for requests without variables and diverges from the response's object shape.

### D2 — New injectable `TryItOutVariableBindings` component (`service.domain`)
Per AGENTS.md (conversion/validation in injectable components), move `convertVariablesToBindings` out of `TryItOutService` into a `@Component @LogExecution` class:

```java
/** Returns request index → constant bindings for every index 0..requestCount-1 (absent/null entry → empty list). */
Map<Integer, List<InputBindingDto>> toBindingsByRequest(Map<Integer, Map<String, Object>> variables, int requestCount)
```
- Throws `service.domain.exception.ValidationException` (→ 400 `VALIDATION_ERROR`) for the **first** invalid key in iteration order:
  - null key → `variables: request index must not be null` (unreachable from JSON; guards Java callers);
  - `< 0` or `>= requestCount` → `"variables: request index " + k + " is out of range (chain length " + requestCount + ")"` — same wording style as `ResolvedRequestService`'s `requestIndex` check (`ResolvedRequestService.java:101-103`); one message for every suite kind (single-request and MCP have chain length 1).
- Pure (no dependencies), so unit tests use a real instance: `TryItOutServiceTest` constructs `new TryItOutVariableBindings()`, never a mock.
- Per-entry rules unchanged: blank name skipped, null value skipped.
- Returns a `LinkedHashMap` with every index present, so callers never null-check.

`requestCount`: MCP → 1; HTTP → `1 + additionalRequests.size()` (null list → 1).

### D3 — Validation timing
`toBindingsByRequest` is called right after `validateChainPreconditions` / `validateMcpPreconditions` and before any resolve/invoke, so an out-of-range index is a 400 with zero invocations. Ordering vs. existing precondition 400s: preconditions first (unchanged), then index validation.

### D4 — Path wiring in `TryItOutService.tryWithVariables(UUID, Map<Integer, Map<String,Object>>)`
- MCP: `bindings = byRequest.get(0)`; `resolveWithVariables(argumentTemplate, bindings, variablesOrEmpty(0))` — third arg is the request-0 inner map (or `Map.of()`), keeping the resolver's current data semantics.
- Single-request: `requestResolver.resolve(template, byRequest.get(0), Map.of())` — lenient path unchanged.
- Chain: `runChain(plan, deploymentRef, testSuiteId, byRequest)`; `runChain`'s last parameter becomes `Map<Integer, List<InputBindingDto>> bindingsByRequest` (null in test-case mode). Per spec: `bindingsByRequest != null ? bindingsByRequest.getOrDefault(spec.requestIndex(), List.of()) : spec.inputBindings()`. Javadoc's D8 sentence updated to "replaces that request's own bindings".

### D5 — Controller / OpenAPI
- Controller signature unchanged except the passthrough type; operation description gains one sentence on the indexed shape (`"0"` = suite request, `"n"` = `additionalRequests[n-1]`), 400 description adds "request index out of range".
- Request examples (registered path `/api/v1/test-suites/{testSuiteId}/try-it-out` → key `api-v1-test-suites-testSuiteId-try-it-out`): rewrite `…-POST-request-minimal.json` (`{"variables":{"0":{"prompt":"Hello"}}}`) and `…-POST-request-full.json` (index 0 with several vars); add `…-POST-request-chained.json` (indices 0 and 1, same name `user_message` with different values). `chained` is already in `OpenApiExampleCustomizer.EXAMPLE_NAMES`. Note: request examples are published as the raw file **text** (`new Example().value(value)`, `OpenApiExampleCustomizer.java:82-90`), unlike response examples which go through `parseJson`; the `/v3/api-docs` test therefore parses the example's text value. Changing the customizer is out of scope.

### D6 — Docs
`docs/patterns/multi-request-suites.md` "Try-out coverage": variables mode takes variables keyed by request index; each request resolves only with its own entry (replace semantics).

### D7 — Existing tests encode the old leak
`TryItOutFunctionalTests.shouldExecuteChainForVariablesMode` / `shouldStopVariablesChainAtFirstFailedRequest` (only request 1 uses `${{prompt}}`, sent flat) and `TryItOutServiceTest`'s chain test (verifies `resolveForRun(template1, <request-0 bindings>, …)`) assert the behaviour this change removes. They are re-keyed to the index that actually uses the variable (`"1"`), and comments mentioning "every chain element's bindings" are rewritten. Functional tests assert per-request values via `history[i].resolvedRequest.body` (DIAL Core is a `@MockitoBean` `DialCoreDeploymentInvoker`; there is no WireMock), and "zero invocations" via `verify(deploymentInvoker, never()).invokeWithStreaming(…)` / `verify(mcpToolInvoker, never())`.

## Risks / Trade-offs

- **FE/BE skew** → old FE flat map gets 400 on every try-out. Mitigation: same PR/release as template-variables change; one FE handoff.
- **Mid-chain required-variable failure** (missing entry for request `i>0`) still returns 400 after earlier requests were invoked, with no history. Accepted (pre-existing behaviour; frame values make up-front validation unsound). Noted in non-goals.
- **Key normalisation**: Jackson parses `"01"` as `1`; `{"1":…,"01":…}` collapses (last wins). Accepted — no FE would send this.
