# Suite Run Snapshot

## Purpose
This spec defines the suite snapshot mechanism: freezing suite configuration and test case data at run start time so that execution is isolated from live suite/test case mutations. Covers the `SuiteSnapshotDto` model, snapshot phase in `TestSuiteEvaluationJob`, `test_case_run_inputs` table, two-tier column selection on `test_suite_runs`, retention cleanup, and snapshot-driven execution in `InProcessEvaluationExecutor`.

Status: **Implemented**

## Key Terms
- **SuiteSnapshotDto**: Versioned DTO capturing all execution-relevant suite fields at snapshot time. Fields: `snapshotVersion` (default `"2"`), `suiteType`, and type-specific fields (DEPLOYMENT: `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema`; MCP_TOOL: `mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema`). Annotated with `@JsonIgnoreProperties(ignoreUnknown = true)` for forward compatibility.
- **SuiteSnapshotBuilder**: `@Component` in `service.domain` that builds `SuiteSnapshotDto` from a `TestSuite` model via `JsonbMapper`. Always sets `snapshotVersion` to `SuiteSnapshotDto.CURRENT_VERSION` (currently `"2"`).
- **test_case_run_inputs**: Append-only meta table with columns `run_id`, `position`, `test_case_id`, `test_case_name`, `test_case_data` (JSONB), `request_template_override` (JSONB, nullable), `input_bindings_override` (JSONB, nullable). Primary key `(run_id, position)`.
- **Snapshot phase**: The first phase of `executeRunAsync` — runs before the RUNNING state transition, uses `ISOLATION_REPEATABLE_READ`, retries on `40001`, idempotent.

## Requirements

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

### Requirement: Two-tier column selection on test_suite_runs
The `PostgresTestSuiteRunRepository` SHALL use two distinct SELECT column sets to avoid TOAST overhead.
Status: **Implemented**

#### Scenario: List queries exclude suite_snapshot
- **WHEN** `findAll()` or count queries are called
- **THEN** they SHALL use `SELECT_LIST_COLUMNS` which excludes `suite_snapshot`; returned `TestSuiteRun` objects SHALL have `suiteSnapshot = null`

#### Scenario: Detail query includes suite_snapshot
- **WHEN** `findById()` is called
- **THEN** it SHALL use `SELECT_DETAIL_COLUMNS` which includes `suite_snapshot`; the `TestSuiteRunRowMapper` uses `hasColumn()` to conditionally map the field

### Requirement: Retention cleanup
Expired `test_case_run_inputs` rows SHALL be deleted by a daily cleanup job.
Status: **Implemented**

#### Scenario: Retention job deletes expired inputs
- **WHEN** `TestCaseRunInputsRetentionJob.deleteExpiredInputs()` runs
- **THEN** it SHALL delete rows from `test_case_run_inputs` where `run_id IN (SELECT id FROM test_suite_runs WHERE status IN ('COMPLETED','FAILED') AND updated_at_ms < NOW() - retention)`

#### Scenario: Non-terminal run inputs preserved
- **WHEN** a run has status PENDING or RUNNING
- **THEN** its `test_case_run_inputs` rows SHALL NOT be deleted regardless of age

#### Scenario: Recent terminal run inputs preserved
- **WHEN** a run reached terminal state within the retention window (default 1 day)
- **THEN** its `test_case_run_inputs` rows SHALL NOT be deleted

#### Scenario: Retention failure does not crash
- **WHEN** the retention job encounters an exception
- **THEN** it SHALL log a warning and return normally (no re-throw)

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

## Implementation Notes
- `TestSuiteEvaluationJob.attemptSnapshot` selects via `RunnableTestCaseSelector` (translation-layer
  reuse), replacing the direct `TestCaseRepository.findValidByDatasetIdExcludingIds` call; null
  `testCaseFilter` short-circuits to the prior valid + excluded predicate. See `suite-test-case-filter`.
  Selection now pages by distinct `conversation_id` in `SNAPSHOT_PAGE_SIZE` batches so a conversation's
  turn rows never straddle a page boundary; the selector aggregates per `conversation_id` for the
  `testCaseFilter` (all-turns-match) and applies tail-only disable semantics.
- Contiguity/completeness classification and ordered-turns assembly happen during the snapshot
  transaction before batch-insert into `test_case_run_inputs`; broken conversations are inserted as
  marker inputs.
- Conversation ordering for the `position` column is deterministic: `min(created_at_ms)` of the
  conversation's turns, then `conversation_id`.
- `MAX_CONVERSATION_TURNS` is the surviving-turn cap enforced here (mirrors the cheaper write-time cap).
- No suite `multiTurn` flag and no `SuiteSnapshotDto.multiTurn` field: multi-turn is emergent from the
  presence of per-turn conversation rows; the `turn_index` / `total_turns` columns on the analytics
  result and summary tables are retained. `snapshotVersion` stays `"2"`.
