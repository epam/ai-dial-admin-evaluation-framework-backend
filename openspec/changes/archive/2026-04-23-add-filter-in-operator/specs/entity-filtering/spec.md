## MODIFIED Requirements

### Requirement: Structured filtering via repeatable `filter` parameter
List endpoints SHALL support structured filtering via a repeatable query parameter: `filter=<field>:<op>:<value>`.

Supported operators SHALL include: `eq`, `ne`, `contains` (case-insensitive substring), `gt`, `gte`, `lt`, `lte`, `in` (set membership). Per-entity whitelists define allowed fields and operators.

The `in` operator is supported on `STRING` and `UUID` field types only. The value for `in` is a comma-separated list of values (e.g., `filter=testCaseName:in:name1,name2,name3`). Each element is URL-decoded individually. Literal commas within a value SHALL be percent-encoded as `%2C` by the caller.

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
