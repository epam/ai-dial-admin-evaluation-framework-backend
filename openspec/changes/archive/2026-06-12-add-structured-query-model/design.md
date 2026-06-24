## Context

The legacy list-query DSL is exposed through `filter=`/`sort=`/`page`/`size`/`cursor` query
parameters and parsed in the web/service layer into `data.db` carriers (`FilterCondition`,
`SortKey`, `PageRequest`) executed via jOOQ `WhereBuilder`/`OrderByBuilder` against per-entity
`FilterWhitelists`/`SortWhitelists`. It cannot express boolean composition, aggregation, or
projection, splits pagination across meta/analytics, and caps complexity at the URL length limit.

This change introduces the **request-side object model** for a body-delivered structured query
contract (`docs/experimental/structured-query-model.md`, v7). It is deliberately **inert**: no
controller, validator, or translator is wired yet. The goal is to pin the wire grammar and prove
the Jackson binding before the heavier validation/translation layers are designed. The model lives
under `com.epam.aidial.evaluation.experimental.query.model` — the `experimental.*` namespace marks
it as not production-wired.

Constraints: Java 25 records + sealed interfaces; Jackson 3 (`tools.jackson.*` databind,
`com.fasterxml.jackson.annotation.*` annotations); the shared `@Primary JsonMapper`
(`JsonMapperConfiguration`) which enables `ACCEPT_CASE_INSENSITIVE_ENUMS`, disables
`FAIL_ON_UNKNOWN_PROPERTIES`, applies `NON_NULL` inclusion, and does **not** register the
parameter-names module.

## Goals / Non-Goals

**Goals:**
- A faithful, immutable record model of the §1–§7 wire grammar that round-trips through the
  production `JsonMapper`.
- Two declarative polymorphic hierarchies (`Expr` by `type`, `PageSpec` by `type`) and one
  custom-routed hierarchy (`FilterNode` by `op`).
- Exact snake_case wire contract and lowercase enum codes, proven by a deserialization test on
  this project's `test_case_eval_summaries` entity.
- Capture open decisions (D1/D5/D6/D8/D10) as labeled `TODO` markers so follow-up work is anchored.

**Non-Goals:**
- No REST endpoint, no `@RestController`, no request DTO wiring into the API surface.
- No validation / per-entity allowlist (§9), no SQL translation, no response envelopes (§7.4/§8.2).
- No DB schema, migration, config property, or dependency change.
- No change to the legacy query DSL or its `data.db` carriers — the two coexist.

## Decisions

**D-1: Records + sealed interfaces over Lombok DTOs.**
The model is a closed algebraic grammar; sealed hierarchies give exhaustive `switch` for the future
translator and immutability for free. Project DTO convention is Lombok classes, but records are the
right tool for a fixed wire grammar and the spec itself prescribes them. *Alternative:* Lombok
`@Value` classes — rejected (no exhaustiveness, more ceremony for pure carriers).

**D-2: `Expr` and `PageSpec` use declarative `@JsonTypeInfo(NAME, "type")`.**
Both are clean closed unions keyed by a dedicated `type` field, so `@JsonSubTypes` is sufficient and
needs no custom code. *Alternative:* deduction-based polymorphism — rejected (`FieldExpr` and
`ParamExpr` share `{type,name}`, so structural deduction is ambiguous; an explicit discriminator is
clearer and matches the spec sketch).

**D-3: `FilterNode` routed by a custom `FilterNodeDeserializer`, not `@JsonTypeInfo`.**
In CQL2-JSON the `op` key is *both* the discriminator *and* operator data, and many op codes
(`eq`/`ne`/…) map to the single `ComparisonNode` class. A declarative `@JsonTypeInfo(property="op",
visible=true)` emits a **duplicate `op`** on serialization (ambiguous type-id + the record's own
`op`). The deserializer routes `and`/`or`/`not` → `LogicalNode`, else → `ComparisonNode`;
serialization stays automatic because each record carries its own single `op`. *Alternative:* a
synthetic non-wire discriminator — rejected (would diverge from the CQL2-JSON contract).

**D-4: Custom deserializer wired at use sites, never on the interface.**
`@JsonDeserialize` on a sealed interface is inherited by its subtypes, so placing it on `FilterNode`
makes it fire for `LogicalNode`/`ComparisonNode` too → infinite recursion. It is therefore attached
via `using` on `StructuredQuery.filter`/`having` and `contentUsing` on `LogicalNode.args`.
`ComparisonNode.args` needs nothing — `List<Expr>` resolves through `Expr`'s own `type`
discriminator.

**D-5: `@JsonProperty` only for snake_case renames; rely on native record binding.**
Jackson 3 binds records by `RecordComponent` name independent of the parameter-names module, so
explicit `@JsonProperty` is needed only for compound keys (`group_by`, `value_type`,
`include_total`, `as`). *Alternative:* register `ParameterNamesModule` on the shared mapper —
rejected (wider blast radius for an exploratory model).

**D-6: Lowercase `@JsonValue` enum codes.**
Each wire enum (`ComparisonOp`, `LogicalOp`, `QueryMode`, `SortDir`, `ValueType`) carries an
explicit lowercase code via `@JsonValue` so serialization matches the spec verbatim; inbound parsing
additionally benefits from the mapper's `ACCEPT_CASE_INSENSITIVE_ENUMS`.

**D-7: Comparison operands are uniformly `List<Expr>`.**
Both sides of a predicate are general expressions (a column is a `FieldExpr`), so
`length(test_suite_id) = 3` — where neither side is a bare column — is expressible. There is no
dedicated property-reference node. Per-operator arity/typing is a validation concern, not a
structural one.

**D-8: `array` as the fifth `Expr` kind (v7).**
A set literal (e.g. the right operand of `in`) is `{ "type": "array", "items": [...] }` →
`ArrayExpr(List<Expr> items)`. The key is deliberately `items` (a collection), distinct from a
function's `args` (positional arguments). This resolved former decision D9.

**D-9: Rename wire `Page` → `PageSpec`.**
The spec's `Page` collides with the existing `data.db.model.pagination.Page<T>`. The model type is
renamed `PageSpec` to keep both importable without FQNs (Checkstyle forbids FQNs in code).

## Risks / Trade-offs

- **[Inert model drifts from the future validator/translator]** → The deserialization test pins the
  wire contract now; the spec marks validation/translation/response as Planned with explicit
  scenarios, so follow-up changes inherit the contract rather than reinventing it.
- **[Custom `FilterNodeDeserializer` is the one non-declarative seam]** → Concentrated in a single
  ~20-line class, covered by the round-trip test (nested boolean tree, fn-on-left, null check, `in`
  with array). The interface deliberately carries no `@JsonDeserialize` to avoid recursion.
- **[`experimental.*` namespace could be mistaken for production-ready]** → The package name and the
  proposal/spec "Planned" markers signal status; no bean, controller, or DI wiring exists, so the
  model cannot be invoked accidentally.
- **[Open decisions D1/D5/D6/D8/D10 unresolved]** → Carried as labeled `TODO(Dn)` markers in code
  and listed in the spec, so they surface at the next design step rather than being lost.

## Migration Plan

Additive only — no migration. New package, new docs, new test. Rollback = delete the
`experimental.query.model` package, the two `docs/experimental/*.md` files, and the test; nothing
else references them. No deployment, schema, or config impact.

## Open Questions

- **D1** — require `mode` explicitly (current) or infer from `group_by`/`aggregate` presence.
- **D5 / D8** — auto total-order tiebreaker (default: primary key) and default null ordering
  (`NULLS FIRST/LAST`); both affect offset determinism and cursor seek correctness — translator
  concerns.
- **D6** — aggregate response typing: keep dynamic `keys`+`metrics`, or generate per-query typed
  shapes (response side, out of scope here).
- **D10** — runtime-parameter (`param`) source/registry and its trust boundary.
- Where the production model eventually lives (promote out of `experimental.*` into
  `web`/`service.domain`) and how validation/translation map onto the existing
  `WhereBuilder`/jOOQ layer — deferred to the follow-up change.
