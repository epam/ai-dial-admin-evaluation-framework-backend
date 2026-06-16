## MODIFIED Requirements

### Requirement: Structured filtering via repeatable `filter` parameter
List endpoints SHALL support structured filtering via a repeatable query parameter: `filter=<field>:<op>:<value>`.

Supported operators (v1) SHALL include: `eq`, `ne`, `co` (case-insensitive substring), `gt`, `gte`, `lt`, `lte`. Per-entity whitelists define allowed fields and operators.

The `eq` and `ne` operators on `STRING` and `JSONB_STRING` fields SHALL perform case-insensitive exact matching (`lower(column) = lower(:value)` / `lower(column) <> lower(:value)`). For all other field types (`UUID`, `LONG`, `BOOLEAN`, `JSONB_NUMERIC`), `eq`/`ne` perform exact (non-lowercased) matching as before.

**Filter key naming convention**: Filter field names SHALL match the JSON property names in the corresponding response DTO. For primitive `boolean` fields serialized by Jackson without the `is` prefix (e.g., `valid`, `enabled`), the filter key SHALL also omit the `is` prefix.

#### Scenario: AND semantics
- **WHEN** client provides multiple `filter` parameters
- **THEN** system SHALL apply them with AND semantics

#### Scenario: Whitelisted filter fields/operators
- **WHEN** client provides `filter` conditions
- **THEN** system SHALL accept only whitelisted fields/operators for that endpoint and bind values as query parameters

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

#### Scenario: `eq` on non-STRING field remains case-sensitive
- **WHEN** client sends `filter=id:eq:550e8400-E29B-41D4-A716-446655440000`
- **THEN** system SHALL perform exact byte-level comparison (UUID matching is normalized separately)

#### Scenario: `contains` operator is rejected (breaking change)
- **WHEN** client sends a filter with operator `contains` (former name)
- **THEN** system SHALL respond with HTTP 400 indicating unsupported operator
