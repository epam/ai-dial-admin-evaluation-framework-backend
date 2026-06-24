## 1. Wire contract documentation

- [x] 1.1 Author the v7 wire contract `docs/experimental/structured-query-model.md` (envelope,
  modes, filter sub-grammar, expression grammar incl. `array`, aggregation, sort, pagination,
  worked examples, validation hooks, open decisions)
- [x] 1.2 Author design notes `docs/experimental/structured-query-object-model-notes.md` (model
  shape table, Jackson decisions, open-decision TODO markers)

## 2. Request object model (`experimental.query.model`)

- [x] 2.1 Envelope + enums: `StructuredQuery`, `QueryMode` (done: §1/§2 fields bind, `package-info`
  documents the package)
- [x] 2.2 Filter tree: `FilterNode` (sealed), `LogicalNode`, `LogicalOp`, `ComparisonNode`,
  `ComparisonOp` (done: §3 nodes bind)
- [x] 2.3 Expression grammar: `Expr` (sealed, `@JsonTypeInfo` NAME/`type`), `FieldExpr`,
  `ValueExpr`, `ParamExpr`, `FnExpr`, `ArrayExpr`, `ValueType` (done: §4 five kinds bind, `array`
  uses `items` key)
- [x] 2.4 Aggregation / sort / pagination: `AggregateCall` (alias from `as`), `SortItem`, `SortDir`,
  `PageSpec` (sealed), `OffsetPage`, `CursorPage` (done: §5–§7 shapes bind; `Page` renamed to
  `PageSpec` to avoid colliding with `data.db.model.pagination.Page<T>`)
- [x] 2.5 Custom routing: `FilterNodeDeserializer` wired via `using`/`contentUsing` at use sites,
  never on the `FilterNode` interface (done: routes `and`/`or`/`not` → logical, else → comparison;
  no serialization-time duplicate `op`)
- [x] 2.6 Lowercase `@JsonValue` wire codes on all wire enums; `@JsonProperty` only on the four
  snake_case compound keys (`group_by`, `value_type`, `include_total`, `as`)
- [x] 2.7 Carry open decisions D1/D5/D6/D8/D10 as labeled `// TODO(Dn)` markers

## 3. Tests & quality gates

- [x] 3.1 `StructuredQueryDeserializationTest` round-trips the spec examples through the production
  `JsonMapper` (nested boolean tree, fn-on-left comparison, null check, `in` with array operand,
  nested fn expression, full row + aggregate envelopes, serialize→deserialize equality), using the
  `test_case_eval_summaries` entity rather than generic placeholders
- [x] 3.2 `./gradlew test --tests "*StructuredQueryDeserializationTest"` passes
- [x] 3.3 `./gradlew spotlessApply checkstyleMain checkstyleTest` clean

## 4. Spec index & docs housekeeping

- [x] 4.1 Update `openspec/specs/README.md` per Spec Index Maintenance Policy (done: Cross-cutting
  Concerns index now lists `structured-query-model` — Partial, with a summary noting the request
  model is Implemented and validation/translation/response are Planned)
- [x] 4.2 Confirm no `config.yaml`, AGENTS.md, `docs/configuration.md`, or `docs/database-schema.md`
  update is required — this change adds no new architectural layer, convention, config property, or
  schema (the `experimental.query.model` package is inert and follows existing record/Jackson
  patterns) (done: reviewed, no edits needed)
