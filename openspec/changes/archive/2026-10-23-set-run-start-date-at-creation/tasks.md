## 1. Revert the set-at-creation iteration

- [x] 1.1 Revert `TestSuiteRunService` (remove the `Clock` field/import and `.startedAt(clock.millis())` in `createAndSaveRun`), `TestSuiteRunMapper` (remove `traceableStartedAt` and the `RunStatus` import) and the `GrafanaLinkBuilder` javadoc edit (done: `git diff` shows no change in these three files)
- [x] 1.2 Revert `updateToRunning` to `updateToRunning(UUID id, long startedAt, long updatedAt)` writing `started_at_ms` in `PostgresTestSuiteRunRepository`, `TestSuiteRunRepository` and the call in `TestSuiteEvaluationJob.run` (done: the PENDING → RUNNING write sets the execution start again)
- [x] 1.3 Revert the `startedAt` `@Schema` text in `TestSuiteRunResponseDto` and the PENDING example in `api-v1-test-suite-runs-GET-response-200-full.json` (done: `startedAt: null` for the PENDING example again)
- [x] 1.4 Delete `V1.38__DeleteFailedRunsWithoutStartDate.sql` and `DeleteFailedRunsWithoutStartDateMigrationTest`; revert test changes that belonged to the first iteration: `MetaTestDataHelper.createRunWithStatus` `startedAt`, the `TestSuiteRunServiceTest` clock/constructor argument and `setsStartedAtWhenRunIsCreated`, the two `TestSuiteRunMapperTest` PENDING/RUNNING Grafana tests, `TestSuiteEvaluationJobTest` stubs, and the `PostgresTestSuiteRunRepositoryFunctionalTests` `updateToRunning` assertions (done: tests compile against the reverted signatures)

## 2. Repository: a run that never ran gets startedAt = completedAt

- [x] 2.1 `PostgresTestSuiteRunRepository.updateToFailed`: also set `STARTED_AT_MS = COALESCE(STARTED_AT_MS, completedAt)` (done: a PENDING run failed by the snapshot phase or by the DIAL route-trigger failure has `startedAt == completedAt`; a RUNNING run keeps its start)
- [x] 2.2 `failOrphanedRuns`: set `STARTED_AT_MS = COALESCE(STARTED_AT_MS, now)` together with `COMPLETED_AT_MS = now` (done: orphaned PENDING runs get `startedAt == completedAt`, orphaned RUNNING runs keep theirs)
- [x] 2.3 `updateStatusOptimistic` (the PENDING → CANCELLED write used by `TestSuiteRunService.cancelRun`): set `STARTED_AT_MS = COALESCE(STARTED_AT_MS, now)` (done: cancel-while-PENDING gives `startedAt == completedAt`). `updateToCancelled` only matches RUNNING/CANCELLING rows, which always have a start, so it needs no change
- [x] 2.4 Check `cancelOrphanedCancellingRuns` and any other write of `COMPLETED_AT_MS` in the repository: apply the same COALESCE only where a run can reach it without having been RUNNING (done: recorded in a code comment or confirmed unnecessary)

## 3. Migration

- [x] 3.1 Add `src/main/resources/db/migration/meta/POSTGRES/V1.38__BackfillStartedAtForNeverStartedRuns.sql`: `UPDATE test_suite_runs SET started_at_ms = completed_at_ms WHERE started_at_ms IS NULL AND completed_at_ms IS NOT NULL AND status IN ('FAILED', 'CANCELLED');` — SQL only, no DDL (done: V1.37 is still the latest version before naming)
- [x] 3.2 Add `BackfillStartedAtForNeverStartedRunsMigrationTest` next to the existing migration tests: migrate to 1.37, seed FAILED/CANCELLED rows without a start, a FAILED row with a start, a PENDING row, then migrate to 1.38 (done: the first two get `started_at_ms = completed_at_ms`, the others are unchanged, no row is deleted)

## 4. Tests

- [x] 4.1 Reconciliation test (`TestSuiteRunFunctionalTests`): a PENDING run failed by `reconcileOrphanedRuns` has `startedAt == completedAt`, a RUNNING one keeps its `startedAt` (done: deterministic assertions via the API/repository)
- [x] 4.2 Functional test: a PENDING run has `startedAt == null` through the API; cancelling a PENDING run gives CANCELLED with `startedAt == completedAt` (done: passes via `PostgresFunctionalTests$TestSuiteRunTests`)
- [x] 4.3 Repository functional tests in `PostgresTestSuiteRunRepositoryFunctionalTests` for `updateToFailed` on a PENDING row and on a RUNNING row, and for `updateToCancelled` / `updateStatusOptimistic` (done: COALESCE behavior asserted for both cases)
- [x] 4.4 Unit/functional test for the route-trigger failure path: `TestSuiteRunService.markRunFailed` / `dispatchEvaluation` leaves the run FAILED with `startedAt == completedAt` (done: covers "Failed to trigger evaluation via DIAL Core route")

## 5. Docs, formatting and verification

- [x] 5.1 Update the `started_at_ms` row in `docs/database-schema.md` (line ~397): null while PENDING, execution start for runs that ran, equal to `completed_at_ms` for runs that ended without running; keep the "Last sync" marker at V1.38 (done: row reflects the new meaning)
- [x] 5.2 Sync the new delta spec into `openspec/specs/test-suite-runs/spec.md` per the archive checklist in `openspec/config.yaml` `rules.archive` (done: `git diff` of main specs shows only the three modified requirements; `grafana-deep-links` is unchanged)
- [x] 5.3 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` (done: no violations)
- [x] 5.4 Run the affected unit tests and `./gradlew :test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestSuiteRunTests"`, the repository functional class and the migration tests, then the full `./gradlew :test` (done: all pass)
- [x] 5.5 Update the frontend handoff note: `startedAt` is null only for PENDING, equals `completedAt` for runs that ended without running, nothing else changes (done: handoff file refreshed)
