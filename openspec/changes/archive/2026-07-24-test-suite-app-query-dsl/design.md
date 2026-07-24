## Context

The `test_suites` QueryDSL entity (entity name `"test_suites"`) is a **simple** (non-complex) entity
backed by `PostgresTestSuiteEntityResolver`. Its current field bindings and schema are derived
mechanically from the jOOQ-generated `TEST_SUITES` table by `JooqTableSchemaResolver`, which maps
every JSONB column without a `'[]'::jsonb` default to `QueryFieldType.OBJECT`. This means
`deployment_ref` and `mcp_deployment_ref` are exposed as opaque `OBJECT`-typed blobs — clients can
use them in equality comparisons against whole JSON objects, but cannot filter or project on
individual sub-fields (e.g. `deployment_ref::name = 'My App'`).

The `JsonbFieldResolver` already handles sub-field path families for `eval_summaries` via the
`data::`, `response::`, `metric::`, and `metricInfo::` prefix conventions, using the `textPath()`
helper which calls `JsonPathAccessor.jsonbAtAsText` guarded by a `jsonbColumn()` existence check.
The same pattern is directly applicable here for `deployment_ref::` and `mcp_deployment_ref::`.

No DB schema changes are required. The `deployment_ref` and `mcp_deployment_ref` JSONB columns are
already present on the `test_suites` table and already appear as `OBJECT`-typed bindings in the
entity resolver. The change is purely additive in the query translation and schema-discovery layers.

## Goals / Non-Goals

**Goals:**
- Expose `deployment_ref::id`, `deployment_ref::name`, `deployment_ref::version` as flat `STRING`
  queryable fields on the `test_suites` entity (filter, select, sort, group_by).
- Expose `mcp_deployment_ref::id`, `mcp_deployment_ref::name`, `mcp_deployment_ref::type` as flat
  `STRING` queryable fields on the `test_suites` entity.
- Advertise the new fields in the schema-discovery base schema response.
- Retain the existing opaque `deployment_ref` (OBJECT) and `mcp_deployment_ref` (OBJECT) bindings
  to avoid any breaking change for existing callers.

**Non-Goals:**
- No `test_cases` entity changes — test cases have no deployment relationship.
- No filtering on `mcp_deployment_ref::transport` in this change.
- No changes to other JSONB columns on `test_suites` (e.g. `endpoint_ref`, `tool_ref`).
- No changes to classic REST `?filter=` allowlists.
- No DB/Flyway/jOOQ changes.

## Decisions

### Decision 1: Reuse `JsonbFieldResolver.textPath()` rather than inlining in the entity resolver

**Choice:** Add `deployment_ref::` and `mcp_deployment_ref::` dispatch branches in `JsonbFieldResolver`,
backed by the existing `textPath()` helper, instead of computing jOOQ field expressions directly in
`PostgresTestSuiteEntityResolver`.

**Rationale:** `JsonbFieldResolver` is the single, tested, injection-point for JSONB sub-field
resolution across all entities. It already handles the `jsonbColumn()` guard (silently returns null
if the entity doesn't have the backing JSONB column), which makes the resolver entity-agnostic. If
we inline the expressions in the entity resolver, every future JSONB sub-field family would need the
same duplicated guard logic. Reusing `textPath()` is the natural extension of the existing pattern.

**Alternative considered:** Add a new `buildDeploymentRefBindings()` helper on the entity resolver
that constructs the jOOQ expressions directly without going through `JsonbFieldResolver`. This would
work but bypasses the resolver's guard pattern and diverges from the `eval_summaries` precedent.

### Decision 2: Pre-compute sub-field bindings at construction time (same as existing static bindings)

**Choice:** The 6 new `QueryFieldBinding` entries are computed once in
`PostgresTestSuiteEntityResolver`'s constructor alongside the existing static bindings from
`JooqTableSchemaResolver.bindings(TEST_SUITES)`.

**Rationale:** `test_suites` is a **simple** (non-complex) entity — `bindings(StructuredQuery)` is
query-agnostic. Pre-computing at construction time is consistent with the current implementation and
avoids allocation on every query. The `JsonPathAccessor` bean must be injected into the constructor
(it is not currently used there); this is a trivial addition.

### Decision 3: Expose sub-fields from `TestSuitesSchemaProvider.baseSchema()` directly

**Choice:** The 6 new `QuerySchemaFieldDto` entries are appended to the list produced by
`JooqTableSchemaResolver.resolve(TEST_SUITES)` in `TestSuitesSchemaProvider`'s constructor.

**Rationale:** `test_suites` is a simple entity with no `detailedSchema()`. The base schema is the
only advertised schema surface. Appending virtual entries here mirrors how
`TestCasesSchemaProvider.detailedSchema()` appends `data::<field>` entries beyond the base columns.
The `source` field of each `QuerySchemaFieldDto` SHALL be set to the backing JSONB column name
(`"deployment_ref"` / `"mcp_deployment_ref"`) to correctly signal the physical origin.

### Decision 4: Field naming uses `::` separator (consistent with existing `data::`, `response::` families)

**Choice:** Field names are `deployment_ref::id`, `deployment_ref::name`, `deployment_ref::version`,
`mcp_deployment_ref::id`, `mcp_deployment_ref::name`, `mcp_deployment_ref::type`.

**Rationale:** The `::` separator is already the established convention for JSONB sub-field paths in
this DSL (`data::`, `response::`, `metric::`, `metricInfo::`). Using `.` would create ambiguity with
existing naming and require new parsing logic; using `::` keeps the `JsonbFieldResolver` dispatch
pattern uniform.

## Risks / Trade-offs

**[Risk] `deployment_ref` JSONB is nullable** — when a suite has no deployment ref (e.g. MCP-type
suites), `deployment_ref ->> 'name'` returns SQL `NULL`. Filtering `deployment_ref::name = 'X'`
against an MCP suite will correctly produce no match, but projecting `deployment_ref::name` in a
`select` for an MCP suite will return `null` for that row.
→ Mitigation: This is standard NULL semantics, consistent with how all other nullable JSONB paths
behave in the DSL. No special handling needed; document in spec scenarios.

**[Risk] `JsonbFieldResolver` dispatch order** — if a `test_suites` query names a field like
`deployment_ref` (the plain OBJECT binding) and another field `deployment_ref::name`, both must
coexist without conflict. The resolver dispatches on prefix first (`::`-split), so `deployment_ref`
(no `::`) routes through the existing bindings map, not through `JsonbFieldResolver`; only
`deployment_ref::name` hits the new branch.
→ Mitigation: No code change needed; the existing `ExprTranslator` lookup order (bindings map first,
`JsonbFieldResolver` fallback) naturally handles this.

**[Risk] Unknown sub-field keys** — a client submitting `deployment_ref::unknownField` will get a
`null` SQL result (Postgres `jsonb ->> 'unknownField'` on a missing key returns NULL) rather than a
400 error. This is consistent with how `data::unknownField` behaves on `test_cases`.
→ Mitigation: Accepted; the schema-discovery endpoint advertises the allowed field names; clients
querying undiscovered fields get NULL matches, not errors.

## Migration Plan

No migration required. The change is purely additive:
1. Deploy new code — new fields appear in schema discovery and are available for query.
2. Existing queries using `deployment_ref` (OBJECT) continue to work unchanged.
3. No rollback concerns — removing the new bindings on rollback would only affect clients who
   started using the new fields, which is expected behaviour for an experimental endpoint.
