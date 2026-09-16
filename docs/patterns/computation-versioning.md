# Computation Versioning (No `is_latest` flag)

Entities versioned by `computation_id` (UUID) use append-only writes — the analytics result tables, and the meta `run_metric_snapshots` table that captures each computation's metric catalog. "Latest" is resolved at query time (`ORDER BY computed_at_ms DESC LIMIT 1`); API callers pass `computation=<uuid>` or `computation=latest` (or omit).

**Resolution reads `test_case_eval_summaries`, not `run_metric_snapshots`** — `ComputationResolver.resolve` delegates to `EvalSummaryRepository.findLatestComputationId` (backed by `idx_eval_summaries_run_computed_at`), so "latest" means "latest computation with readable rows" and a metric-less run still resolves; do not re-point it at the snapshot table. Since meta V1.32 that table is in the **meta** database, so re-pointing it would also turn a single-datasource read into a cross-database one.

The one legitimate snapshot-based lookup is `EvalSummariesSchemaProvider`, which asks a *metric-catalog* question ("which `metric::*` families does this computation have?"), not a has-results question. It reads the meta snapshot table while the surrounding schema work reads analytics — see [dual-datasource.md](dual-datasource.md) on keeping such a read out of the other datasource's transaction.

Export decides explicit-`computation` existence the same way, via `EvalSummaryRepository.existsByRunIdAndComputationId`.
