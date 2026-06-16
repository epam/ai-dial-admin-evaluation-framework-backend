## ADDED Requirements

### Requirement: Suite snapshot model
The system SHALL define a `SuiteSnapshotDto` that captures all execution-relevant suite configuration fields: `snapshotVersion`, `suiteType`, `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `testCaseSchema`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`. The DTO SHALL be serializable to/from JSON. The DTO SHALL use `@JsonIgnoreProperties(ignoreUnknown = true)` for forward compatibility with future suite fields. The `snapshotVersion` field SHALL be a string with initial value `"1"`; it SHALL be bumped when the snapshot schema changes in a non-backward-compatible way (field rename, field semantic change, structural reshape).
Status: **Planned**

#### Scenario: Snapshot includes all DEPLOYMENT suite fields
- **WHEN** a snapshot is created from a DEPLOYMENT-type suite with `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, and `testCaseSchema`
- **THEN** the `SuiteSnapshotDto` SHALL contain all these fields with their current values

#### Scenario: Snapshot includes all MCP_TOOL suite fields
- **WHEN** a snapshot is created from an MCP_TOOL-type suite with `mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `inputBindings`, `responseColumns`, and `testCaseSchema`
- **THEN** the `SuiteSnapshotDto` SHALL contain all these fields with their current values

#### Scenario: Snapshot excludes non-execution metadata
- **WHEN** a snapshot is created from a suite
- **THEN** the `SuiteSnapshotDto` SHALL NOT contain `id`, `name`, `description`, `createdBy`, `version`, `valid`, `validationWarnings`, `createdAt`, or `updatedAt`

### Requirement: Suite snapshot persistence
The `test_suite_runs` table SHALL have a nullable `suite_snapshot JSONB` column. The snapshot SHALL be populated at the start of async run execution (snapshot phase in `executeRunAsync()`) under a REPEATABLE READ transaction, before the run transitions to RUNNING. The snapshot SHALL be immutable after creation — no updates after the initial write.
Status: **Planned**

#### Scenario: Snapshot persisted before execution starts
- **WHEN** a test suite run transitions from PENDING to RUNNING
- **THEN** `suite_snapshot` SHALL already be populated in `test_suite_runs` with the suite's execution-relevant config as it existed at snapshot time

#### Scenario: Snapshot is transactionally consistent with test case inputs
- **WHEN** the snapshot phase runs
- **THEN** the suite read and the test case data reads SHALL occur within the same REPEATABLE READ transaction, ensuring `suite_snapshot` and `test_case_run_inputs` reflect the same point-in-time state

#### Scenario: Existing runs have null snapshot
- **WHEN** a run was created before the migration
- **THEN** `suite_snapshot` SHALL be `NULL`

### Requirement: Test case run inputs table
The system SHALL maintain a `test_case_run_inputs` table in the **meta DB** that stores one row per enabled+valid test case per run, capturing the full test case data and per-case overrides at snapshot time. Rows SHALL be written under the same REPEATABLE READ transaction as the `suite_snapshot`. The table SHALL have a FK to `test_suite_runs(id)` with `ON DELETE CASCADE` so inputs are automatically removed when the run is deleted. The `test_case_id` column SHALL be a **loose reference** — NO foreign key to `test_cases(id)` is defined. This decoupling is deliberate: deletion of individual test cases MUST NOT invalidate snapshotted inputs (the snapshot is the source of truth for execution).
Status: **Planned**

#### Scenario: Table structure
- **WHEN** the `test_case_run_inputs` table is created
- **THEN** it SHALL have the following columns and constraints:
  - `run_id VARCHAR(36) NOT NULL` — FK to `test_suite_runs(id)` ON DELETE CASCADE
  - `position INTEGER NOT NULL`
  - `test_case_id VARCHAR(36) NOT NULL`
  - `test_case_name VARCHAR(255) NOT NULL`
  - `test_case_data JSONB NOT NULL`
  - `request_template_override JSONB NULL`
  - `input_bindings_override JSONB NULL`
  - `PRIMARY KEY (run_id, position)`

#### Scenario: Inputs populated at snapshot phase
- **WHEN** the snapshot phase runs for a suite with N enabled and valid test cases
- **THEN** `test_case_run_inputs` SHALL contain exactly N rows for that `run_id`, one per test case, with `test_case_data`, `request_template_override`, `input_bindings_override`, and `position` (zero-based sort order)

#### Scenario: Order matches repository sort order
- **WHEN** the inputs are captured
- **THEN** `position` values SHALL reflect the same order as returned by `findEnabledValidByTestSuiteId`

#### Scenario: Disabled and invalid test cases excluded
- **WHEN** a suite has test cases with `is_enabled = false` or `is_valid = false`
- **THEN** those test cases SHALL NOT appear in `test_case_run_inputs`

#### Scenario: Inputs cascade-deleted with run
- **WHEN** a `test_suite_runs` row is deleted
- **THEN** all associated `test_case_run_inputs` rows SHALL be deleted automatically via the FK CASCADE

#### Scenario: Legacy runs have no inputs rows
- **WHEN** a run was created before the migration
- **THEN** no `test_case_run_inputs` rows exist for that `run_id`

#### Scenario: Test cases deleted after snapshot
- **WHEN** a test case that exists in `test_case_run_inputs` is subsequently deleted from `test_cases`
- **THEN** the `test_case_run_inputs` row SHALL remain intact — the inputs table is a snapshot, not a live FK reference

### Requirement: Suite snapshot builder
The system SHALL provide a `SuiteSnapshotBuilder` component that constructs a `SuiteSnapshotDto` from a `TestSuite` model. The builder SHALL use `JsonbMapper` to deserialize JSONB string fields from the suite into typed DTOs for the snapshot. The builder SHALL always set `snapshotVersion = "1"` on every built snapshot.
Status: **Planned**

#### Scenario: Builder produces typed snapshot from suite model
- **WHEN** `SuiteSnapshotBuilder.build(suite)` is called with a valid `TestSuite`
- **THEN** it SHALL return a `SuiteSnapshotDto` with all execution-relevant fields deserialized from the suite's JSONB strings into typed objects, and `snapshotVersion = "1"`

### Requirement: Two-tier column selection for `test_suite_runs` (TOAST optimization)
`PostgresTestSuiteRunRepository` SHALL define two SELECT-column tiers that control whether `suite_snapshot` is loaded per query:

- **List tier** (`SELECT_LIST_COLUMNS`): excludes `suite_snapshot`
- **Detail tier** (`SELECT_DETAIL_COLUMNS`): includes `suite_snapshot`

The API detail endpoint (`findById`) SHALL use the detail tier. List endpoints (`findAll`, paginated queries) SHALL use the list tier. The `TestSuiteRunRowMapper` SHALL use `hasColumn()` to handle the absent column gracefully.
Status: **Planned**

#### Scenario: List runs excludes snapshot column
- **WHEN** client calls `GET /api/v1/test-suites/{id}/runs` (list endpoint)
- **THEN** the query SHALL NOT select `suite_snapshot`, and each run in the response SHALL have `suiteSnapshot` as `null`

#### Scenario: Get run by ID includes suite snapshot
- **WHEN** client calls `GET /api/v1/test-suites/{id}/runs/{runId}` (detail endpoint)
- **THEN** the query SHALL select `suite_snapshot`, and the response SHALL include `suiteSnapshot`

### Requirement: Snapshot schema evolution
The snapshot SHALL include a `snapshotVersion` string field identifying the schema version. New additive fields SHALL NOT require a version bump. Renames, removals, or semantic changes SHALL require a version bump and an explicit compatibility strategy. When reading a historical snapshot, the system SHALL inspect `snapshotVersion` and branch accordingly.
Status: **Planned**

#### Scenario: Reading a snapshot with the current version
- **WHEN** the executor or DTO mapper deserializes a `suite_snapshot` whose `snapshotVersion` equals the currently supported version
- **THEN** deserialization SHALL proceed normally and produce a populated `SuiteSnapshotDto`

#### Scenario: Reading a snapshot with an older, still-supported version
- **WHEN** the executor or DTO mapper deserializes a `suite_snapshot` whose `snapshotVersion` is older than the current version but still supported
- **THEN** deserialization SHALL succeed; unknown-to-new fields default to null/empty; removed-since-then fields are ignored via `@JsonIgnoreProperties(ignoreUnknown = true)`

#### Scenario: Reading a snapshot with an unknown or newer version
- **WHEN** the executor deserializes a `suite_snapshot` whose `snapshotVersion` is not recognized (e.g., after a rollback to an older binary)
- **THEN** the executor SHALL log a warning, refuse to execute the run, mark the run with status FAILED and error code `UNSUPPORTED_SNAPSHOT_VERSION`, and return the raw JSON as-is to API callers on the detail endpoint

#### Scenario: Reading a snapshot with missing version field
- **WHEN** `snapshotVersion` is missing
- **THEN** the snapshot SHALL be treated as version `"1"`
