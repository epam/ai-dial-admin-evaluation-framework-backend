## MODIFIED Requirements

### Requirement: Provide pagination and safe sorting
List queries SHALL support pagination and MUST protect sorting parameters from SQL injection, including multi-column sorting.

#### Scenario: Sort parameter safety
- **WHEN** list endpoints accept sort fields (single or multiple)
- **THEN** repository layer SHALL whitelist allowed sort columns to prevent SQL injection
- **AND THEN** it MUST NOT interpolate raw client input into SQL identifiers

#### Scenario: Multi-column sorting order
- **WHEN** a client provides multiple sort keys
- **THEN** the repository layer SHALL generate an `ORDER BY` clause with the same key precedence as the request

#### Scenario: Invalid sort field
- **WHEN** a client requests a sort field that is not in the allowlist for that query
- **THEN** the system SHALL reject the request with HTTP 400 (rather than attempting to execute SQL)

#### Scenario: Stable ordering for pagination
- **WHEN** sorting does not uniquely identify a row order
- **THEN** list queries SHOULD add a deterministic tie-breaker (e.g., `id ASC`) to keep pagination stable

