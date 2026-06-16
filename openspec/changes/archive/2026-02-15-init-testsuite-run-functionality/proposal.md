## Why

Test suites currently exist as static configurations without execution capability. Users need to trigger test suite evaluations, track their progress asynchronously, and manage run history. This change introduces the foundational infrastructure for test suite execution, enabling async job orchestration and run lifecycle management. The actual evaluation/metrics computation will be added later; this focuses on the execution framework and API surface.

## What Changes

- **New domain concept**: TestSuiteRun — represents a single execution of a test suite with run configuration
- **Async execution model**: POST endpoint triggers long-running job, returns runId immediately
- **Run configuration**: Initial support for `numberOfRuns` (max 64, configurable) and optional `testRunName` (extensible for future config options)
- **Run tracking**: SSE endpoint for real-time status updates with filtering support (PENDING → RUNNING → COMPLETED/FAILED/CANCELLED)
- **Run management**: CRUD operations with filtering, sorting, pagination, cancellation, and deletion
- **Job simulation**: Mock evaluation job with randomized duration (0-60s) and random failure (20% probability)
- **Persistence layer**: Database schema for test suite runs, statuses, timestamps, and relationships
- **Status lifecycle**: Track run states from submission through completion/failure/cancellation
- **Cancellation**: Support interrupting in-progress runs via thread interruption
- **Startup reconciliation**: On startup, mark orphaned non-terminal runs (PENDING/RUNNING) as FAILED to prevent stuck runs after crash/restart
- **Resource cleanup**: Delete runs with cascade to related resources (test results when implemented)

## Capabilities

### New Capabilities
- `test-suite-runs`: Async execution, tracking, and management of test suite runs. Includes run configuration, status lifecycle, filtering/sorting/pagination, SSE progress tracking, and deletion with cascade cleanup.

### Modified Capabilities
<!-- No existing capabilities are being modified - this is net new functionality -->

## Impact

**New Components**:
- `web.controller.TestSuiteRunController` — REST endpoints for run management
- `service.domain.TestSuiteRunService` — orchestration, job triggering, status management
- `service.domain.job.TestSuiteEvaluationJob` — mock job executor (sleeps to simulate work)
- `data.db.repository.TestSuiteRunRepository` — persistence for runs
- `data.db.mapper.TestSuiteRunRowMapper` — JDBC mapping
- `web.controller.TestSuiteRunSseController` — SSE endpoint for status streaming
- `service.domain.TestSuiteRunReconciliation` — startup reconciliation of orphaned runs
- DTOs: `TestSuiteRunRequestDto`, `TestSuiteRunResponseDto`, `TestSuiteRunUpdateDto`, `RunConfigDto`, `RunErrorDetailsDto`, `SseStatusEventDto`

**Database**:
- New table: `test_suite_runs` (id, test_suite_id, status, run_config JSONB, timestamps, error details)
- Flyway migration: `V1.6__CreateTestSuiteRunsTable.sql`

**API Surface**:
- `POST /api/v1/test-suites/{id}/runs` — trigger run, return runId
- `GET /api/v1/test-suite-runs` — list runs with filtering/sorting/pagination
- `GET /api/v1/test-suite-runs/{id}` — get run details
- `GET /api/v1/test-suite-runs/status-stream` — SSE for status updates (supports filtering by runIds, testSuiteIds, statuses)
- `PATCH /api/v1/test-suite-runs/{id}` — update mutable run properties (testRunName)
- `POST /api/v1/test-suite-runs/{id}/cancel` — cancel PENDING or RUNNING run
- `DELETE /api/v1/test-suite-runs/{id}` — delete run (terminal status only)

**Dependencies**:
- Existing TestSuite domain and APIs (reads test suite configuration)
- Job execution framework (async processing, thread pools or executor service)
- SSE support via Spring SseEmitter

**Documentation**:
- `docs/database-schema.md` — new table schema
- `openspec/specs/test-suite-runs/spec.md` — full API contract and requirements
