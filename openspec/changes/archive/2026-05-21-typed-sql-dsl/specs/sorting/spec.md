## MODIFIED Requirements

### Requirement: Validate and whitelist sort fields
The system MUST protect sorting parameters from SQL injection and undefined behavior by validating and whitelisting supported sort fields per endpoint/entity. The whitelist value for each allowed sort field SHALL be a typed `org.jooq.Field<?>` reference (not a `String` column name), so a schema rename or type change fails at compile time before the request can be served.

**Sort key naming convention**: Sort field names SHALL match the JSON property names in the corresponding response DTO. For primitive `boolean` fields serialized by Jackson without the `is` prefix (e.g., `valid`, `enabled`), the sort key SHALL also omit the `is` prefix.

Status: **Implemented**

#### Scenario: Unknown sort field
- **WHEN** a client requests sorting by a field that is not supported by the endpoint/entity
- **THEN** the system SHALL reject the request with HTTP 400

#### Scenario: Unsafe input rejection
- **WHEN** a `sort` value is malformed (empty field, invalid direction, invalid tokenization)
- **THEN** the system SHALL reject the request with HTTP 400

#### Scenario: Column allowlist mapping
- **WHEN** a request is accepted with sorting
- **THEN** the repository/data-access layer SHALL translate each API sort field to a typed `org.jooq.SortField<?>` built from a whitelisted `Field<?>` plus the requested direction
- **AND THEN** the system MUST NOT interpolate raw client input into SQL identifiers

#### Scenario: Sort key names match JSON response property names
- **WHEN** a response DTO has a boolean field serialized as `valid` in JSON
- **THEN** the sort key SHALL be `valid` (not `isValid`)
