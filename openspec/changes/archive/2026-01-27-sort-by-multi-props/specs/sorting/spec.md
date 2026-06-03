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
The system MUST protect sorting parameters from SQL injection and undefined behavior by validating and whitelisting supported sort fields per endpoint/entity.

#### Scenario: Unknown sort field
- **WHEN** a client requests sorting by a field that is not supported by the endpoint/entity
- **THEN** the system SHALL reject the request with HTTP 400

#### Scenario: Unsafe input rejection
- **WHEN** a `sort` value is malformed (empty field, invalid direction, invalid tokenization)
- **THEN** the system SHALL reject the request with HTTP 400

#### Scenario: Column allowlist mapping
- **WHEN** a request is accepted with sorting
- **THEN** the repository/data-access layer SHALL translate each API sort field to a whitelisted SQL column/expression
- **AND THEN** the system MUST NOT interpolate raw client input into SQL identifiers

