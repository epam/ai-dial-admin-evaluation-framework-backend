## MODIFIED Requirements

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

### Requirement: Assistant reply extraction (hardcoded OpenAI path, non-streaming)
The engine SHALL append each step's assistant turn to the running history as the full `choices[0].message` object of the response, verbatim — preserving every field it contains (e.g. `role`, `content`, `tool_calls`, `refusal`, `reasoning_content`, structured/array `content`, and any provider-specific fields), including an explicit `content: null`. It SHALL NOT reconstruct a reduced `{ "role": "assistant", "content": <value> }` message from `choices[0].message.content`. The appended message SHALL be stored such that explicit JSON `null` values survive serialization of the resent history (i.e. a `null` field is emitted as JSON `null`, not dropped). The `choices[0].message` path is hardcoded (OpenAI-shaped). Multi-step steps SHALL always be invoked non-streaming regardless of any streaming hint. A step is considered to have no usable reply — aborting the conversation per the fail-fast requirement — only when the response has no `choices[0].message` object (missing `choices`, an empty `choices` array, or a `message` that is not a JSON object); a present `message` object whose `content` is `null` is a valid turn.
Status: **Planned**

#### Scenario: Full assistant message appended verbatim
- **WHEN** a step returns a 2xx response whose `choices[0].message` contains fields beyond `role`/`content` (e.g. `tool_calls`, `refusal`) and/or an explicit `content: null`
- **THEN** the engine SHALL append that `message` object to the running history verbatim, preserving those extra fields and the explicit `content: null`

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
- **AND** `extractedColumns` SHALL contain the per-step maps for the steps completed before the failure

#### Scenario: Failure at step 0 yields an empty extractedColumns array
- **WHEN** a conversation fails at step 0 (before any step completes)
- **THEN** `extractedColumns` SHALL be an empty JSON array `[]`
- **AND** the metric phase SHALL normalize that empty array to an empty JSON object `{}` (see the metric-normalization requirement)
