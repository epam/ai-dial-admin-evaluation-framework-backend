## Context

The backend uses SpringDoc OpenAPI to generate the API spec and Swagger UI from controller and DTO annotations. The spec is code-first; today it has no request/response examples, so FE, other BE, and BA must guess payloads. Adding examples and a clear maintenance rule makes the spec the single source of truth for both structure and realistic usage.

Constraints:

- No change to API contracts or runtime behavior; annotations only.
- Keep examples in code (no separate openapi.yml) so they stay in sync with DTOs and controllers.
- Rules must be enforceable in review and by AI agents (OpenSpec global rule + AGENTS.md).

## Goals / Non-Goals

**Goals:**

- Add request and response examples to the OpenAPI spec via `@Schema(example = …)` and `@Content(examples = @ExampleObject(...))`.
- Define and document two requirements: (1) at least two request examples (minimal + fully filled) per endpoint when applicable; (2) update existing examples when changing endpoints or request/response.
- Document conventions in AGENTS.md (and optionally docs) so BE and AI agents apply them consistently.

**Non-Goals:**

- Changing API behavior, DTO fields, or controller logic.
- Introducing a separate OpenAPI YAML file or code generation from spec.
- Postman/Insomnia collection generation or API portal tooling (can be added later).

## Decisions

### Where to put examples

- **DTOs:** Use `@Schema(example = "…")` on fields where it helps (e.g. name, description). For complex nested objects, one `@Schema(example = "{\"key\":\"value\"}")` or rely on operation-level examples.
- **Operations (request/response bodies):** Operation-level examples (minimal + full) are **not** inline in controller annotations. They live as JSON files under `src/main/resources/openapi/examples/` and are injected into the OpenAPI spec by `OpenApiExampleCustomizer`. Naming: `{pathKey}-{method}-request-{name}.json` or `{pathKey}-{method}-response-{status}-{name}.json` (pathKey = path with '/' → '-', braces stripped; name = "minimal" or "full"). Controllers declare only schema in `@Content`/`@RequestBody`; the customizer adds the examples from classpath so controllers stay lean.
- **Minimal vs full:** For endpoints that warrant two request/response examples, provide one JSON file with minimal required fields and one with all (or most) fields filled so consumers see both patterns.

**Alternative considered:** Inline `@ExampleObject` in controllers. Rejected to avoid controller bloat; resource-based JSON keeps examples editable without touching Java.
**Alternative considered:** Separate `openapi.yaml` with examples. Rejected to avoid drift; code-first + resource JSON keeps examples next to the codebase and in sync.

### Exception for “no/simple” endpoints

Endpoints with no query/body/path params (e.g. GET collection with defaults only) or a single trivial body/param do not require two examples; one example or none is acceptable. This avoids noise on endpoints where examples add little value.

### OpenSpec global rule and spec

- **OpenSpec config:** A `rules.global` entry (in `openspec/config.yaml`) states that when changing an endpoint or its request/response, existing OpenAPI examples must be updated. (Note: schema may not recognize `global`; the rule is also captured in the openapi-examples spec.)
- **openapi-examples spec:** The capability spec states both requirements (minimal + full when applicable; update examples on API change) so they can be synced to main specs and enforced by AI agents.

### Documentation

- **AGENTS.md:** Add a short “OpenAPI examples” subsection under conventions: use `@Schema`/`@ExampleObject`, minimal + full rule, exception for simple endpoints, and “update examples when changing endpoint/request/response.”
- **Optional:** `docs/api-documentation.md` or a subsection in `docs/configuration.md` for consumers (FE/BA) on where to find the spec and examples (Swagger UI, `/v3/api-docs`).

## Risks / Trade-offs

- **Examples can drift:** If someone changes a DTO and forgets to update examples, Swagger UI shows stale payloads. Mitigation: global rule + AGENTS.md + code review checklist; optional later: test that asserts example JSON conforms to schema.
- **Verbosity:** Avoided by storing operation-level examples in JSON files under `openapi/examples/` and injecting them via `OpenApiExampleCustomizer`; controllers keep only schema references.

## Migration Plan

No runtime or data migration. Steps:

1. Add OpenSpec global rule and openapi-examples spec (done in proposal / this change).
2. Add example annotations to DTOs; add operation-level example JSON files under `openapi/examples/` and ensure `OpenApiExampleCustomizer` injects them (controllers keep only schema).
3. Update AGENTS.md with the conventions.
4. Optionally add a short “API documentation” section in docs for consumers.

Rollback: Remove or relax annotations and doc text if needed; no side effects.

## Open Questions

- None. Scope is documentation and annotations only; no open technical decisions.
