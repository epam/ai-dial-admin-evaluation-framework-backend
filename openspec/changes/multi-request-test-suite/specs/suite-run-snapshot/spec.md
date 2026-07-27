## MODIFIED Requirements

### Requirement: SuiteSnapshotDto model
`SuiteSnapshotDto` SHALL be the canonical frozen representation of suite configuration for a run. `CURRENT_VERSION` SHALL remain `"2"`. The Jackson `@Builder.Default` on `snapshotVersion` SHALL be `CURRENT_VERSION` (i.e., `"2"`) so that any JSON missing the field deserializes as the current version (treating the omission as a producer bug, not as a legacy-shape row). New writes performed by `SuiteSnapshotBuilder` SHALL explicitly stamp `snapshotVersion = "2"`. After the V1.22 backfill, no stored snapshot is shaped as v1; the `UnsupportedSnapshotVersionException` path in `resolveSnapshot` remains in place as defense-in-depth for any future producer (e.g., a hypothetical v3) writing an unsupported version.

The snapshot SHALL additionally carry `additionalRequests` — the frozen chain configuration — **mirroring the live suite's shape**: request 0 remains represented by the flat `endpointRef`/`requestTemplate`/`inputBindings`/`responseColumns` fields and `requestLabel`, with `additionalRequests` holding the remaining chain elements in order. The snapshot SHALL NOT store a pre-normalized complete request array, so request 0 is never duplicated within one snapshot document and there is no ambiguity about which representation is authoritative. Readers SHALL apply the same chain normalizer used for a live suite.

Adding `additionalRequests` SHALL NOT bump `CURRENT_VERSION`: an absent `additionalRequests` denotes a single-request chain, which is precisely the pre-existing behavior, so no version-conditional interpretation is required. This follows the precedent set by `overallScore`, which was added to the version-2 snapshot backward-compatibly.
Status: **Planned**

#### Scenario: DEPLOYMENT suite snapshot fields
- **WHEN** snapshot is built for a DEPLOYMENT suite
- **THEN** snapshot SHALL include `snapshotVersion = "2"` (explicitly stamped by `SuiteSnapshotBuilder`), `suiteType = "DEPLOYMENT"`, `datasetRef` (`{id, version, name}` for the dataset referenced by the suite at snapshot time), `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `requestLabel`, `additionalRequests`, `testCaseSchema` (sourced from the dataset, not the suite); MCP-specific fields SHALL be null/absent

#### Scenario: MCP_TOOL suite snapshot fields
- **WHEN** snapshot is built for an MCP_TOOL suite
- **THEN** snapshot SHALL include `snapshotVersion = "2"` (explicitly stamped), `suiteType = "MCP_TOOL"`, `datasetRef`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema` (sourced from the dataset); deployment-specific fields SHALL be null/absent, and `additionalRequests` SHALL be null/absent because MCP chaining is not supported

#### Scenario: Missing snapshotVersion defaults to CURRENT_VERSION
- **WHEN** a stored snapshot JSON lacks `snapshotVersion`
- **THEN** `SuiteSnapshotDto` SHALL default it to `CURRENT_VERSION` (currently `"2"`) via `@Builder.Default`; for snapshots that were written before V1.22, the backfill step in V1.22 sets the key explicitly to `"2"`, so this default fires only for hypothetical producer bugs going forward

#### Scenario: Post-backfill: every stored snapshot has populated datasetRef
- **WHEN** a `test_suite_runs.suite_snapshot` value is read into a `SuiteSnapshotDto`
- **THEN** `datasetRef` SHALL be non-null; the value either was written that way by `SuiteSnapshotBuilder` (for runs created after V1.22) or was synthesized by the V1.22 backfill step from the joined `test_suites` row (for pre-V1.22 runs)

#### Scenario: Unknown snapshot version causes executor to fail
- **WHEN** a stored snapshot has `snapshotVersion` that is neither `"2"` nor an empty/absent value
- **THEN** `TestSuiteEvaluationJob.resolveSnapshot()` SHALL log a warning and throw `UnsupportedSnapshotVersionException`; the run execution SHALL halt. This is defense-in-depth — no stored snapshot reaches this branch after the V1.22 backfill, but a future producer writing `"3"` (or a corrupted row) would.

#### Scenario: Snapshot without additionalRequests is a single-request chain
- **WHEN** a stored version-2 snapshot written before this capability is read
- **THEN** `additionalRequests` SHALL be absent, normalization SHALL yield a one-element chain from the flat fields, and the run SHALL execute exactly as before — with no version bump and no version-conditional branch

#### Scenario: Snapshot chain is frozen against later suite edits
- **WHEN** a run's snapshot captures a three-request chain and the live suite is subsequently edited to a five-request chain
- **THEN** the run SHALL continue to execute, export, and expose the schema for its frozen three-request chain

#### Scenario: Snapshot and live suite normalize identically
- **WHEN** a snapshot and the live suite that produced it are both normalized
- **THEN** the resulting chains SHALL be identical in length, order, labels, and per-element configuration

## Implementation notes

`SuiteSnapshotDto` gains `additionalRequests` and `requestLabel`; `SuiteSnapshotBuilder` copies them from the suite. Consumers — the chain executor, `EvalSummaryExportColumnPlanner`, and `EvalSummariesSchemaProvider` — read the chain exclusively through the shared normalizer.
