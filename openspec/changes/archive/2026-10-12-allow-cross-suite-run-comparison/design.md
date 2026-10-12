## Context

See proposal.md — Why. `RunComparisonService.compare` runs `requireSameSuite` right after loading both runs via
`TestSuiteRunService.getRun`. Everything after it is scoped per run and never reads `test_suite_id`:

- computation: `ComputationResolver.resolve(null, runId)`
- metric fields: `RunMetricSnapshotRepository.findByRunIdAndComputationId` (meta) → `MetricFieldDiscoverer`
- matching: `PostgresEvalSummaryRepository.countMatches` / `findUnmatchedIds` / `findUnmatchedTestCaseIds` — keyed
  on `(test_suite_run_id, computation_id)` plus `lower(test_case_name)`, `run_index`, `request_index`,
  `turn_index`
- recomputation: `FilteredMetricScoreAggregator`, binding only `:runId` / `:computationId`, with
  `overallScore` and `metricScoreAggregation` taken from each run's own suite snapshot

So removing the guard is behaviour-neutral for every other step.

## Goals / Non-Goals

**Goals:**
- Delete the guard; touch nothing else in the execution path.

**Non-Goals:**
- Any replacement guard, mismatch signal, response field or match-key change.

## Decisions

### 1. Remove the guard rather than replace it with a same-dataset guard

The target scenario (A/B over a shared dataset) could be expressed as "same suite OR same
`suiteSnapshot.datasetRef.id`". Rejected: it adds a branch and a legacy-snapshot rule (v1 snapshots carry no
`datasetRef`) while protecting nothing technical; the client already sees overlap through `totalRowCount` /
`matchedRowCount` and decides what a comparison means. A strict same-dataset guard was also rejected — it would
break today's same-suite comparisons whose dataset was re-bound between runs.

### 2. Keep name-based matching

Within one dataset `test_case_id` is stable too, but the existing name key already serves both same-suite and
cross-suite pairs, keeps the delete-then-restore behaviour, and needs no change.

### 3. Guard order

With the suite guard gone the order is 400 → 404 → 422 → 409 (computation) → 409 (cap). No call is reordered in
code — the suite check simply disappears from between `getRun` and `requireSnapshot`.

## Risks / Trade-offs

- [Unrelated runs produce meaningless-looking numbers] → Overlap is reported per run (zero overlap returns
  empty `scores`); interpretation is the client's by design.
- [Name-based matching falsely pairs generic names across unrelated datasets] → Two datasets that both hold
  e.g. `case 1` yield a non-zero `matchedRowCount` over unrelated test cases. Accepted: the target scenario is a
  shared dataset, where names are unique per dataset and pairing is exact; the client owns interpretation.
- [Low-overlap pairs hit the `max-unmatched-rows` 409] → Pre-existing, already-defined outcome with a message
  naming count and limit; operators can raise `ANALYTICS_COMPARISON_MAX_UNMATCHED_ROWS`. No change.
- [Error contract relaxation] → A request that previously got 409 now gets 200. No client depends on the 409 as
  a feature; not treated as breaking.

## Migration Plan

None — no schema, data or config change. Rollback is reverting the commit.
