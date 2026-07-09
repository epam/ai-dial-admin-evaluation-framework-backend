## Context

The multi-turn (`multiStep`) POC stores an entire conversation as **one** `test_case_run_results`
row: array-valued test-case columns drive the turns, and the single row carries **column-major
arrays** of per-turn extracted values. Metric bindings select a turn with a `jsonataExpression`
(`$[-1]`), and the executor grew array accumulators, an array/scalar mismatch guard, and
`stepIndex`-tagged warnings. This cannot express "score only the last turn", because the condition
evaluator sees a conversation, not a turn.

This change inverts storage granularity: **one result row (and one eval-summary row) per turn**. Each
turn row holds scalar projected test-case data and scalar extracted columns, so metric evaluation
reverts to the simple single-value path and the JSONata/array machinery is deleted. Two new columns
(`turn_index`, `total_turns`) uniquely key each turn and let `ConditionContext` carry turn position.

**Constraints / current state:**
- The entire `multiStep` POC (the `multi_step` column, all `MultiStep*` classes, binding
  `jsonataExpression` fields) is on branch `feat/17-multistep-support-poc` only — never merged to
  `development`. Its reshaping needs **no** production data/API migration.
- `test_case_run_results` and `test_case_eval_summaries` **are** released for single-turn, so the
  two-column additions need a backfilling migration.
- Postgres 17.4; jOOQ typed DSL with generated sources; JDBC-only; dual datasource (analytics DB owns
  both result tables). Idempotent writes use `INSERT … ON CONFLICT (<natural key>) DO NOTHING`.

## Goals / Non-Goals

**Goals:**
- One `TestCaseRunResult` and one `EvalSummary` per turn, uniquely keyed by `turn_index`.
- Scalar per-turn binding resolution; remove the JSONata/array binding machinery entirely.
- `ConditionContext` carries `turnIndex`/`totalTurns` (enables a future last-turn condition; conditions
  already run per turn as a byproduct).
- Expose `turnIndex`/`totalTurns` on both list DTOs and CSV export; conversation-granular progress.
- Single-turn behavior end-to-end byte-identical (`turnIndex=0, totalTurns=1`).
- Canonical "turn" vocabulary across new surfaces.

**Non-Goals:**
- The `isLastTurn()` (or any) custom condition function — `ConditionFunctionRegistry` stays empty.
- Any Phase-3 / aggregate SQL change (per-turn aggregation is emergent).
- New filter/sort whitelist entries for turn fields; a grouped/nested conversation API.

## Decisions

### D1 — Uniform `turn_index` / `total_turns`, `NOT NULL`, plain unique index
Add `turn_index INTEGER NOT NULL DEFAULT 0` and `total_turns INTEGER NOT NULL DEFAULT 1` to both
analytics tables. `ADD COLUMN … NOT NULL DEFAULT` backfills existing single-turn rows to `0/1` in one
metadata-only statement (PG 11+, no rewrite). Extend both natural-key unique indexes with `turn_index`.
- **Alternatives rejected:** nullable columns + `NULLS NOT DISTINCT` (leaks nullable dual-semantics
  into the object model, or needs a coalescing seam); nullable + partial indexes (two indexes to
  maintain). Uniform sentinels keep the domain model `int` (never null) with no downstream null
  handling, at the cost of new single-turn rows being `0/1` rather than blank — invisible to users.
- **Conventions:** `turn_index` 0-based (matches `run_index`); `total_turns` a count (last turn ⇔
  `turn_index == total_turns - 1`).

### D2 — Executor emits `List<TestCaseRunResult>`; abort persists partial turns
`EvaluationWorker.execute` returns `List<TestCaseRunResult>` (single-turn = one-element list).
`MultiStepConversationExecutor` → `MultiTurnConversationExecutor`, rewritten to build **one scalar
result per turn** via the single-turn result-building path (reusing `ResponseColumnExtractor` +
`TestCaseRunResultFactory`), dropping `MultiStepColumnAccumulator`/`MultiStepWarningAccumulator`/
`MultiStepResultAssembler`.
- **Abort at turn `k`:** persist `k` SUCCESS rows (`0..k-1`) + 1 ERROR row (`k`), all with
  `total_turns = planned N` (known upfront from the turn planner). The last turn is legitimately
  absent when a conversation dies early.
- **Data-shape failure (no turn ran):** one ERROR row, `turn_index=0, total_turns=0` — distinguishes
  "never started" from a real single turn (`0/1`). Harmless w.r.t. conditions (non-SUCCESS rows skip
  metric/condition evaluation).
- **Per-turn row content:** `test_case_data` = per-turn projected scalar view
  (`turnPlan.project(data, i)`); `request_body` = the full accumulated request actually sent for that
  turn; `response_body`/`extracted_columns`/`extraction_warnings`/timing/retry per turn; `trace_id` =
  shared conversation span id on every row.

### D3 — Full JSONata/array rollback in binding resolution
Remove `jsonataExpression` from `ResponseBindingSourceDto`/`TestCaseBindingSourceDto`,
`BindingResolver.applyJsonataSelector` (+ its `JsonataEvaluationService` dep),
`MetricDefinitionValidationService` `INVALID_EXPRESSION` checks 3b/4b, `ArrayBindingTypeMismatchDetector`,
and `ExtractionWarningDto.stepIndex`. `BindingResolver` resolves the scalar column value directly.
`JsonataEvaluationService`/`ResponseColumnExtractor` JSONata usage stays (conditions + extraction).
- **Rationale:** each turn row is already scalar; there is nothing to select or normalize.

### D4 — `ConditionContext` gains `turnIndex`/`totalTurns`
Add primitive `int turnIndex`/`totalTurns` to the `ConditionContext` record, populated in
`InProcessMetricEvaluationExecutor` from the result row. Always valid because conditions only evaluate
on SUCCESS rows (which always carry real values). Conditions now run per turn because each turn is a
result — no code change beyond populating the fields.

### D5 — Conversation-granular progress
`ResultBatchWriter.addResult` → `addResults(buffer, List<TestCaseRunResult>)`: +1 completed-conversation
counter per call (single-turn = one-element list), buffers all turn rows, still flushes by row batch
size, and calls `notifyProgress(conversationsCompleted, totalCases)` with `totalCases =
numberOfTestCases * numberOfRuns` unchanged. Keeps progress 0–100%.
- **Alternatives rejected:** turn-granular denominator (total turns unknown upfront — jumpy);
  leave-as-is (reports >100%).

### D6 — Minimal API/export surface
Add `turnIndex`/`totalTurns` to both list response DTOs (flat rows; frontend groups by
`(testCaseId, runIndex)`) and as CSV identity columns after `runIndex`. No new filter/sort whitelist
entries; no grouped API.

**Within-conversation turn ordering is a client-side concern (chosen over a DB-side keyset change).**
Every result/summary row of a run shares one `created_at_ms`, so the keyset spine
`(created_at DESC, id DESC)` orders a run's rows by (random) `id`; turns of a conversation are not
contiguous in the flat page. Making the DB return turn-ordered rows would require threading
`turn_index` into the shared `Cursor` record + opaque `CursorCodec` and a mixed-direction keyset
comparison. We deliberately keep the keyset spine and opaque-cursor wire format unchanged: the
frontend already groups by `(testCaseId, runIndex)` and sorts each group by `turnIndex` client-side
(the DTO/CSV carry both fields). No repository `ORDER BY` change.

### D7 — "turn" vocabulary; rename handling
New surfaces use "turn": `turn_index`/`total_turns`, `ConditionContext.turnIndex/totalTurns`, DTO
fields, `MultiTurnConversationExecutor`, `MAX_CONVERSATION_TURNS`, `multi_turn`/`multiTurn`,
`isSnapshotMultiTurn()`. To keep delta targeting robust, spec requirement **headers** and the
`multi-step-conversation` **folder** keep their names in the deltas; the mechanical header + folder
rename (`Multi-step`→`Multi-turn`) is done during the sync/archive pass (see Migration Plan + tasks).

### D8 — `multi_step` → `multi_turn` migration
Because `V1.25__AddMultiStepToTestSuites.sql` is unreleased and branch-only, **edit V1.25 in place**
to name the column `multi_turn` (no churn rename migration). Local dev DBs need `flyway repair` /
recreate due to the checksum change.
- **Alternative:** a new `V1.27__RenameMultiStepToMultiTurn.sql` (preserves migration immutability,
  but adds a rename for a column that never shipped). Recommend in-place; confirm at implementation.

## Risks / Trade-offs

- **Unique-index recreation locks large analytics tables** → run the index swap as a
  **non-transactional** Flyway migration using `CREATE UNIQUE INDEX CONCURRENTLY` + drop-old, or accept
  a brief lock if the deployment's tables are small.
- **`ON CONFLICT` target drift** → the result/summary idempotent writers' `ON CONFLICT (<cols>)` list
  MUST be updated in lockstep with the new natural keys, or writes fail. Covered by functional tests.
- **All-turns metric cost + aggregate shift** → with no condition, a metric runs `N`× per conversation
  and Phase-3 aggregates span all turns. Multi-turn only (unreleased), single-turn unaffected — no
  regression; documented interim until a last-turn condition narrows it.
- **`turnPlan.project` correctness** → per-turn projection must broadcast scalars/constants and index
  only array-valued bound columns; reused from existing planner, covered by unit tests.
- **In-place V1.25 edit** → checksum change breaks already-migrated local DBs; acceptable on an
  unmerged branch (repair/recreate).

## Migration Plan

1. Analytics migrations: `V1.13__AddTurnColumnsToTestCaseRunResults.sql`,
   `V1.14__AddTurnColumnsToEvalSummaries.sql` (V1.12 is intentionally left free for development's
   incoming analytics migration, to avoid a rebase/merge collision) — `ADD COLUMN` (backfill) + unique-index swap
   (`CONCURRENTLY` if non-transactional). Meta: edit `V1.25` in place `multi_step`→`multi_turn`.
2. `./gradlew generateJooq`; commit generated diff; update `docs/database-schema.md`.
3. Model/mapper/repo (result + summary): add turn columns, update INSERT column list + `ON CONFLICT`
   target.
4. Executor: worker → `List`; `MultiTurnConversationExecutor` per-turn rows + abort/data-error
   semantics; `InProcessEvaluationExecutor` uses `addResults`.
5. `ResultBatchWriter` conversation-granular progress.
6. Metric-eval simplification: `BindingResolver` scalar-direct; remove jsonata/array classes + DTO
   fields + `INVALID_EXPRESSION`; `ConditionContext` + populate turn fields; EvalSummary envelope +
   turn fields.
7. DTOs + CSV export + order-by; OpenAPI examples.
8. Rename sweep (`step`→`turn`, `multiStep`→`multiTurn`); rewrite AGENTS.md multi-step convention.
9. During sync/archive: rename `multi-step-conversation` spec folder → `multi-turn-conversation`,
   rename "Multi-step" requirement headers, refresh `specs/README.md`.
- **Rollback:** all reshaping is branch-local (revert branch). The additive analytics columns are
  backward-compatible with single-turn readers; dropping them would require the reverse migration.

## Open Questions

- In-place `V1.25` edit vs new rename migration (D8) — recommend in-place; confirm at implementation.
- Index swap `CONCURRENTLY` vs transactional — decide per expected analytics table size at deploy time.
