## Why

Today a multi-turn is only runnable when its enabled/valid turns form a **contiguous prefix `0..k`**: a filtered-out or disabled turn at the start or in the middle marks the whole multi-turn **broken** (a single `0/0` sentinel ERROR row, no model call). This is an artificial restriction — the suite's `testCaseFilter` and `disabledTestCaseIds` are legitimate authoring tools, and a user who filters out turn 0, or a middle turn, has expressed a clear intent that we currently refuse. We should honor it: run whatever turns survive, in their authored order.

## What Changes

- **Ordering/sequencing constraint removed.** Contiguity from `0` is no longer required. A multi-turn's surviving turns (valid ∧ not-disabled ∧ filter-matching) run in ascending authored `turn_index` order regardless of gaps, a missing turn 0, or a filtered-out middle/start turn.
- **`broken` narrows to two causes.** A multi-turn now breaks (still one visible `0/0` ERROR sentinel row, no model call, run continues) **only** when (1) a surviving turn is invalid (`is_valid = false`), or (2) the surviving-turn **count** exceeds `MAX_MULTI_TURN_TURNS`. Missing turn 0, gaps, and non-contiguous authored indices are **no longer** break causes.
- **Authored `turn_index` preserved (no renumbering).** Result rows keep the client-authored `turn_index` (e.g. survivors `0, 2, 5` report `turn_index` `0, 2, 5`), so the original position stays visible in results/exports.
- **New `lastTurnIndex` carrier for `turn.last` correctness.** `turn.last` is currently derived as `(turnIndex + 1) == totalTurns`, which silently breaks once gaps exist. A new `last_turn_index` (max authored surviving index) is added to the assembled unit, persisted on each analytics result/summary row, threaded into the condition context, and `turn.last` is redefined as `turnIndex == lastTurnIndex`. `total_turns` keeps its current meaning: **the count of surviving turns that ran**.
- **Turn cap relocated.** The write-time `turnIndex < MAX_MULTI_TURN_TURNS` check (meaningless once gaps are allowed — a high index no longer implies many turns) is **removed**; the cap becomes a **surviving-turn-count** guard at assembly (over-cap ⇒ broken). Write-time validation keeps both-or-neither, `turnIndex >= 0`, and the duplicate `(multiTurnId, turnIndex)` 409.
- **All-turns-removed unchanged.** A multi-turn with zero survivors (every turn disabled/filtered out) still drops silently — no execution unit, not a broken row.
- **BREAKING (result-shape, opt-in surface):** a multi-turn that previously reported `broken` due to a missing turn 0 / middle hole will now **run** and emit real per-turn result rows. Datasets relying on the old "middle hole ⇒ broken" behavior change outcome.

## Capabilities

### New Capabilities

_None._ This change modifies existing behavior only.

### Modified Capabilities

- `test-cases`: drop the write-time `turnIndex < MAX_MULTI_TURN_TURNS` bound from the multi-turn-grouping-fields validation (keep both-or-neither, valid UUID, `turnIndex >= 0`, duplicate 409). The cap is no longer a per-row concern.
- `multi-turn`: drop the "contiguous prefix `0..k`" requirement — surviving turns run in ascending authored `turn_index` order with gaps allowed and authored indices preserved; the per-turn result row gains `last_turn_index` (max authored surviving index) while `turn_index` stays authored and `total_turns` stays the surviving count.
- `suite-run-snapshot`: rework snapshot-time integrity — a filtered/disabled start or middle turn no longer breaks the multi-turn (its survivors assemble in order); `broken` narrows to invalid-surviving-turn OR surviving-count-over-cap; the assembled unit carries `lastTurnIndex`.
- `conditional-metric-execution`: redefine the `turn.last` semantics in the condition dictionary from `index == total - 1` to `index == lastTurnIndex` so it remains correct when surviving turns are non-contiguous; `turn.index` (authored) and `turn.total` (surviving count) are unchanged.

### Analytics schema note

The new `last_turn_index` column is added to `test_case_run_results` only (the correctness-critical carrier for `turn.last`). It is **not** exposed on response DTOs or the CSV export, and is **not** added to `test_case_eval_summaries`, to keep the API/export contract unchanged; those are deliberate non-goals (see design).

## Impact

- **Production classes:** `MultiTurnAssembler` (drop contiguity check, add `lastTurnIndex` to `AssembledMultiTurn`, `broken = anyInvalid || survivors > cap`); `MultiTurnExecutor` (stamp `lastTurnIndex`, keep authored `turnIndex`); `EvaluationWorker` (reword `BROKEN_MULTI_TURN` message, set `last_turn_index` on the sentinel row); `ConditionContext` (add `lastTurnIndex`); `ConditionExpressionEvaluator` (redefine `turn.last`); `InProcessMetricEvaluationExecutor` (pass `result.lastTurnIndex`); `MultiTurnFieldsValidator` (drop MAX bound); `ValidationConstants` (`MAX_MULTI_TURN_TURNS` retained, now enforced only at assembly).
- **Analytics schema:** new `last_turn_index INTEGER NOT NULL DEFAULT 0` on `test_case_run_results` only. Flyway migration `V{major}.{minor}__AddLastTurnIndexToTestCaseRunResults.sql` (analytics), followed by `./gradlew generateJooq` and a committed generated-sources diff. Not part of any unique/idempotency key (those already include `turn_index`).
- **Analytics model / mapper:** `TestCaseRunResult` model gains `lastTurnIndex`; its RecordMapper and the batch-write insert are updated. No response-DTO or CSV-export change (internal correctness column only).
- **Config:** none (`MAX_MULTI_TURN_TURNS` is a non-configurable constant, not a property).
- **Docs:** AGENTS.md multi-turn inline-conventions paragraph, `docs/patterns/suite-run-snapshot.md`, `docs/database-schema.md`.
- **Tests:** `MultiTurnAssemblerTest`, `EvaluationWorkerTest`, `SnapshotInputWriterTest`, `MultiTurnExecutorTest`, `MultiTurnFieldsValidatorTest`, `ConditionExpressionEvaluatorTest`, `MultiTurnRunFunctionalTests` (contiguity cases flip broken→runs; invalid-turn and over-cap stay broken; new `turn.last` / `last_turn_index` coverage under gaps).
