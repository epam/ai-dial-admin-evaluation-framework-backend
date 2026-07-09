## Why

The current multi-turn (`multiStep`) POC stores a **whole conversation as one**
`test_case_run_results` row: array-valued test-case columns drive the turns, and the single result
row carries **column-major arrays** of per-turn extracted values. To score a specific turn, metric
bindings had to select an element with a `jsonataExpression` (`$[-1]` etc.), and the executor grew
array accumulators (`MultiStepColumnAccumulator`, `MultiStepWarningAccumulator`), an array/scalar
mismatch guard, and `stepIndex`-tagged warnings. This is complex, and it can't express the real
goal — *"evaluate metrics only on the last turn"* — because the condition evaluator sees a
conversation, not a turn.

This change **inverts the storage granularity**: each turn produces its **own**
`test_case_run_results` row (and therefore its own `test_case_eval_summaries` row). Because each turn
row holds **scalar** projected test-case data and **scalar** extracted columns, metric evaluation
reverts to the simple single-value path — the entire JSONata/array-selection machinery is removed.
Two new columns (`turn_index`, `total_turns`) make each turn row unique and let
`ConditionContext` carry "which turn / how many turns", which is the enabling step for a future
last-turn condition function.

The whole `multiStep` POC (the `multi_step` column, all `MultiStep*` classes, the binding
`jsonataExpression` fields) lives **only on branch `feat/17-multistep-support-poc`** and has never
merged to `development`, so its reshaping needs **no** production data or API migration. The
`test_case_run_results` / `test_case_eval_summaries` tables themselves **are** released for
single-turn, so the two-column additions carry a backfilling migration.

## What Changes

### Data model — per-turn rows
- Add `turn_index INTEGER NOT NULL DEFAULT 0` and `total_turns INTEGER NOT NULL DEFAULT 1` to
  **`test_case_run_results`** and **`test_case_eval_summaries`**. The `ADD COLUMN … NOT NULL DEFAULT`
  backfills existing single-turn rows to `0/1` in one metadata-only statement (PG 11+) — **no
  separate backfill, no table rewrite**.
- Extend both natural-key unique indexes to include `turn_index`:
  - `uq_results_run_case_index` → `(test_suite_run_id, test_case_id, run_index, turn_index, created_at_ms)`
  - `uq_eval_summaries_natural_key` → `(test_suite_run_id, test_case_id, run_index, turn_index, computation_id, created_at_ms)`
  - Columns are `NOT NULL`, so plain unique indexes suffice — **no `NULLS NOT DISTINCT`**.
- Conventions: `turn_index` is **0-based** (matches `run_index`); `total_turns` is a **count**
  (last turn ⇔ `turn_index == total_turns - 1`).

### Executor — emit one result per turn
- `MultiStepConversationExecutor` → **`MultiTurnConversationExecutor`**, rewritten to return a
  `List<TestCaseRunResult>` (one per turn) instead of one aggregated row. Each turn row stores:
  - `test_case_data` = the **per-turn projected scalar view** (element `i` of each array-valued bound
    column; scalars/constants broadcast — today's `turnPlan.project(data, i)`). Required, because
    metric bindings now read the column value directly.
  - `request_body` = the **full accumulated request actually sent** for that turn (`messages[]`
    history through that turn's user message) — faithful and replayable.
  - `response_body` = that turn's raw response; `extracted_columns` = that turn's scalar object;
    `extraction_warnings` = that turn's warnings (no `stepIndex`); timing/retry/`logDetails` per turn;
    `trace_id` = the shared conversation span id on every turn row.
  - `turn_index = i`, `total_turns = planned N` (known upfront from the turn planner).
- `EvaluationWorker.execute` returns `List<TestCaseRunResult>`; the single-turn path returns a
  one-element list.
- **Abort semantics** (conversation fails at turn `k`): persist `k` SUCCESS rows (turns `0..k-1`) +
  **1 ERROR row** (turn `k`), all with `total_turns = planned N`. The last turn is legitimately absent
  when a conversation dies early — a future last-turn metric correctly won't fire on a broken
  conversation.
- **Data-shape failure** (no turn ran — no array-valued bound column, mismatched lengths, over the
  turn cap): **one ERROR row** with `turn_index = 0, total_turns = 0`, cleanly distinguishing "never
  started" (`0/0`) from a real single turn (`0/1`). Harmless w.r.t. conditions — non-SUCCESS rows
  skip metric/condition evaluation.

### Metric evaluation — revert to simple single-value (full JSONata/array rollback)
Remove, everywhere:
- `jsonataExpression` from `ResponseBindingSourceDto` and `TestCaseBindingSourceDto`.
- `BindingResolver.applyJsonataSelector` (+ its `JsonataEvaluationService` dependency) — bindings
  resolve scalar column values directly.
- `MetricDefinitionValidationService` checks 3b/4b (`INVALID_EXPRESSION` for binding
  `jsonataExpression`).
- `ArrayBindingTypeMismatchDetector` (used only by `MetricEvaluationWorker`).
- `MultiStepColumnAccumulator`, `MultiStepWarningAccumulator`, `MultiStepResultAssembler`.
- `ExtractionWarningDto.stepIndex` and its `ConversationOutcome` stamping.

`JsonataEvaluationService` / `ResponseColumnExtractor`'s own JSONata usage **stays** (conditions and
response-column extraction still need it).

### Conditions — per-turn granularity (emergent) + turn info in context
- Add `turnIndex` and `totalTurns` (primitive `int`) to **`ConditionContext`**, populated from each
  result row in `InProcessMetricEvaluationExecutor`.
- Because each turn is now its own `TestCaseRunResult`, the condition evaluator already runs **per
  turn** — this change *delivers* per-turn condition granularity as a byproduct; the condition sees
  each turn's scalar `data`/`response` plus `turnIndex`/`totalTurns`.
- **Out of scope (non-goal):** the `isLastTurn()` function itself — `ConditionFunctionRegistry`
  stays shipped-empty. This change only *populates* the fields.

### Vocabulary — "turn" everywhere (full rename, branch-local)
- `MultiStepConversationExecutor` → `MultiTurnConversationExecutor`; `MAX_CONVERSATION_STEPS` →
  `MAX_CONVERSATION_TURNS`; all `step`/`stepIndex` → `turn`/`turnIndex`.
- `test_suites.multi_step` → `multi_turn`; `SuiteSnapshotDto.multiStep` → `multiTurn`;
  `EvaluationContext.isSnapshotMultiStep()` → `isSnapshotMultiTurn()`. All branch-only, so no external
  breakage.
- Rewrite the AGENTS.md multi-step inline convention in turn vocabulary.

### Progress — conversation-granular
- The result buffer's progress numerator counts **completed conversations**, not rows.
  `ResultBatchWriter.addResult` → `addResults(buffer, List<TestCaseRunResult>)`: +1 conversation per
  call (single-turn = 1-element list), buffers all rows, still flushes by row batch size, and calls
  `notifyProgress(conversationsCompleted, totalCases)`. Stays 0–100%; `totalCases`
  (`numberOfTestCases * numberOfRuns`) unchanged and meaningful.

### API / frontend exposure — minimal
- Add `turnIndex` / `totalTurns` to both list response DTOs (test-case-run-results and
  eval-summaries). **Flat rows** carry the fields; the frontend groups by `(testCaseId, runIndex)`.
  No grouped/nested API — keyset cursor pagination, filtering, sorting untouched.
- CSV export (already per-turn, since it rides on `EvalSummary`) gains two columns: `turnIndex`,
  `totalTurns`.
- Within-conversation ordering is client-side: the frontend groups by `(testCaseId, runIndex)` and
  sorts each group by `turnIndex`. The repository ordering / keyset spine is left untouched (see design D6).
- **No** new `FilterWhitelists` / `SortWhitelists` entries (deferred until the frontend needs them).

Not changing: response extraction semantics, the `is_valid` soft-validation model, RunMetricSnapshot
writing, Phase-3 aggregate SQL, single-turn behavior end-to-end.

## Capabilities

### Modified Capabilities
- `multi-turn-conversation` (the `multiStep`/`multiTurn` POC capability) — storage inverts to one
  result row per turn; array-driven turn resolution stays; per-turn rows carry `turn_index` /
  `total_turns`; the JSONata/array binding machinery is removed.
- `metric-evaluation` — bindings resolve scalar values directly (no turn selection); each turn row
  yields its own eval summary; `ConditionContext` carries `turnIndex`/`totalTurns`; conditions
  evaluate per turn.
- `test-case-results` / `eval-summary-export` — result and summary list DTOs and the CSV export gain
  `turnIndex`/`totalTurns`; results are flat per-turn rows ordered by turn.

_(Exact capability folder names to be reconciled against `openspec/specs/` during the specs
artifact.)_

## Impact

- **API**: `TestCaseRunResultResponseDto`, `EvalSummaryResponseDto`, and the CSV export gain
  `turnIndex`/`totalTurns`. `ResponseBindingSourceDto`/`TestCaseBindingSourceDto` **lose**
  `jsonataExpression` (branch-only, never shipped). List payloads for a multi-turn conversation
  become N flat rows. OpenAPI schema + examples updated for all touched DTOs.
- **Data** (Flyway): analytics —
  `V1.13__AddTurnColumnsToTestCaseRunResults.sql`,
  `V1.14__AddTurnColumnsToEvalSummaries.sql` (add columns + recreate unique index; V1.12 left free for
  development's incoming analytics migration). Meta —
  `multi_step` → `multi_turn` on `test_suites` (see Rollout for in-place vs rename-migration). jOOQ
  regenerated (`./gradlew generateJooq`); `docs/database-schema.md` updated.
- **Config**: none.
- **Security**: none.
- **New/renamed classes**: `MultiTurnConversationExecutor` (renamed + rewritten). **Removed**:
  `MultiStepColumnAccumulator`, `MultiStepWarningAccumulator`, `MultiStepResultAssembler`,
  `ArrayBindingTypeMismatchDetector`. **Modified**: `EvaluationWorker`, `InProcessEvaluationExecutor`,
  `ResultBatchWriter`(+`Transactional`), `TestCaseRunResult`(+mapper/repo), `EvalSummary`
  (+mapper/repo/`EvalSummaryBatchWriteItemDto`), `InProcessMetricEvaluationExecutor`,
  `ConditionContext`, `BindingResolver`, `MetricEvaluationWorker`, `MetricDefinitionValidationService`,
  the two binding-source DTOs, `SuiteSnapshotDto`, `EvaluationContext`, order-by builder.

### Status: Implemented vs Planned
- **Planned** (this change): everything above.
- **Explicit non-goals (deferred):** the `isLastTurn()` custom condition function (registry stays
  empty); any Phase-3 / aggregate change; new filter/sort whitelist entries for turn fields; a
  grouped/nested conversation API.

### Interim consequence (documented, accepted)
With **no condition set, a metric runs on every turn**, so a metric provider is called `N`× per
conversation and Phase-3 aggregates average across **all turns** of all conversations. **Single-turn
suites are unaffected** (1 turn = 1 result). This is the interim behavior for the still-unreleased
multi-turn path until a last-turn condition narrows it — no regression.

## Risks

- **Unique-index recreation on large analytics tables.** Dropping/recreating
  `uq_results_run_case_index` / `uq_eval_summaries_natural_key` in a transactional Flyway migration
  takes an exclusive lock and a full scan. Mitigation: run the index swap as a **non-transactional**
  migration using `CREATE UNIQUE INDEX CONCURRENTLY` + drop-old, or accept a brief lock if the
  deployment's tables are small. Decide in design/tasks.
- **`ON CONFLICT` target must match the new index.** The idempotent result/summary writers reference
  the natural-key constraint; the insert `ON CONFLICT (...)` column list must be updated in lockstep
  with the index, or writes fail.
- **All-turns metric cost / aggregate shift** — see interim consequence above; multi-turn only,
  unreleased, documented.
- **`multi_step` → `multi_turn` migration edit** — see Rollout.

## Rollout plan

- **Branch-only reshaping.** All `multiStep` POC code is unmerged; the JSONata/array removal and the
  `turn` rename need no production migration.
- **`multi_step` → `multi_turn`:** because `V1.25__AddMultiStepToTestSuites.sql` is unreleased and
  branch-only, prefer **editing V1.25 in place** to name the column `multi_turn` (no churn migration);
  local dev DBs need a `flyway repair` / recreate due to the checksum change. Alternative: a new
  `V1.27__RenameMultiStepToMultiTurn.sql`. Recommend in-place; confirm at tasks time.
- **Order:** column additions → jOOQ regen → model/mapper/repo → executor + writer + progress →
  metric-eval simplification + ConditionContext → DTO/export/order-by → rename sweep → docs + AGENTS.md.

## Test plan

- **Unit:** `MultiTurnConversationExecutor` emits N rows with correct `turn_index`/`total_turns`;
  abort → `k` SUCCESS + 1 ERROR, `total_turns = N`; data-shape failure → single `0/0` ERROR;
  `BindingResolver` resolves scalars directly (no JSONata); record mappers round-trip the two columns;
  `ResultBatchWriter.addResults` increments conversations once per call;
  `InProcessMetricEvaluationExecutor` populates `ConditionContext.turnIndex/totalTurns` and emits one
  summary per turn.
- **Functional (`@PostgresFunctionalTests`):** a multi-turn run writes N unique per-turn result +
  summary rows (unique-index holds); conversation-granular progress stays ≤100%; CSV export renders
  per-turn rows with `turn`/`totalTurns`; a condition evaluates per turn; **single-turn regression** —
  a non-multi-turn suite behaves byte-identically end-to-end with `turn_index=0, total_turns=1`.
