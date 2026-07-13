## Why

The current multi-turn design encodes a whole conversation in **one** test-case row whose columns hold **arrays**; `TurnPlan.project` unwraps element `i` per turn. This forces two awkward constraints: a per-binding "which field iterates" flag is needed to tell iterating columns from broadcast ones, and the dataset schema can no longer type a field cleanly (it is sometimes a scalar, sometimes an array). It also concentrates a conversation into a single unit that cannot be authored, disabled, or reasoned about turn-by-turn.

Modeling **each turn as its own test-case row** (a conversation = an ordered group of rows keyed by `conversationId` + `turnIndex`) removes both constraints: every column is a plain scalar validated against the dataset schema, per-turn data is fully independent, and single- and multi-turn test cases coexist in one dataset. The analytics/result side already carries `turn_index`/`total_turns`, so this change is concentrated in authoring + snapshot + dispatch. This is a feature-branch POC, so the array-based path is removed outright rather than kept in parallel.

## What Changes

- **BREAKING** — Remove the array-based multi-turn path: the suite-level `multiTurn` flag (and its `SuiteSnapshotDto` field), `TurnPlan`, `ConversationTurnPlanner`, the array-projection branch of `MultiTurnConversationExecutor`, and the JSONata/array-binding metric machinery. Multi-turn becomes **emergent from data** — the runner dispatches multi-turn when it sees grouped rows, with no suite flag.
- **Test-case model**: add two nullable top-level columns — `conversation_id VARCHAR(36)` (client-supplied UUID, validated) and `turn_index INTEGER`. `NULL/NULL` = single-turn (every existing row, backward compatible). Neither lives inside the `data` JSONB.
- **Test-case API**: raw `conversationId`/`turnIndex` fields on `TestCaseRequestDto`, `TestCaseResponseDto`, and `TestCaseBatchPutItemDto` (client-managed grouping — no new conversation resource). Bulk-patch whitelist unchanged (`{testCaseName, data}`). Write-time validation: both-or-neither, `turnIndex >= 0`, `turnIndex < MAX_CONVERSATION_TURNS`, `data` validated against the dataset schema (now uniform, no array ambiguity).
- **CSV import/export**: two reserved headers `conversationId`/`turnIndex` (same reserved-column pattern as `testCaseName`); simple round-trip, per-row validation only.
- **Selection/validity semantics** (a conversation is the unit): any invalid turn → whole conversation broken/skipped; per-row disable is **tail-only** (a middle hole breaks the conversation); `testCaseFilter` includes a conversation only if **all** its turns match. A broken conversation (missing turn 0, gap, duplicate index, any invalid turn, over-cap) is skipped and surfaced as one **ERROR result row (0/0 sentinel)**; the run continues.
- **Snapshot & runner**: snapshot pages by distinct `conversation_id`, groups + validates contiguity + freezes each conversation into **one assembled `test_case_run_inputs` row** (ordered turns with per-turn `test_case_id` + scalar `data`); single-turn = length-1. Dispatch stays one-task-per-input (× `runIndex`); turns run sequentially within, reusing the accumulated-messages (chat-completions) executor. Runnable **conversations** (not rows) drive counting/guards/progress. `total_turns` = surviving count; `turn.last` fires on the last surviving turn.
- **Scope guards**: multi-turn is HTTP-deployment only; an MCP suite bound to a dataset containing conversation rows is **rejected at run creation (409)** — forward-compatible for future tool-call sequences. The chat-completions assumption is kept (no generalized `historyPath`/`responseTurnExpr` this round); accumulation stays isolated so it can be generalized later.
- **Migrations**: because this is an isolated feature branch, the branch's existing multi-turn migrations are **reshaped in place** (no throwaway DB ops promoted to production); the `turn_index`/`total_turns` result columns are retained.
- **Frontend**: `conversationId`/`turnIndex` exposed on test-case responses (results already expose `turnIndex`/`totalTurns`); no grouped endpoints.

## Capabilities

### New Capabilities
_None — this change modifies existing capabilities._

### Modified Capabilities
- `multi-turn-conversation`: replace the array-per-column model with the row-per-turn model (conversation = ordered group keyed by `conversationId` + `turnIndex`); remove the suite `multiTurn` flag; multi-turn becomes emergent from grouped rows.
- `test-cases`: add `conversationId`/`turnIndex` fields, write-time validation, and CSV reserved columns; `data` schema validation is now uniform (no array shapes).
- `suite-run-snapshot`: group runnable rows by `conversation_id`, validate contiguity/completeness, and freeze each conversation into one assembled input; broken conversations become 0/0 ERROR rows.
- `eval-execution-engine`: execution unit is a conversation (one task per input × runIndex); counting/progress/guards operate on runnable conversations; broken-conversation ERROR-row handling.
- `suite-test-case-filter`: filter is evaluated per conversation (include only if all turns match), not per row.
- `test-suites`: remove the `multiTurn` suite flag and its snapshot field.
- `metric-evaluation`: roll back the JSONata/array-binding path — metrics evaluate per-turn scalar values (turn context sourced from row grouping).

## Impact

- **DB (meta)**: `test_cases` gains `conversation_id`, `turn_index`; new defensive partial unique `(dataset_id, conversation_id, turn_index) WHERE conversation_id IS NOT NULL`; index for grouping. `test_suites` drops `multi_turn`. `test_case_run_inputs` reshaped to assembled per-conversation inputs. All done by **reshaping existing branch migrations** (Flyway `V{major}.{minor}__…`), then `./gradlew generateJooq`. Update `docs/database-schema.md`.
- **DB (analytics)**: none — `turn_index`/`total_turns` already exist on results/summaries.
- **APIs**: `TestCase*Dto` gain two fields; OpenAPI examples updated; new 409 at run creation for MCP + conversation rows. `TestSuite*Dto` lose `multiTurn`.
- **Code removed**: `TurnPlan`, `ConversationTurnPlanner`, array-projection executor branch, JSONata/array-binding metric machinery, `SuiteValidationService.validateMultiTurnBody`.
- **Code added/changed**: snapshot grouping/assembly/contiguity validator, conversation-aware `RunnableTestCaseSelector`/`RunnableTestCaseCounter` (aggregate per `conversation_id`), CSV reserved-column handling, run-creation MCP guard, `ConditionContext` turn fields sourced from grouping.
- **Constants**: reuse `MAX_CONVERSATION_TURNS` (write cap + snapshot cap).
- **Config**: none expected.
- **Tests**: functional tests for authoring (fields, validation, CSV round-trip), snapshot grouping/contiguity/broken handling, disable tail-truncation, filter all-match, MCP rejection, per-turn metric/condition evaluation.
