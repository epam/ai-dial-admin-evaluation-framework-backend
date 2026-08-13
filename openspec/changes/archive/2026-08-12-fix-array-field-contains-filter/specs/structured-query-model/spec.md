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
literal SHALL use `@> to_jsonb(<operand>)` (JSONB containment of the scalar element), with the operand
bound as a parameter (never concatenated). When the left operand of `co`/`nc` is a **case-normalizing
function** (`lower` or `upper`) applied to a bare array-typed field, the wrapper SHALL be discarded and
`co`/`nc` SHALL translate to **case-insensitive whole-element** array containment / its negation: the row
matches when some array element equals the right operand ignoring case, and an element that merely
contains the operand as a substring SHALL NOT match. Elements SHALL be compared by their JSON **text
rendering**, so a string operand also matches a non-string element whose rendering equals it (e.g. `"1"`
matches the element `1`) — unlike the bare-field `?` form, which inspects string elements only. Under this
case-insensitive form a row whose array-declared field holds a **non-array** value (or no value) SHALL NOT
match, and SHALL NOT cause the statement to fail — a further divergence from the bare-field form, where
`?` matches a string value equal to the operand and an object value carrying it as a key. The operand
SHALL be bound as a parameter. The wrapper name SHALL be matched case-insensitively, as the function
registry resolves it. For a non-string right operand the
wrapper SHALL be discarded and the case-sensitive `@>` form SHALL be used (a non-string literal has no
case). No other operator and no other function SHALL be unwrapped: outside the `co`/`nc` array branch,
`lower`/`upper` SHALL keep translating to the SQL function itself.
Aggregate functions SHALL translate to their SQL aggregates;
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

#### Scenario: Contains on a case-normalized array field matches whole elements ignoring case
- **WHEN** a query uses the `co` operator whose left operand is `lower(<array-typed field>)` (or
  `upper(...)`) with a string right operand (e.g. `lower(tags) CONTAINS 'tee'`)
- **THEN** the translator discards the wrapper and emits case-insensitive whole-element array
  containment with the operand bound as a parameter, so a row whose array holds `"Tee"` matches, a row
  whose array holds only `"tee-shirt"` does not, and the emitted SQL never applies `lower`/`upper` to a
  JSONB value

#### Scenario: NOT CONTAIN on a case-normalized array field negates the containment
- **WHEN** a query uses the `nc` operator whose left operand is `lower(<array-typed field>)` with a
  string right operand
- **THEN** the translator emits the negation of the case-insensitive whole-element containment, and it
  stays total over null operands (a row whose array is null or absent matches)

#### Scenario: Case-normalized array containment ignores a non-array value instead of failing
- **WHEN** a query filters `lower(<array-typed field>) CONTAINS 'tee'` over rows where one row's
  array-declared field holds a non-array JSON value (e.g. the string `"tee"` or an object) and another
  holds no value at all
- **THEN** the statement executes successfully, those rows do not match, and the rows whose arrays hold a
  matching element still match; under `nc` the non-array and missing-value rows match

#### Scenario: Case-normalized array containment compares elements by their text rendering
- **WHEN** a query filters `lower(<array-typed field>) CONTAINS '1'` (a string operand) and a row's array
  is `[1, 2]`
- **THEN** the row matches, even though the bare-field `?` form of the same comparison would not

#### Scenario: Wrapper name is recognized regardless of its case
- **WHEN** a query uses the `co` operator whose left operand is `LOWER(<array-typed field>)` (an upper-case
  spelling the function registry resolves the same way as `lower`)
- **THEN** the translator routes it to case-insensitive whole-element containment, exactly as for `lower`,
  and never emits the SQL function against the JSONB value

#### Scenario: Case-normalized array field with a non-string operand keeps JSON containment
- **WHEN** a query uses the `co` operator whose left operand is `lower(<array-typed field>)` and whose
  right operand is a non-string literal (e.g. an integer)
- **THEN** the translator discards the wrapper and emits the `@> to_jsonb(<operand>)` containment
  predicate

#### Scenario: Contains on a non-array left operand falls through to LIKE
- **WHEN** a query uses the `co` operator whose left operand is neither a bare array-typed field nor a
  `lower`/`upper` wrapper around one (e.g. a scalar field, a `lower(<string field>)`, or any other
  function-wrapped expression)
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

## Implementation notes
- `FilterTranslator.toComparison`: array-field detection triggers when the left operand **resolves to** a
  bare `FieldExpr` whose `QueryFieldBinding` type is `QueryFieldType.ARRAY` (looked up via
  `bindings.get(name)`, which wins over the `JsonbFieldResolver` fallback) — either directly, or by
  unwrapping a single-argument `lower`/`upper` `FnExpr` around such a field, which additionally selects
  the case-insensitive containment form. Any other left operand — a scalar field, a `lower` over a
  non-array field, any other function — keeps scalar LIKE. jOOQ plain SQL escapes the `?` operator as
  `??`. Array-typed flattened `data::<field>` bindings are produced by `TestCaseFieldBindingsBuilder`
  (see `query-schema-discovery`).
  <!-- ALREADY SYNCED (task 4.5): this bullet replaced the pre-change bullet with the same
       `FilterTranslator.toComparison:` lead-in — whose "a non-`FieldExpr` left operand keeps scalar LIKE"
       is no longer true — directly in openspec/specs/structured-query-model/spec.md, because the archive
       appends notes rather than replacing them. Do NOT append it again at archive time. -->
- The case-insensitive form expands the array with `jsonb_array_elements_text` over a
  `case when jsonb_typeof(<col>) = 'array' then <col> else '[]'::jsonb end` argument: the guard must sit
  inside the function argument, since `jsonb_array_elements_text` raises on a scalar/object value and
  `AND` conjunct evaluation order is not guaranteed by the planner.
- The wrapper is meaningful only as a case-normalization hint: `lower`/`upper` are undefined on `jsonb`
  in Postgres, so translating such an operand literally yields a statement that fails at execution
  (SQLSTATE 42883) rather than a different result set (GH #142).
- Case-insensitive whole-element containment expands the array with `jsonb_array_elements_text` and
  compares each element to the bound operand case-insensitively — a per-row element scan. This costs no
  index access that the bare form has: `test_cases.data` carries no GIN index, and both forms put the
  extracted expression `data -> '<field>'` on the left, which `jsonb_ops` cannot serve anyway.
- The `CASE` type guard — not the `is not false` wrapper — is what makes the wrapped `nc` total over
  null/absent/non-array values: `EXISTS` is never UNKNOWN, so `nullSatisfies` is inert on this branch and
  is kept only for uniformity with the `?`/`@>` forms.
- Consumers inherit the behavior without change: the `/queries/execute` endpoint, list-endpoint filters,
  and suite `testCaseFilter` run selection (`QueryDslRunnableTestCaseSelector`, whose ALL-turns-match
  quantifier wraps whatever leaf predicate the translator produces).
