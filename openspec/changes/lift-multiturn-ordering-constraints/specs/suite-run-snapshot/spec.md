## MODIFIED Requirements

### Requirement: Snapshot phase execution
The snapshot phase SHALL execute atomically before the run transitions to RUNNING. The phase reads the live `TestSuite` and the live `Dataset` referenced by the suite, and pages through the dataset's test cases excluding those in the suite's `disabledTestCaseIds` and, when the suite has a `testCaseFilter`, keeping only the test cases that match it. Paging SHALL be by **distinct `conversation_id`** so that no conversation's turn rows straddle a page boundary. The phase SHALL group turn rows by `conversation_id`, order them by `turn_index`, assemble the surviving turns in ascending authored `turn_index` order (gaps allowed — a disabled or filtered-out start/middle/tail turn simply drops), classify the conversation runnable-or-broken (broken only when a surviving turn is invalid or the surviving count exceeds `MAX_CONVERSATION_TURNS`), and freeze **each conversation into exactly one** `test_case_run_inputs` input holding the ordered surviving turns. A single-turn test case (both `conversation_id` and `turn_index` NULL) is a length-1 unit — treated uniformly as a conversation of one turn. `numberOfTestCases` counts runnable **conversations** (multi-turn groups plus standalone single-turn rows), not raw turn rows.
Status: **Planned**

#### Scenario: Snapshot phase sequence
- **WHEN** snapshot phase runs
- **THEN** it SHALL in a single `ISOLATION_REPEATABLE_READ` transaction:
  1. Delete any leftover `test_case_run_inputs` from prior failed attempts (`deleteByRunId`)
  2. Load the live `TestSuite`; throw `SnapshotSuiteMissingException` if absent
  3. Load the live `Dataset` referenced by `testSuite.datasetId`; throw `SnapshotDatasetMissingException` if absent
  4. Build `SuiteSnapshotDto` via `SuiteSnapshotBuilder.build(testSuite, dataset)`; the builder sources `testCaseSchema` from the dataset and populates `datasetRef = {id: dataset.id, version: dataset.version, name: dataset.name}`
  5. Serialize snapshot to JSON; throw `IllegalStateException` on serialization error
  6. Page the runnable test cases by distinct `conversation_id` (single-turn rows are their own singleton conversation), group each conversation's turns, assemble the surviving turns in ascending authored `turn_index` order (computing `lastTurnIndex` = max authored surviving index), classify runnable-or-broken (invalid surviving turn or over-cap ⇒ broken), assemble each conversation into ONE `test_case_run_inputs` input (ordered surviving turns; each turn carries `test_case_id`, authored `turn_index`, scalar `test_case_data` snapshot), and batch-insert
  7. Call `updateSuiteSnapshot(runId, snapshotJson)` and `updateNumberOfTestCases(runId, totalConversations)`

#### Scenario: Conversation is frozen as one ordered-turns input
- **WHEN** a dataset contains a conversation `conv-A` with turns `turn_index = 0,1,2` (all valid, enabled, matching the filter)
- **THEN** `test_case_run_inputs` SHALL contain exactly ONE input for `conv-A` holding the three turns in ascending `turn_index` order, each turn carrying its own `test_case_id`, `turn_index`, and scalar `test_case_data` snapshot

#### Scenario: Single-turn test case is a length-1 unit
- **WHEN** a dataset row has `conversation_id` and `turn_index` both NULL
- **THEN** it SHALL be materialized as one `test_case_run_inputs` input containing a single turn (`turn_index` treated as 0), uniform with multi-turn inputs

#### Scenario: Paging never straddles a conversation
- **WHEN** a conversation's turn rows would span a page boundary during snapshot paging
- **THEN** paging SHALL be keyed by distinct `conversation_id` so that all turn rows of any one conversation are loaded and assembled together within a single page (never split across pages)

#### Scenario: Snapshot excludes disabled test cases
- **WHEN** the suite's `disabledTestCaseIds = [tc-2, tc-5]` and the dataset has single-turn test cases `[tc-1, tc-2, tc-3, tc-4, tc-5]`
- **THEN** `test_case_run_inputs` for the run SHALL contain inputs for `[tc-1, tc-3, tc-4]` only; `numberOfTestCases = 3`

#### Scenario: Tail disable shortens a conversation
- **WHEN** conversation `conv-A` has turns `0,1,2` and the suite disables the turn row at `turn_index = 2`
- **THEN** `conv-A` SHALL be materialized as a length-2 input with surviving turns `0,1` (`total_turns = 2`, `lastTurnIndex = 1`)

#### Scenario: Middle disable hole is honored (survivors run)
- **WHEN** conversation `conv-A` has turns `0,1,2` and the suite disables only the turn row at `turn_index = 1`
- **THEN** `conv-A` SHALL be materialized as a length-2 input with surviving turns at authored `turn_index` `0` and `2` (run in that order, indices preserved, `total_turns = 2`, `lastTurnIndex = 2`) and SHALL NOT be treated as broken

#### Scenario: Snapshot applies the suite testCaseFilter row-level (like disable)
- **WHEN** the suite has a `testCaseFilter`
- **THEN** the filter SHALL be applied per turn (not aggregated per `conversation_id`): a conversation is enumerated when at least one of its turns matches, only its filter-matching turns are loaded, and `ConversationAssembler` treats a non-matching turn like a disabled turn — the turn drops and the surviving turns (at the start, middle, or tail) run in ascending authored order. A null `testCaseFilter` SHALL impose no additional restriction

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

### Requirement: Conversation contiguity and completeness validation at snapshot
The snapshot phase SHALL classify each grouped conversation as runnable or **broken** over its surviving (valid, enabled, filter-matched) turns. Ordering and sequencing are NO LONGER integrity concerns: a missing `turn_index = 0`, a gap in the surviving turn indices, and a disabled/filtered-out start or middle turn SHALL NOT break the conversation — the surviving turns run in ascending authored `turn_index` order with their authored indices preserved. A conversation SHALL be classified BROKEN only when, over its surviving turns, EITHER (a) any surviving turn has `is_valid = false` (an invalid turn — head, middle, or tail — breaks the whole conversation; it is not silently dropped), OR (b) the surviving turn count exceeds `MAX_CONVERSATION_TURNS`. A conversation with ZERO surviving turns (every turn disabled or filtered out) SHALL be dropped entirely (no input, not a broken marker). A conversation that is not broken is materialized as an ordered-turns input carrying `lastTurnIndex` = the max authored surviving `turn_index`.
Status: **Planned**

#### Scenario: Missing turn 0 does not break the conversation
- **WHEN** conversation `conv-A` has surviving turns `1,2` (no `turn_index = 0`)
- **THEN** it SHALL be materialized as a runnable ordered-turns input with surviving turns `1,2` (`total_turns = 2`, `lastTurnIndex = 2`)

#### Scenario: Gap in turn indices does not break the conversation
- **WHEN** conversation `conv-A` has surviving turns `0,1,3` (authored `2` disabled or filtered out)
- **THEN** it SHALL be materialized as a runnable ordered-turns input with surviving turns `0,1,3` (`total_turns = 3`, `lastTurnIndex = 3`)

#### Scenario: Any invalid surviving turn breaks the conversation
- **WHEN** conversation `conv-A` has surviving turns `0,1,2` and the turn at `turn_index = 1` has `is_valid = false`
- **THEN** the WHOLE conversation SHALL be classified broken (an invalid surviving turn breaks; it is never silently dropped)

#### Scenario: Over-cap conversation breaks
- **WHEN** conversation `conv-A` has more surviving turns than `MAX_CONVERSATION_TURNS`
- **THEN** it SHALL be classified broken

#### Scenario: Fully-disabled conversation is dropped
- **WHEN** every turn of conversation `conv-A` is disabled or filtered out (zero survivors)
- **THEN** `conv-A` SHALL contribute no `test_case_run_inputs` input at all and SHALL NOT be a broken marker (it is not counted toward `numberOfTestCases`)

### Requirement: Broken conversation is materialized as a marker input
A broken conversation SHALL NOT abort the run and SHALL NOT be silently omitted. The snapshot phase SHALL write it as a broken-conversation marker input in `test_case_run_inputs` so that the execution phase emits exactly ONE ERROR result row (sentinel `turn_index = 0`, `total_turns = 0`, `last_turn_index = 0`) for that conversation while all other conversations proceed normally. The sentinel ERROR row's message SHALL reference only the two remaining break causes (an invalid turn, or too many turns) and SHALL NOT mention turn-0 presence, gaps, or contiguity. A broken conversation counts toward `numberOfTestCases` (it is a runnable-unit slot that resolves to an error at execution time).
Status: **Planned**

#### Scenario: Broken conversation yields one ERROR row, run continues
- **WHEN** a dataset has conversations `conv-A` (valid, turns `0,1`) and `conv-B` (broken — a surviving turn is invalid)
- **THEN** the snapshot SHALL materialize an ordered-turns input for `conv-A` and a broken-conversation marker input for `conv-B`; at execution the marker SHALL produce exactly one ERROR result row with sentinel `turn_index = 0`, `total_turns = 0`, `last_turn_index = 0`; `conv-A` SHALL execute normally; the run SHALL NOT be aborted

#### Scenario: Broken conversation is counted
- **WHEN** the snapshot materializes 3 valid conversations and 1 broken conversation
- **THEN** `numberOfTestCases = 4` (broken units occupy a slot that resolves to an ERROR row)
