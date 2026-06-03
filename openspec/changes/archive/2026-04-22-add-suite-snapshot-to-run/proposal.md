## Why

When a test suite run is executing, the executor re-reads the suite's mutable configuration (request template, input bindings, deployment ref, endpoint ref, MCP config) from the database per test case via `ResolvedRequestService.resolveRequest()`, and iterates test cases via live `OFFSET/LIMIT` pagination. If a user edits the suite or any test case's data while a run is in progress, test cases executed after the edit use the new state — producing inconsistent, unreliable results within a single run.

This is a data integrity bug that compounds for large suites (tens of thousands of test cases with large RAG-sized data) and long-running executions. Additionally, V2 features (partial rerun of failed test cases, time-travel rerun with historical suite state) require knowing exactly what configuration and data was used for each run — information not currently persisted.

## What Changes

- **Add `suite_snapshot` JSONB column** to `test_suite_runs` — captures all execution-relevant suite configuration (template, bindings, deployment refs, MCP fields, response columns).
- **Add `test_case_run_inputs` table** (meta DB) — snapshots the full per-test-case data (including overrides) for each run. Written under a REPEATABLE READ transaction at the start of async execution, streaming TC data directly from meta DB to the inputs table without holding data in memory.
- **Short REPEATABLE READ snapshot phase** — at the start of `executeRunAsync()`, a dedicated REPEATABLE READ transaction reads suite config + all enabled/valid test cases and persists both snapshots. Transaction duration is bounded by DB I/O only (~10–15s for 50K TCs) — no HTTP calls occur during this window.
- **Refactor executor** — reads suite config from `suite_snapshot` (via `EvaluationContext`) and iterates test cases from `test_case_run_inputs`, eliminating all live meta DB reads during execution.
- **Expose snapshot in run response** — `TestSuiteRunResponseDto` includes `suiteSnapshot` (nullable, detail endpoint only).

## Capabilities

### New Capabilities
- `suite-run-snapshot`: Defines the snapshot model (`SuiteSnapshotDto` with schema versioning), persistence (`suite_snapshot` in `test_suite_runs`), the `test_case_run_inputs` table and its lifecycle, the REPEATABLE READ snapshot phase, and how the executor consumes the combined snapshot.

### Modified Capabilities
- `test-suite-runs`: Run creation no longer reads all TCs synchronously; snapshot is deferred to async phase. Run response DTO gains `suiteSnapshot` field.
- `eval-execution-engine`: Executor reads from `test_case_run_inputs` instead of live `test_cases`. Worker uses suite config from `EvaluationContext` snapshot. No live meta DB reads during execution.

## Impact

**Database:**
- New Flyway migration `V1.17__AddSuiteSnapshotToTestSuiteRuns.sql` — adds nullable `suite_snapshot JSONB` to `test_suite_runs`. `docs/database-schema.md` must be updated.
- New Flyway migration `V1.18__CreateTestCaseRunInputsTable.sql` — creates `test_case_run_inputs` in meta DB with FK to `test_suite_runs` (CASCADE DELETE). `docs/database-schema.md` must be updated.

**Code:**
- `TestSuiteRun` model: new `suiteSnapshot` field
- `TestSuiteRunRowMapper`: map new column (with `hasColumn()` for two-tier SELECT)
- `PostgresTestSuiteRunRepository`: two SELECT tiers (list excludes snapshot, detail includes it); `updateSuiteSnapshot()` method
- `TestCaseRunInput` model + `TestCaseRunInputRepository` / `PostgresTestCaseRunInputRepository` (meta DB)
- `TestSuiteEvaluationJob`: snapshot phase using `TransactionTemplate` with `ISOLATION_REPEATABLE_READ`
- `InProcessEvaluationExecutor`: reads from `test_case_run_inputs` by `(runId, position)`
- `EvaluationWorker`: suite config from `EvaluationContext`, no `ResolvedRequestService.resolveRequest()` DB call
- `ResolvedRequestService`: new method accepting pre-resolved template + bindings (no DB read)
- `EvaluationContext`: carries deserialized snapshot config

**APIs:**
- `GET /api/v1/test-suites/{id}/runs/{runId}` gains `suiteSnapshot` field (additive, non-breaking)
- `GET /api/v1/test-suites/{id}/runs` list — `suiteSnapshot` excluded (TOAST optimization)

**New error codes** (all under category `INTERNAL`, added to `RunErrorDetailsDto` / `ErrorCode` catalog):
- `SNAPSHOT_STATE_INCONSISTENT` — exactly one of `suite_snapshot` / `test_case_run_inputs` is present
- `UNSUPPORTED_SNAPSHOT_VERSION` — `snapshotVersion` missing (and default not accepted) or unknown/newer than supported
- `SNAPSHOT_FAILED` — generic snapshot-phase failure (DB error, deserialization failure)
- `SNAPSHOT_SERIALIZATION_CONFLICT` — PostgreSQL serialization failure (SQLState 40001) after retries exhausted
- `SNAPSHOT_SUITE_MISSING` — live suite no longer exists when snapshot phase (or legacy-run fallback) runs

**No breaking changes.** Existing runs with `suite_snapshot = NULL` / no `test_case_run_inputs` rows fall back to the synthesized-snapshot path.

**New packages/classes:**
- `SuiteSnapshotDto` in `service.domain.dto`
- `SuiteSnapshotBuilder` in `service.domain`
- `TestCaseRunInput` in `data.db.model`
- `TestCaseRunInputRepository` / `PostgresTestCaseRunInputRepository` in `data.db.repository`
- `TestCaseRunInputRowMapper` in `data.db.mapper`
