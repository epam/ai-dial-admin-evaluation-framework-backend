## Context

Spring MVC's default handling of `@RequestParam List<String>` parameters invokes `StringToCollectionConverter`, which splits a single-value parameter on commas when the target type is a `Collection`. For the `filter` query parameter this behavior is accidental rather than designed-for:

- A request `?filter=field:in:a,b,c` arrives at the controller as `["field:in:a", "b", "c"]`, losing the IN operator's intended grouping.
- A request `?filter=name:eq:hello,world` arrives as `["name:eq:hello", "world"]`, silently splitting a literal comma in the value.

A short-term fix was applied in `FilterParser.recombineIfSpringTokenized` on the current branch (mirroring the heuristic `SortParser` uses for sort params). It relies on the structural shape of `<field>:<op>:<value>` to detect Spring-split fragments and reassemble them. The heuristic solves the failing CI test but:

- Silently merges genuinely malformed multi-filter submissions (e.g. `?filter=name:eq:x&filter=broken`) that previously returned HTTP 400.
- Cannot recover IN values that themselves contain two or more colons.
- Mixes HTTP-binding concerns (what the wire produced) with domain parsing (what the filter means).

This change moves the fix to the correct layer — the HTTP binding boundary — and removes the heuristic.

## Goals / Non-Goals

**Goals:**
- Bind the `filter` parameter exactly as it appears on the wire, preserving commas verbatim.
- Preserve the existing per-request limit on the number of `filter` parameters (`MAX_LIST_FILTER_PARAMS`) and its HTTP 400 behavior.
- Keep the public API contract for documented usage unchanged: repeated `?filter=…` for multiple conditions, comma-separated list of values inside an IN operator.
- Remove `FilterParser.recombineIfSpringTokenized` and its tests so the parser has no HTTP-binding concerns.

**Non-Goals:**
- Changing the filter DSL syntax itself (operators, field whitelists, value encoding rules).
- Changing how `sort` parameters are bound (`SortParser.recombineIfSpringTokenized` stays as-is; sort values never contain commas as data and the heuristic there is tighter).
- Reworking OpenAPI query parameter documentation generation.
- Migrating other `List<String>` request params (only `filter` is in scope).

## Decisions

### Decision: Use a custom `HandlerMethodArgumentResolver` with a dedicated annotation
Introduce `@FilterParam` and `FilterParamArgumentResolver`. The resolver:
- Uses `HttpServletRequest.getParameterValues("filter")` to obtain the raw string[] values as sent by the client, with no conversion.
- Returns `List<String>` (empty list when absent).
- Enforces the `max` attribute of `@FilterParam`; on violation, throws the same validation exception shape produced today by `@Size`, so the `DefaultExceptionHandler` response stays identical.

**Rationale:**
- Surgical and explicit — only affects parameters annotated `@FilterParam`. No global side effects on other `@RequestParam List<String>` usage (e.g., `sort`).
- Heuristic-free: the resolver does not know or care about filter DSL grammar.
- Idiomatic Spring MVC extension point; well-supported and testable with `MockHttpServletRequest`.

**Alternatives considered:**
- *Keep the `recombineIfSpringTokenized` heuristic in `FilterParser`*: rejected — symptom-level, silently changes behavior of malformed requests, cannot handle all IN/colon edge cases.
- *Override the global `ConversionService` to remove `StringToCollectionConverter`*: rejected — too invasive, would break other sites that intentionally rely on comma splitting (if any; at minimum it would require auditing the entire codebase).
- *Change the IN value delimiter (e.g., `|`)*: rejected — non-standard (OData, JSON:API, ElasticSearch all use `,`), does not solve the literal-comma-in-eq-value case, and requires breaking the just-archived `add-filter-in-operator` spec.
- *URL-encode commas in tests and document the requirement*: rejected — places the workaround on every API consumer forever and contradicts `FilterParser`'s own URL-decoding logic for IN elements.

### Decision: Annotation carries both the parameter name and the size cap
`@FilterParam` exposes `name()` (default `"filter"`) and `max()` (default `ValidationConstants.MAX_LIST_FILTER_PARAMS`). The resolver enforces `max`. This keeps the annotation self-contained and avoids scattering `@Size` declarations; it also means the validation source of truth for filter count lives in one class.

**Alternatives considered:**
- *Keep `@Size` on the parameter and rely on `@Validated`*: rejected — requires that `@Validated` still see the parameter after a custom resolver materializes it. While this can be made to work, it splits the validation across two mechanisms. Consolidating in the annotation is simpler.

### Decision: Place new types in the existing `web.pagination` package
The package already hosts `PaginationParamResolver`, a closely analogous query-param resolver. Co-locating `FilterParamArgumentResolver` there keeps all list-endpoint binding helpers together.

### Decision: Register the resolver via a single `WebMvcConfigurer`
Add `FilterWebMvcConfiguration implements WebMvcConfigurer` in `web.pagination` (or extend an existing configurer if one exists) and register the resolver in `addArgumentResolvers`. One integration point, easy to audit.

## Risks / Trade-offs

- **[Risk]** A controller site is missed during migration and keeps `@RequestParam(name = "filter") List<String>`. → **Mitigation:** grep-based audit listed in `tasks.md` (all 13 sites enumerated); ArchUnit or compile-time check is not pursued for simplicity.
- **[Risk]** OpenAPI description generation (`OpenApiQueryParamCustomizer`) relies on the `@RequestParam` annotation being present. → **Mitigation:** verify springdoc still documents the parameter via the resolver's contribution or via a fallback; extend `@FilterParam` with `@Parameter`-compatible metadata if needed. Add an explicit check to the test plan.
- **[Risk]** A client silently depended on the accidental comma-split behavior (`?filter=a,b` yielding two conditions). → **Mitigation:** this behavior was never documented; document the repeated-parameter requirement explicitly in the spec delta; flag in release notes.
- **[Trade-off]** Adds ~3 small classes to the codebase versus ~10 lines in the parser. The extra surface area pays for itself by making the binding behavior exact, testable in isolation, and free of heuristic edge cases.

## Migration Plan

1. Add `@FilterParam`, `FilterParamArgumentResolver`, and the `WebMvcConfigurer` (no effect until controllers adopt the annotation).
2. Swap `@RequestParam(name = "filter", …) List<String>` → `@FilterParam(max = …) List<String>` across all 13 sites in one commit.
3. Remove `FilterParser.recombineIfSpringTokenized` + associated tests.
4. Run `./gradlew clean build` (checkstyle + tests) locally and verify the previously failing functional test passes.
5. No rollback migration is needed; reverting the commit restores prior behavior.

## Open Questions

- Whether springdoc needs a small companion customization to keep rendering the parameter description/example for `@FilterParam` (to be confirmed during implementation).
