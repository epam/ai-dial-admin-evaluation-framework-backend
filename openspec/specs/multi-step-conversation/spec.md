# Multi-Step Conversation

## Purpose
This spec defines the multi-step (conversational) evaluation contract for `DEPLOYMENT` suites: how a single chat-completions `requestTemplate` is reused across sequential turns, how conversation history accumulates and is re-sent each step, how assistant replies are extracted, fail-fast behavior on step failure, the per-result shape (array-valued `extractedColumns`, accumulated `responseBody`), and how the metric phase normalizes multi-step columns to the last step. This is a POC capability.

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
The engine SHALL read each step's assistant reply from `choices[0].message.content` of the response and append it to history as `{ "role": "assistant", "content": <value> }`. Multi-step steps SHALL always be invoked non-streaming regardless of any streaming hint.
Status: **Planned**

#### Scenario: Assistant content appended from response
- **WHEN** a step returns a 2xx response with `choices[0].message.content` present
- **THEN** the engine SHALL append an assistant message with that content to the running history

#### Scenario: Missing assistant content aborts the conversation
- **WHEN** a step returns a 2xx response with no extractable `choices[0].message.content`
- **THEN** the engine SHALL treat the step as failed (history cannot continue)
- **AND** the conversation SHALL abort per the fail-fast requirement

### Requirement: Fail-fast on step failure
If any step fails after retries — a non-2xx final status, a timeout/network error, an oversized (truncated) response, or an unextractable assistant reply — the engine SHALL stop the conversation at that step and SHALL NOT send subsequent steps. The persisted result SHALL reflect partial progress.
Status: **Planned**

#### Scenario: Failure at step k stops remaining steps
- **WHEN** step `k` of an `N`-step conversation fails after exhausting retries
- **THEN** steps `k+1 .. N-1` SHALL NOT be sent
- **AND** the result `executionStatus` SHALL be the failing step's status
- **AND** `responseStatusCode` SHALL be the failing step's status code
- **AND** `responseBody` SHALL contain the failing turn's raw response body (or be absent when no response was received)
- **AND** `extractedColumns` SHALL contain the per-step maps for the steps completed before the failure

#### Scenario: Failure at step 0 yields an empty extractedColumns array
- **WHEN** a conversation fails at step 0 (before any step completes)
- **THEN** `extractedColumns` SHALL be an empty JSON array `[]`
- **AND** the metric phase SHALL normalize that empty array to an empty JSON object `{}` (see the metric-normalization requirement)

### Requirement: Multi-step result shape reuses existing columns
A multi-step run SHALL persist exactly one `TestCaseRunResult` per `(runId, testCaseId, runIndex)`, reusing existing columns: `responseBody` SHALL hold the last attempted turn's raw response body (preserving its technical fields, e.g. `id`/`usage`/`model`) — mirroring `requestBody`, which holds that turn's raw request — and `extractedColumns` SHALL hold a JSON array of per-step extraction maps (one element per completed step). The full conversation remains recoverable from the last request body (which carries the whole message history through the final user turn) plus the final response. Single-step runs SHALL keep the existing object shape for `extractedColumns`. The `multiStep` flag is the indicator readers use to interpret the shape.
Status: **Planned**

#### Scenario: Multi-step extractedColumns is an array
- **WHEN** a 3-step conversation completes successfully
- **THEN** `extractedColumns` SHALL be a JSON array of length 3
- **AND** element `i` SHALL be the extraction map for step `i`

#### Scenario: responseBody holds the last turn's raw response
- **WHEN** a multi-step conversation completes
- **THEN** `responseBody` SHALL be the last turn's raw response body, with its technical fields (e.g. `id`) preserved, and `requestBody` SHALL be that turn's raw request body

#### Scenario: Single-step result shape unchanged
- **WHEN** a suite has `multiStep == false`
- **THEN** `extractedColumns` SHALL be a JSON object and `responseBody` SHALL be the single response, exactly as before

### Requirement: Metric evaluation normalizes multi-step columns to the last step
When the metric evaluation phase reads a result's `extractedColumns`, it SHALL normalize by shape: if the value is a JSON array, it SHALL use the last element (`array[n-1]`); if the array is empty (`n == 0`), it SHALL yield an empty JSON object `{}` (it SHALL NOT throw and SHALL NOT produce an array); if it is a JSON object, it SHALL use it as-is. Metric input/config bindings SHALL resolve against the normalized object, and the value copied into the `EvalSummary` SHALL be the normalized (last-step) object.
Status: **Planned**

#### Scenario: Metrics score the last turn for multi-step
- **WHEN** the metric phase processes a multi-step result whose `extractedColumns` is an array of length `n`
- **THEN** metric bindings SHALL resolve against element `n-1`
- **AND** `EvalSummary.extractedColumns` SHALL store element `n-1` as a JSON object

#### Scenario: Empty extractedColumns array normalizes to an empty object
- **WHEN** the metric phase processes a result whose `extractedColumns` is an empty JSON array `[]` (e.g. a conversation that failed at step 0 before any step completed)
- **THEN** normalization SHALL yield an empty JSON object `{}` rather than throwing or producing an array
- **AND** `EvalSummary.extractedColumns` SHALL store `{}`

#### Scenario: Single-step metric behavior unchanged
- **WHEN** the metric phase processes a single-step result whose `extractedColumns` is a JSON object
- **THEN** metric bindings SHALL resolve against that object unchanged
- **AND** the downstream summary, CSV export, and query-filter layer SHALL operate on an object shape as before

## Implementation Notes
- Turn loop lives in a new `service.domain.job.MultiStepConversationExecutor`, delegated to from `EvaluationWorker.execute`.
- Template resolution reuses `service.domain.ResolvedRequestService.resolve`; per-step extraction reuses `service.domain.ResponseColumnExtractor.extract`.
- Metric normalization is applied at the result→metric boundary at **two distinct call sites** via a single shared injectable component (e.g. `service.domain.job.ExtractedColumnsNormalizer`):
  - **Metric binding resolution**: `MetricEvaluationWorker.buildRequest` normalizes the `extractedColumns` value it passes to `BindingResolver.resolveBindings` (the `parseJsonMap(result.getExtractedColumns())` call); the sibling `testCaseData` parse is left untouched.
  - **EvalSummary copy**: `InProcessMetricEvaluationExecutor.buildItem` normalizes the `extractedColumns` value (its private `parseJsonNode(result.getExtractedColumns())` path) before storing it into `EvalSummary`; this path runs for both SUCCESS and propagated non-SUCCESS results.
- Empty-array (`n == 0`) extractedColumns normalize to an empty JSON object `{}` at both sites.
