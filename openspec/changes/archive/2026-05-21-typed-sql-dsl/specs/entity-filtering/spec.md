## MODIFIED Requirements

### Requirement: Structured filtering via repeatable `filter` parameter
List endpoints SHALL support structured filtering via a repeatable query parameter: `filter=<field>:<op>:<value>`.

Supported operators SHALL include: `eq`, `ne`, `co` (case-insensitive substring), `gt`, `gte`, `lt`, `lte`, `in` (set membership). Per-entity whitelists define allowed fields and operators. The whitelist value for each allowed field SHALL be a typed `org.jooq.Field<?>` reference (not a `String` column name), so a schema rename or type change fails at compile time before the request can be served. The type system enforces column existence and column type at compile time; operator allowlisting remains a runtime check returning HTTP 400 with the existing `InvalidFilterException` payload on disallowed operator/field combinations.

The `eq` and `ne` operators on `STRING` and `JSONB_STRING` fields SHALL perform case-insensitive exact matching (`lower(column) = lower(:value)` / `lower(column) <> lower(:value)`). For all other field types (`UUID`, `LONG`, `BOOLEAN`, `JSONB_NUMERIC`), `eq`/`ne` perform exact (non-lowercased) matching.

The `in` operator is supported on `STRING` and `UUID` field types only. The value for `in` is a comma-separated list of values (e.g., `filter=testCaseName:in:name1,name2,name3`). Each element is URL-decoded individually. Literal commas within a value SHALL be percent-encoded as `%2C` by the caller.

**Filter key naming convention**: Filter field names SHALL match the JSON property names in the corresponding response DTO. For primitive `boolean` fields serialized by Jackson without the `is` prefix (e.g., `valid`, `enabled`), the filter key SHALL also omit the `is` prefix.

Status: **Implemented**

#### Scenario: AND semantics
- **WHEN** client provides multiple `filter` parameters
- **THEN** system SHALL apply them with AND semantics

#### Scenario: Whitelisted filter fields/operators
- **WHEN** client provides `filter` conditions
- **THEN** system SHALL accept only whitelisted fields/operators for that endpoint
- **AND THEN** SHALL translate each allowed condition to a typed `org.jooq.Condition` against the whitelisted `Field<?>`
- **AND THEN** SHALL bind values as typed DSL parameters — never inline them into SQL text

#### Scenario: Invalid filter
- **WHEN** client provides an invalid filter syntax, unsupported operator, or non-whitelisted field
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Filter key names match JSON response property names
- **WHEN** a response DTO has a boolean field serialized as `valid` in JSON
- **THEN** the filter key SHALL be `valid` (not `isValid`)

#### Scenario: Boolean filter for enabled field
- **WHEN** client sends `filter=enabled:eq:true`
- **THEN** system SHALL filter test cases where enabled is true

#### Scenario: `co` operator rejected for non-string fields
- **WHEN** client sends a `co` filter on a numeric or UUID field
- **THEN** system SHALL respond with HTTP 400

#### Scenario: `co` substring match is case-insensitive
- **WHEN** client sends `filter=name:co:test`
- **THEN** system SHALL return entities whose `name` contains `"test"` regardless of case (e.g., `"MyTest"`, `"TEST"`)

#### Scenario: `eq` on STRING field is case-insensitive
- **WHEN** client sends `filter=name:eq:Test`
- **THEN** system SHALL return entities whose `name` equals `"test"`, `"TEST"`, `"Test"`, etc. (case-insensitive exact match)

#### Scenario: `ne` on STRING field is case-insensitive
- **WHEN** client sends `filter=name:ne:Test`
- **THEN** system SHALL exclude entities whose `name` equals `"test"` in any casing

#### Scenario: `eq` on non-STRING field remains exact-match
- **WHEN** client sends `filter=id:eq:550e8400-E29B-41D4-A716-446655440000`
- **THEN** system SHALL perform exact byte-level comparison (UUID matching is normalized separately)

#### Scenario: `contains` operator is rejected
- **WHEN** client sends a filter with operator `contains` (former operator name, renamed to `co`)
- **THEN** system SHALL respond with HTTP 400 indicating unsupported operator

#### Scenario: IN operator matches multiple values for STRING field
- **WHEN** client sends `filter=testCaseName:in:name1,name2`
- **THEN** system SHALL return (or act on) only entities whose `testCaseName` is exactly `name1` or `name2`

#### Scenario: IN operator matches multiple values for UUID field
- **WHEN** client sends a filter like `filter=testCaseId:in:<uuid1>,<uuid2>`
- **THEN** system SHALL match only entities whose ID equals one of the provided UUIDs

#### Scenario: IN operator with a single value
- **WHEN** client sends `filter=testCaseName:in:name1`
- **THEN** system SHALL behave identically to `filter=testCaseName:eq:name1`

#### Scenario: IN operator with empty or blank value rejected
- **WHEN** client sends `filter=testCaseName:in:` or `filter=testCaseName:in:,`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: IN operator on unsupported field type rejected
- **WHEN** client sends an `in` filter on a field whose whitelist type is `BOOLEAN` or `LONG`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: IN operator with invalid UUID element rejected
- **WHEN** client sends `filter=testCaseId:in:not-a-uuid,<validUuid>` where the first element is not a valid UUID
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Comma in value is percent-encoded
- **WHEN** a value contains a literal comma and the client encodes it as `%2C`
- **THEN** system SHALL treat `%2C` as a literal comma within that element, not as a separator

## ADDED Requirements

### Requirement: JSONB path access goes through a dialect seam
JSONB-typed filter fields (`JSONB_STRING`, `JSONB_NUMERIC`) SHALL traverse JSONB paths via the injectable `JsonPathAccessor` component, not via plain-SQL templating in `WhereBuilder`. Behavior for callers is unchanged: dotted field syntax (`testCaseData.someKey`, `metricValues.<metric>.<output>`) is still parsed at the filter layer, validated against the field type's path-depth rule, and bound as named parameters.

Status: **Implemented**

#### Scenario: Single-level JSONB path
- **WHEN** a client sends `filter=testCaseData.userId:eq:abc`
- **THEN** the filter layer SHALL invoke `JsonPathAccessor.jsonbAtAsText(testCaseData, val("userId"))` and compare the result to `val("abc")` via a typed `Condition`

#### Scenario: Two-level JSONB numeric path
- **WHEN** a client sends `filter=metricValues.Accuracy.score:gte:0.8`
- **THEN** the filter layer SHALL invoke `JsonPathAccessor.jsonbAtAsNumeric(metricValues, val("Accuracy"), val("score"))` and compare the result to `val(new BigDecimal("0.8"))` via a typed `Condition`

#### Scenario: Path depth validation unchanged
- **WHEN** a client sends a three-level path on a `JSONB_NUMERIC` field (e.g. `metricValues.a.b.c:gte:1`)
- **THEN** the system SHALL respond with HTTP 400 with the existing "nested JSONB paths deeper than two levels not supported" message

#### Scenario: JSONB path keys bound as DSL parameters
- **WHEN** a filter targets a `JSONB_NUMERIC` field (e.g. `metricValues.Accuracy.score:gte:0.8`)
- **THEN** the SQL SHALL bind each JSONB path key (`"Accuracy"`, `"score"`) as a parameter via `DSL.val(String)`, NOT inline them via `DSL.inline(String)` and NOT pass them as identifier fragments via `DSL.field(String)`
- **AND THEN** the path-key parameters SHALL flow through the same prepared-statement binding pipeline as the comparison value
- **AND THEN** SQL injection via path keys SHALL be impossible by construction, even though path keys are already constrained by configured whitelists
