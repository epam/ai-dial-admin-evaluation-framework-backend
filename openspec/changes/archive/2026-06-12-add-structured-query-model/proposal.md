## Why

The legacy list-query DSL (`filter=`/`sort=`/`page`/`size`/`cursor` query params) has hit
structural limits: URL-length caps on complex filters, no boolean composition (`OR`/`NOT`/nested
groups), per-entity hand-picked allowlists, two incompatible pagination models (meta=offset,
analytics=cursor), no aggregation, and no client-driven projection. We need a single structured
query contract — sent in a request body, never as SQL — that all listable entities (meta and
analytics) share. This change formalizes the **request-side wire contract and its Java object
model** that the exploratory work on `feat/query-dsl` produced, so the grammar is pinned down
before validation and SQL translation are built on top of it.

## What Changes

- Add the **structured query wire contract** (`docs/experimental/structured-query-model.md`, v7):
  a top-level envelope with `entity`, `filter`, `mode` (`row`/`aggregate`), `select`, `group_by`,
  `aggregate`, `having`, `sort`, `page`. Filter nodes are CQL2-JSON (`op`/`args`); expression and
  pagination nodes are discriminated by `type`.
- Add the **request object model** under a new package
  `com.epam.aidial.evaluation.experimental.query.model`: immutable Java records + sealed
  interfaces mirroring §1–§7 of the spec.
  - `Expr` (sealed) → `FieldExpr`, `ValueExpr`, `ParamExpr`, `FnExpr`, `ArrayExpr` — declarative
    `@JsonTypeInfo(NAME, "type")` polymorphism.
  - `FilterNode` (sealed) → `LogicalNode`, `ComparisonNode` — routed by a custom
    `FilterNodeDeserializer` because `op` is both discriminator and operator data.
  - `PageSpec` (sealed) → `OffsetPage`, `CursorPage` — declarative `type` discriminator (renamed
    from the spec's `Page` to avoid colliding with `data.db.model.pagination.Page<T>`).
  - Supporting records/enums: `StructuredQuery`, `AggregateCall`, `SortItem`, `QueryMode`,
    `LogicalOp`, `ComparisonOp`, `SortDir`, `ValueType`.
- Add a Jackson round-trip test (`StructuredQueryDeserializationTest`) proving the records bind,
  the discriminators resolve, and the snake_case / `@JsonValue` wire codes match the spec — using
  this project's `test_case_eval_summaries` entity, not generic placeholders.
- Carry the spec's open decisions (D1, D5, D6, D8, D10) as labeled `// TODO(Dn)` markers in code.

This change is **request-model + wire-contract only**. It introduces **no** controller endpoint,
**no** validation, **no** SQL translation, and **no** response envelopes — those are explicitly
Planned (see below) and become follow-up changes.

## Capabilities

### New Capabilities
- `structured-query-model`: The structured query wire contract and its request-side object model —
  envelope, modes, filter tree, expression grammar, aggregation calls, sort, and pagination
  request shapes; the Jackson binding contract (discriminators, snake_case keys, `@JsonValue`
  enum codes). Validation/allowlist (§9), SQL translation, and response envelopes (§7.4/§8.2) are
  documented as **Planned** scope within this spec but not implemented here.

### Modified Capabilities
<!-- None. The legacy entity-filtering / sorting specs are untouched; this is an additive,
     parallel wire-contract layer that does not change any existing requirement yet. -->

## Impact

- **New package** `com.epam.aidial.evaluation.experimental.query.model` (23 record/enum/interface
  types + `package-info.java` + `FilterNodeDeserializer`). The `experimental.*` namespace signals
  the model is not yet wired to any endpoint.
- **New docs**: `docs/experimental/structured-query-model.md` (v7 wire contract) and
  `docs/experimental/structured-query-object-model-notes.md` (design notes / Jackson findings).
- **New test**: `experimental/query/model/StructuredQueryDeserializationTest`.
- **No** DB schema changes, **no** Flyway migrations, **no** config properties, **no** new
  dependencies (Jackson 3 / `tools.jackson.*` is already on the classpath).
- **No** impact on existing endpoints, security, or the legacy query DSL — the model is inert
  until a future change adds the controller, validator, and translator.
- Touches `openspec/specs/README.md` (new spec folder) per the Spec Index Maintenance Policy.
