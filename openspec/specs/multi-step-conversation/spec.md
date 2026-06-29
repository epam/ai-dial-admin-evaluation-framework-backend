# Multi-Step Conversation

## Purpose
This spec defines the multi-step (conversational) evaluation contract for `DEPLOYMENT` suites: how a single chat-completions `requestTemplate` is reused across sequential turns, how conversation history accumulates and is re-sent each step, how assistant replies are extracted, fail-fast behavior on step failure, the per-result shape (array-valued `extractedColumns`, accumulated `responseBody`), and how the metric phase normalizes multi-step columns to the last step. This is a POC capability.

Status: **Planned**

## Requirements

### Requirement: Multi-step conversation contract (chat-completions `messages`)
When a `DEPLOYMENT` suite has `multiStep == true`, the request body resolved from `requestTemplate` MUST be JSON containing a top-level `messages` array (chat-completions shape). The engine SHALL treat each conversation step as one turn whose new messages are derived from that step's resolved template body. Non-chat bodies (multipart, url-encoded, or JSON without a top-level `messages` array) are not supported for multi-step.
Status: **Planned**

#### Scenario: Body without a messages array is unsupported
- **WHEN** a suite has `multiStep == true` and its `requestTemplate` body has no top-level `messages` array
- **THEN** suite validation SHALL mark the suite invalid (see test-suites multi-step validation)
- **AND** the suite SHALL NOT be runnable as a multi-step conversation

#### Scenario: Single template reused across steps
- **WHEN** a multi-step suite is configured
- **THEN** the engine SHALL reuse the single, unchanged `requestTemplate` for every step
- **AND** only the bound values (from `multistepInputBindings[i]`) differ between steps

### Requirement: Per-step turn loop with full-history resend
For a multi-step test case, the engine SHALL maintain a running `messages` history `H` (initially empty) and execute steps sequentially for `i` in `0 .. multistepInputBindings.size() - 1`. For each step it SHALL: (1) resolve `requestTemplate` with `multistepInputBindings[i]` and the test-case data; (2) append the resolved body's `messages` to `H`; (3) send the request with its `messages` field overwritten by the full `H` (all other body fields as resolved for that step); (4) append the assistant reply to `H`; (5) extract response columns for that step. The full accumulated history MUST be re-sent on every step.
Status: **Planned**

#### Scenario: Two-step conversation accumulates history
- **WHEN** a test case runs against a 2-step suite and both steps succeed
- **THEN** step 0 SHALL send `messages` = [turn-0 user message]
- **AND** step 1 SHALL send `messages` = [turn-0 user, turn-0 assistant, turn-1 user]
- **AND** the assistant reply from each step SHALL be appended to history before the next step

#### Scenario: Template messages represent the new turn only
- **WHEN** a step's resolved template body contains messages
- **THEN** those messages SHALL be appended verbatim to the running history as that step's new turn
- **AND** the engine SHALL NOT special-case step 0 versus later steps

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
- **AND** `responseBody` SHALL contain the history accumulated through the failed turn
- **AND** `extractedColumns` SHALL contain the per-step maps for the steps completed before the failure

#### Scenario: Failure at step 0 yields an empty extractedColumns array
- **WHEN** a conversation fails at step 0 (before any step completes)
- **THEN** `extractedColumns` SHALL be an empty JSON array `[]`
- **AND** the metric phase SHALL normalize that empty array to an empty JSON object `{}` (see the metric-normalization requirement)

### Requirement: Multi-step result shape reuses existing columns
A multi-step run SHALL persist exactly one `TestCaseRunResult` per `(runId, testCaseId, runIndex)`, reusing existing columns: `responseBody` SHALL hold the accumulated `messages` array as of the last attempted turn, and `extractedColumns` SHALL hold a JSON array of per-step extraction maps (one element per completed step). Single-step runs SHALL keep the existing object shape for `extractedColumns`. The `multiStep` flag is the indicator readers use to interpret the shape.
Status: **Planned**

#### Scenario: Multi-step extractedColumns is an array
- **WHEN** a 3-step conversation completes successfully
- **THEN** `extractedColumns` SHALL be a JSON array of length 3
- **AND** element `i` SHALL be the extraction map for step `i`

#### Scenario: responseBody holds accumulated messages
- **WHEN** a multi-step conversation completes
- **THEN** `responseBody` SHALL be the full `messages` array (all user and assistant turns) as of the last turn

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
