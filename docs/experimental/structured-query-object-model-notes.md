# Structured Query DSL — Object Model Notes (exploratory)

Companion to [`structured-query-model.md`](structured-query-model.md). Records the Java
**request** object model built to feel out the implementation shape before a formal OpenSpec
change. **Request side only** — validation (§9), SQL translation, and response envelopes
(§7.4/§8.2) are out of scope.

Package: `com.epam.aidial.evaluation.experimental.query.model`
Test: `StructuredQueryDeserializationTest` (round-trips the spec's worked examples through the
production `JsonMapper`).

## Shape

Immutable Java records + sealed interfaces, one type per file, mirroring §1–§7:

| Spec | Type(s) |
|------|---------|
| §1 envelope | `StructuredQuery` |
| §2 mode | `QueryMode {ROW, AGGREGATE}` |
| §3 filter tree | `FilterNode` (sealed) → `LogicalNode` (`LogicalOp`), `ComparisonNode` (`ComparisonOp`) |
| §4 expressions | `Expr` (sealed) → `FieldExpr`, `ValueExpr`, `ParamExpr`, `FnExpr`, `ArrayExpr`; `ValueType` |
| §5 aggregation | `AggregateCall` (alias on wire key `as`) |
| §6 sort | `SortItem`, `SortDir` |
| §7 pagination | `PageSpec` (sealed) → `OffsetPage`, `CursorPage` |

`ComparisonNode.args` is a `List<Expr>` — **both sides of a comparison are general expressions**,
with no dedicated property-reference type. A column is just a `FieldExpr`, so comparisons where
neither operand is a bare column (e.g. `length(test_suite_id) = 3` → `fn(field) eq value`) are
expressible. `in` is an ordinary binary predicate whose right operand is typically an `ArrayExpr`
(§8.5). Each operator's arity/argument expectations are a validation concern, not enforced by the
record shape. (Spec v7 §3 now matches this model — operands are expressions, no separate `property`
node — so the earlier `{property}` divergence is reconciled.)

`ArrayExpr(List<Expr> items)` is the fifth expression kind (spec v7 §4.6). Its wire key is `items`
(a collection), deliberately distinct from `FnExpr`'s `args` (positional arguments). It resolves
declaratively via `Expr`'s `type` discriminator (`"type":"array"`) — no custom serde. This replaces
the former D9 placeholder (`in` operand shape): a set literal is now `{"type":"array","items":[…]}`,
not a special `ValueExpr` variant.

`PageSpec` is renamed from the spec's `Page` to avoid colliding with the legacy
`data.db.model.pagination.Page<T>` / `PageRequest`.

## Jackson decisions (this is Jackson 3 — `tools.jackson.*`)

The non-obvious findings, all proven by the round-trip test:

1. **`Expr` polymorphism is the clean declarative case** — `@JsonTypeInfo(use = NAME, property =
   "type")` + `@JsonSubTypes`, exactly as the spec sketches. Likewise `PageSpec` by `type`.

2. **`FilterNode` cannot use `@JsonTypeInfo`.** In CQL2-JSON the `op` key is *both* the
   discriminator *and* operator data, and many ops (`eq`/`ne`/…) map to one class
   (`ComparisonNode`). `@JsonTypeInfo(property="op", visible=true)` produced a **duplicate `op`**
   on serialization (an ambiguous type-id — Jackson picks the first registered name — plus the
   record's own `op`). Resolved with a custom `FilterNodeDeserializer` that routes by `op`
   (`and`/`or`/`not` → `LogicalNode`, else `ComparisonNode`); serialization is automatic because
   each record carries its own single `op`.

3. **`@JsonDeserialize` on a sealed *interface* is inherited by its subtypes.** Putting the custom
   deserializer on `FilterNode` made it fire for `LogicalNode` / `ComparisonNode` too → infinite
   recursion. Fix: wire it at the **use site** via `using` / `contentUsing` on the specific record
   component, never on the interface:
   - `StructuredQuery.filter` / `having` → `FilterNodeDeserializer` (`using`)
   - `LogicalNode.args` → `FilterNodeDeserializer` (`contentUsing`)

   (`ComparisonNode.args` needs nothing — `List<Expr>` resolves declaratively via `Expr`'s own
   `type` discriminator. Modeling args uniformly as `Expr` removed an earlier `Operand` union and
   its custom serializer/deserializer entirely.)

4. **Records bind natively in Jackson 3** (component names come from `RecordComponent`,
   independent of the `-parameters` flag and of the parameter-names module the `@Primary`
   `JsonMapper` does not register). `@JsonProperty` is used only for the snake_case renames
   (`group_by`, `value_type`, `include_total`, `as`).

5. **Wire enums** carry an explicit lowercase `@JsonValue` code (`eq`, `and`, `row`, `asc`,
   `string`, …) so serialization matches the spec verbatim; inbound parsing also benefits from the
   mapper's `ACCEPT_CASE_INSENSITIVE_ENUMS`.

## Open decisions carried as `// TODO(Dn)` markers

- **D1** — `mode` is explicit (per spec); could be inferred. (`StructuredQuery`)
- **D5 / D8** — total-order tiebreaker / null ordering are translator concerns. (`SortItem`)
- **D10** — `param` source/registry and trust boundary undefined. (`ParamExpr`)
- **D6** — aggregate response typing: response side is out of scope here.

(D9 — the `in` operand shape — is resolved in spec v7: a set literal is an `ArrayExpr`. No open
marker remains in the model.)

## Schema discovery API (`/api/v0/queries`)

The first server-side surface built on the model: entity and flat-schema discovery, publishing the
flat property namespace the DSL's `field` expressions reference (spec §4.5 "as published by the
entity's schema endpoint"). `/api/v0` marks the API experimental; code lives in
`experimental.query.web` / `experimental.query.service`, mirroring the standard layering under
`experimental` (enforced by dedicated layers in `LayeredArchitectureTest`).

```
GET /api/v0/queries/entities                      → registered entities (QueryEntityRegistry)
GET /api/v0/queries/entities/schema/{name}        → flat base schema; JSONB fields listed as-is
GET /api/v0/queries/entities/schema/{name}/{id}   → detailed flat schema for complex entities
```

- Entities are contributed by `QueryableEntitySchemaProvider` `@Component`s; current registry:
  `test_suites` (simple) and `eval_summaries` (complex, `schemaIdField: testSuiteId`).
- Base schemas are **derived from the generated jOOQ tables** (`JooqTableSchemaResolver`), not
  hand-rolled, so a Flyway migration + `./gradlew generateJooq` updates the published schema
  automatically. Conventions encoded: `VARCHAR(36)` → `uuid` (project UUID storage convention),
  JSONB → `array` when the DDL default is `'[]'::jsonb` else `object`, `*_at_ms` → `*At`
  (`created_at_ms` → `createdAt`, but `exec_duration_ms` keeps its unit), boolean `is_` prefix
  stripped (`is_valid` → `valid`); per-entity name/type override maps cover future exceptions, and
  unknown column types fail fast at startup.
- Schema fields are `{ name, type, source }` — `type` from the DSL-aligned `QueryFieldType`
  vocabulary (`ValueType` + `object`/`array`), `source` naming the backing entity field
  (`metric:Accuracy:score` → `metricValues`; plain columns are self-sourced).
- Flattened names reuse the CSV-export column families (`EvalSummaryExportColumnConstants`):
  `data:<field>` (dataset test-case schema), `response:<column>` (suite response columns),
  `metric:<name>:<field>` and `metricInfo:<name>` (enabled+valid TSMD output schemas). The detailed
  schema is derived from the **current** suite state, not a run snapshot; schema drift over time is
  intentionally not handled at this stage.
- Metric typing is assumed by the domain, not read from the output schema: **every metric value is
  numeric**, so `metric:<name>:<field>` is always `decimal` (we use only the output-schema field
  *names*). Non-numeric metric output lives in `metric_infos`, exposed per metric name only as
  `metricInfo:<name>` (`object`), not split per field. `extractionWarnings` stays as-is (`array`).
- Errors: unknown entity / unknown suite → 404; detailed schema on a simple entity or malformed
  UUID → 400. `/api/v0/**` is authenticated like `/api/v1/**` (`SecurityConfiguration`).
- Proven by `QuerySchemaDiscoveryFunctionalTests` (Postgres-backed, fixture suite + dataset + TSMD)
  and unit tests for the registry and both providers.

## Deliberately not modeled (becomes the formal OpenSpec change)

Mode coherence (§2) and all field/function/literal/pagination validation (§9); per-entity
allowlist; SQL translation; response envelopes; cursor encoding; capability flags
(filterable/sortable/…) on schema fields — deferred to the validation/allowlist layer.
