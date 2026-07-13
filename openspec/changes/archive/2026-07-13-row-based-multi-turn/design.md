## Context

Multi-turn conversations are currently modeled as **one** test-case row whose columns hold **arrays**; `TurnPlan.project(data, i)` unwraps element `i` per turn, `ConversationTurnPlanner` derives the turn count from the common length of array-valued bound columns, and a suite-level `multiTurn` flag toggles the behavior. This forces a per-binding "which field iterates" flag and breaks dataset-schema typing (a field is sometimes scalar, sometimes array). The analytics side already stores per-turn results (`turn_index`/`total_turns` on `test_case_run_results` / `test_case_eval_summaries`, natural key includes `turn_index`).

This change re-models a conversation as an **ordered group of discrete test-case rows** (one row per turn), keyed by `conversationId` + `turnIndex`. It is a feature-branch POC; the array-based path is removed outright. Constraints: layered architecture (web/service/data), JDBC-only via jOOQ, UUIDs as `VARCHAR(36)`, meta vs analytics dual datasource, `Clock`-injected time, config defaults in `application.yml`.

## Goals / Non-Goals

**Goals:**
- Model multi-turn as row-per-turn; conversation = ordered group by `conversationId`+`turnIndex`.
- Eliminate array columns, the iterate-flag, and schema-type ambiguity — every column is a scalar validated against the dataset schema.
- Mixed single- and multi-turn test cases in one dataset.
- Keep authoring thin (raw `conversationId`/`turnIndex` fields; client-managed integrity) and push contiguity/completeness validation to snapshot.
- Preserve the existing per-turn result/summary contract and the `turn`-aware condition context.

**Non-Goals:**
- No conversation-as-first-class resource / grouped write endpoints (raw fields only this round).
- No generalized accumulation config (`historyPath`/`responseTurnExpr`) — chat-completions assumption kept; keep the accumulator isolated so it can be generalized later.
- No MCP multi-turn (tool-call sequences) — rejected at run creation, forward-compatible.
- No re-sequencing of surviving turns (disable is tail-only, not gap-healing).

## Decisions

### D1 — Conversation = ordered group of rows keyed by `conversationId` + `turnIndex`
Two nullable top-level columns on `test_cases`: `conversation_id VARCHAR(36)` (client-supplied UUID) and `turn_index INTEGER`. Both NULL ⇒ single-turn (all existing rows; backward compatible). Not stored inside `data` JSONB (kept out of schema validation and template variables).
- **Alternatives:** conversation as a first-class entity/table (heavier, deferred); encode grouping in `test_case_name` (fragile, collides with the name index) — both rejected.

### D2 — `conversationId` is a client-supplied UUID
Authored across independent create calls, so the client must supply the grouping key; server-generation can't link rows across calls without a conversation-create endpoint (out of scope). UUID over free-form string for format validation and consistency with existing id conventions.
- **Trade-off:** UUIDs are awkward to hand-author/CSV-paste vs a business key; accepted for consistency.

### D3 — Client-managed integrity; contiguity validated at snapshot
Write-time checks are per-row and cheap (both-or-neither, `turnIndex >= 0`, `turnIndex < MAX_CONVERSATION_TURNS`, schema-validate `data`). Completeness/contiguity is NOT checked at write (turns arrive across requests) — it is enforced ONCE at the snapshot phase.
- **Consequence:** a mis-authored conversation persists fine and fails at run time as a single skipped conversation, not a 400 at authoring.

### D4 — Snapshot assembles + validates per conversation; execution unit = conversation
Snapshot pages by **distinct `conversation_id`** (never straddling a page boundary), groups turn rows, validates, and freezes each conversation into **one** assembled `test_case_run_inputs` row (ordered turns, each with `test_case_id`, `turn_index`, scalar `data`). Single-turn = length-1. Dispatch stays one-task-per-input (× `runIndex`); turns run sequentially inside, reusing the accumulated-messages executor.
- **Why:** proving "continuous and exhaustive" once, centrally, keeps the executor simple (it never re-checks ordering). Deterministic `position` via `min(created_at_ms)` then `conversation_id`.
- **Alternative:** one input row per turn, grouped at execution enumeration — rejected (re-introduces grouping + page-straddle in the executor).

### D5 — Turn `turnIndex` is the ordering source of truth
Explicit user-supplied `turn_index`, never inferred from `created_at_ms`/`id` (both non-deterministic: uniform timestamps within a batch, random-UUID tiebreak, no CSV file-order preservation).

### D6 — Broken conversation ⇒ skip + one ERROR result row (0/0 sentinel)
Broken = missing turn 0, gap/non-contiguous, duplicate `turn_index`, any invalid turn, or surviving count > `MAX_CONVERSATION_TURNS`. Snapshot writes a "broken marker" input; the execution phase emits one ERROR row (`turn_index=0, total_turns=0`) without invoking the model. Other conversations proceed; the run is not aborted. Keeps all result writes in the execution phase.
- **Alternatives:** fail the whole run (too harsh); silent skip + run-level warning (not visible per-conversation) — rejected.

### D7 — Exclusion semantics are conversation-scoped
- **Invalid turn (`is_valid=false`) anywhere ⇒ whole conversation broken** (invalid never truncates; it surfaces the authoring error).
- **Disable (per-row `disabledTestCaseIds`) is tail-only:** enabled turns must form a contiguous prefix `0..k`; a trailing disabled run truncates; a middle hole ⇒ broken. Chosen so "disable the last N turns" works without gap-healing/re-sequencing.
- **Filter (`testCaseFilter`) is all-match:** a conversation is included only if ALL its turns match (atomic; no holes). The runnable selector aggregates per `conversation_id` (`GROUP BY ... HAVING all match`), a change from the current per-row predicate.

### D8 — Counting operates on runnable conversations
Guard #4 (zero-runnable), `number_of_test_cases`, and progress count runnable **conversations** (multi-turn groups + standalone rows), not raw turn rows. Progress stays conversation-granular (`conversationsCompleted`), already the case today.

### D9 — Full removal of the array path
Remove the suite `multiTurn` flag + its `SuiteSnapshotDto` field, `TurnPlan`, `ConversationTurnPlanner`, the array-projection branch of `MultiTurnConversationExecutor`, the JSONata/array-binding metric machinery, and `SuiteValidationService.validateMultiTurnBody`. Metric evaluation reverts to the simple per-turn scalar binding path; `ConditionContext.turnIndex`/`totalTurns` are sourced from the conversation grouping. `turn_index`/`total_turns` result columns are retained.

### D10 — Scope guards
Multi-turn is HTTP-deployment only. An MCP_TOOL suite bound to a dataset containing any conversation rows is rejected at run creation with **409 INVALID_OPERATION** (a cheap `EXISTS` check), forward-compatible for future tool-call sequences. No run-creation template-capability guard — a template lacking a `messages` array fails per-conversation at run time as an ERROR row (keeps the suite side lean).

## Risks / Trade-offs

- **[Persisted-but-broken conversations]** Client-managed integrity means broken conversations live in the dataset until run time → Mitigation: snapshot validates centrally and surfaces each as a visible 0/0 ERROR row; per-row write checks catch the cheap cases early.
- **[Noisier test-case listing]** One row per turn inflates the flat `GET /test-cases` list and interleaves turns under the default `createdAt DESC` sort → Mitigation: expose `conversationId`/`turnIndex` so the frontend can group; no grouped endpoint this round (accepted).
- **[Filter cost]** All-match filtering requires per-`conversation_id` aggregation instead of a flat per-row predicate → Mitigation: index on `(dataset_id, conversation_id)`; single-turn rows keep the fast per-row path.
- **[Snapshot page-straddle]** A conversation split across snapshot pages would break grouping → Mitigation: page by distinct `conversation_id`, load each group's turns fully.
- **[Global unique names]** Every turn needs a distinct `test_case_name` → Mitigation: authoring convention (`conv1 / turn 0`); a defensive partial unique on `(dataset_id, conversation_id, turn_index)` prevents duplicate indices regardless.
- **[Disabled-middle-turn surprise]** Disabling a middle turn breaks the conversation rather than healing the gap → documented; tail-only is the supported disable shape.

## Migration Plan

- Isolated feature branch: **reshape the branch's existing multi-turn migrations in place** rather than stacking new ones, so throwaway DB ops are not promoted to production (`flyway_history` cleared locally). Meta: `test_cases` gains `conversation_id`, `turn_index`; add index `(dataset_id, conversation_id)` and partial unique `(dataset_id, conversation_id, turn_index) WHERE conversation_id IS NOT NULL`; `test_suites` drops `multi_turn`; `test_case_run_inputs` reshaped to assembled per-conversation inputs. Analytics: none (`turn_index`/`total_turns` already present).
- Run `./gradlew generateJooq` and commit generated sources; update `docs/database-schema.md`.
- Rollback: branch-local; revert the branch. No production migration is promoted.

## Open Questions

- None blocking. Deferred by decision: generalized accumulation (`historyPath`/`responseTurnExpr`) and MCP tool-call sequences (guarded off, to be designed later).
