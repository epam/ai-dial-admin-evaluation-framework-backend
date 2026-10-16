## MODIFIED Requirements

### Requirement: SuiteSnapshotDto model
`SuiteSnapshotDto` SHALL be the canonical frozen representation of suite configuration for a run. `CURRENT_VERSION` SHALL be `"2"`. The Jackson `@Builder.Default` on `snapshotVersion` SHALL be `CURRENT_VERSION` (i.e., `"2"`) so that any JSON missing the field deserializes as the current version (treating the omission as a producer bug, not as a legacy-shape row). New writes performed by `SuiteSnapshotBuilder` SHALL explicitly stamp `snapshotVersion = "2"`. After the V1.22 backfill, no stored snapshot is shaped as v1; the `UnsupportedSnapshotVersionException` path in `resolveSnapshot` remains in place as defense-in-depth for any future producer (e.g., a hypothetical v3) writing an unsupported version.

The DTO SHALL additionally carry `routesRef` (`RouteReferenceDto` `{ name }`, nullable), captured from the suite's `routesRef` at snapshot time; it is non-null only for `ROUTE` suites. This addition SHALL be **additive-optional**: `snapshotVersion` SHALL REMAIN `"2"`, a snapshot JSON written before this field existed SHALL deserialize with `routesRef = null`, and no backfill of stored snapshots SHALL be performed. For a `ROUTE` suite the snapshot is an HTTP snapshot — it carries `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `requestName` and `additionalRequests` — but `deploymentRef` SHALL be null.

The DTO SHALL additionally carry the suite's request chain: `additionalRequests` (a list of `RequestDefinitionDto`, defaulting to an empty list) and `requestName` (`String`, nullable — the label of the suite's own request). Both additions SHALL be **additive-optional**: `snapshotVersion` SHALL REMAIN `"2"`, and a snapshot JSON written before these fields existed SHALL deserialize unchanged, yielding an empty `additionalRequests` and a null `requestName` (a one-request chain). No backfill of stored snapshots SHALL be performed.

The DTO SHALL additionally carry `overallScoreThreshold` (`Double`, nullable), captured from the suite's `overallScoreThreshold` at snapshot time, alongside the existing `overallScore` field. This addition SHALL also be **additive-optional**: a snapshot JSON written before this field existed SHALL deserialize with `overallScoreThreshold = null`, and no backfill of stored snapshots SHALL be performed. Once captured, `overallScoreThreshold` SHALL remain fixed for the life of the run regardless of subsequent edits to the live suite's threshold.

The DTO SHALL additionally carry `testCaseOverallScore` (`OverallScoreDefinition`, nullable), captured from the suite's `testCaseOverallScore` at snapshot time, alongside the existing `overallScore` field. This addition SHALL also be **additive-optional**: a snapshot JSON written before this field existed SHALL deserialize with `testCaseOverallScore = null`. `SuiteSnapshotDto` carries `testCaseOverallScore` as captured, with no fallback logic of its own; resolving the effective per-row definition (`testCaseOverallScore` if present, else `overallScore`) is a Phase-2 concern performed once per run by `TestSuiteEvaluationJob.buildMetricEvaluationContext` (see `test-suites`, `eval-summary-scoring`), not by this DTO or its builder.
Status: **Implemented**

#### Scenario: DEPLOYMENT suite snapshot fields
- **WHEN** snapshot is built for a DEPLOYMENT suite
- **THEN** snapshot SHALL include `snapshotVersion = "2"` (explicitly stamped by `SuiteSnapshotBuilder`), `suiteType = "DEPLOYMENT"`, `datasetRef` (`{id, version, name}` for the dataset referenced by the suite at snapshot time), `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `requestName`, `additionalRequests`, `testCaseSchema` (sourced from the dataset, not the suite), `overallScore`, `overallScoreThreshold`, and `testCaseOverallScore`; MCP-specific fields SHALL be null/absent
- **AND** `routesRef` SHALL be null

#### Scenario: ROUTE suite snapshot
- **WHEN** snapshot is built for a ROUTE suite
- **THEN** snapshot SHALL include `snapshotVersion = "2"`, `suiteType = "ROUTE"`, `datasetRef`, `routesRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `requestName`, `additionalRequests` (as configured on the suite), `testCaseSchema` (sourced from the dataset), `overallScore`, `overallScoreThreshold`, and `testCaseOverallScore`
- **AND** `deploymentRef` SHALL be null and MCP-specific fields SHALL be null/absent

#### Scenario: Older snapshot without routesRef deserialises with routesRef null
- **WHEN** a stored `suite_snapshot` JSON has no `routesRef` key
- **THEN** it SHALL deserialize successfully with `routesRef = null` and `snapshotVersion = "2"`

#### Scenario: ROUTE run snapshots routesRef and executes against the resolved root path
- **WHEN** a run is created for a ROUTE suite whose `requestTemplate.urlTemplate` is `/validate/${{path}}` and an input binding maps template variable `path` to dataset field `routePath` and the test case has `routePath = "items"`
- **THEN** the run SHALL complete, `GET /api/v1/test-suite-runs/{id}` SHALL return `suiteSnapshot.suiteType = "ROUTE"` with `suiteSnapshot.routesRef` equal to the suite's `routesRef` and a null `suiteSnapshot.deploymentRef`, and DIAL Core SHALL receive the path `/validate/items`

#### Scenario: MCP_TOOL suite snapshot fields
- **WHEN** snapshot is built for an MCP_TOOL suite
- **THEN** snapshot SHALL include `snapshotVersion = "2"` (explicitly stamped), `suiteType = "MCP_TOOL"`, `datasetRef`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema` (sourced from the dataset), `overallScore`, `overallScoreThreshold`, and `testCaseOverallScore`; deployment-specific fields SHALL be null/absent and `additionalRequests` SHALL be empty (an MCP suite cannot carry a chain)

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

#### Scenario: Chain is frozen against later suite edits
- **WHEN** a run's snapshot is taken and the suite's `additionalRequests` is subsequently edited or emptied
- **THEN** the run SHALL continue executing the chain as captured in its snapshot

#### Scenario: Snapshot written before overallScoreThreshold existed deserializes as null
- **WHEN** a stored `suite_snapshot` JSON has no `overallScoreThreshold` key
- **THEN** it SHALL deserialize with `overallScoreThreshold = null`, and any per-row `passed` computed against that run SHALL be `null` (no threshold to compare against)

#### Scenario: Threshold is frozen against later suite edits
- **WHEN** a run's snapshot is taken and the suite's `overallScoreThreshold` is subsequently edited
- **THEN** per-row `passed` values computed for that run SHALL continue to reflect the threshold as it was at snapshot time, not the suite's current value

#### Scenario: Snapshot written before testCaseOverallScore existed deserializes as null
- **WHEN** a stored `suite_snapshot` JSON has no `testCaseOverallScore` key
- **THEN** it SHALL deserialize with `testCaseOverallScore = null`, and Phase 2's per-row scoring for that run SHALL fall back to `overallScore`

#### Scenario: testCaseOverallScore is frozen against later suite edits
- **WHEN** a run's snapshot is taken and the suite's `testCaseOverallScore` is subsequently edited or cleared
- **THEN** the run SHALL continue computing per-row scores using `testCaseOverallScore` (or its `overallScore` fallback) as captured in its snapshot, not the suite's current value

## Implementation notes

- DTO: `SuiteSnapshotDto` (`routesRef`; `endpointRef`/`requestTemplate` descriptions now read "DEPLOYMENT and ROUTE suites"), `RouteReferenceDto`; producer: `SuiteSnapshotBuilder`, `JsonbMapper`.
- Tests: `SuiteSnapshotBuilderTest` ("ROUTE suite builds an HTTP snapshot with routesRef and no deploymentRef"), `SuiteSnapshotFunctionalTests` ("ROUTE run snapshots routesRef and invokes the resolved root path").
- The ROUTE end-to-end scenario also exercises the engine behaviour specified in the `eval-execution-engine` capability of this change.
