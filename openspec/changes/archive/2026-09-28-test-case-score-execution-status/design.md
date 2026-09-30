## Context

`test_case_eval_scores` (see `metrics-storage`/`eval-summary-scoring`) stores a per-row `score`/
`passed`, broadcast identically to every `test_case_eval_summaries` row of a test case for a
computation. Today that score is computed purely from `test_case_metric_scores_aggregated`'s numeric
metric averages (`eval-summary-scoring`, `test-case-metric-score-aggregation`) and never looks at
whether the underlying rows actually succeeded. A metric execution failure already flips a row's own
`execution_status` to `FAILED` (`InProcessMetricEvaluationExecutor.checkForErrors`), and a
condition-skipped metric (`ConditionError`/`isSkip()`, see `conditional-metric-execution`) never
does — but nothing today rolls that per-row signal up to the test-case grain the score is computed
at, so a test case with a failed metric can still get a (misleadingly complete-looking) score.

Constraints from the user's decisions (captured in proposal.md and confirmed during planning):
- `test_case_metric_scores_aggregated`'s existing "absent metric key = no numeric sample, never a
  zero/null entry" contract must not change.
- The new aggregate collapses to `SUCCESS`/`FAILED` only (no `TIMEOUT`/`ERROR` distinction at the
  test-case grain).
- The current write gate stays: no `overallScoreDefinition` ⇒ no `test_case_eval_scores` rows at
  all, `execution_status` included.
- The DB has only ever been migrated locally so far, so the existing `V1.21` migration is edited in
  place instead of adding a new versioned migration.
- `execution_status` must be written in the same upsert as `score`/`passed` — never a separate
  follow-up `UPDATE`.

## Goals / Non-Goals

**Goals:**
- Give `test_case_eval_scores` an `execution_status` column reflecting whether *any* of a test
  case's rows (across turns/requests/reruns, for one computation) failed.
- Stop computing `score`/`passed` for a test case whose aggregate is `FAILED` — write `null` for
  both instead of a partial/misleading number, without running the score SQL for those test cases.
- Ensure a test case with zero numeric metric samples but a `FAILED` row still gets a row in
  `test_case_eval_scores` (today it would be silently absent).
- Keep this change additive to the write path's existing fail-soft, per-flush-batch shape — one more
  lightweight query per batch, not a redesign of the flush cycle.

**Non-Goals:**
- Distinguishing `TIMEOUT`/`ERROR`/`FAILED` at the test-case aggregate grain (deferred; SUCCESS/FAILED
  is sufficient for the scoring-gate use case).
- Changing `test_case_metric_scores_aggregated`'s schema or aggregation contract.
- Exposing `execution_status` through any new REST endpoint — `test_case_eval_scores` has none today
  and this change does not add one; only the existing LEFT JOIN read surface and the
  `test_case_eval_scores` Query DSL entity are extended.
- Changing behavior when no `overallScoreDefinition` is configured (still: no rows written at all).

## Decisions

### 1. A separate `TestCaseExecutionStatusAggregator`, not a `test_case_metric_scores_aggregated` column
**Decision:** Compute the per-test-case `execution_status` aggregate with a new, small component that
queries `test_case_eval_summaries` directly (`GROUP BY test_case_id`,
`bool_or(execution_status <> 'SUCCESS')`), independent of `TestCaseMetricScoreAggregator`.

**Why not fold it into `test_case_metric_scores_aggregated`:** that aggregator's CTE drives its
result set from `jsonb_each(metric_values)` — a row whose `metric_values = '{}'` (every metric
condition-skipped, or the row failed before any metric ran) never appears in that join at all, so
the aggregator has no way to see such a row. Reworking it to also drive from the base table would
change its documented "absent means no numeric sample" contract and risk regressing
`eval-summary-scoring`'s existing coalesce-to-zero behavior for metrics. A separate query sidesteps
this entirely: it scans `test_case_eval_summaries` directly, so it sees every row regardless of
whether it produced any numeric metric value.

**Alternative considered:** add `execution_status` to `test_case_metric_scores_aggregated` and change
`TestCaseMetricScoreAggregator` to always emit one row per test case (driven by a `test_case_id`
list from the base table, left-joined with the metric-stats CTE). Rejected: bigger blast radius on an
already-implemented, separately-specified table/contract for a benefit (one fewer query per batch)
that doesn't outweigh the risk of an unrelated regression.

### 2. Collapse to SUCCESS/FAILED at the aggregate level
**Decision:** `TestCaseExecutionStatusAggregator` returns only `ExecutionStatus.SUCCESS` or
`ExecutionStatus.FAILED` per test case, via `bool_or(execution_status <> 'SUCCESS')`.

**Why:** the only thing the score-gating logic needs is a binary "did anything fail" signal; the
finer-grained `TIMEOUT`/`ERROR` distinction that exists per-row has no defined meaning once several
rows with potentially different statuses are collapsed to one value for a test case (e.g. one row
`TIMEOUT`, another `FAILED` — there is no natural "worse" ordering to justify picking one to
propagate). Reusing the existing `ExecutionStatus` enum (rather than a new two-value type) keeps the
column's vocabulary consistent with `test_case_eval_summaries.execution_status`, as requested.

### 3. Query only for `SUCCESS` test cases, skip the score SQL entirely for `FAILED` ones
**Decision:** `InProcessMetricEvaluationExecutor.writeRowScores` first computes
`Map<UUID, ExecutionStatus> statusByTestCase`, then calls
`EvalSummaryRowScoreComputer.computeByTestCase(...)` only with the subset of test case ids whose
status is `SUCCESS`.

**Why:** "don't calculate the score if a metric failed" is explicit in the requirement — this also
avoids wasted SQL work for a value that would be discarded anyway. `toScoreItem` still forces
`score = null` defensively even though `scoresByTestCase` will simply have no entry for a `FAILED`
test case, keeping the null-forcing logic in one obvious place rather than relying on map-absence.

### 4. Write a `test_case_eval_scores` item for every FAILED test case, plus every SUCCESS test case with a computed score
**Decision:** `writeRowScores` builds a `TestCaseEvalScoreBatchWriteItemDto` for every buffered item whose
test case is `FAILED` (unconditionally), or whose test case is `SUCCESS` **and** appears in
`scoresByTestCase` (i.e. the score query actually returned a value for it).

**Why:** this is the mechanism that fixes the "test case with zero numeric metric samples was silently
absent from `test_case_eval_scores`" gap for the FAILED case, without also writing a spurious row for a
SUCCESS test case with nothing numeric (e.g. every metric condition-skipped) — that case must stay absent
from `test_case_eval_scores`, exactly as it stays absent from `test_case_metric_scores_aggregated`,
preserving the pre-existing "these two tables cover the same test cases" invariant for the SUCCESS case.
Only the FAILED case is a deliberate, intentional expansion beyond that invariant.

### 5. Edit `V1.21` migration in place
**Decision:** add the `execution_status` column and its backfill directly into
`V1.21__AddRunCaseContextToTestCaseEvalScores.sql` rather than a new `V1.22` migration.

**Why:** per the user's explicit direction — the analytics schema has only ever been applied
locally so far, so there is no deployed Flyway history to preserve, and this keeps all of
`test_case_eval_scores`'s denormalized-context columns defined in one migration. Anyone with a
locally migrated DB needs to drop/recreate it (or `flyway repair` + re-migrate) after pulling this
change; this is called out in `tasks.md` as an operational note, not a spec requirement (per the
"specs are functional-only" rule).

### 6. Single upsert, no follow-up UPDATE
**Decision:** `execution_status` is set in the same `insertInto(...).set(...)` chain (and the same
`onConflict(EVAL_SUMMARY_ID).doUpdate()` clause) that already sets `score`/`passed`/`computed_at_ms`
in `PostgresTestCaseEvalScoreRepository.saveAll`.

**Why:** explicit user requirement — one write per row via the existing batched upsert, not a second
round trip.

## Risks / Trade-offs

- **[Risk]** One additional SQL query per Phase-2 flush batch (`TestCaseExecutionStatusAggregator`),
  on top of the existing metric-aggregation and score queries → **Mitigation:** it is a simple
  `GROUP BY`/`bool_or` scan scoped to the batch's distinct test case ids (same scoping as the two
  existing per-batch queries), and it replaces work that was previously wasted (computing a score
  SQL for test cases that would then be excluded anyway is not currently done, so this is a net-new
  but cheap query, consistent with this write path's existing "a few extra queries per batch" shape).
- **[Risk]** Collapsing SUCCESS/FAILED loses the specific failure reason (TIMEOUT vs ERROR vs FAILED)
  at the test-case grain → **Mitigation:** the specific reason remains available per-row on
  `test_case_eval_summaries.execution_status`; `test_case_eval_scores.execution_status` is
  deliberately a coarser, scoring-focused signal, not a replacement for the row-level detail.
- **[Risk]** Editing an already-created migration file changes its checksum, which would break
  Flyway on any environment that already recorded the old checksum → **Mitigation:** explicitly
  accepted by the user because the schema has only been applied locally so far; call out the
  drop/re-migrate step in `tasks.md`.
- **[Trade-off]** A test case whose every row is `FAILED` now gets a `test_case_eval_scores` row
  where it previously had none (when it also had zero numeric samples) → this is the intended
  behavior change, but note it changes what "row exists" means for any future direct consumer of the
  Query DSL `test_case_eval_scores` entity that assumed "no row = no attempt". Documented in
  `docs/patterns/eval-summaries-read-surface.md`.

## Migration Plan

1. Edit `V1.21__AddRunCaseContextToTestCaseEvalScores.sql` to add and backfill `execution_status`.
2. Run `./gradlew generateJooq`; commit regenerated sources.
3. Implement `TestCaseExecutionStatusAggregator`, wire it into
   `InProcessMetricEvaluationExecutor.writeRowScores`/`toScoreItem`.
4. Thread `executionStatus` through the model/DTO/repository/entity-resolver layers.
5. Update docs (`docs/database-schema.md`, `docs/patterns/eval-summaries-read-surface.md`,
   `docs/patterns/overall-score-definition.md`) and the `eval-summary-scoring` delta spec.
6. Anyone with a locally migrated DB drops/recreates it (no production data at risk — local-only so
   far). No rollback path is defined beyond reverting the commit, since there is no deployed
   environment to roll back.

## Open Questions
None outstanding — all prior open decisions (separate-aggregation vs. folded-in, SUCCESS/FAILED
collapsing vs. detailed status, write-gate behavior, migration strategy) were resolved with the user
during planning and are captured as Decisions above.
