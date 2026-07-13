# Suite Run Snapshot

## MODIFIED Requirements

### Requirement: Snapshot phase execution
The snapshot phase SHALL execute atomically before the run transitions to RUNNING. The phase reads the live `TestSuite` and the live `Dataset` referenced by the suite, and pages through the dataset's test cases excluding those in the suite's `disabledTestCaseIds` and, when the suite has a `testCaseFilter`, keeping only the test cases that match it. Paging SHALL be by **distinct `conversation_id`** so that no conversation's turn rows straddle a page boundary. The phase SHALL group turn rows by `conversation_id`, order them by `turn_index`, validate contiguity/completeness, and freeze **each conversation into exactly one** `test_case_run_inputs` input holding the ordered turns. A single-turn test case (both `conversation_id` and `turn_index` NULL) is a length-1 unit — treated uniformly as a conversation of one turn. `numberOfTestCases` counts runnable **conversations** (multi-turn groups plus standalone single-turn rows), not raw turn rows.
Status: **Planned**

#### Scenario: Snapshot phase sequence
- **WHEN** snapshot phase runs
- **THEN** it SHALL in a single `ISOLATION_REPEATABLE_READ` transaction:
  1. Delete any leftover `test_case_run_inputs` from prior failed attempts (`deleteByRunId`)
  2. Load the live `TestSuite`; throw `SnapshotSuiteMissingException` if absent
  3. Load the live `Dataset` referenced by `testSuite.datasetId`; throw `SnapshotDatasetMissingException` if absent
  4. Build `SuiteSnapshotDto` via `SuiteSnapshotBuilder.build(testSuite, dataset)`; the builder sources `testCaseSchema` from the dataset and populates `datasetRef = {id: dataset.id, version: dataset.version, name: dataset.name}`
  5. Serialize snapshot to JSON; throw `IllegalStateException` on serialization error
  6. Page the runnable test cases by distinct `conversation_id` (single-turn rows are their own singleton conversation), group each conversation's turns, validate contiguity/completeness, assemble each conversation into ONE `test_case_run_inputs` input (ordered turns; each turn carries `test_case_id`, `turn_index`, scalar `test_case_data` snapshot), and batch-insert
  7. Call `updateSuiteSnapshot(runId, snapshotJson)` and `updateNumberOfTestCases(runId, totalConversations)`

#### Scenario: Conversation is frozen as one ordered-turns input
- **WHEN** a dataset contains a conversation `conv-A` with turns `turn_index = 0,1,2` (all valid, enabled, matching the filter)
- **THEN** `test_case_run_inputs` SHALL contain exactly ONE input for `conv-A` holding the three turns in ascending `turn_index` order, each turn carrying its own `test_case_id`, `turn_index`, and scalar `test_case_data` snapshot

#### Scenario: Single-turn test case is a length-1 unit
- **WHEN** a dataset row has `conversation_id` and `turn_index` both NULL
- **THEN** it SHALL be materialized as one `test_case_run_inputs` input containing a single turn (`turn_index` treated as 0), uniform with multi-turn inputs

#### Scenario: Paging never straddles a conversation
- **WHEN** the runnable test cases span more than one `SNAPSHOT_PAGE_SIZE` page
- **THEN** paging SHALL be keyed by distinct `conversation_id` so that all turn rows of any one conversation are loaded and assembled together within a single page (never split across pages)

#### Scenario: Snapshot excludes disabled test cases
- **WHEN** the suite's `disabledTestCaseIds = [tc-2, tc-5]` and the dataset has single-turn test cases `[tc-1, tc-2, tc-3, tc-4, tc-5]`
- **THEN** `test_case_run_inputs` for the run SHALL contain inputs for `[tc-1, tc-3, tc-4]` only; `numberOfTestCases = 3`

#### Scenario: Tail-only disable truncates a conversation
- **WHEN** conversation `conv-A` has turns `0,1,2` and the suite disables the turn row at `turn_index = 2`
- **THEN** `conv-A` SHALL be materialized as a length-2 input with surviving turns `0,1` (enabled turns form the contiguous prefix `0..1`)

#### Scenario: Middle disable hole breaks the conversation
- **WHEN** conversation `conv-A` has turns `0,1,2` and the suite disables only the turn row at `turn_index = 1` (an enabled turn `2` remains after a disabled turn `1`)
- **THEN** `conv-A` SHALL be treated as broken (enabled turns do not form a contiguous prefix) and written as a broken-conversation marker input

#### Scenario: Snapshot honors the suite testCaseFilter atomically per conversation
- **WHEN** the suite has a `testCaseFilter`
- **THEN** a conversation SHALL be included only if ALL its turns match the filter (atomic include/exclude, no holes); the runnable selector aggregates per `conversation_id`; a null `testCaseFilter` SHALL impose no additional restriction

#### Scenario: Stale disabled ID is silently ignored
- **WHEN** the suite's `disabledTestCaseIds = [tc-deleted]` and `tc-deleted` is no longer in the dataset
- **THEN** the snapshot-phase query SHALL produce all valid test cases in the dataset; the stale id is naturally excluded by set-membership semantics and does NOT cause an error

#### Scenario: Snapshot row ordering is deterministic
- **WHEN** the snapshot phase pages through runnable conversations
- **THEN** conversations SHALL be ordered by `min(created_at_ms)` of their turns, then `conversation_id` (single-turn rows use their own `created_at_ms`, then `id`), so that `test_case_run_inputs.position` is assigned in a stable, repeatable order across attempts

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

## ADDED Requirements

### Requirement: Conversation contiguity and completeness validation at snapshot
The snapshot phase SHALL validate each grouped conversation's turn sequence and classify it as runnable or **broken**. A conversation is BROKEN if any of the following hold over its surviving (valid, enabled, filter-matched) turns: it is missing `turn_index = 0`; the surviving turn indices are non-contiguous (a gap); a `turn_index` is duplicated; ANY turn of the conversation has `is_valid = false` (an invalid turn — head, middle, or tail — never truncates, it breaks the whole conversation); the enabled turns do not form a contiguous prefix `0..k` (a middle disable hole); or the surviving turn count exceeds `MAX_CONVERSATION_TURNS`. A conversation that is not broken is materialized as an ordered-turns input; a broken conversation is not silently dropped.
Status: **Planned**

#### Scenario: Missing turn 0 breaks the conversation
- **WHEN** conversation `conv-A` has surviving turns `1,2` (no `turn_index = 0`)
- **THEN** it SHALL be classified broken and written as a broken-conversation marker input

#### Scenario: Gap in turn indices breaks the conversation
- **WHEN** conversation `conv-A` has surviving turns `0,1,3` (missing `2`)
- **THEN** it SHALL be classified broken

#### Scenario: Duplicate turn index breaks the conversation
- **WHEN** conversation `conv-A` has two rows with `turn_index = 1`
- **THEN** it SHALL be classified broken (the DB partial unique index also defends this at write time, but the snapshot validates it independently)

#### Scenario: Any invalid turn breaks the conversation
- **WHEN** conversation `conv-A` has turns `0,1,2` and the turn at `turn_index = 1` has `is_valid = false`
- **THEN** the WHOLE conversation SHALL be classified broken (invalid turns break, they never truncate)

#### Scenario: Over-cap conversation breaks
- **WHEN** conversation `conv-A` has more surviving turns than `MAX_CONVERSATION_TURNS`
- **THEN** it SHALL be classified broken

### Requirement: Broken conversation is materialized as a marker input
A broken conversation SHALL NOT abort the run and SHALL NOT be silently omitted. The snapshot phase SHALL write it as a broken-conversation marker input in `test_case_run_inputs` so that the execution phase emits exactly ONE ERROR result row (sentinel `turn_index = 0`, `total_turns = 0`) for that conversation while all other conversations proceed normally. A broken conversation counts toward `numberOfTestCases` (it is a runnable-unit slot that resolves to an error at execution time).
Status: **Planned**

#### Scenario: Broken conversation yields one ERROR row, run continues
- **WHEN** a dataset has conversations `conv-A` (valid, turns `0,1`) and `conv-B` (broken — missing turn 0)
- **THEN** the snapshot SHALL materialize an ordered-turns input for `conv-A` and a broken-conversation marker input for `conv-B`; at execution the marker SHALL produce exactly one ERROR result row with sentinel `turn_index = 0`, `total_turns = 0`; `conv-A` SHALL execute normally; the run SHALL NOT be aborted

#### Scenario: Broken conversation is counted
- **WHEN** the snapshot materializes 3 valid conversations and 1 broken conversation
- **THEN** `numberOfTestCases = 4` (broken units occupy a slot that resolves to an ERROR row)

## REMOVED Requirements

### Requirement: Array-based multiTurn snapshot field
**Reason**: Multi-turn is now emergent from the presence of per-turn conversation rows in the bound dataset (grouped by `conversation_id`, ordered by `turn_index`), assembled at snapshot into ordered-turns `test_case_run_inputs` units. The prior model — a suite-level `multiTurn` boolean carried on `SuiteSnapshotDto`, with array-valued bound columns unwrapped per turn by `TurnPlan`/`ConversationTurnPlanner` — is fully replaced. There is no suite `multiTurn` flag and no additive `SuiteSnapshotDto.multiTurn` field; per-turn variation lives in the data (distinct rows), not in array-shaped columns.

**Migration**: This is an isolated feature branch performing a full replacement, not a production upgrade. The branch's existing multi-turn Flyway migrations are reshaped in place (local `flyway_history` cleared); no throwaway migration is stacked. The `turn_index` / `total_turns` columns on the analytics result and summary tables are RETAINED (they now carry the authored turn position from the row-based model). Any snapshot JSON that carried a `multiTurn` field remains deserializable via `@JsonIgnoreProperties(ignoreUnknown = true)`; the field is simply ignored. `snapshotVersion` stays `"2"` (the removal is additive-field removal, not a shape break).

## Implementation notes
- `TestSuiteEvaluationJob.attemptSnapshot` selects via `RunnableTestCaseSelector` (translation-layer reuse), now paging by distinct `conversation_id` in `SNAPSHOT_PAGE_SIZE` batches so a conversation's turn rows never straddle a page boundary; the selector aggregates per `conversation_id` for the `testCaseFilter` (all-turns-match) and applies tail-only disable semantics.
- Contiguity/completeness classification and ordered-turns assembly happen during the snapshot transaction before batch-insert into `test_case_run_inputs`; broken conversations are inserted as marker inputs.
- Conversation ordering for the `position` column is deterministic: `min(created_at_ms)` of the conversation's turns, then `conversation_id`.
- `MAX_CONVERSATION_TURNS` is the surviving-turn cap enforced here (mirrors the cheaper write-time cap).
