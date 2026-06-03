# Health

## Purpose
This spec defines the backend health endpoint(s) that support basic service monitoring.

Status: **Implemented**

## Requirements

### Requirement: Provide health status
The service SHALL expose a health endpoint that indicates service availability.
Status: **Implemented**

#### Scenario: Basic health check
- **WHEN** client calls `GET /api/v1/health`
- **THEN** system SHALL respond with HTTP 200 and a payload indicating service is up

#### Scenario: Database health integration
- **WHEN** database health is enabled
- **THEN** service health SHALL reflect database connectivity (directly or via actuator integration)

## Implementation Notes
- Controller: `com.epam.aidial.evaluation.web.controller.HealthController`
- Custom indicator: `com.epam.aidial.evaluation.service.infrastructure.health.DatabaseHealthIndicator`
- Config: `management.health.db.enabled` in `src/main/resources/application.yml`

## Open Questions / TODO
- Define a stable response schema for `/api/v1/health` (currently depends on controller implementation).

