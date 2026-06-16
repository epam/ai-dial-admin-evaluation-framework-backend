# Suite Run Snapshot Phase

When a run starts (`executeRunAsync`), a snapshot phase runs before the `RUNNING` state transition:

1. Opens a `TransactionTemplate` with `ISOLATION_REPEATABLE_READ` on meta datasource
2. Reads the live `TestSuite` and builds `SuiteSnapshotDto` via `SuiteSnapshotBuilder`
3. Pages through all enabled+valid `TestCase` rows and batch-inserts into `test_case_run_inputs`
4. Writes serialized snapshot JSON to `test_suite_runs.suite_snapshot` and updates `number_of_test_cases`
5. Retries up to 2 times on PostgreSQL `40001` (serialization failure); each attempt deletes prior inputs first (idempotency)
6. On failure, marks run `FAILED` with structured error code (`SNAPSHOT_SERIALIZATION_CONFLICT`, `SNAPSHOT_FAILED`, `SNAPSHOT_SUITE_MISSING`)

After `RUNNING` transition, an inconsistent-snapshot guard fires: if exactly one of `suite_snapshot` / `test_case_run_inputs` is present, the run is immediately failed with `SNAPSHOT_STATE_INCONSISTENT`.

**Legacy runs** (created before snapshot feature): `suite_snapshot` is null. `buildContext()` calls `SuiteSnapshotBuilder.build(liveSuite)` to synthesize a transient snapshot; if the live suite is gone, fails with `SNAPSHOT_SUITE_MISSING`.

**Snapshot version handling**: `SuiteSnapshotDto.CURRENT_VERSION = "2"`. The `@Builder.Default` for `snapshotVersion` is `CURRENT_VERSION`, so a stored snapshot whose JSON lacks `snapshotVersion` deserializes as the current version (treated as a producer bug, not a legacy v1 snapshot). New writes always set `"2"` explicitly via `SuiteSnapshotBuilder.build(testSuite, dataset)`. The `introduce-dataset-entity` migration (V1.22) backfills all pre-V1.22 `test_suite_runs.suite_snapshot` rows in place — rewriting `snapshotVersion` to `"2"` and synthesizing `datasetRef` from the joined `test_suites` row (deterministic via D1's `dataset.id = source_suite.id` invariant). After V1.22 runs, every non-null stored snapshot carries `snapshotVersion = "2"` and a populated `datasetRef`; the `UnsupportedSnapshotVersionException` path in `resolveSnapshot` remains as defense-in-depth for future producer bugs (e.g., a hypothetical v3).

`SuiteSnapshotBuilder` (`service.domain`) — `@Component` that builds `SuiteSnapshotDto` from a `TestSuite` plus its bound `Dataset` via `JsonbMapper`. Always sets `snapshotVersion = "2"` and populates `datasetRef` (`{id, version, name}` of the dataset at snapshot time). `testCaseSchema` and `responseColumns` are inlined from the dataset and suite respectively. DEPLOYMENT path includes `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`; MCP_TOOL path includes `mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `inputBindings`.
