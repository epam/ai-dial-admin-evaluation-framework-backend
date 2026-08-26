## ADDED Requirements

### Requirement: Null handling in comparison and negation operators
The system SHALL make **negated** filter operators total over null operands: `nc` (in both its
scalar-`LIKE` form and its array-element-containment form) and `ne` with a non-null right operand SHALL
evaluate to **true** when either operand is null, rather than to SQL UNKNOWN. `eq`/`ne` against an explicit
null literal SHALL keep their `IS NULL`/`IS NOT NULL` translation, which is already total. The `not`
logical node SHALL likewise be total: `not(<child>)` SHALL evaluate to true when the child predicate is
false **or** unknown.

**Positive** operators (`co`, `eq`, `lt`, `gt`, `le`, `ge`, `in`) SHALL retain SQL three-valued semantics —
a null operand yields UNKNOWN, which excludes the row in a `WHERE` clause and counts as non-matching under
the multi-turn all-turns quantifier. This asymmetry is intentional: an absent value cannot satisfy a
positive assertion, but it trivially satisfies a negated one.

These semantics SHALL apply uniformly to every queryable entity and to every filter consumer (the query
execution endpoint, list-endpoint filters, and suite `testCaseFilter` run selection).
Status: **Implemented**

#### Scenario: NOT CONTAIN matches a row whose field is null
- **WHEN** a filter is `nc(field, "London")` and a row's `field` is null or absent
- **THEN** the row SHALL match, because a missing value does not contain "London"

#### Scenario: NOT CONTAIN on an array field matches a null array
- **WHEN** a filter is `nc(arrayField, "text")` and a row's `arrayField` JSONB value is null or absent
- **THEN** the row SHALL match

#### Scenario: NOT EQUALS matches a row whose field is null
- **WHEN** a filter is `ne(field, "London")` with a non-null right operand and a row's `field` is null
- **THEN** the row SHALL match

#### Scenario: Explicit null literal comparison is unchanged
- **WHEN** a filter is `eq(field, null)` or `ne(field, null)`
- **THEN** the translator SHALL emit `IS NULL` / `IS NOT NULL` respectively, unchanged by this requirement

#### Scenario: CONTAINS does not match a row whose field is null
- **WHEN** a filter is `co(field, "London")` and a row's `field` is null or absent
- **THEN** the row SHALL NOT match

#### Scenario: Negation of a positive predicate over a null field matches
- **WHEN** a filter is `not(co(field, "London"))` and a row's `field` is null or absent
- **THEN** the row SHALL match, consistent with `nc(field, "London")`

## Implementation notes
- `query/service/translate/FilterTranslator.java` — negated comparisons are wrapped so an
  UNKNOWN result is treated as satisfied; the `not` node is wrapped so an UNKNOWN child is treated as
  negatable. Positive comparisons are emitted unwrapped so they stay sargable on indexed columns.
- `query/model/ComparisonOp.java` — each operator declares its own null polarity.
