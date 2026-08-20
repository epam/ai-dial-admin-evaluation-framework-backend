## REMOVED Requirements

### Requirement: MCP Suites Reject Multi-Turn Test Cases Pre-Flight
**Reason**: The guard existed solely to mirror the EF backend's run-creation rejection of MCP + multi-turn, which this change removes. With the backend accepting the combination, the CLI guard would now reject suite/dataset combinations the backend runs successfully — the exact drift the guard was written to prevent, inverted. Replaced by "MCP Suites Execute Multi-Turn Cases and Request Chains".
**Migration**: No client action required. `run` and `evaluate` against an MCP tool suite whose fetched test cases carry per-turn data now execute instead of aborting. A bundle fetched by an older CLI that did not record the dataset schema is still rejected for any multi-turn case, by the separate stale-bundle requirement — re-run `fetch` for such suites.

## ADDED Requirements

### Requirement: MCP Suites Execute Multi-Turn Cases and Request Chains
The CLI SHALL execute MCP tool suites with the same turn and chain semantics the EF backend applies, so a suite run through the CLI produces the same rows the backend would produce for the same suite, dataset, and target. Turn count per request SHALL be decided from the fetched dataset schema and that request's bindings; a fetched request chain SHALL be executed in order, fail-fast; and each result row SHALL carry the turn and request dimensions under the same stamping rules the backend applies.

Status: **Planned**

#### Scenario: MCP suite with multi-turn cases runs
- **WHEN** the `run` (or `evaluate`) command targets an MCP tool suite whose bindings reference a per-turn schema field and whose fetched cases carry per-turn data
- **THEN** the CLI SHALL invoke the tool once per turn, in order, and write one results row per executed turn

#### Scenario: MCP suite without multi-turn cases runs unchanged
- **WHEN** the `run` command targets an MCP tool suite whose fetched test cases all carry only shared data
- **THEN** the CLI SHALL execute one tool call per test-case repetition, exactly as before this capability existed

#### Scenario: MCP request chain is executed in order
- **WHEN** the fetched suite is an MCP tool suite carrying a non-empty request chain
- **THEN** the CLI SHALL invoke each chain position in order against the suite's single MCP deployment reference, threading each request's extracted columns into the next

#### Scenario: Chain aborts on first failure
- **WHEN** a chain position's tool call fails for a test case
- **THEN** the CLI SHALL stop that test case's remaining turns and later requests, retain the rows already produced, and continue with the next test case

#### Scenario: Stale bundle without a schema is still rejected for multi-turn
- **WHEN** a bundle fetched by an earlier CLI version carries no dataset test-case schema and at least one fetched case carries per-turn data
- **THEN** the CLI SHALL fail fast with an error directing the user to re-run `fetch`, since turn counts cannot be decided without the schema

## Implementation Notes

- `RunOrchestrationService`'s `rejectMcpSuiteWithMultiTurnCases` pre-flight check is deleted; `requireFreshSchemaForMultiTurn` is retained and now also protects MCP suites, since per-turn detection for MCP depends on the same fetched schema.
- The CLI gains no execution logic of its own: turn and chain semantics come from `evaluation-runner-core`, which is the point of sharing that module.
