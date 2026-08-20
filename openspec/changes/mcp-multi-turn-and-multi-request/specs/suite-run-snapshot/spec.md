## MODIFIED Requirements

### Requirement: SuiteSnapshotDto model
`SuiteSnapshotDto` SHALL be the canonical frozen representation of suite configuration for a run. `CURRENT_VERSION` SHALL be `"2"`. The Jackson `@Builder.Default` on `snapshotVersion` SHALL be `CURRENT_VERSION` (i.e., `"2"`) so that any JSON missing the field deserializes as the current version (treating the omission as a producer bug, not as a legacy-shape row). New writes performed by `SuiteSnapshotBuilder` SHALL explicitly stamp `snapshotVersion = "2"`. After the V1.22 backfill, no stored snapshot is shaped as v1; the `UnsupportedSnapshotVersionException` path in `resolveSnapshot` remains in place as defense-in-depth for any future producer (e.g., a hypothetical v3) writing an unsupported version.

The DTO SHALL additionally carry the suite's request chain: `additionalRequests` (a list of `RequestDefinitionDto`, defaulting to an empty list) and `requestName` (`String`, nullable — the label of the suite's own request). Both additions SHALL be **additive-optional**: `snapshotVersion` SHALL REMAIN `"2"`, and a snapshot JSON written before these fields existed SHALL deserialize unchanged, yielding an empty `additionalRequests` and a null `requestName` (a one-request chain). No backfill of stored snapshots SHALL be performed.

The chain SHALL be captured for both suite types. For an `MCP_TOOL` suite, each `additionalRequests` entry SHALL carry that request's `toolRef` and `argumentTemplate` (with `endpointRef` and `requestTemplate` absent); for a `DEPLOYMENT` suite each entry SHALL carry `endpointRef` and `requestTemplate` (with `toolRef` and `argumentTemplate` absent). Adding MCP chain support SHALL NOT change `snapshotVersion`: a stored `MCP_TOOL` snapshot written when MCP chains were rejected carries an empty `additionalRequests` and SHALL continue to deserialize and execute as a one-request chain.
Status: **Planned**

#### Scenario: DEPLOYMENT suite snapshot fields
- **WHEN** snapshot is built for a DEPLOYMENT suite
- **THEN** snapshot SHALL include `snapshotVersion = "2"` (explicitly stamped by `SuiteSnapshotBuilder`), `suiteType = "DEPLOYMENT"`, `datasetRef` (`{id, version, name}` for the dataset referenced by the suite at snapshot time), `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `requestName`, `additionalRequests`, `testCaseSchema` (sourced from the dataset, not the suite); MCP-specific fields SHALL be null/absent

#### Scenario: MCP_TOOL suite snapshot fields
- **WHEN** snapshot is built for an MCP_TOOL suite
- **THEN** snapshot SHALL include `snapshotVersion = "2"` (explicitly stamped), `suiteType = "MCP_TOOL"`, `datasetRef`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `inputBindings`, `responseColumns`, `requestName`, `additionalRequests`, `testCaseSchema` (sourced from the dataset); deployment-specific fields SHALL be null/absent

#### Scenario: MCP_TOOL chain is captured in the snapshot
- **WHEN** snapshot is built for an MCP_TOOL suite carrying two `additionalRequests` entries
- **THEN** the snapshot's `additionalRequests` SHALL contain both entries in chain order, each with its own `toolRef`, `argumentTemplate`, `responseColumns` and `inputBindings`

#### Scenario: Missing snapshotVersion defaults to CURRENT_VERSION
- **WHEN** a stored snapshot JSON lacks `snapshotVersion`
- **THEN** `SuiteSnapshotDto` SHALL default it to `CURRENT_VERSION` (currently `"2"`) via `@Builder.Default`; for snapshots that were written before V1.22, the backfill step in V1.22 sets the key explicitly to `"2"`, so this default fires only for hypothetical producer bugs going forward

#### Scenario: Post-backfill: every stored snapshot has populated datasetRef
- **WHEN** a `test_suite_runs.suite_snapshot` value is read into a `SuiteSnapshotDto`
- **THEN** `datasetRef` SHALL be non-null; the value either was written that way by `SuiteSnapshotBuilder` (for runs created after V1.22) or was synthesized by the V1.22 backfill step from the joined `test_suites` row (for pre-V1.22 runs)

#### Scenario: Unknown snapshot version causes executor to fail
- **WHEN** a stored snapshot has `snapshotVersion` that is neither `"2"` nor an empty/absent value
- **THEN** `TestSuiteEvaluationJob.resolveSnapshot()` SHALL log a warning and throw `UnsupportedSnapshotVersionException`; the run execution SHALL halt. This is defense-in-depth — no stored snapshot reaches this branch after the V1.22 backfill, but a future producer writing `"3"` (or a corrupted row) would.

#### Scenario: Snapshot written before the chain fields deserializes as a one-request chain
- **WHEN** a stored `suite_snapshot` JSON has no `additionalRequests` and no `requestName` key
- **THEN** it SHALL deserialize with `snapshotVersion = "2"`, an empty `additionalRequests` and a null `requestName`, and the run SHALL execute a one-request chain identical to its pre-change behavior

#### Scenario: Stored MCP snapshot with an empty chain still executes single-shot
- **WHEN** a stored `MCP_TOOL` snapshot written before MCP chains were supported carries `additionalRequests: []`
- **THEN** it SHALL execute as a one-request chain, producing a row identical to its pre-change behavior

#### Scenario: Chain is frozen against later suite edits
- **WHEN** a run's snapshot is taken and the suite's `additionalRequests` is subsequently edited or emptied
- **THEN** the run SHALL continue executing the chain as captured in its snapshot

## Implementation Notes

- `SuiteSnapshotBuilder` keeps its `suiteType` branch for the target-specific fields (`deploymentRef`/`endpointRef`/`requestTemplate` vs `mcpDeploymentRef`/`toolRef`/`argumentTemplate`); only the stale comment asserting that `additionalRequests` is always `'[]'` for `MCP_TOOL` is removed.
- No snapshot backfill and no version bump: the chain fields already exist on the DTO and the addition is a widening of which suite types populate them.
