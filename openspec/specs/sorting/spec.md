## ADDED Requirements

### Requirement: Support multi-column sorting in list endpoints
The system SHALL allow clients to request an ordered list of sort keys for list endpoints.

#### Scenario: Sort parameter format
- **WHEN** a list endpoint supports sorting
- **THEN** it SHALL accept a repeatable query parameter `sort`
- **AND THEN** each `sort` value SHALL be either `<field>` or `<field>,<direction>`
- **AND THEN** `<direction>` SHALL be `asc` or `desc` (case-insensitive)
- **AND THEN** if `<direction>` is omitted, it SHALL default to `asc`

#### Scenario: Multi-key precedence
- **WHEN** a client provides multiple `sort` parameters (e.g., `sort=a,asc&sort=b,desc`)
- **THEN** the system SHALL apply them in the order provided (first key is highest precedence)

#### Scenario: Stable ordering
- **WHEN** the provided sort keys do not guarantee a unique ordering
- **THEN** the system SHALL apply a deterministic tie-breaker sort as the last key (e.g., `id ASC`) unless already present

### Requirement: Validate and whitelist sort fields
The system MUST protect sorting parameters from SQL injection and undefined behavior by validating and whitelisting supported sort fields per endpoint/entity. The whitelist value for each allowed sort field SHALL be a typed `org.jooq.Field<?>` reference (not a `String` column name), so a schema rename or type change fails at compile time before the request can be served.

**Sort key naming convention**: Sort field names SHALL match the JSON property names in the corresponding response DTO. For primitive `boolean` fields serialized by Jackson without the `is` prefix (e.g., `valid`, `enabled`), the sort key SHALL also omit the `is` prefix.

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

### Requirement: Sort parameter parsing is consistent at the API boundary
The system SHALL parse repeatable query parameter `sort` values at the API boundary into a structured, ordered list of sort keys, before any SQL is constructed.

#### Scenario: Equivalent representations are accepted
- **WHEN** a client requests sorting using `sort=<field>` or `sort=<field>,<direction>`
- **THEN** the system SHALL interpret the request according to the sorting requirements (direction defaults to `asc` when omitted)

#### Scenario: Framework tokenization does not change semantics
- **WHEN** the HTTP framework tokenizes a single `sort=<field>,<direction>` value into separate tokens during request binding
- **THEN** the system SHALL treat it as if the original `<field>,<direction>` value was provided

#### Scenario: Malformed values are rejected
- **WHEN** a `sort` value is malformed (blank field, invalid direction, invalid tokenization)
- **THEN** the system SHALL reject the request with HTTP 400

