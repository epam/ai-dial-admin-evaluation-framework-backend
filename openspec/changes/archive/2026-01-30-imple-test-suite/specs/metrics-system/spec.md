## ADDED Requirements

### Requirement: List metric definitions (stub)
The service SHALL provide a paginated endpoint to list metric definitions available for discovery.
Status: **Planned (stub)**

#### Scenario: Empty catalog
- **WHEN** client calls `GET /api/v1/metric-definitions` and no metric definitions exist
- **THEN** system SHALL respond with HTTP 200 and an empty page result

#### Scenario: Pagination and sorting
- **WHEN** client calls `GET /api/v1/metric-definitions?page=<p>&size=<s>&sort=<field>[,<asc|desc>]` (repeatable)
- **THEN** system SHALL apply pagination and safe sorting using a whitelist of allowed fields

