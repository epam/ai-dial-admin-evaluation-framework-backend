## Why

Imported runs (CSV import via `eval-cli`'s clone → fetch → run → import flow, or a direct `POST .../runs/import`) are dispatched with `skipDeploymentPhase=true`, which today also skips the entire snapshot phase — so `test_suite_runs.suite_snapshot` stays `null` forever for these runs. Phase 3 score computation (`TestSuiteEvaluationJob.computeMetricScores` → `resolveSnapshot`) falls back to synthesizing a transient snapshot from the **live** suite/dataset at score-computation time instead of reading a frozen record, so a suite edited after import silently changes what `overallScoreDefinition` gets used. Retrospectively, `GET .../runs/{id}` returns `suiteSnapshot: null` for every imported run, so there is no durable record of which deployment/endpoint (or MCP tool) was targeted, which is needed to look back at "what application, metric, and scores" a past imported run actually used. Metric snapshots (`run_metric_snapshots`) and score results (`MetricScoreResult`) are already captured correctly for imported runs today — only the suite-level snapshot is missing.

## What Changes

- The snapshot phase now runs for **every** run, including imported runs (`skipDeploymentPhase=true`) — always builds and persists `SuiteSnapshotDto` onto `test_suite_runs.suite_snapshot`.
- The snapshot phase's `test_case_run_inputs` paging and `number_of_test_cases` overwrite remain scoped to runs that will execute Phase 1 (`skipDeploymentPhase=false`) — imported runs keep their `numberOfTestCases` as set from the actual CSV row count at run creation, and never get `test_case_run_inputs` rows (Phase 1 never reads them for an imported run).
- `TestSuiteEvaluationJob.attemptSnapshot`/`executeSnapshotPhase` gain a `captureTestCaseInputs` flag controlling the above split; the inconsistent-snapshot guard (`suite_snapshot` vs `test_case_run_inputs` presence) stays scoped to `!skipDeploymentPhase` since it only applies when both were supposed to be written together.
- Phase 3's `resolveSnapshot(run)` now finds a persisted, non-null snapshot for imported runs and no longer falls back to the legacy live-resynthesis path for them.
- No API/DTO/controller changes — `TestSuiteRunResponseDto.suiteSnapshot` already exposes the field; it will simply stop being `null` for imported runs going forward.
- No DB schema changes — reuses the existing `suite_snapshot` column and existing `SuiteSnapshotDto`/`SuiteSnapshotBuilder`.

## Capabilities

### New Capabilities
(none — this closes a gap in existing snapshot behavior)

### Modified Capabilities
- `suite-run-snapshot`: the snapshot phase requirement changes from "runs only when the deployment phase runs" to "always persists `suite_snapshot`; only the test-case-input capture is deployment-phase-gated." Updates the "Snapshot phase execution" requirement's scenarios to cover the imported-run case.
- `eval-results-import`: the "Import run" key term and the requirement describing `executeRunAsync(..., skipDeploymentPhase=true)` currently state "no `suiteSnapshot` ... rows are created for it" and "skips the snapshot phase" — this becomes "captures `suiteSnapshot` but not `test_case_run_inputs`."

## Impact

- **Code**: `src/main/java/com/epam/aidial/evaluation/service/domain/job/TestSuiteEvaluationJob.java` (`executeRunAsync`, `executeSnapshotPhase`, `attemptSnapshot` signature change to thread a `captureTestCaseInputs` boolean).
- **Tests**: `src/test/java/com/epam/aidial/evaluation/service/domain/job/TestSuiteEvaluationJobTest.java` (`ExecuteRunAsyncSkipDeploymentPhase` nested class) — add assertions that the suite snapshot is now persisted for `skipDeploymentPhase=true` while `test_case_run_inputs`/`number_of_test_cases` are untouched; add missing stubs to the mid-Phase-2-cancellation test now that the snapshot phase runs unconditionally. Recommend an added/updated `PostgresFunctionalTests` case asserting `GET .../runs/{id}` returns a non-null `suiteSnapshot` after import, with `numberOfTestCases` still equal to the imported CSV row count.
- **Docs**: `openspec/specs/suite-run-snapshot/spec.md` and `openspec/specs/eval-results-import/spec.md` delta specs (this change); `docs/patterns/suite-run-snapshot.md` note that the snapshot phase always runs now, with test-case-input capture gated separately.
- **No** DB migration, no config property changes, no new packages/classes.
