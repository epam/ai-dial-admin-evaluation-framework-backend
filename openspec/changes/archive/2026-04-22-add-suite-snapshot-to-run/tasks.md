## 1. Database Migrations

- [x] 1.1 Create Flyway migration `V1.17__AddSuiteSnapshotToTestSuiteRuns.sql` adding nullable `suite_snapshot JSONB` column to `test_suite_runs`
- [x] 1.2 Create Flyway migration `V1.18__CreateTestCaseRunInputsTable.sql` creating `test_case_run_inputs` in meta DB: columns `run_id VARCHAR(36)`, `position INTEGER`, `test_case_id VARCHAR(36)`, `test_case_name VARCHAR(255)`, `test_case_data JSONB NOT NULL`, `request_template_override JSONB`, `input_bindings_override JSONB`; PRIMARY KEY `(run_id, position)`; FK `run_id → test_suite_runs(id) ON DELETE CASCADE`; index on `run_id`
- [x] 1.3 Update `docs/database-schema.md` with both new columns/tables

## 2. Snapshot Model & Builder

- [x] 2.1 Create `SuiteSnapshotDto` in `service.domain.dto` with `snapshotVersion` (string, default `"1"`) and all execution-relevant fields (`suiteType`, `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`) — use typed DTOs, `@JsonIgnoreProperties(ignoreUnknown = true)`
- [x] 2.2 Create `SuiteSnapshotBuilder` component in `service.domain` that builds `SuiteSnapshotDto` from `TestSuite` model using `JsonbMapper`; always sets `snapshotVersion = "1"`
- [x] 2.3 Unit test `SuiteSnapshotBuilder` — DEPLOYMENT and MCP_TOOL snapshots correct, excluded fields absent, `snapshotVersion = "1"` always set
- [x] 2.4 Unit test snapshot version handling — current version deserializes normally; missing `snapshotVersion` defaults to `"1"`; unknown/newer version logs warning and causes executor to fail fast with `UNSUPPORTED_SNAPSHOT_VERSION`

## 3. Data Layer — `test_suite_runs`

- [x] 3.1 Add `suiteSnapshot` field to `TestSuiteRun` model
- [x] 3.2 Update `TestSuiteRunRowMapper` to map `suite_snapshot` with `hasColumn()` check
- [x] 3.3 Update `PostgresTestSuiteRunRepository` — add `SELECT_LIST_COLUMNS` (no `suite_snapshot`) and `SELECT_DETAIL_COLUMNS` (includes `suite_snapshot`); include `suite_snapshot` in INSERT; add `updateSuiteSnapshot(UUID runId, String snapshotJson)` method; `findAll` uses list tier, `findById` uses detail tier

## 4. Data Layer — `test_case_run_inputs`

- [x] 4.1 Create `TestCaseRunInput` model in `data.db.model` with fields: `runId`, `position`, `testCaseId`, `testCaseName`, `testCaseData`, `requestTemplateOverride`, `inputBindingsOverride`
- [x] 4.2 Create `TestCaseRunInputRowMapper` in `data.db.mapper`
- [x] 4.3 Create `TestCaseRunInputRepository` interface and `PostgresTestCaseRunInputRepository` in `data.db.repository` (meta datasource qualifier) with methods: `insertBatch(List<TestCaseRunInput>)`, `findByRunId(UUID runId, int offset, int limit) → List<TestCaseRunInput>`, `countByRunId(UUID runId) → long`, `existsByRunId(UUID runId) → boolean`

## 5. Snapshot Phase in Executor

- [x] 5.1 Add snapshot phase method in `TestSuiteEvaluationJob` (or a dedicated `RunSnapshotService` component) — uses `TransactionTemplate` with `ISOLATION_REPEATABLE_READ` on meta datasource; reads suite, builds `SuiteSnapshotDto`, pages through all enabled+valid test cases, batch-inserts rows into `test_case_run_inputs`, calls `updateSuiteSnapshot()` on run record
- [x] 5.2 Invoke snapshot phase at start of `executeRunAsync()`, before `updateToRunning()`. If snapshot phase fails, mark run FAILED and return.
- [x] 5.3 Add inconsistent-snapshot guard: if exactly one of `suite_snapshot` / `test_case_run_inputs` is present, fail run with `SNAPSHOT_STATE_INCONSISTENT`
- [x] 5.4 In the snapshot phase tx, UPDATE `test_suite_runs.number_of_test_cases = COUNT(test_case_run_inputs for this run)` immediately after batch insert — replacing any stale creation-time count.
- [x] 5.5 Retry snapshot phase up to 2 times on SQLException `40001` (serialization failure); on final failure mark run FAILED with `SNAPSHOT_SERIALIZATION_CONFLICT`; each attempt begins with DELETE FROM test_case_run_inputs WHERE run_id = :runId to ensure idempotency
- [x] 5.6 Wire up the five new error codes (`SNAPSHOT_STATE_INCONSISTENT`, `UNSUPPORTED_SNAPSHOT_VERSION`, `SNAPSHOT_FAILED`, `SNAPSHOT_SERIALIZATION_CONFLICT`, `SNAPSHOT_SUITE_MISSING`) in `RunErrorDetailsDto` / `ErrorCode` catalog under category `INTERNAL`.

## 6. Execution Engine — Use Snapshot

- [x] 6.1 Extend `EvaluationContext` with snapshot fields: typed `requestTemplate` (`RequestTemplateDto`), `inputBindings` (`List<InputBindingDto>`), `deploymentRef` (String), `endpointRef` (String), `responseColumns` (String)
- [x] 6.2 Update `TestSuiteEvaluationJob.buildContext()` — when `suite_snapshot` is non-null, deserialize into `SuiteSnapshotDto` and populate context fields. When null (legacy run), synthesize transient `SuiteSnapshotDto` via `SuiteSnapshotBuilder.build(liveSuite)`. Single code path for downstream — no branching in executor.
- [x] 6.3 Refactor `InProcessEvaluationExecutor.execute()` — page through `testCaseRunInputRepository.findByRunId()` instead of `testCaseRepository.findEnabledValidByTestSuiteId()`. For legacy runs (no inputs rows), page through live test cases (same interface). Pass `TestCaseRunInput` (or equivalent carrier) to worker.
- [x] 6.4 Refactor `EvaluationWorker.execute()` for BOTH DEPLOYMENT (HTTP) and MCP_TOOL paths — use `ResolvedRequestService.resolve()` with template/bindings from context + test case data from input row + overrides from input row, instead of `resolveRequest(suiteId, tcId)`. Read `deploymentRef`/`endpointRef` from context. The MCP path also reads `argumentTemplate`/overrides from the input row + context (`mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `inputBindings` from `EvaluationContext`; `testCaseData` and overrides from the `TestCaseRunInput` row).
- [x] 6.5 Add `resolve(RequestTemplateDto, List<InputBindingDto>, Map<String,Object> data)` overload (or package-private method exposure) in `ResolvedRequestService` — no DB read, combines template + bindings + data with override logic

## 7. API Response

- [x] 7.1 Add `suiteSnapshot` field (nullable `SuiteSnapshotDto`) to `TestSuiteRunResponseDto`
- [x] 7.2 Update `TestSuiteRunMapper` to deserialize `suiteSnapshot` JSON to `SuiteSnapshotDto`
- [x] 7.3 Update OpenAPI annotations — `@Schema` on new DTO fields
- [x] 7.4 Add/update OpenAPI example JSON files under `src/main/resources/openapi/examples/` for `GET /runs/{runId}` response (DEPLOYMENT and MCP_TOOL variants with `suiteSnapshot`); update run-list example to show `suiteSnapshot: null`

## 8. Tests

- [x] 8.1 Functional test: create run, verify snapshot phase completes before RUNNING status — `suite_snapshot` non-null and `test_case_run_inputs` rows exist when run is RUNNING
- [x] 8.2 Functional test: create run, wait until suite_snapshot is non-null AND test_case_run_inputs rows exist (snapshot phase committed), then modify live suite config, allow execution to proceed, and assert every result used the snapshotted config (not the live modification).
- [x] 8.3 Functional test: create run, wait until snapshot phase has committed (as in 8.2), then modify live test case data, allow execution to proceed, and assert affected results used the snapshotted test case data.
- [x] 8.4 Functional test: `GET /runs/{runId}` includes `suiteSnapshot`; `GET /runs` list has `suiteSnapshot: null`
- [x] 8.5 Unit test: `InProcessEvaluationExecutor` pages from inputs table, skip on missing row
- [x] 8.6 Unit test: `EvaluationWorker` uses context snapshot fields; does not call `resolveRequest(suiteId, tcId)`
- [x] 8.7 Unit test: `TestSuiteEvaluationJob.buildContext()` synthesizes transient snapshot from live suite when no persisted snapshot (legacy run)
- [x] 8.8 Repository test: `PostgresTestSuiteRunRepository` — list queries exclude `suite_snapshot`; `findById` includes it; `hasColumn()` prevents errors when column absent
- [x] 8.9 Repository test: `PostgresTestCaseRunInputRepository` — `insertBatch`, `findByRunId` pagination, `existsByRunId`
- [x] 8.10 Unit test: `TestSuiteEvaluationJob.buildContext()` fails with `SNAPSHOT_SUITE_MISSING` when a legacy run (null snapshot) references a deleted suite.

## 9. Retention Cleanup Job

- [x] 9.1 Add `deleteByRunIdsInTerminalStateOlderThan(Duration retention)` method to `PostgresTestCaseRunInputRepository` — single DELETE using a subquery: `DELETE FROM test_case_run_inputs WHERE run_id IN (SELECT id FROM test_suite_runs WHERE status IN ('COMPLETED','FAILED') AND updated_at < NOW() - :retentionInterval)`
- [x] 9.2 Add `evaluation.run.inputs-retention-days` configuration property (default `1`) to `EvaluationRunProperties` (or equivalent `@ConfigurationProperties`) and `application.yml`; add row to `docs/configuration.md`
- [x] 9.3 Create `TestCaseRunInputsRetentionJob` `@Component` with a `@Scheduled` fixed-rate method (daily, configurable via standard Spring scheduling); inject `PostgresTestCaseRunInputRepository` and the retention duration from properties; log count of deleted rows at INFO level
- [x] 9.4 Unit test: `TestCaseRunInputsRetentionJob` calls repository delete with the configured retention duration
- [x] 9.5 Functional test: after a run reaches COMPLETED and its `updated_at` is older than the configured retention window, the job deletes the inputs rows; inputs for a PENDING/RUNNING run are not deleted

## 10. Documentation

- [x] 10.1 Update `openspec/specs/README.md` — add `suite-run-snapshot` entry; update `test-suite-runs` and `eval-execution-engine` summaries to reflect snapshot-driven execution
- [x] 10.2 Update `AGENTS.md` — add `SuiteSnapshotBuilder` + `test_case_run_inputs` snapshot phase to Unique Patterns; document two-tier column selection on `PostgresTestSuiteRunRepository`; update Key Packages Reference if new packages added
- [x] 10.3 Delta-sync openspec/specs/test-suite-runs/spec.md — modify the 'Number of test cases snapshot' requirement (no longer immutable at creation; finalized at snapshot phase); add 'Suite snapshot field' coverage on TestSuiteRunResponseDto.
- [x] 10.4 Delta-sync openspec/specs/eval-execution-engine/spec.md — add snapshot phase requirements + inputs-table-driven execution requirements.
