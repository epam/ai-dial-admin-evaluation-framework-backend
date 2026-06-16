## ADDED Requirements

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

