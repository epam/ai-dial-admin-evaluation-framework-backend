## MODIFIED Requirements

### Requirement: Structured filtering via repeatable `filter` parameter
List endpoints SHALL support structured filtering via a repeatable query parameter: `filter=<field>:<op>:<value>`.

Supported operators (v1) SHALL include: `eq`, `ne`, `contains` (case-insensitive substring), `gt`, `gte`, `lt`, `lte`. Per-entity whitelists define allowed fields and operators.

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
- **WHEN** client sends `filter=enabled:EQ:true`
- **THEN** system SHALL filter test cases where enabled is true
