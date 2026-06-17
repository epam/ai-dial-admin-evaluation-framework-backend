## Context

`structured-query-model` froze the request envelope and its Jackson binding but stopped before
execution. The `feat/query-dsl` branch now executes a structured query end-to-end:
`POST /api/v1/queries/execute` → validate + translate to parameterized jOOQ → run on the entity's
datasource → return rows. Two entities are wired: `test_suites` (meta DSLContext) and `eval_summaries`
(analytics DSLContext). This design records the executor as-built and is deliberate about where it is
**narrower than the originally-planned vision**, so the spec does not overclaim.

## Goals / Non-Goals

**Goals:**
- Document the `/execute` contract, entity dispatch, validation, SQL translation, pagination, response
  shape, and error mapping exactly as implemented.
- State precisely what validation does and does NOT cover.

**Non-Goals:**
- Schema discovery (the `/entities*` endpoints) — owned by `add-query-schema-discovery`.
- The request object model — already in `structured-query-model`.
- Building the richer planned response envelope or cursor pagination (both currently absent/rejected).
- Demo HTML pages — out of scope, not part of the contract.

## Decisions

### Endpoint and dispatch
`StructuredQueryController.execute` (`POST /api/v1/queries/execute`) takes a `StructuredQuery` body and
returns `StructuredQueryResultDto`. `StructuredQueryService` collects all `StructuredQueryRepository`
beans at construction into a map keyed by `supportedEntity()` and routes by `query.entity()`; an
unknown entity throws `ValidationException` (→ 400) naming the supported entities. Repositories are
`@ConditionalOnProperty` on their datasource vendor, so the supported set follows deployment.

### Validation is schema-driven and permissive (the deliberate narrowing)
Rather than the planned data-driven per-field capability-flag allowlist, validation is "does this
resolve against the discovered schema and can Postgres run it":
- **Field resolution** — flat names resolve to jOOQ `QueryFieldBinding`s; `data:`/`response:`/`metric:`/
  `metricInfo:` names resolve via `JsonbFieldResolver` against the backing JSONB column. Unknown names →
  `ValidationException`.
- **Functions** — a closed supported set (`lower`, `upper`, `length`, `trim`, `abs`, `width_bucket`;
  aggregates `count`/`sum`/`avg`/`min`/`max`, with `distinct` where applicable). Anything else →
  `ValidationException`.
- **Literals** — wire `value` strings are parsed to their declared `value_type`
  (`ValueExprToObjectMapper`); parse failure → `ValidationException`.
- **`in`** — right operand must be an `array` of value literals, else `ValidationException`.
- **Pagination governance** — offset ≥ 0; cursor pagination rejected; limit clamped to [100, 1000].
- **NOT enforced (deferred):** per-field capability flags
  (filterable/projectable/groupable/aggregatable/sortable), mode coherence (aggregate vs row), and
  array element type-homogeneity. A query that is structurally accepted but semantically invalid (e.g.
  averaging a non-numeric field) is caught at execution time (below) and surfaced as 400, not 500.

### SQL translation
`StructuredQueryBuilder` assembles a jOOQ `SelectQuery`. `ExprTranslator` maps expressions to fields;
`JsonbFieldResolver` expands flattened names to JSONB navigation (`-> 'k' ->> text`; metric values cast
`:: numeric`) via bind parameters — never string concatenation. `FilterTranslator` maps operators:
`eq`/`ne`/`lt`/`gt`/`le`/`ge` direct, `co`/`nc` → case-insensitive (NOT) LIKE with `%…%`, `in` → `IN`,
and `eq`/`ne` against a null literal → `IS [NOT] NULL`. Aggregate mode requires aliased select entries;
`group_by`/`having`/`sort` keys resolve against base fields ∪ select aliases (aliases referenced by
name to keep PostgreSQL GROUP BY semantics). `distinct` sets SELECT DISTINCT. Default limit 100, max
1000.

### Response shape (narrower than planned)
`StructuredQueryResultDto { List<Map<String,Object>> rows, Long totalCount }`. `JsonbRowConverter`
parses JSONB columns in each row back to nested JSON. `totalCount` is populated only for **row-mode**
queries with `include_total=true`, via a separate `COUNT(*)` of the same filter. There is **no** `page`
object, `offset`/`next_cursor`, or aggregate `keys`+`metrics` shape — those remain unbuilt.

### Transaction boundaries & datasources
Each repository runs on its own DSLContext (`metaDsl` for `test_suites`, `analyticsDsl` for
`eval_summaries`); execution is read-only.

### Error mapping
`ValidationException` → 400 (unknown entity/field/function, bad literal, unsupported feature, null
misuse). `StructuredQueryExecutor` catches Spring `BadSqlGrammarException` and
`DataIntegrityViolationException` and rethrows them as `ValidationException` (→ 400) so DB type/grammar
errors are client errors, not 500s. `EntityNotFoundException` → 404. Everything else → 500.

## Component interaction flow

```
POST /api/v1/queries/execute  { entity, filter, mode, select, group_by, having, sort, page }
  StructuredQueryController.execute
    → StructuredQueryService.execute(query)
        → repository = routeByEntity(query.entity())     // 400 if unknown
        → StructuredQueryExecutor.execute(query, dsl, table)
            → StructuredQueryBuilder.build(...)           // validate + translate (400 on reject)
            → run jOOQ SelectQuery                        // DB error → caught → 400
            → optional countRows() when include_total
            → JsonbRowConverter parses JSONB columns
    → StructuredQueryResultDto { rows, totalCount }
```

## Risks / Trade-offs

- **Permissive validation surfaces some errors late.** Without capability flags / mode coherence, an
  invalid-but-structurally-accepted query reaches the database; the executor converts DB grammar/type
  errors to 400 so the client still gets a clean error, but the message is DB-derived. Acceptable for an
  experimental surface; the capability-flag allowlist remains a future tightening.
- **Response shape differs from the planned envelope.** Clients get `{rows, totalCount}`, not a `page`
  object or cursors. Documented honestly; richer paging is deferred.
- **Cursor pagination rejected.** Analytics-style keyset paging is not yet wired into this path.
- **Experimental namespace.** `/api/v1/queries` may change; no stable client depends on it yet.
