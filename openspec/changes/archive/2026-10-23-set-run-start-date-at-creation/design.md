## Context

`test_suite_runs.started_at_ms` is nullable and written in exactly one place: `PostgresTestSuiteRunRepository.updateToRunning`, called by `TestSuiteEvaluationJob.run` once the snapshot phase succeeds. Every path that ends a run directly from PENDING bypasses it and writes only `completed_at_ms`:

- `failOrphanedRuns` (startup reconciliation, also used for RUNNING orphans),
- `updateToFailed` (snapshot-phase failure in the job; `TestSuiteRunService.markRunFailed` for import failure and for "Failed to trigger evaluation via DIAL Core route"),
- `updateToCancelled` / `updateStatusOptimistic` (cancel while PENDING).

Result: FAILED/CANCELLED rows with an End Date and no Start Date (issue #271). An earlier iteration of this change set `startedAt` at creation; rejected because a PENDING run has not started.

## Goals / Non-Goals

**Goals:**
- PENDING has a null `startedAt`; every run that has left PENDING has a non-null `startedAt`.
- Existing broken rows are repaired without losing history.

**Non-Goals:**
- Distinct error code/message for runs failed while PENDING; PENDING-timeout sweep.
- Making the column `NOT NULL` (PENDING rows legitimately have no value).
- Changes to the response shape, `GrafanaLinkBuilder`, or `updateToRunning`.

## Decisions

**D1. Repository-level `COALESCE` on every status write that can end a run from PENDING.**
The writes set `started_at_ms = COALESCE(started_at_ms, <completed value>)`: an existing execution start (a run that was RUNNING) is kept; a run that never ran gets its end time. The end time already passed to those methods (`completedAt`, or the transaction timestamp) is reused, so `startedAt == completedAt` exactly. Alternative considered: set it in the callers (job, service, reconciliation). Rejected: several call sites would each need the rule; the guarded SQL writes are the single choke point and already own the status transition. Alternative: leave both null for never-started runs. Rejected: this is the "Failed without Start Date" row the issue reports.

**D2. `startedAt == completedAt` means "never executed".**
Duration is 0, which is true. No new column or error code. A client that wants to tell a never-started run from an instant one can compare the two values; this is not part of the contract.

**D3. One data-only Flyway migration: `V1.38__BackfillStartedAtForNeverStartedRuns.sql` in `db/migration/meta/POSTGRES/`.**
`UPDATE test_suite_runs SET started_at_ms = completed_at_ms WHERE started_at_ms IS NULL AND completed_at_ms IS NOT NULL AND status IN ('FAILED', 'CANCELLED');` Plain SQL, no DDL, so `generateJooq` and `JooqSchemaDriftTest` are unaffected; idempotent. PENDING/RUNNING rows are untouched. Alternative considered: delete such rows (the earlier plan). Rejected: it destroys run history and error details, and the repository fix makes repair possible.

**D4. No change to `updateToRunning`, the job, the service or the mapper.**
`startedAt` keeps meaning "execution began" for runs that ran, so Duration is unchanged for them, and `GrafanaLinkBuilder` still receives null for PENDING runs.

## Risks / Trade-offs

- [`startedAt` of a never-started run is a synthetic value (the end time), not a real start] → Documented in the spec and DB docs; Duration 0 is accurate.
- [A FAILED run with `startedAt == completedAt` can be an instant failure or a never-started run] → Accepted; both ran for zero time.
- [Grafana link for a never-started FAILED/CANCELLED run now exists because `startedAt` is non-null] → Accepted: the range is `startedAt - 5 min` to `completedAt + 5 min`; the query simply returns no traces.
- [Migration touches all historical FAILED/CANCELLED rows lacking a start] → Single idempotent UPDATE on a small table.

## Migration Plan

1. Deploy; Flyway applies `V1.38` once on startup (meta datasource, after analytics).
2. Rollback: redeploy the previous version. The backfilled values are harmless to old code.

## Open Questions

- None.
