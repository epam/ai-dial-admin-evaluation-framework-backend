## MODIFIED Requirements

### Requirement: Multi-step conversation contract (chat-completions `messages`)
When a `DEPLOYMENT` suite has `multiStep == true`, the request body resolved from `requestTemplate` MUST be JSON containing a top-level `messages` array (chat-completions shape). The engine SHALL treat each conversation step as one turn whose new messages are derived from the resolved template body. Bindings are the suite's single `inputBindings` (the same field a single-step suite uses); per-turn variation comes from the **data**, not from per-turn binding definitions. Non-chat bodies (multipart, url-encoded, or JSON without a top-level `messages` array) are not supported for multi-step.
Status: **Planned**

#### Scenario: Body without a messages array is unsupported
- **WHEN** a suite has `multiStep == true` and its `requestTemplate` body has no top-level `messages` array
- **THEN** suite validation SHALL mark the suite invalid (see test-suites multi-step validation)
- **AND** the suite SHALL NOT be runnable as a multi-step conversation

#### Scenario: Single template and single bindings reused across steps
- **WHEN** a multi-step suite is configured
- **THEN** the engine SHALL reuse the single, unchanged `requestTemplate` and the single `inputBindings` for every step
- **AND** the values that differ between steps SHALL come from the per-turn element of the array-valued bound columns in the test case's data

### Requirement: Per-step turn loop with full-history resend
For a multi-step test case, the engine SHALL maintain a running `messages` history `H` (initially empty) and execute steps sequentially for `i` in `0 .. N-1`, where `N` is the turn count derived per test case (see the turn-count requirement). For each step it SHALL: (1) build the per-turn data by projecting each array-valued bound column to its `i`-th element (leaving scalar columns and `constantValue` bindings unchanged); (2) resolve `requestTemplate` with the single `inputBindings` and that per-turn data; (3) append the resolved body's `messages` to `H`; (4) send the request with its `messages` field overwritten by the full `H` (all other body fields as resolved for that step); (5) append the assistant reply to `H`; (6) extract response columns for that step. The full accumulated history MUST be re-sent on every step.
Status: **Planned**

#### Scenario: Two-step conversation accumulates history
- **WHEN** a test case whose array-valued bound column has length 2 runs and both steps succeed
- **THEN** step 0 SHALL send `messages` = [turn-0 user message]
- **AND** step 1 SHALL send `messages` = [turn-0 user, turn-0 assistant, turn-1 user]
- **AND** the assistant reply from each step SHALL be appended to history before the next step

#### Scenario: Template messages represent the new turn only
- **WHEN** a step's resolved template body contains messages
- **THEN** those messages SHALL be appended verbatim to the running history as that step's new turn
- **AND** the engine SHALL NOT special-case step 0 versus later steps

## ADDED Requirements

### Requirement: Turn count derived per test case from array-valued bound columns
For a multi-step suite, the number of turns `N` SHALL be derived per test case from that test case's data: `N` equals the common length of all array-valued columns referenced by the suite's `inputBindings` `dataField`s (when there are no array-valued bound columns, `N` is undefined and the no-array failure scenario applies). Columns whose value is a scalar, and `constantValue` bindings, SHALL be reused (broadcast) on every turn. Because `N` is computed per test case, two test cases in the same suite MAY run different numbers of turns. The engine SHALL cap `N` at `MAX_CONVERSATION_STEPS`. A test-case-level data problem SHALL fail only that test case (result `executionStatus = ERROR` with a descriptive message) while other test cases in the run proceed.
Status: **Planned**

#### Scenario: Turn count comes from the array column length
- **WHEN** a multi-step test case binds a template variable to a column whose value is an array of length 3
- **THEN** the engine SHALL run exactly 3 turns, using element `i` of the array on turn `i`

#### Scenario: Different test cases run different turn counts
- **WHEN** one test case's bound array column has length 2 and another's has length 3 in the same suite run
- **THEN** the first SHALL run 2 turns and the second SHALL run 3 turns

#### Scenario: Scalar and constant bindings broadcast across turns
- **WHEN** a multi-step test case has one array-valued bound column and one scalar bound column (or a `constantValue` binding)
- **THEN** the array column SHALL iterate per turn
- **AND** the scalar column / constant SHALL be used unchanged on every turn

#### Scenario: Mismatched array lengths fail only that test case
- **WHEN** a multi-step test case has two array-valued bound columns of different lengths
- **THEN** that test case's result SHALL be `ERROR` with a message identifying the mismatch
- **AND** other test cases in the run SHALL still execute

#### Scenario: No array-valued bound column fails only that test case
- **WHEN** a multi-step test case has no array-valued bound column
- **THEN** that test case's result SHALL be `ERROR` with a descriptive message
- **AND** other test cases in the run SHALL still execute

#### Scenario: Turn count over the cap fails only that test case
- **WHEN** a multi-step test case's derived `N` exceeds `MAX_CONVERSATION_STEPS`
- **THEN** that test case's result SHALL be `ERROR` with a message referencing the cap
- **AND** other test cases in the run SHALL still execute
