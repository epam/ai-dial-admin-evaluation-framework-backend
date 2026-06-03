## Context

Current list endpoints (e.g., `GET /api/v1/test-suites`) support pagination but do not define a reusable contract for sorting by multiple properties. At the data-access layer we rely on JDBC + `NamedParameterJdbcTemplate`, so dynamic sorting requires explicit SQL clause construction and must be protected against SQL injection by whitelisting allowed columns.

This change introduces a shared, cross-cutting sorting mechanism that:
- Accepts an ordered list of sort keys in the API.
- Validates/normalizes the sort specification.
- Produces a safe `ORDER BY` fragment based on an entity-specific allowlist.

## Goals / Non-Goals

**Goals:**
- Support **multi-column sorting** for list endpoints via a consistent query parameter format.
- Keep sorting **SQL-injection safe** by mapping client-provided sort fields to a strict allowlist of columns (never interpolating raw input).
- Provide deterministic ordering by applying a stable tiebreaker (e.g., `id`) when needed.
- Make the implementation reusable by any entity repository.

**Non-Goals:**
- Adding new database indexes or redesigning existing schemas for performance (we may recommend indexes, but not part of this change by default).
- Supporting arbitrary expressions (functions, JSON paths) as sort keys.
- Providing server-side “sort by nested resource” (joins) beyond what a repository explicitly supports.

## Decisions

### Decision: Query parameter format for multi-sort
Use a **repeatable** `sort` query parameter:
- `sort=<field>` (defaults to ascending)
- `sort=<field>,asc|desc`
- repeated to define precedence, e.g. `?sort=status,asc&sort=updatedAt,desc`

Rationale:
- Widely used pattern and easy for UIs to build.
- Preserves explicit ordering of sort keys.
- Backwards compatible: clients omitting `sort` get default ordering.

### Decision: Central parsing + normalization
Introduce a small, shared utility in the data/pagination area to parse the request sort spec into a structured representation (e.g., list of `{property, direction}`) and to normalize:
- trim whitespace
- default direction to `asc`
- reject empty/invalid tokens early (mapped to HTTP 400 at web layer)

Rationale:
- Avoid per-controller parsing differences.
- Keep repository layer focused on mapping validated sort keys to SQL.

### Decision: Repository-level allowlist mapping
Each repository that supports sorting exposes an allowlist mapping from **API sort property** → **SQL column expression** (e.g., `updatedAt` → `updated_at_ms`, `name` → `name`).

Rationale:
- Guarantees SQL safety.
- Decouples API naming from DB columns.
- Makes supported sorts explicit and testable.

### Decision: Stable tie-breaker
If the client does not include `id` (or another unique key) in the sort list, append `id ASC` as a final tiebreaker when the underlying query returns deterministic pages only with stable ordering.

Rationale:
- Prevents “flapping” items between pages when multiple rows share the same primary sort key.
- Keeps behavior consistent across UIs.

## Risks / Trade-offs

- **Risk**: Some entities may not have a natural `id` column exposed in list queries. → **Mitigation**: allow repositories to specify their own tiebreaker (or explicitly opt out if not applicable).
- **Risk**: Adding sorting to endpoints changes default ordering expectations. → **Mitigation**: keep default order unchanged unless `sort` is provided; document supported fields and defaults in OpenAPI/specs.
- **Risk**: Incorrect allowlist mapping could break queries. → **Mitigation**: add functional tests that validate ordering and error handling for unknown fields.

## Migration Plan

- Deploy change with backward-compatible API (new optional query params).
- No DB migration required.
- Rollback: revert to prior build; clients that started sending `sort` will lose sorting but still receive 200 responses if we keep `sort` ignored in previous versions. (If strict 400 validation is introduced, document this as a behavioral change per endpoint.)

## Open Questions

- For each list endpoint, what is the canonical set of supported sort fields (and their API names)?
- Should unknown sort fields be rejected (400) or ignored? (Design favors **reject** for safety and predictability.)
