# Proposal: Add OpenAPI request/response examples

## Why

Frontend, other backends, and BA need concrete request/response examples in the OpenAPI spec and Swagger UI so they can integrate and test without guessing payloads. Today the spec is generated from code but has no examples; adding them makes the API contract the single source of truth for both structure and realistic usage. Defining a clear requirement for examples (including a “minimal + full” rule) ensures consistency and makes the rule enforceable (e.g. during spec sync or review).

## What Changes

- Add **request and response examples** to the OpenAPI spec:
  - Use `@Schema(example = "…")` (or `examples`) on DTO fields where helpful.
  - For operation-level examples (minimal + full), store JSON files under `src/main/resources/openapi/examples/` and inject them via `OpenApiExampleCustomizer`; controllers declare only schema in @Content/@RequestBody.
- Introduce a **global OpenAPI requirement** (to be reflected in main specs after sync): **all APIs/endpoints must have at least two request examples—one minimal, one fully filled—when it makes sense.** This rule can be skipped for endpoints that have no parameters or only very simple parameters/body (e.g. a single optional query param or a trivial body), so we avoid noise where examples add little value.
- Document the convention (and the minimal + full rule) in AGENTS.md or docs so BE applies it when adding or changing endpoints.
- Add an **OpenSpec global rule** (and state it in the openapi-examples spec): **when changing an endpoint or its request/response definitions, update existing OpenAPI examples to reflect the change.** AI agents and developers must keep examples in sync with the API so consumers (FE, other BE, BA) always see accurate payloads.

## Capabilities

### New Capabilities

- **openapi-examples**: How we add and maintain request/response examples in the OpenAPI spec; includes (1) the requirement that endpoints have at least two request examples (minimal and fully filled) when applicable, with an explicit exception for no- or trivial-parameter cases, and (2) the requirement that when an endpoint or its request/response is changed, existing examples must be updated (AI agents and developers must keep examples in sync).

### Modified Capabilities

- None. This does not change API behavior or existing capability specs (test-suites, metrics-system, etc.); it only enriches documentation and adds a documentation requirement.

## Impact

- **Code:** DTOs gain @Schema(example = …); operation-level examples live in JSON under openapi/examples/ and are injected by OpenApiExampleCustomizer (controllers keep only schema). No change to API contracts or runtime behavior.
- **Docs:** AGENTS.md and/or docs (e.g. configuration or a new api-documentation.md) will describe the example convention and the “minimal + full” rule.
- **Specs:** After sync, the global OpenAPI/spec documentation will state both requirements (minimal + full examples where applicable, and update examples when changing endpoints/request/response), so the rules are visible and enforceable in review or by AI agents.
