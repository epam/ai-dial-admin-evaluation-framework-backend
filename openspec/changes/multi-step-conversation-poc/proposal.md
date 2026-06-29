## Why

The evaluation engine today executes exactly **one** request per test case: populate the request template, invoke the deployment, extract response columns, score metrics. Many real assistant use cases are **multi-turn** — the quality of turn _N_ depends on the conversation that preceded it. We need a proof-of-concept that lets a suite drive a fixed, scripted sequence of turns against a chat-completions deployment, accumulating conversation history, so we can evaluate multi-turn behavior end-to-end before investing in the full feature (dynamic turns, response-referencing bindings, per-step metrics).

This is explicitly a **POC**: scope is deliberately minimal and several production concerns are deferred (see Non-Goals).

## What Changes

- **New suite configuration** (`DEPLOYMENT` suites only):
  - `multiStep` (boolean, default `false`) — marks a suite as multi-turn.
  - `multistepInputBindings` (`List<List<InputBindingDto>>`) — one binding list per conversation step; `list[i]` populates the (unchanged, single) `requestTemplate` for step _i_. Total steps = `multistepInputBindings.size()`.
  - When `multiStep == true`, the engine uses **only** `multistepInputBindings`; the existing single `inputBindings` is ignored (and vice-versa). Mutually exclusive.
- **New execution path**: a `MultiStepConversationExecutor` runs a sequential turn loop per test case:
  - Maintains a running chat-completions `messages` history; **full history is re-sent on every turn**.
  - Each step populates the template, appends that step's `messages` (the new turn — author owns its shape) to history, sends the full history (**non-streaming only**), reads the assistant reply from **`choices[0].message.content`** (hardcoded for the POC), and appends it as an `assistant` message.
  - Runs `ResponseColumnExtractor` per step and accumulates a **per-step array** of extraction maps.
  - **Fail-fast**: any step that fails after retries — or whose assistant reply is not extractable (history cannot continue) — aborts the conversation; the result is persisted with partial history and partial per-step extractions.
- **Result persistence reuses existing columns** (no new `test_case_run_results` columns):
  - `responseBody` = the accumulated `messages` array as of the last turn.
  - `extractedColumns` = a JSON **array** of per-step maps (single-step runs remain a JSON object). The `multiStep` flag is the switch readers use to interpret the shape.
- **Metrics** (in scope) normalize via **shape detection** at the result→metric boundary: if `extractedColumns` is an array, metrics evaluate against the **last element** (`array[n-1]`); if an object, as-is. `EvalSummary.extractedColumns` therefore stores **the last step's object only**, leaving the entire summary / CSV export / query-filter layer unchanged.
- **Snapshot** carries the two new fields **additively** — `SuiteSnapshotDto.CURRENT_VERSION` stays `"2"` (missing fields default to single-step). No snapshot migration.
- **Validation** (kept simple): when `multiStep == true`, any violation marks the suite invalid via the existing warning mechanism — body must be JSON with a top-level `messages` array; `multistepInputBindings` must be non-empty; each step's bindings validate against template placeholders + test-case schema. New step-count cap of **10** in `ValidationConstants`.
- **DB**: `test_suites` gains `multi_step` (BOOLEAN NOT NULL DEFAULT false) and `multistep_input_bindings` (JSONB, nullable) via a Flyway migration; jOOQ regenerated.

## Capabilities

### New Capabilities
- `multi-step-conversation`: End-to-end POC requirements for multi-turn conversation evaluation — the chat-completions `messages` contract, the per-step turn loop with full-history resend, hardcoded `choices[0].message.content` assistant extraction, non-streaming-only, fail-fast semantics, the reused-column result shape (accumulated `messages` in `responseBody`, per-step array in `extractedColumns`), and last-step metric normalization via shape detection.

### Modified Capabilities
- `test-suites`: Adds the `multiStep` + `multistepInputBindings` configuration fields and the multi-step validation rules (messages-array body required, non-empty per-step bindings, 10-step cap, mutual exclusivity with `inputBindings`).
- `eval-execution-engine`: Adds the multi-step branch — `EvaluationWorker` delegates to `MultiStepConversationExecutor` when the snapshot's `multiStep` is true; one semaphore permit per conversation (steps sequential); last step's `traceId` persisted.

## Impact

- **New class**: `service.domain.job.MultiStepConversationExecutor` (delegated from `EvaluationWorker`).
- **Models / DTOs**: `data.db.model.TestSuite` (+`multiStep`, +`multistepInputBindings`); `TestSuiteRequestDto`, `TestSuiteResponseDto`, `SuiteSnapshotDto` (+ both fields, `multistepInputBindings` typed `List<List<InputBindingDto>>`).
- **Mappers**: `JsonbMapper` (new `List<List<InputBindingDto>>` ser/deser pair); `TestSuiteMapper` (MapStruct); `TestSuiteRecordMapper`; `SuiteSnapshotBuilder`.
- **Validation**: `SuiteValidationService.validateDeploymentSuite` (messages-array + per-step binding checks); `ValidationConstants` (new `MAX_CONVERSATION_STEPS = 10`).
- **Metrics**: `BindingResolver.parseJsonMap` (array→last-element normalization) and the `InProcessMetricEvaluationExecutor` path that copies columns into `EvalSummary` — both via shape detection. The downstream summary/export/query layer (`EvalSummaryExportRow`, `EvalSummaryExportColumnPlanner`, `JsonbFieldResolver`) is **unaffected** because `EvalSummary` keeps an object shape.
- **DB / migration**: new meta Flyway migration `V{next}__AddMultiStepToTestSuites.sql`; `./gradlew generateJooq`; update `docs/database-schema.md`.
- **Run creation**: `TestSuiteRunService.createRun` runnable-test-case guard is **unchanged** (a multi-step test case still counts once; it simply issues N calls during execution).
- **Docs / tests**: OpenAPI `@Schema` examples for the new fields; unit tests (executor loop, fail-fast, validation, JSONB list-of-lists, metric shape-detection normalization); at least one functional test booting the context for a multi-step run.

### Out of Scope (POC — deferred)
- Input bindings referencing **prior steps' response** fields (the stated long-term goal).
- Streaming (SSE) responses for multi-step.
- Configurable / non-OpenAI assistant-reply path.
- First-class per-step result rows, per-step metrics, per-step `traceId` (last-step only).
- Cross-step retry aggregation (only the failing step's retries are captured).
- Any UI work (backend only).
