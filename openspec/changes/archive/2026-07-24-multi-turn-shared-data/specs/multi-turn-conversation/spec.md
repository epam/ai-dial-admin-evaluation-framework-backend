## MODIFIED Requirements

### Requirement: Multi-turn is a single test case carrying an ordered turn array
A test case SHALL support an optional `multiTurnData` field — an ordered array of maps, where each element is one turn's per-turn data. A test case is single-turn when `multiTurnData` is absent/null (all fields in `data`) and multi-turn when `multiTurnData` is non-empty. The two fields are NOT mutually exclusive: a multi-turn case MAY also populate `data` with the dataset's **shared** (`perTurn=false`) fields, which are constant across turns, while each turn map carries the **per-turn** (`perTurn=true`) fields. The multi-turn discriminator is the presence of `multiTurnData` alone (independent of whether `data` is empty). Multi-turn behavior is emergent from the data — there is no suite-level flag.
Status: **Implemented**

#### Scenario: Multi-turn case is identified by multiTurnData
- **WHEN** a test case is stored with a non-empty `multiTurnData` array
- **THEN** it is treated as a multi-turn conversation whose turns are the array elements in order (`turn_index` = array position, `0..N-1`), regardless of whether `data` is empty or carries shared fields

#### Scenario: Single-turn case is unaffected
- **WHEN** a test case has `multiTurnData` absent/null
- **THEN** it behaves exactly as today, using its `data` map as a single turn

#### Scenario: Shared data coexists with turns
- **WHEN** a multi-turn case has `data` carrying shared fields and `multiTurnData` carrying per-turn fields
- **THEN** both are stored and the case is multi-turn; the shared fields are visible to every turn (see the merged-view execution requirement)

### Requirement: Sequential turn-loop execution with full-history resend
A multi-turn case SHALL execute as one sequential unit. The engine maintains a running `messages` history; for each turn in order it resolves the suite's single `requestTemplate`/`inputBindings` against that turn's **effective view** — the merge of the case's shared `data` map with that turn's own per-turn map (per-turn keys take precedence on any overlap) — appends the resolved `messages` to the history, sends the request with the full accumulated history (non-streaming), appends the assistant reply `choices[0].message` verbatim to the history, extracts that turn's response columns, and persists that turn as its own result row. The merged effective view is also the `data` namespace supplied to conditional-metric evaluation for that turn.
Status: **Implemented**

#### Scenario: Two-turn conversation accumulates history
- **WHEN** a 2-turn case runs successfully
- **THEN** turn 0 is sent with its own messages, turn 1 is sent with turn 0's messages + turn 0's assistant reply + turn 1's messages, and two SUCCESS result rows are persisted with `turn_index` 0 and 1 and `total_turns=2`

#### Scenario: Shared field is visible on every turn
- **WHEN** a template placeholder is bound to a shared field and the case is multi-turn
- **THEN** every turn resolves that placeholder from the shared `data` value (the merged effective view), without the value being repeated in each turn map

#### Scenario: Turns run sequentially under one permit
- **WHEN** a multi-turn case executes
- **THEN** its turns run strictly in order under a single concurrency permit (concurrency applies across cases, not across turns of one case)

### Requirement: Flat CSV import/export multiplication
CSV import/export SHALL remain flat: a multi-turn case is represented as one row per turn. A reserved `turnIndex` header groups and orders turns; it and `testCaseName` are excluded from `data` and from schema auto-detection. Per-turn columns vary per row. Shared columns SHALL be repeated on every turn row of a case; on import the shared columns of a case's rows MUST be identical, and a mismatch SHALL be reported as a conflict warning that invalidates the case. Single-turn cases export one row with a blank `turnIndex`.
Status: **Implemented**

#### Scenario: Export multiplies turns to rows
- **WHEN** a multi-turn case with N turns is exported
- **THEN** it produces N contiguous rows sharing `testCaseName`, with `turnIndex` `0..N-1` in order; shared columns carry the same value on every row; single-turn cases export one row with a blank `turnIndex`

#### Scenario: Import assembles a contiguous run into one case
- **WHEN** consecutive import rows share a `testCaseName` and carry non-blank `turnIndex` values
- **THEN** they are assembled into one multi-turn test case whose `multiTurnData` is the per-turn columns sorted by `turnIndex`, and whose shared `data` is taken from the (identical) shared columns

#### Scenario: Conflicting shared columns are a conflict
- **WHEN** two turn rows of the same case carry different values for a shared column
- **THEN** a conflict warning is reported and the case is invalidated

#### Scenario: Non-contiguous name is a conflict
- **WHEN** a `testCaseName` reappears non-contiguously, or a `turnIndex` is duplicated within a run
- **THEN** a row/conflict error is reported
