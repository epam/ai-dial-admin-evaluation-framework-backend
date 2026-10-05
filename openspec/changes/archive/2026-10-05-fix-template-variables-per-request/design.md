## Context

See proposal.md — Why. `TemplateVariableService` has two public entry points (suite-level, test-case-level) that both funnel into `resolveVariables(template, bindings, testCaseSchema, endpoint, data)` (HTTP) or `resolveMcpVariables(...)` (MCP). `resolveVariables` is already request-agnostic: it takes one request's template/bindings/endpoint. The only missing piece is iterating the chain. `JsonbMapper.mapAdditionalRequests(String)` yields `List<RequestDefinitionDto>` (`name`, `endpointRef`, `requestTemplate`, `responseColumns`, `inputBindings`); the runtime (`RequestChainExecutor.buildSpecs`) uses each definition's own fields with no fallback to request #0. The only caller of the service is `TemplateVariableController`.

## Goals / Non-Goals

**Goals:**
- Both endpoints return the full chain keyed by request index, matching runtime per-request semantics.
- Minimal surface: no new classes, no change to `TemplateVariableDto`, `resolveVariables` reused as-is.

**Non-Goals:**
- Accumulated-frame awareness (variables that reference prior requests' columns are just placeholders here — no resolution against the frame).
- Per-turn `resolvedValue`.

## Decisions

### D1 — Response type `Map<Integer, List<TemplateVariableDto>>` (LinkedHashMap, chain order)
Jackson writes integer keys as decimal strings (`"0"`, `"1"`). `LinkedHashMap` keeps insertion order, so chain-order keys are a server guarantee (spec: "in chain order"); clients still index by key.
- *Alt: `requestIndex` query param* — rejected by the user; FE would need one call per tab.
- *Alt: wrapper DTO `{"requests": {...}}` or a list of `{requestIndex, requestName, variables}`* — more extensible (could carry `name`), but the user asked for a plain map. The FE already knows request names from the suite.
- *Alt: flat list with `requestIndex` on each DTO* — rejected: same variable name may appear in several requests with different bindings; flat grouping by name breaks.

### D2 — Always emit every index `0..N`
Even an empty/null template emits `key → []`. Lets the FE treat a missing key as a bug, not "no variables", and matches the chain length the FE renders tabs for.

### D3 — Chain assembly inside `TemplateVariableService` (private helper)
A private `buildChainVariables(TestSuite suite, List<FieldDefinitionDto> testCaseSchema, Map<String, Object> data)`:
1. `MCP_TOOL` → `Map.of(0, resolveMcpVariables(...))` (wrapped in `LinkedHashMap` for type uniformity). `additionalRequests` is ignored — write-time validation guarantees `[]`.
2. `DEPLOYMENT` (non-MCP) → put `0 → resolveVariables(suite.requestTemplate, suite.inputBindings, schema, suite.endpointRef, data)`; then for `i` over `jsonbMapper.mapAdditionalRequests(suite.getAdditionalRequests())` (null-safe → empty) put `i + 1 → resolveVariables(def.requestTemplate, def.inputBindings, schema, def.endpointRef, data)`.

Both public methods keep their existing guards (404s, unbound-suite handling) and just replace the final branch with this helper. A private method rather than a new component: it is pure orchestration over already-injected collaborators with no reusable conversion logic (AGENTS.md "specialized injectable components" targets conversion/validation logic, which stays in `TemplateVariableExtractor` / `TemplateVariableResolver`). Tested through the public methods.

Null `RequestDefinitionDto` elements cannot occur (rejected at write time by `TestSuiteRequestValidator.validateNoNullAdditionalRequests`), so no null-element guard.

### D4 — Shared inputs loaded once
Dataset schema (`datasetSchemaProvider.getSchema`) and test case `data` are loaded once per call and passed to each request's resolution. Chain length ≤ 11 (`MAX_ADDITIONAL_REQUESTS` = 10), so per-request work (endpoint schema extraction, binding maps) is negligible. Transaction boundary unchanged (`@Transactional(metaTransactionManager, readOnly = true)` on each public method).

### D5 — OpenAPI
Controller methods return `Map<Integer, List<TemplateVariableDto>>`, both `@GetMapping`s declare `produces = MediaType.APPLICATION_JSON_VALUE`, and the 200 `@ApiResponse` carries **no `content`** — springdoc infers `{"type":"object","additionalProperties":{"type":"array","items":{"$ref":"…/TemplateVariableDto"}}}` from the return type, and `produces` places it under `application/json` instead of `*/*`.
- *Tried: `@Content(schema = @Schema(type = "object"), additionalPropertiesArraySchema = …)`* — springdoc (swagger-annotations 2.2.47) drops `additionalPropertiesArraySchema`, rendering a bare `{"type":"object"}`.
- *Tried: `@Content(mediaType = "application/json")` without schema* — renders an empty schema.

Examples: `OpenApiExampleCustomizer.pathToKey` keeps path-variable names (braces stripped), so the files are named `api-v1-test-suites-testSuiteId-template-variables-GET-response-200-{minimal,full}.json` and `api-v1-test-suites-testSuiteId-test-cases-testCaseId-template-variables-GET-response-200-{minimal,full}.json`. The previous names (without `testSuiteId`/`testCaseId`) never matched, so these operations had silently shipped without examples; the rename fixes that. `minimal` = `{"0": [ … ]}`, `full` = two-request chain. A functional test asserts the rendered schema and example presence. OpenAPI descriptions document the current shape only; BREAKING wording lives in PR / release notes / FE handoff.

### Error handling
No new error paths. Malformed `additionalRequests` JSONB surfaces exactly as it does today in other readers (`JsonbMapper` fail-fast).

## Risks / Trade-offs

- [Breaking response shape; FE reading an array gets an object] → release BE and FE together; mark **BREAKING** in PR / release notes; produce FE handoff.
- [JSON object keys are strings, FE might expect numbers] → document in OpenAPI description and handoff (`response[String(index)]`).
- [Additional request references prior columns (e.g. `$configId` in JSONata) — not template variables, so not listed] → expected; consistent with current request #0 behaviour.

## Migration Plan

Single deploy, no data migration. Rollback = revert the PR (FE must roll back in lockstep).
