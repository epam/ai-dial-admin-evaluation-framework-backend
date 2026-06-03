# Entity Filtering (delta: meta-model validations)

## ADDED Requirements

### Requirement: Upper bound on filter and sort parameter count (separate limits)
List endpoints SHALL enforce **separate** upper bounds on the number of repeatable `filter` parameters (default 32) and `sort` parameters (default 32) per request. When the client exceeds either limit, the system SHALL respond with HTTP 400.

#### Scenario: Filter list over limit
- **WHEN** client calls a list endpoint with more than 32 `filter` parameters
- **THEN** system SHALL respond with HTTP 400 and indicate the filter limit was exceeded

#### Scenario: Sort list over limit
- **WHEN** client calls a list endpoint with more than 32 `sort` parameters
- **THEN** system SHALL respond with HTTP 400 and indicate the sort limit was exceeded

#### Scenario: Within limits accepted
- **WHEN** client calls a list endpoint with filter count ≤ 32 AND sort count ≤ 32
- **THEN** system SHALL process the request normally

#### Scenario: Both at limit accepted
- **WHEN** client calls a list endpoint with exactly 32 filters AND 32 sorts
- **THEN** system SHALL process the request normally (limits are inclusive)
