## Context

Today `com.epam.aidial.evaluation.data.db.model.pagination.PageRequest` contains `parseSortParams(List<String>)`, which parses raw `sort` query parameter values and includes Spring MVC binding quirks (comma-separated values can be split into collections).

This creates an undesirable dependency direction:
- `.data.db` (data model) is aware of HTTP/MVC request parsing details.
- Controllers/services can’t easily swap/mimic parsing behavior in tests (logic is static on a low-level type).

This change aims to restore clearer layer boundaries without changing externally observable sorting behavior described in `openspec/specs/sorting/spec.md`.

## Goals / Non-Goals

**Goals:**
- Remove HTTP/MVC query parameter parsing from `.data.db` model types (`PageRequest`).
- Introduce an injectable parsing component at the API boundary (web/service layer).
- Keep repositories/data-access responsible for SQL-safe allowlisting/mapping of sort fields.
- Preserve current behavior for valid requests (repeatable `sort=<field>[,<asc|desc>]`).

**Non-Goals:**
- Changing the public API contract (query parameter names/format) or introducing new sorting features.
- Moving SQL allowlisting/mapping into web/service layers (must remain in data-access).

## Decisions

### Decision: Make `PageRequest` a pure carrier (no parsing)

**Choice:** Remove `PageRequest.parseSortParams(...)` and keep `PageRequest` responsible only for:
- pagination (`page`, `size`, `offset`)
- structured sorting (`List<PageRequest.Sort>` and legacy single-key fields, if still needed during transition)

**Rationale:** `.data.db` must not depend on MVC/web concerns. A pure carrier is reusable across entry points and easier to reason about and test.

**Alternatives considered:**
- Keep parsing in `PageRequest` but move it into a separate `PageRequestParser` inside `.data.db`.
  - Rejected: still keeps HTTP request parsing coupled to data-access layer, only relocated.
- Use Spring’s `Sort`/`Pageable` types.
  - Rejected: would introduce a Spring Web dependency into lower layers and require broader refactoring; project already uses custom pagination types.

### Decision: Parse sort params via an injectable component at the boundary

**Choice:** Create a Spring component (e.g., `SortParamParser`) in web/service layer that converts raw `List<String>` request params into `List<PageRequest.Sort>`.

**Rationale:**
- Clear ownership: HTTP parsing belongs near controllers.
- Testability: parser can be mocked/replaced in controller/service tests.
- Extensibility: future query parsing changes don’t require touching `.data.db` model code.

**Notes:**
- Parser must keep current “split token” handling to avoid regressions with Spring binding edge cases.
- Parser should validate syntax (blank field, invalid direction, too many tokens) and throw a domain-specific exception that the web exception handler maps to HTTP 400.

### Decision: Keep allowlisting/mapping in data-access

**Choice:** Continue to whitelist and map API sort fields to SQL columns/expressions in data-access (`PageRequestSqlBuilder` + `PageRequestParams.allowedSortColumns`).

**Rationale:** This is the security boundary against SQL injection via identifiers; it must be enforced where SQL is constructed.

## Risks / Trade-offs

- **[Risk] Behavior drift for malformed inputs** → **Mitigation**: add/adjust tests around parsing edge cases (blank values, invalid direction, “split token” shape).
- **[Risk] Controllers diverge in parsing behavior** → **Mitigation**: use a single shared parser component injected into all controllers that accept sorting.
- **[Trade-off] Slightly more wiring in controllers** → **Mitigation**: keep controller code thin; parsing component returns the structured list used to build `PageRequest`.

