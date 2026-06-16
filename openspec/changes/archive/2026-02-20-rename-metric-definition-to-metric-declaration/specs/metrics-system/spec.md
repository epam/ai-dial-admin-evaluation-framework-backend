# Metrics System (Delta)

Delta for change **rename-metric-definition-to-metric-declaration**: rename stub from "metric definitions" to "metric declarations" and update API paths.

## MODIFIED Requirements

### Requirement: List metric declarations (stub)
The service SHALL provide a paginated endpoint to list metric declarations available for discovery.
Status: **Implemented (stub)**

#### Scenario: Empty catalog
- **WHEN** client calls `GET /api/v1/metric-declarations` and no metric declarations exist
- **THEN** system SHALL respond with HTTP 200 and an empty page result

#### Scenario: Pagination and sorting
- **WHEN** client calls `GET /api/v1/metric-declarations?page=<p>&size=<s>&sort=<field>[,<asc|desc>]` (repeatable)
- **THEN** system SHALL apply pagination and safe sorting using a whitelist of allowed fields
