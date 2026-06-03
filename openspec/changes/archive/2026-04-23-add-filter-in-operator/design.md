## Context

The filter system (`FilterParser` → `FilterCondition` → `WhereBuilder`) supports scalar operators: `eq`, `ne`, `contains`, `gt`, `gte`, `lt`, `lte`. All conditions are combined with AND in the generated WHERE clause.

The immediate problem: the bulk DELETE endpoint (`DELETE /api/v1/test-suites/{id}/test-cases?filter=…`) needs to target rows by a set of names. There is no multi-value operator, so clients have no correct way to express this — multiple `eq` conditions AND-ed together are logically contradictory and silently match nothing.

The existing `entity-filtering` spec already anticipates this under "Filtering DSL refinement (future)" and names `in` as a candidate operator.

## Goals / Non-Goals

**Goals:**
- Add an `in` operator that matches a column value against a non-empty set of values in a single filter parameter.
- Scope the operator to `STRING` and `UUID` field types only.
- Keep the change minimal and contained within the existing filter pipeline.
- No breaking changes to existing operators or existing callers.

**Non-Goals:**
- OR grouping across different fields.
- `NOT IN` operator (can be added later if needed).
- Support on `BOOLEAN`, `LONG`, or `JSONB_*` field types.
- Changes to pagination, sorting, or any other part of the query pipeline.

## Decisions

### Decision 1: Comma-separated values as the IN value syntax

**Chosen**: `filter=testCaseName:in:name1,name2,name3`

`FilterParser` already splits on the first two `:` separators. Everything after the second `:` is the raw value. For `in`, that raw value is further split on `,` with each part URL-decoded individually.

**Alternative considered**: Repeating the same field with `eq` and collapsing to `IN` automatically. Rejected — it would require the builder to detect field repetition and change semantics post-parse, which adds hidden state and breaks the simple one-condition-per-list-item contract.

**Alternative considered**: A bracketed syntax `in:[name1,name2]`. Rejected — adds parsing complexity with no benefit; comma separation is sufficient and consistent with common REST filter conventions.

**Comma escaping**: Values containing literal commas are URL-encoded: `name%2Cwith%2Ccomma`. `FilterParser` already URL-decodes each value; splitting on `,` before URL-decoding ensures commas inside encoded values do not split incorrectly. Therefore the split happens **before** URL-decode on a per-element basis, or equivalently: split the raw (undecoded) value on `,`, then URL-decode each part.

### Decision 2: `parsedValue` carries `List<String>` for IN

`FilterCondition.parsedValue` is `Object`. For all existing operators it holds a scalar. For `IN` it will hold `List<String>` (for both STRING and UUID — UUIDs are stored as `VARCHAR(36)` so `List<String>` is correct). `WhereBuilder` inspects the operator enum to know whether the param is a collection.

**Why not a dedicated field?** `FilterCondition` is a pure carrier in `.data.db.model`; adding a second `Object` field would duplicate the slot. The `operator` field is the discriminator — no ambiguity.

### Decision 3: Splitting responsibility stays in `FilterParser` (service layer)

The layering rule is clear: `.data.db` models are pure carriers with no parsing logic. `FilterParser` lives in `service.domain.filter` and already owns the `field:op:value` tokenization. Splitting the comma-separated IN value belongs there, not in `WhereBuilder` or in `FilterCondition`.

`WhereBuilder` receives a pre-split `List<String>` in `parsedValue` and only handles SQL generation — it does not inspect the raw string.

### Decision 4: SQL generation uses `column IN (:paramN)` with collection parameter binding

`NamedParameterJdbcTemplate` natively expands a `List<String>` param in `IN (:param)` to `IN (:param_0, :param_1, …)`. No custom SQL templating is needed.

For UUID fields the column already contains `VARCHAR(36)` strings, so `List<String>` binds correctly without casting.

### Decision 5: Empty list and blank-element validation in `FilterParser`

- An `in` value that is blank after splitting (empty list) → HTTP 400 (`FilterValidationException`).
- Any element that is blank after URL-decoding → HTTP 400.
- Minimum 1 element; no enforced maximum (the existing `@Size(max=32)` per-filter-param limit at the controller bounds the total number of filter params, not elements within one `in` value).

## Risks / Trade-offs

- **Large IN lists**: A single `in` filter can carry many values and expand to a large SQL `IN (…)` clause. PostgreSQL handles this well in practice for hundreds of values; the existing `@Size(max=32)` on the `filter` list param doesn't bound per-param element count. For now this is acceptable — bulk operations are the target use case and typically involve tens of values. A max-elements limit (e.g. 1000) can be added in a follow-up if needed. → No mitigation required now.
- **Commas in values**: Values with literal commas must be `%2C`-encoded by the caller. This is documented behavior consistent with URL encoding conventions; no special escaping syntax is introduced.
- **UUID validation for IN**: For `UUID`-typed fields, each element in the list is individually UUID-validated (same as the scalar path). Invalid UUID format → HTTP 400.

## Migration Plan

No DB schema changes. No Flyway migrations. No config changes. The operator is additive — existing filter expressions continue to work unchanged. No client migration required.
