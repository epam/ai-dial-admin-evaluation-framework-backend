# Computation Versioning (No `is_latest` flag)

Analytics entities versioned by `computation_id` (UUID) use append-only writes. "Latest" is resolved at query time (`ORDER BY computed_at_ms DESC LIMIT 1`); API callers pass `computation=<uuid>` or `computation=latest` (or omit).

**Resolution reads `test_case_eval_summaries`, not `run_metric_snapshots`** — `ComputationResolver.resolve` delegates to `EvalSummaryRepository.findLatestComputationId` (backed by `idx_eval_summaries_run_computed_at`), so "latest" means "latest computation with readable rows" and a metric-less run still resolves; do not re-point it at the snapshot table.

The one legitimate snapshot-based lookup is `EvalSummariesSchemaProvider`, which asks a *metric-catalog* question ("which `metric::*` families does this computation have?"), not a has-results question.

Export decides explicit-`computation` existence the same way, via `EvalSummaryRepository.existsByRunIdAndComputationId`.
