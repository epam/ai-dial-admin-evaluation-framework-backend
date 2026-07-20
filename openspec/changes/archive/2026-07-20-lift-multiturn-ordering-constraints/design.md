## Context

Multi-turns are an ordered group of `test_cases` rows sharing a `multi_turn_id`, with a `turn_index`. Today the run pipeline treats a multi-turn as runnable only when its surviving (valid ∧ not-disabled ∧ filter-matching) turns form a **contiguous prefix `0..k`**. Any deviation — no turn 0, a gap, a filtered-out or disabled middle turn — is classified **broken** at snapshot time and produces a single `0/0` sentinel ERROR row at execution.

The break is driven entirely by `MultiTurnAssembler.assemble`:

```java
boolean anyInvalid = survivors.stream().anyMatch(t -> !t.isValid());
boolean contiguous = isContiguousFromZero(survivors);         // the ordering/sequencing rule
boolean broken = anyInvalid || !contiguous || survivors.size() > MAX_MULTI_TURN_TURNS;
```

Everything downstream (`SnapshotInputWriter`, `MultiTurnExecutor`, `EvaluationWorker`) consumes the assembler's output; none of them re-derive ordering. The one non-obvious coupling is `turn.last` in conditional-metric execution: `ConditionExpressionEvaluator` computes `turn.last = (turnIndex + 1) == totalTurns`, which is only correct because contiguity guarantees the last survivor's authored index equals `count - 1`.

This design lifts the ordering/sequencing constraint while keeping the ability to (a) fail a multi-turn loudly when a surviving turn is invalid or the turn count is excessive, and (b) evaluate `turn.last` correctly once gaps exist.

## Goals / Non-Goals

**Goals:**
- Allow any turn — at the start, middle, or end — to be disabled or filtered out; run whatever survives, in authored `turn_index` order.
- Preserve the client-authored `turn_index` in results (no renumbering), so original turn position stays visible.
- Keep `turn.last` (and `turn.index` / `turn.total`) correct for non-contiguous survivors.
- Keep a loud, visible failure for a genuinely unusable multi-turn (invalid surviving turn, or over-cap).
- No change to the public REST/CSV contract.

**Non-Goals:**
- Exposing `last_turn_index` on response DTOs or the CSV export (internal correctness carrier only).
- Adding `last_turn_index` to `test_case_eval_summaries` (deferred; conditions read from `test_case_run_results`, not summaries).
- Making `MAX_MULTI_TURN_TURNS` configurable (stays a non-configurable constant).
- Changing single-turn behavior, MCP behavior, the full-history-resend contract, or fail-fast turn semantics.
- Reworking counting (`RunnableTestCaseCounter`) — the guard already counts individual runnable turns; gaps do not change that.

## Decisions

### D1 — Drop contiguity; `broken = anyInvalid || survivors > cap`
Remove `isContiguousFromZero` and the `contiguous` term from `MultiTurnAssembler`. Survivors are still sorted ascending by authored `turn_index` and run in that order. A missing turn 0, a gap, or a filtered-out middle turn simply yields a shorter survivor list.

**Alternative considered — keep contiguity, add a "compact" suite flag:** rejected. It reintroduces a suite-level gate for behavior that is already fully expressible through `disabledTestCaseIds`/`testCaseFilter`, contradicting the "multi-turn is emergent" principle.

### D2 — Invalid surviving turn still breaks the whole multi-turn (visible error)
An `is_valid = false` turn among the survivors keeps breaking the multi-turn → one `0/0` sentinel ERROR row, no model call, run continues. This is a deliberate divergence from single-turn (which silently excludes invalid rows): a half-run multi-turn with a silently-dropped invalid middle turn would be misleading, so we surface it.

**Alternative considered — drop invalid turns like disabled ones:** rejected by product decision; the error must be visible in results.

### D3 — Preserve authored `turn_index`; add `last_turn_index` (no renumbering)
Result rows keep the authored `turn_index`. Because gaps break the `(turnIndex + 1) == totalTurns` identity, introduce `lastTurnIndex` = the maximum authored index among survivors, computed once in `MultiTurnAssembler` and carried on `AssembledMultiTurn`. `MultiTurnExecutor` stamps it onto every `TestCaseRunResult` of the multi-turn. `total_turns` keeps its meaning — the count of surviving turns that ran.

**Alternative considered — renumber survivors to dense `0..k`:** rejected by product decision; renumbering hides the original authored position from results/exports. Preserving authored indices + a `lastTurnIndex` carrier keeps all three of `turn.index`/`turn.total`/`turn.last` honest and independent.

**Alternative considered — overload `total_turns = maxIndex + 1`:** rejected; it silently redefines an already-exported/DTO-surfaced column and conflates "turns that ran" with "authored span."

### D4 — `last_turn_index` is a persisted column on `test_case_run_results`
The condition dictionary is built at metric-evaluation time in `InProcessMetricEvaluationExecutor` **from the persisted result row**, not from the snapshot-time `AssembledMultiTurn`. A single turn row cannot know the multi-turn's max authored index unless it is stored. So `last_turn_index` must live on the result row. Migration `V{n}__AddLastTurnIndexToTestCaseRunResults.sql` adds `last_turn_index INTEGER NOT NULL DEFAULT 0`; it is not part of the idempotency key (which already includes `turn_index`). Regenerate jOOQ and commit.

**Alternative considered — recompute at metric-eval time by loading sibling turns:** rejected; it turns a per-row evaluation into a per-multi-turn load and duplicates grouping logic already owned by the assembler.

### D5 — `turn.last = (turnIndex == lastTurnIndex)`
`ConditionContext` gains `lastTurnIndex`; `InProcessMetricEvaluationExecutor` populates it from `result.getLastTurnIndex()`; `ConditionExpressionEvaluator` redefines `turn.last`. A single-turn result is `turnIndex = 0`, `lastTurnIndex = 0` → `turn.last = true` (unchanged behavior). `turn.index` (authored) and `turn.total` (surviving count) are unchanged.

### D6 — Cap moves from write-time index bound to assembly-time survivor count
Remove the `turnIndex < MAX_MULTI_TURN_TURNS` check from `MultiTurnFieldsValidator` (with gaps, a high index no longer implies many turns). Keep both-or-neither, valid UUID, `turnIndex >= 0`, and the duplicate `(multi_turn_id, turn_index)` 409. The cap becomes a **surviving-turn-count** guard in `MultiTurnAssembler` (`survivors.size() > MAX_MULTI_TURN_TURNS` ⇒ broken). `MAX_MULTI_TURN_TURNS` remains a constant.

### D7 — Sentinel-row wording
`EvaluationWorker.buildBrokenMultiTurnResult` keeps producing the `0/0` ERROR row but the `BROKEN_MULTI_TURN` message is reworded to only reference the two remaining causes (an invalid turn, or too many turns) — no mention of turn 0 / contiguity. The sentinel row carries `last_turn_index = 0`.

## Component interaction flow

```
Snapshot tx:
  SnapshotInputWriter
    → RunnableTestCaseSelector.loadMultiTurnTurns(filter applied in SQL)
    → MultiTurnAssembler.assemble(turns, excludedIds)
         survivors = filter-matching − disabled, sorted by authored turn_index
         broken = anyInvalid(survivors) || survivors.size() > MAX_MULTI_TURN_TURNS
         lastTurnIndex = max authored index among survivors      ← NEW
         → AssembledMultiTurn{ turnsJson, broken, totalTurns=count, lastTurnIndex }  ← NEW field
    → TestCaseRunInput (turns JSONB, broken)

Execution:
  EvaluationWorker.execute
    broken → buildBrokenMultiTurnResult (0/0 ERROR, last_turn_index=0, reworded msg)
    turns  → MultiTurnExecutor.execute
               per turn: TestCaseRunResult{ turnIndex=authored, totalTurns=count,
                                            lastTurnIndex }                          ← NEW
Metric eval:
  InProcessMetricEvaluationExecutor.evaluateAndBuild
    ConditionContext{ dataJson, responseJson, turnIndex, totalTurns, lastTurnIndex } ← NEW
  ConditionExpressionEvaluator
    turn.last = (turnIndex == lastTurnIndex)                                         ← CHANGED
```

Transaction boundaries are unchanged — the assembler runs inside the existing snapshot transaction; the new column write rides the existing analytics batch insert.

## Risks / Trade-offs

- **Behavior change for existing datasets** → a multi-turn that used to be `broken` (missing turn 0 / gap / middle hole) now runs and emits real per-turn rows. Mitigation: called out as BREAKING in the proposal; covered by functional tests that assert the new run outcome; the snapshot version stays `"2"` (no snapshot-JSON shape change), and reruns re-snapshot cleanly.
- **`last_turn_index` default on historical rows** → `DEFAULT 0` on existing rows makes them look like `turnIndex == lastTurnIndex` (i.e. `turn.last = true`). Mitigation: acceptable — conditions are re-evaluated per run against fresh result rows; historical rows are not re-fed through the evaluator, and `0/1/true` matches prior single-turn semantics.
- **Invalid-turn asymmetry** (single-turn drops invalid, multi-turn breaks on invalid) → could surprise. Mitigation: documented in the spec and the sentinel error message; it is the explicit product decision (D2).
- **Unbounded authored index** now allowed at write time → a client could author `turn_index = 10_000`. Mitigation: harmless — only the survivor *count* is capped, and the count guard runs at assembly.

## Migration Plan

1. Add Flyway analytics migration `V{next}__AddLastTurnIndexToTestCaseRunResults.sql` (`ADD COLUMN last_turn_index INTEGER NOT NULL DEFAULT 0`).
2. `./gradlew generateJooq`; commit `src/main/java-generated/` diff.
3. Ship code changes (assembler, executor, worker, condition context/evaluator, metric executor, validator).
4. Update `docs/database-schema.md`, `docs/patterns/suite-run-snapshot.md`, AGENTS.md multi-turn paragraph.

**Rollback:** the column is additive with a default; reverting code leaves an unused column (safe). A follow-up drop migration can remove it if desired. No data backfill required.

## Open Questions

None — all decisions (renumbering vs authored, invalid-turn handling, cap disposition, DTO/summary exposure) are resolved above.
