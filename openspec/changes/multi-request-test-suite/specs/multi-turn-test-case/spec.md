## ADDED Requirements

### Requirement: Multi-request suites reject multi-turn datasets
Multi-turn test cases and multi-request suites SHALL NOT be combined. A run creation for a **multi-request** suite (non-empty `additionalRequests`) bound to a dataset containing at least one multi-turn test case SHALL be rejected with HTTP 409 `INVALID_OPERATION`. The check SHALL occur at run creation, not suite save, because dataset content is mutable and stored suite validity is configuration-only. This mirrors the existing MCP-suite rejection and reuses the same dataset-level multi-turn presence check.

Consequently every result row and eval summary produced by a multi-request suite SHALL carry `turn_index = 0` and `total_turns = 1`, and `total_turns` retains its existing meaning as the test case's turn count.
Status: **Planned**

#### Scenario: Multi-request suite over a multi-turn dataset rejected at run creation
- **WHEN** a run is created for a multi-request suite whose bound dataset contains at least one case with `multi_turn_data`
- **THEN** it is rejected with HTTP 409 `INVALID_OPERATION`; no run record is persisted and no async job is dispatched

#### Scenario: Multi-turn behavior on single-request suites is unchanged
- **WHEN** a run is created for a single-request suite whose dataset contains multi-turn cases
- **THEN** the run proceeds and multi-turn execution, per-turn rows, and fail-fast behave exactly as specified today

#### Scenario: Multi-request suite over a single-turn dataset proceeds
- **WHEN** a run is created for a multi-request suite whose bound dataset contains no multi-turn cases
- **THEN** the run proceeds and emits one row per chain request

#### Scenario: Adding a multi-turn case blocks subsequent runs
- **WHEN** a multi-request suite has previously run successfully and a multi-turn case is then added to its bound dataset
- **THEN** the next run creation is rejected with HTTP 409 `INVALID_OPERATION`, while previously completed runs and their results remain intact

#### Scenario: Multi-request rows carry inert turn columns
- **WHEN** a multi-request suite writes result rows and eval summaries
- **THEN** every row carries `turn_index = 0` and `total_turns = 1`

### Requirement: Multi-turn rows are identifiable in the eval-summary CSV export
The eval-summary CSV export SHALL include `turnIndex` and `totalTurns` identity columns, so a multi-turn run's exported rows are attributable to their turns without joining back to the database. Previously the export emitted neither, leaving turn rows distinguishable only by opaque identifiers.
Status: **Planned**

#### Scenario: Turn columns appear in the export header
- **WHEN** any eval-summary export is invoked
- **THEN** the header SHALL contain `turnIndex` and `totalTurns` within the identity column block

#### Scenario: Multi-turn rows carry their turn position
- **WHEN** a run containing a multi-turn case of N turns is exported
- **THEN** that case's N rows SHALL carry `turnIndex` `0..N-1` and `totalTurns = N`

#### Scenario: Single-turn rows carry defaults
- **WHEN** a single-turn case is exported
- **THEN** its row SHALL carry `turnIndex = 0` and `totalTurns = 1`

## Implementation notes

Run-creation guard added to `TestSuiteRunService.createRun` at position 3c, reusing `TestCaseRepository.existsMultiTurnByDatasetId` (already used by the MCP guard) combined with the normalized chain length. Export turn columns added in `EvalSummaryExportColumnPlanner`.
