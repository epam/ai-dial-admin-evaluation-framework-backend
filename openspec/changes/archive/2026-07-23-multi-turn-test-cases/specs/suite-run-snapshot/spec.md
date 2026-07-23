## ADDED Requirements

### Requirement: Snapshot freezes multiTurnData per case
The suite-run snapshot SHALL freeze a multi-turn case's turns by carrying `multi_turn_data` into a new nullable column on `test_case_run_inputs`. Each runnable case produces exactly one input row (single-turn or multi-turn); the existing per-case paging is used. There is no cross-row assembly and no "broken" sentinel — invalid and over-cap cases are already `is_valid=false` and excluded by runnable selection.

#### Scenario: Multi-turn case snapshots to one input row
- **WHEN** a runnable multi-turn case is snapshotted
- **THEN** one `test_case_run_inputs` row is written carrying its ordered `multi_turn_data`

#### Scenario: Single-turn snapshot unchanged
- **WHEN** a runnable single-turn case is snapshotted
- **THEN** one input row is written with `multi_turn_data` null, exactly as today

## Implementation notes

Planned. Add `multi_turn_data JSONB` to `test_case_run_inputs` (migration `V1.28`); `data.db.model.TestCaseRunInput` (+ RecordMapper) gains the field; `TestSuiteEvaluationJob` snapshot phase carries it. No `MultiTurnAssembler`, no `broken`/`total_turns`/`turns` columns.
