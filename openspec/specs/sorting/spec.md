# Sorting

## Purpose

Define ordered, allowlisted REST sort keys and their API-boundary parsing for list endpoints.

Status: **Implemented**

## Requirements

### Requirement: Support multi-column sorting in list endpoints
Status: **Implemented**
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
- **WHEN** the endpoint's sort whitelist contains `id` and the requested/default sort omits it
- **THEN** the system SHALL append `id ASC` as the last key
- **AND** whitelists without `id` (such as test-suite metric definitions) SHALL NOT receive an implicit unique tie-breaker

### Requirement: Validate and whitelist sort fields
Status: **Implemented**
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
Status: **Implemented**
The system SHALL parse repeatable query parameter `sort` values at the API boundary into a structured, ordered list of sort keys, before any SQL is constructed.

#### Scenario: Equivalent representations are accepted
- **WHEN** a client requests sorting using `sort=<field>` or `sort=<field>,<direction>`
- **THEN** the system SHALL interpret the request according to the sorting requirements (direction defaults to `asc` when omitted)

#### Scenario: Framework tokenization does not change semantics
- **WHEN** binding produces an even-length list of comma-free tokens and every odd-position token is `asc` or `desc` (case-insensitive)
- **THEN** the parser SHALL recombine adjacent field/direction pairs before parsing
- **AND** other lists SHALL be parsed as supplied rather than guessed into pairs

#### Scenario: Malformed values are rejected
- **WHEN** a `sort` value is malformed (blank field, invalid direction, invalid tokenization)
- **THEN** the system SHALL reject the request with HTTP 400

## Implementation notes

- [SortParser](../../../src/main/java/com/epam/aidial/evaluation/service/domain/sort/SortParser.java) owns ordered API-boundary parsing and the token-recombination heuristic.
- [OrderByBuilder](../../../src/main/java/com/epam/aidial/evaluation/data/db/repository/sql/OrderByBuilder.java) and [SortWhitelists](../../../src/main/java/com/epam/aidial/evaluation/data/db/repository/sql/SortWhitelists.java) own typed SQL mapping, endpoint defaults, and conditional `id` tie-breaking.

