## ADDED Requirements

### Requirement: Multi-turn dispatch and per-turn result emission
The worker that executes one run input SHALL return a list of results. When the input carries `multi_turn_data`, execution is delegated to the multi-turn turn loop, which emits one result per executed turn; otherwise the existing single-turn path is used and returns a single result. MCP inputs are unchanged. Each result carries `turn_index` and `total_turns` (single-turn = `0/1`).

#### Scenario: Multi-turn input yields per-turn results
- **WHEN** a run input has `multi_turn_data`
- **THEN** execution runs the turn loop and returns one `TestCaseRunResult` per executed turn

#### Scenario: Single-turn input is unchanged
- **WHEN** a run input has no `multi_turn_data`
- **THEN** the existing single-turn path runs and returns exactly one result with `turn_index=0, total_turns=1`

### Requirement: One concurrency permit per conversation
The execution unit SHALL be the whole conversation: turns of one multi-turn case run sequentially under a single concurrency permit, and progress is counted one unit per conversation regardless of how many turn rows it writes.

#### Scenario: Progress counts conversations, not turns
- **WHEN** a multi-turn case writes N turn rows
- **THEN** run progress advances by one unit for that case, and the runnable-case count treats the multi-turn case as one unit

## Implementation notes

Planned. `EvaluationWorker.execute` returns `List<TestCaseRunResult>`; branch on `input.multiTurnData != null` → `MultiTurnExecutor`, else single-turn path wrapped in `List.of(...)`. `ResultBatchWriter.addResults(list)` counts one unit per call. `RunnableTestCaseCounter` needs no change (a multi-turn case is one test case).
