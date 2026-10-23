## Why

Issue #271: the Runs list shows runs with status `FAILED`, an End Date, and an empty Start Date. `started_at_ms` is written only when a run moves PENDING → RUNNING, after the snapshot phase. A run that fails or is cancelled while still PENDING (startup reconciliation, snapshot-phase failure, DIAL route-trigger failure "Failed to trigger evaluation via DIAL Core route", cancel-while-pending) therefore has `completedAt` but never `startedAt`, so the list looks inconsistent and Duration cannot be computed. The state is reproduced by a functional test.

A first iteration of this change set `startedAt` at creation. That was rejected: a PENDING run has not started, so it must not show a Start Date.

## What Changes

- **Implemented, behavior change:** every write that ends a run without it ever having been RUNNING sets `started_at_ms` to the same value as `completed_at_ms` (`COALESCE(started_at_ms, <end time>)`). Affected paths: `updateToFailed` (snapshot-phase failure, DIAL route-trigger failure), `failOrphanedRuns` (startup reconciliation), and the PENDING → CANCELLED writes (`updateToCancelled`, `updateStatusOptimistic`). Such a run has a zero duration, which is accurate because nothing ran.
- A PENDING run keeps `startedAt = null`; RUNNING/CANCELLING runs keep the execution start time written by `updateToRunning`. After this change only a PENDING run has a null `startedAt`.
- One Flyway meta migration, `V1.38__BackfillStartedAtForNeverStartedRuns.sql`, sets `started_at_ms = completed_at_ms` for existing `FAILED`/`CANCELLED` rows where `started_at_ms IS NULL AND completed_at_ms IS NOT NULL`. No rows are deleted, so run history and error details are kept. No schema change, no jOOQ regeneration.
- `docs/database-schema.md` row for `started_at_ms` is updated; no API field is added or removed.
- **Not in scope:** a distinct message/error code for runs failed while PENDING, a PENDING-timeout sweep, and any change to `GrafanaLinkBuilder` (PENDING still has a null `startedAt`, so no link).

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `test-suite-runs`: `startedAt` is null only while a run is PENDING; a run that ends without executing (failed by reconciliation, snapshot failure or route-trigger failure, or cancelled while PENDING) has `startedAt` equal to `completedAt`.

## Impact

- Code: `PostgresTestSuiteRunRepository` (`updateToFailed`, `failOrphanedRuns`, `updateToCancelled`, `updateStatusOptimistic`); no service, job, mapper or DTO signature changes. The first iteration's code changes (Clock in `TestSuiteRunService`, two-argument `updateToRunning`, mapper PENDING special case, DTO `@Schema` text, OpenAPI example) are reverted.
- Data: new migration `V1.38__BackfillStartedAtForNeverStartedRuns.sql` (update only).
- API: no field or type change. `startedAt` of FAILED/CANCELLED runs is now non-null; Duration for never-started runs is 0.
- Tests: reconciliation, snapshot-failure, route-trigger-failure and cancel-while-PENDING cases assert `startedAt == completedAt`; PENDING asserts null; a migration test.
- Config: none.
- Rollout: the migration runs once on startup; no feature flag.
