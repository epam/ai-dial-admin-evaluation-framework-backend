## MODIFIED Requirements

### Requirement: SQL translation
The system SHALL translate a validated structured query into parameterized jOOQ SQL. Flat allowlisted
properties SHALL expand to their physical sources — plain columns or JSONB navigation/casts, with
metric-value paths cast to numeric — using bind parameters rather than string concatenation. Comparison
operators SHALL map to SQL per the wire contract: `eq`/`ne`/`lt`/`gt`/`le`/`ge` to direct comparisons,
`co`/`nc` to case-insensitive `LIKE`/`NOT LIKE` with wildcards **when the left operand is a scalar
(text/numeric) field**, `in` to `IN`, and `eq`/`ne` against a null literal to `IS NULL`/`IS NOT NULL`.
When the left operand of `co`/`nc` is an **array-typed field** (a JSONB field declared `array`),
`co`/`nc` SHALL instead translate to JSONB array-element containment / its negation rather than
`LIKE`: a string right operand SHALL use the JSONB `?` element-existence operator, and a non-string
literal SHALL use `@>` against a one-element JSON array, with the operand bound as a parameter (never
concatenated). Aggregate functions SHALL translate to their SQL aggregates;
ordered-set aggregates `percentile_cont`/`percentile_disc` SHALL translate to
`percentile_cont(fraction) WITHIN GROUP (ORDER BY column)` /
`percentile_disc(fraction) WITHIN GROUP (ORDER BY column)` with the `fraction` bound as a parameter.
Aggregate mode SHALL require aliased select entries and SHALL build `group_by`/`having`/`sort` against
the base fields together with the select aliases. The offset pagination strategy SHALL be applied with
a default limit of 100 and a maximum of 1000.
Status: **Implemented**

#### Scenario: Flat metric property expands to JSONB source
- **WHEN** a validated query filters on a flattened metric property such as `metric:Accuracy:score`
- **THEN** the translator emits the parameterized JSONB-navigated, numeric-cast predicate, invisible to
  the client

#### Scenario: Contains operator maps to case-insensitive LIKE
- **WHEN** a query uses the `co` operator on a string field
- **THEN** the translator emits a case-insensitive `LIKE '%value%'` predicate with a bind parameter

#### Scenario: Contains operator on an array field maps to JSONB containment
- **WHEN** a query uses the `co` operator on an array-typed field with a string right operand (e.g.
  `tags CONTAINS 'text'`)
- **THEN** the translator emits a JSONB element-existence predicate (the `?` operator) with the
  operand bound as a parameter, not a `LIKE`, and `nc` emits its negation

#### Scenario: Contains on a non-array left operand falls through to LIKE
- **WHEN** a query uses the `co` operator whose left operand is NOT a bare array-typed field (e.g. a
  scalar field, or a function-wrapped expression)
- **THEN** the translator does not apply array detection and emits the case-insensitive `LIKE`
  predicate (its scalar `co`/`nc` behavior)

#### Scenario: Aggregate query groups by base field and select alias
- **WHEN** an aggregate-mode query groups by a field and selects an aliased aggregate
- **THEN** the translator builds GROUP BY against the field and resolves `having`/`sort` references
  against the base fields plus the select alias

#### Scenario: Percentile translates to WITHIN GROUP ORDER BY
- **WHEN** a query selects `percentile_cont(0.1, "metric:Accuracy:score")`
- **THEN** the translator emits `percentile_cont(?) WITHIN GROUP (ORDER BY <numeric-cast JSONB path>)`
  with the fraction bound as a parameter

## Implementation Notes
- `FilterTranslator.toComparison`: array-field detection triggers ONLY when the left operand is a bare
  `FieldExpr` whose `QueryFieldBinding` type is `QueryFieldType.ARRAY` (looked up via `bindings.get(name)`,
  which wins over the `JsonbFieldResolver` fallback); a non-`FieldExpr` left operand keeps scalar LIKE.
  jOOQ plain SQL escapes the `?` operator as `??`.
- Array-typed flattened `data::<field>` bindings are produced by `TestCaseFieldBindingsBuilder`.
