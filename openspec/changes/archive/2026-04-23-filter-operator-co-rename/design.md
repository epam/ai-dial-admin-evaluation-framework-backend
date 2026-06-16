## Context

The filter DSL has one verbose operator — `contains` — that clashes with the short-name convention (`eq`, `ne`, `gt`, etc.). All other operators fit in 2–3 chars. Additionally, `eq`/`ne` on STRING columns emit `column = :param`, which is case-sensitive in PostgreSQL, creating surprising mismatch for name/tag searches.

Current pipeline:
```
HTTP ?filter=name:contains:foo
        │
        ▼ FilterParser (service.domain.filter)
        operatorRaw.toUpperCase() → FilterOperator.valueOf("CONTAINS") → FilterOperator.CONTAINS
        │
        ▼ WhereBuilder (data.db.repository.sql)
        CONTAINS → "column ILIKE '%' || :p || '%'"
        EQ       → "column = :p"          ← case-sensitive
```

## Goals / Non-Goals

**Goals:**
- Rename HTTP operator token `contains` → `co` (hard breaking rename; no alias)
- Rename `FilterOperator.CONTAINS` → `FilterOperator.CO` everywhere
- Make `eq`/`ne` case-insensitive for `STRING` and `JSONB_STRING` fields via `lower()`
- OpenAPI operator table auto-adapts via `op.name().toLowerCase()` → emits `"co"` after rename

**Non-Goals:**
- Backward-compatibility alias (`contains` still accepted after this change)
- Case-insensitive comparisons for non-string types (`UUID`, `LONG`, `BOOLEAN`, `JSONB_NUMERIC`)
- Any other operator additions or DSL extensions

## Decisions

### 1. Hard rename, no alias
**Decision**: rename `FilterOperator.CONTAINS` → `FilterOperator.CO`; remove support for old token.

**Rationale**: An alias keeps dead code and requires documenting two valid tokens. Since the user confirmed breaking changes, a clean cut is simpler and makes the convention unambiguous.

**Alternative rejected**: Alias map in `FilterParser` — keeps legacy surface, deferred cleanup.

---

### 2. `lower()` SQL for EQ/NE on STRING/JSONB_STRING
**Decision**: Change SQL predicates as follows for `STRING` and `JSONB_STRING` fields:

| Operator | Before | After |
|----------|--------|-------|
| EQ | `column = :p` | `lower(column) = lower(:p)` |
| NE | `column <> :p` | `lower(column) <> lower(:p)` |
| EQ (JSONB) | `column->>:k = :p` | `lower(column->>:k) = lower(:p)` |
| NE (JSONB) | `column->>:k <> :p` | `lower(column->>:k) <> lower(:p)` |

**Rationale**: `lower()` is the correct approach for case-insensitive exact equality in PostgreSQL. `ILIKE` also works but is designed for pattern matching (wildcards `%` and `_`) — using it for exact match is semantically incorrect and could confuse future readers. No index changes are needed for this project's current scale.

**Alternative rejected**: `ILIKE :p` for EQ — works but carries wildcard semantics; wrong tool for equality.

---

### 3. `FilterFieldType` passed into predicate builders
**Decision**: Add `FilterFieldType type` parameter to `WhereBuilder.buildPredicate()` and `WhereBuilder.buildJsonbPredicate()` (private static methods). The call site in `build()` already has the `FilterFieldDefinition`, so passing `definition.getType()` is one line.

**Rationale**: The predicate builder must know whether a field is STRING to decide the SQL variant. The cleanest fix is a parameter over any alternative (e.g., method overloading, separate method per type).

---

### 4. `QueryParamDescriptionGenerator` auto-adapts
`op.name().toLowerCase(Locale.ROOT)` on the renamed enum yields `"co"` automatically. The two explicit `FilterOperator.CONTAINS` references in `generateFilterExample()` and `selectPreferredOperator()` need mechanical renaming to `FilterOperator.CO`. No logic changes required.

## Risks / Trade-offs

- **Breaking API change** → Mitigated by user confirmation; callers using `filter=...:contains:...` will get HTTP 400 after deployment. Communicate in release notes.
- **`lower()` disables B-tree index on `column`** → For this project's size, a full scan is acceptable. If needed in future, a functional index on `lower(column)` resolves it.
- **JSONB_STRING NE with `lower()`** → `lower(column->>:k) <> lower(:p)` returns rows where the key exists AND value differs. NULL JSONB keys are excluded — same behavior as before (no semantic regression).

## Migration Plan

1. Deploy new version. Clients must update filter params from `contains` → `co`.
2. No DB migration needed.
3. Rollback: revert the enum rename and predicate SQL; one-line change.
