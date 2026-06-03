## ADDED Requirements

### Requirement: Post-migration invariant on stored suite snapshots
After the `introduce-dataset-entity` migration (V1.22) completes, every row in `test_suite_runs` with `suite_snapshot IS NOT NULL` SHALL carry `snapshotVersion = "2"` and a populated `datasetRef` containing `id`, `version`, and `name`. Application code that reads stored snapshots SHALL be permitted to assume this shape; observing `snapshotVersion != "2"` or `datasetRef = null` on a stored snapshot indicates either a corrupted row or a producer bug, not a legacy-shape row.
Status: **Planned**

#### Scenario: All non-null stored snapshots carry datasetRef
- **WHEN** any code path reads a `test_suite_runs.suite_snapshot` value that is non-null
- **THEN** the deserialized `SuiteSnapshotDto` SHALL have `snapshotVersion = "2"` and `snapshot.getDatasetRef() != null`; consumers SHALL NOT need legacy-shape handling

#### Scenario: Re-running V1.22 on partially-migrated data is safe
- **WHEN** V1.22 is re-applied against a database where some `test_suite_runs.suite_snapshot` rows already carry `datasetRef`
- **THEN** the backfill step SHALL skip those rows (guarded by `(suite_snapshot -> 'datasetRef') IS NULL`) and SHALL leave their `datasetRef` and `snapshotVersion` values unchanged

#### Scenario: Run with NULL suite_snapshot is unaffected by backfill
- **WHEN** a `test_suite_runs` row has `suite_snapshot IS NULL` (created before the snapshot feature existed)
- **THEN** the backfill step SHALL leave it as NULL; the legacy-fallback synthesis path in `resolveSnapshot` (which builds a transient v2 snapshot from the live suite + dataset) continues to handle it

## MODIFIED Requirements

<!-- This MODIFIED block intentionally drops baseline scenarios "Missing snapshotVersion defaults to \"1\"", "Snapshot persisted before this change with no snapshotVersion field is treated as v1", and "Version-1 snapshots remain readable". Reason: post-V1.22 backfill leaves no v1-shaped rows; the legacy paths these scenarios describe are unreachable. The replacement scenario "Missing snapshotVersion defaults to CURRENT_VERSION" preserves defense-in-depth wording. -->

### Requirement: SuiteSnapshotDto model
`SuiteSnapshotDto` SHALL be the canonical frozen representation of suite configuration for a run. `CURRENT_VERSION` SHALL be `"2"`. The Jackson `@Builder.Default` on `snapshotVersion` SHALL be `CURRENT_VERSION` (i.e., `"2"`) so that any JSON missing the field deserializes as the current version (treating the omission as a producer bug, not as a legacy-shape row). New writes performed by `SuiteSnapshotBuilder` SHALL explicitly stamp `snapshotVersion = "2"`. After the V1.22 backfill, no stored snapshot is shaped as v1; the `UnsupportedSnapshotVersionException` path in `resolveSnapshot` remains in place as defense-in-depth for any future producer (e.g., a hypothetical v3) writing an unsupported version.
Status: **Planned**

#### Scenario: DEPLOYMENT suite snapshot fields
- **WHEN** snapshot is built for a DEPLOYMENT suite
- **THEN** snapshot SHALL include `snapshotVersion = "2"` (explicitly stamped by `SuiteSnapshotBuilder`), `suiteType = "DEPLOYMENT"`, `datasetRef` (`{id, version, name}` for the dataset referenced by the suite at snapshot time), `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema` (sourced from the dataset, not the suite); MCP-specific fields SHALL be null/absent

#### Scenario: MCP_TOOL suite snapshot fields
- **WHEN** snapshot is built for an MCP_TOOL suite
- **THEN** snapshot SHALL include `snapshotVersion = "2"` (explicitly stamped), `suiteType = "MCP_TOOL"`, `datasetRef`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema` (sourced from the dataset); deployment-specific fields SHALL be null/absent

#### Scenario: Missing snapshotVersion defaults to CURRENT_VERSION
- **WHEN** a stored snapshot JSON lacks `snapshotVersion`
- **THEN** `SuiteSnapshotDto` SHALL default it to `CURRENT_VERSION` (currently `"2"`) via `@Builder.Default`; for snapshots that were written before V1.22, the backfill step in V1.22 sets the key explicitly to `"2"`, so this default fires only for hypothetical producer bugs going forward

#### Scenario: Post-backfill: every stored snapshot has populated datasetRef
- **WHEN** a `test_suite_runs.suite_snapshot` value is read into a `SuiteSnapshotDto`
- **THEN** `datasetRef` SHALL be non-null; the value either was written that way by `SuiteSnapshotBuilder` (for runs created after V1.22) or was synthesized by the V1.22 backfill step from the joined `test_suites` row (for pre-V1.22 runs)

#### Scenario: Unknown snapshot version causes executor to fail
- **WHEN** a stored snapshot has `snapshotVersion` that is neither `"2"` nor an empty/absent value
- **THEN** `TestSuiteEvaluationJob.resolveSnapshot()` SHALL log a warning and throw `UnsupportedSnapshotVersionException`; the run execution SHALL halt. This is defense-in-depth — no stored snapshot reaches this branch after the V1.22 backfill, but a future producer writing `"3"` (or a corrupted row) would.

<!--
This MODIFIED block has two intentional rewrites against the baseline:

1. Requirement body bullet rewrite: the baseline first bullet at openspec/specs/suite-run-snapshot/spec.md:128 reads "The serialized DTO SHALL carry `datasetRef` for snapshots written at version `"2"` and `null` for legacy version `"1"` snapshots". The new bullet here drops the `null` clause and asserts a non-null `datasetRef` for every non-null `suiteSnapshot` — reflecting the V1.22 backfill outcome (no v1 rows remain after migration).

2. Scenario drop: the baseline scenario "Detail response carries null datasetRef for legacy snapshots" (baseline lines 138-140) is dropped. Reason: post-V1.22 backfill, no legacy snapshot exists; the replacement scenario "Detail response includes datasetRef for snapshots backfilled by V1.22" covers the same response surface with the new invariant.
-->

### Requirement: API surface
- `TestSuiteRunResponseDto` SHALL include a `suiteSnapshot` field (nullable `SuiteSnapshotDto`) populated from the stored `suite_snapshot` JSON via `TestSuiteRunMapper`. For every non-null `suiteSnapshot`, the serialized DTO SHALL carry `snapshotVersion = "2"` and a non-null `datasetRef`.
- The detail endpoint (`GET /test-suite-runs/{id}`) SHALL return `suiteSnapshot` when available.
- The list endpoint (`GET /test-suite-runs`) SHALL return `suiteSnapshot: null` for all items (list-tier SELECT excludes the column).

Status: **Planned**

#### Scenario: Detail response includes datasetRef for snapshots written after V1.22
- **WHEN** a run was started after V1.22 and `GET /test-suite-runs/{id}` is called
- **THEN** the response `suiteSnapshot.datasetRef` SHALL be populated with `{id, version, name}` of the dataset at snapshot time

#### Scenario: Detail response includes datasetRef for snapshots backfilled by V1.22
- **WHEN** a run was started before V1.22 (snapshot JSON lacked `datasetRef`) and `GET /test-suite-runs/{id}` is called after V1.22 has been applied
- **THEN** the response `suiteSnapshot.datasetRef` SHALL be populated with the synthesized `{id, version=1, name='DATASET_'||suite.name}` value written by the V1.22 backfill step; `snapshotVersion` SHALL be `"2"`

#### Scenario: List response omits suite_snapshot column
- **WHEN** `GET /test-suite-runs` is called
- **THEN** every item in the response SHALL have `suiteSnapshot: null` regardless of whether the underlying row has `suite_snapshot` populated; this is by design (list-tier SELECT excludes the column to avoid TOAST overhead)

