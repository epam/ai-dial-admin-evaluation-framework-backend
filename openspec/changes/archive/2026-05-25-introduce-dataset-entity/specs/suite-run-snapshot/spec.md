# Suite Run Snapshot — Delta

## MODIFIED Requirements

### Requirement: SuiteSnapshotDto model
`SuiteSnapshotDto` SHALL be the canonical frozen representation of suite configuration for a run. `CURRENT_VERSION` SHALL be `"2"`. New writes performed by `SuiteSnapshotBuilder` SHALL explicitly stamp `snapshotVersion = "2"`. The Jackson `@Builder.Default` for the field SHALL remain `"1"` so that stored snapshots written before this change — which serialized at version `"1"` and may also omit the field on JSON whose serializer suppressed defaults — continue to deserialize as `"1"` with a null `datasetRef`. Snapshots stored with `snapshotVersion = "1"` SHALL remain readable for in-flight runs (they have all version-1 fields embedded; the only new field — `datasetRef` — is nullable on deserialization).
Status: **Planned**

#### Scenario: DEPLOYMENT suite snapshot fields
- **WHEN** snapshot is built for a DEPLOYMENT suite
- **THEN** snapshot SHALL include `snapshotVersion = "2"` (explicitly stamped by `SuiteSnapshotBuilder`), `suiteType = "DEPLOYMENT"`, `datasetRef` (`{id, version, name}` for the dataset referenced by the suite at snapshot time), `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema` (sourced from the dataset, not the suite); MCP-specific fields SHALL be null/absent

#### Scenario: MCP_TOOL suite snapshot fields
- **WHEN** snapshot is built for an MCP_TOOL suite
- **THEN** snapshot SHALL include `snapshotVersion = "2"` (explicitly stamped), `suiteType = "MCP_TOOL"`, `datasetRef`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema` (sourced from the dataset); deployment-specific fields SHALL be null/absent

#### Scenario: Missing snapshotVersion defaults to "1"
- **WHEN** a stored snapshot JSON lacks `snapshotVersion`
- **THEN** `SuiteSnapshotDto` SHALL default to `"1"` via `@Builder.Default`; this preserves correct classification of pre-existing snapshots written before this change whose serializer omitted the field. New writes never trigger this default because `SuiteSnapshotBuilder` always stamps `"2"` explicitly.

#### Scenario: Snapshot persisted before this change with no snapshotVersion field is treated as v1
- **WHEN** a snapshot JSON stored before this change has no `snapshotVersion` field at all (and no `datasetRef`)
- **THEN** the deserialized DTO SHALL carry `snapshotVersion = "1"` and `datasetRef = null`; legacy-fallback synthesis or v1 execution paths apply

#### Scenario: Version-1 snapshots remain readable
- **WHEN** a run created before this change has `suite_snapshot.snapshotVersion = "1"`
- **THEN** `TestSuiteEvaluationJob.resolveSnapshot()` SHALL deserialize it successfully; `datasetRef` SHALL be null on the deserialized DTO; execution continues using the embedded `testCaseSchema` and other v1 fields

#### Scenario: Unknown snapshot version causes executor to fail
- **WHEN** a stored snapshot has `snapshotVersion` that is neither `"1"` nor `"2"`
- **THEN** `TestSuiteEvaluationJob.resolveSnapshot()` SHALL log a warning and throw `UnsupportedSnapshotVersionException`; the run execution SHALL halt

### Requirement: Snapshot phase execution
The snapshot phase SHALL execute atomically before the run transitions to RUNNING. The phase reads the live `TestSuite` and the live `Dataset` referenced by the suite, and pages through the dataset's test cases excluding those in the suite's `disabledTestCaseIds`.
Status: **Planned**

#### Scenario: Snapshot phase sequence
- **WHEN** snapshot phase runs
- **THEN** it SHALL in a single `ISOLATION_REPEATABLE_READ` transaction:
  1. Delete any leftover `test_case_run_inputs` from prior failed attempts (`deleteByRunId`)
  2. Load the live `TestSuite`; throw `SnapshotSuiteMissingException` if absent
  3. Load the live `Dataset` referenced by `testSuite.datasetId`; throw `SnapshotDatasetMissingException` (new error code) if absent
  4. Build `SuiteSnapshotDto` via `SuiteSnapshotBuilder.build(testSuite, dataset)`; the builder sources `testCaseSchema` from the dataset and populates `datasetRef = {id: dataset.id, version: dataset.version, name: dataset.name}`
  5. Serialize snapshot to JSON; throw `IllegalStateException` on serialization error
  6. Page through valid test cases in the dataset (`findValidByDatasetIdExcludingIds(datasetId, testSuite.disabledTestCaseIds, offset, SNAPSHOT_PAGE_SIZE = 100)`) and batch-insert into `test_case_run_inputs`
  7. Call `updateSuiteSnapshot(runId, snapshotJson)` and `updateNumberOfTestCases(runId, totalInputs)`

#### Scenario: Snapshot excludes disabled test cases
- **WHEN** the suite's `disabledTestCaseIds = [tc-2, tc-5]` and the dataset has test cases `[tc-1, tc-2, tc-3, tc-4, tc-5]`
- **THEN** `test_case_run_inputs` for the run SHALL contain rows for `[tc-1, tc-3, tc-4]` only; `numberOfTestCases = 3`

#### Scenario: Stale disabled ID is silently ignored
- **WHEN** the suite's `disabledTestCaseIds = [tc-deleted]` and `tc-deleted` is no longer in the dataset
- **THEN** the snapshot-phase query SHALL produce all valid test cases in the dataset; the stale id is naturally excluded by set-membership semantics and does NOT cause an error

#### Scenario: Snapshot excludes invalid test cases
- **WHEN** a test case in the dataset has `isValid = false`
- **THEN** it SHALL NOT appear in `test_case_run_inputs` (only `valid = true` rows are materialized)

#### Scenario: Snapshot row ordering is deterministic
- **WHEN** the snapshot phase pages through valid test cases via `TestCaseRepository.findValidByDatasetIdExcludingIds(datasetId, disabledIds, offset, pageSize)`
- **THEN** the implementation SHALL `ORDER BY created_at_ms ASC, id ASC` so that `test_case_run_inputs.position` is assigned in a stable, repeatable order across attempts; this matches the legacy behavior of `findEnabledValidByTestSuiteId` (which ordered by `CREATED_AT_MS asc, ID asc`) so the snapshot of a given (suite, dataset) pair has the same row ordering before and after the migration. See task 10.4 (and 7.3/7.4a for the repository signatures).

#### Scenario: Retry on serialization failure
- **WHEN** snapshot transaction fails with SQL state `40001`
- **THEN** system SHALL retry up to 2 times (3 total attempts); on final failure mark run FAILED with `SNAPSHOT_SERIALIZATION_CONFLICT`

#### Scenario: Non-serialization failure
- **WHEN** snapshot transaction fails with any other exception
- **THEN** system SHALL mark run FAILED; error code is `SNAPSHOT_SUITE_MISSING` for suite-not-found, `SNAPSHOT_DATASET_MISSING` for dataset-not-found, `SNAPSHOT_FAILED` otherwise

#### Scenario: Snapshot committed before RUNNING transition
- **WHEN** snapshot phase succeeds
- **THEN** `suite_snapshot` and `test_case_run_inputs` rows SHALL both be committed in the DB before `status` is set to RUNNING

#### Scenario: Inconsistent snapshot guard
- **WHEN** run transitions to RUNNING and exactly one of `suite_snapshot` / `test_case_run_inputs` is present
- **THEN** run SHALL be immediately marked FAILED with `SNAPSHOT_STATE_INCONSISTENT`

#### Scenario: Legacy-fallback synthesis sources schema from live dataset
- **WHEN** `resolveSnapshot()` runs against a run row created before the snapshot feature (i.e., `suite_snapshot IS NULL`)
- **THEN** synthesis SHALL load the live `TestSuite` AND the live `Dataset` referenced by the suite; build a transient `SuiteSnapshotDto` via `SuiteSnapshotBuilder.build(testSuite, dataset)` (version `"2"`, schema sourced from the live dataset); if either the suite or the dataset is missing, fail the run with the corresponding error code

### Requirement: API surface
- `TestSuiteRunResponseDto` SHALL include a `suiteSnapshot` field (nullable `SuiteSnapshotDto`) populated from the stored `suite_snapshot` JSON via `TestSuiteRunMapper`. The serialized DTO SHALL carry `datasetRef` for snapshots written at version `"2"` and `null` for legacy version `"1"` snapshots
- The detail endpoint (`GET /test-suite-runs/{id}`) SHALL return `suiteSnapshot` when available
- The list endpoint (`GET /test-suite-runs`) SHALL return `suiteSnapshot: null` for all items (list-tier SELECT excludes the column)

Status: **Planned**

#### Scenario: Detail response includes datasetRef for new snapshots
- **WHEN** a run was started after this change and `GET /test-suite-runs/{id}` is called
- **THEN** the response `suiteSnapshot.datasetRef` SHALL be populated with `{id, version, name}` of the dataset at snapshot time

#### Scenario: Detail response carries null datasetRef for legacy snapshots
- **WHEN** a run was started before this change (version-1 snapshot) and `GET /test-suite-runs/{id}` is called
- **THEN** the response `suiteSnapshot` SHALL deserialize successfully with `snapshotVersion = "1"` and `datasetRef = null`; `testCaseSchema` and other v1 fields are populated as before
