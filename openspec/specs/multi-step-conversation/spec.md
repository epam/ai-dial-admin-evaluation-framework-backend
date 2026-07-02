# Multi-Step Conversation

## Purpose
This spec defines the multi-step (conversational) evaluation contract for `DEPLOYMENT` suites: how a single chat-completions `requestTemplate` is reused across sequential turns, how conversation history accumulates and is re-sent each step, how assistant replies are extracted, fail-fast behavior on step failure, the per-result shape (column-major `extractedColumns` — one per-turn array per response column — and accumulated last-turn `responseBody`), and how the metric phase reads those columns raw (turn selection is a per-binding `jsonataExpression` concern). This is a POC capability.

Status: **Planned**

## Requirements

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
For a multi-step test case, the engine SHALL maintain a running `messages` history `H` (initially empty) and execute steps sequentially for `i` in `0 .. N-1`, where `N` is the turn count derived per test case (see the turn-count requirement). For each step it SHALL: (1) build the per-turn data by projecting each array-valued bound column to its `i`-th element (leaving scalar columns and `constantValue` bindings unchanged); (2) resolve `requestTemplate` with the single `inputBindings` and that per-turn data; (3) append the resolved body's `messages` to `H`; (4) send the request with its `messages` field overwritten by the full `H` (all other body fields as resolved for that step); (5) append the assistant reply — the full `choices[0].message` object of the response, verbatim — to `H`; (6) extract response columns for that step. The full accumulated history MUST be re-sent on every step.
Status: **Planned**

#### Scenario: Two-step conversation accumulates history
- **WHEN** a test case whose array-valued bound column has length 2 runs and both steps succeed
- **THEN** step 0 SHALL send `messages` = [turn-0 user message]
- **AND** step 1 SHALL send `messages` = [turn-0 user, turn-0 assistant, turn-1 user]
- **AND** the full assistant message (`choices[0].message`) from each step SHALL be appended verbatim to history before the next step

#### Scenario: Template messages represent the new turn only
- **WHEN** a step's resolved template body contains messages
- **THEN** those messages SHALL be appended verbatim to the running history as that step's new turn
- **AND** the engine SHALL NOT special-case step 0 versus later steps

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

### Requirement: Assistant reply extraction (hardcoded OpenAI path, non-streaming)
The engine SHALL append each step's assistant turn to the running history as the full `choices[0].message` object of the response, verbatim — preserving the fields it contains (e.g. `role`, `content`, `tool_calls`, `refusal`, `reasoning_content`, structured/array `content`, and any provider-specific fields). It SHALL NOT reconstruct a reduced `{ "role": "assistant", "content": <value> }` message from `choices[0].message.content`. The `choices[0].message` path is hardcoded (OpenAI-shaped). Multi-step steps SHALL always be invoked non-streaming regardless of any streaming hint. A step is considered to have no usable reply — aborting the conversation per the fail-fast requirement — only when the response has no `choices[0].message` object (missing `choices`, an empty `choices` array, or a `message` that is not a JSON object); a `message` object without a string `content` (e.g. a tool-call turn) is a valid turn. Because the response body is serialized with the shared `NON_NULL` object mapper before the message is read, a reply whose `content` is JSON `null` is appended with `content` absent (functionally equivalent for resend); all present fields are preserved.
Status: **Planned**

#### Scenario: Full assistant message appended verbatim
- **WHEN** a step returns a 2xx response whose `choices[0].message` contains fields beyond `role`/`content` (e.g. `tool_calls`, `refusal`)
- **THEN** the engine SHALL append that `message` object to the running history verbatim, preserving those extra fields

#### Scenario: Tool-call turn without string content is a valid turn
- **WHEN** a step returns a 2xx response whose `choices[0].message` is a message object with no string `content` (e.g. a tool-call-only message)
- **THEN** the engine SHALL append that `message` object to history and SHALL NOT abort the conversation

#### Scenario: Plain-content message appended verbatim
- **WHEN** a step returns a 2xx response whose `choices[0].message` is `{ "role": "assistant", "content": "..." }`
- **THEN** the engine SHALL append that `message` object to history verbatim

#### Scenario: Missing message object aborts the conversation
- **WHEN** a step returns a 2xx response with no `choices[0].message` object (missing `choices`, an empty `choices` array, or a `message` that is not a JSON object)
- **THEN** the engine SHALL treat the step as failed (history cannot continue)
- **AND** the conversation SHALL abort per the fail-fast requirement

### Requirement: Fail-fast on step failure
If any step fails after retries — a non-2xx final status, a timeout/network error, an oversized (truncated) response, or a 2xx response with no assistant `message` object — the engine SHALL stop the conversation at that step and SHALL NOT send subsequent steps. The persisted result SHALL reflect partial progress.
Status: **Planned**

#### Scenario: Failure at step k stops remaining steps
- **WHEN** step `k` of an `N`-step conversation fails after exhausting retries
- **THEN** steps `k+1 .. N-1` SHALL NOT be sent
- **AND** the result `executionStatus` SHALL be the failing step's status
- **AND** `responseStatusCode` SHALL be the failing step's status code
- **AND** `responseBody` SHALL contain the failing turn's raw response body (or be absent when no response was received)
- **AND** `extractedColumns` SHALL be a column-major object whose per-column arrays each contain one element per step completed before the failure

#### Scenario: Failure at step 0 yields an empty extractedColumns object
- **WHEN** a conversation fails at step 0 (before any step completes)
- **THEN** `extractedColumns` SHALL be an empty JSON object `{}`
- **AND** metric bindings SHALL resolve against that empty object with no normalization step

### Requirement: Multi-step result shape reuses existing columns
A multi-step run SHALL persist exactly one `TestCaseRunResult` per `(runId, testCaseId, runIndex)`, reusing existing columns: `responseBody` SHALL hold the last attempted turn's raw response body (preserving its technical fields, e.g. `id`/`usage`/`model`) — mirroring `requestBody`, which holds that turn's raw request — and `extractedColumns` SHALL hold a **column-major JSON object** mapping each response column name to an array of that column's per-step extracted values (one element per completed step, each element type-reconciled per step). The full conversation remains recoverable from the last request body (which carries the whole message history through the final user turn) plus the final response. Single-step runs SHALL keep the existing object-of-scalars shape for `extractedColumns`. The `multiStep` flag is the indicator readers use to interpret the shape.
Status: **Planned**

#### Scenario: Multi-step extractedColumns is a column-major object
- **WHEN** a 3-step conversation with response columns `answer` and `score` completes successfully
- **THEN** `extractedColumns` SHALL be a JSON object
- **AND** `extractedColumns.answer` SHALL be an array of length 3 whose element `i` is `answer` extracted at step `i`
- **AND** `extractedColumns.score` SHALL be an array of length 3 whose element `i` is `score` extracted at step `i`

#### Scenario: Per-step extraction failure preserves index alignment
- **WHEN** a 3-step conversation completes but extraction of column `answer` fails at step 1
- **THEN** `extractedColumns.answer` SHALL be an array of length 3 whose element at index 1 is JSON `null`

#### Scenario: responseBody holds the last turn's raw response
- **WHEN** a multi-step conversation completes
- **THEN** `responseBody` SHALL be the last turn's raw response body, with its technical fields (e.g. `id`) preserved, and `requestBody` SHALL be that turn's raw request body

#### Scenario: Single-step result shape unchanged
- **WHEN** a suite has `multiStep == false`
- **THEN** `extractedColumns` SHALL be a JSON object of scalar values and `responseBody` SHALL be the single response, exactly as before

### Requirement: Metric evaluation reads raw multi-step columns (no normalization)
The metric evaluation phase SHALL read a result's `extractedColumns` **as stored**, with no shape normalization. Both single-step (object of scalars) and multi-step (column-major object of per-column arrays) values are used directly: metric config/input bindings resolve against the raw object, and the value copied into the `EvalSummary` is the raw object. Turn/element selection for a multi-step column is a per-binding concern, done via a `Response` binding's optional `jsonataExpression` (see the metric-evaluation binding-resolution requirement).
Status: **Planned**

#### Scenario: Metric bindings resolve against the raw multi-step object
- **WHEN** the metric phase processes a multi-step result whose `extractedColumns` is `{ "answer": ["Paris","Tokio"] }`
- **THEN** a `Response` binding to `answer` SHALL resolve against `["Paris","Tokio"]` (selecting a turn only if it carries a `jsonataExpression`)
- **AND** `EvalSummary.extractedColumns` SHALL store `{ "answer": ["Paris","Tokio"] }` unchanged

#### Scenario: Single-step metric behavior unchanged
- **WHEN** the metric phase processes a single-step result whose `extractedColumns` is a JSON object of scalars
- **THEN** metric bindings SHALL resolve against that object unchanged
- **AND** the downstream summary, CSV export, and query-filter layer SHALL operate on the object shape (array-valued cells for multi-step render as compact JSON)

## Implementation Notes
- Turn loop lives in a new `service.domain.job.MultiStepConversationExecutor`, delegated to from `EvaluationWorker.execute`.
- Template resolution reuses `service.domain.ResolvedRequestService.resolve`; per-step extraction reuses `service.domain.ResponseColumnExtractor.extract`, transposed into a column-major object by `MultiStepConversationExecutor`.
- There is **no** metric-boundary normalization (the former `ExtractedColumnsNormalizer` was removed): `MetricEvaluationWorker.buildRequest` and `InProcessMetricEvaluationExecutor.buildItem` read `extractedColumns` as stored. Turn/element selection is a per-binding concern via a `Response` binding's optional `jsonataExpression` (see metric-evaluation).
