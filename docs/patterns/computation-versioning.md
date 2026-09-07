# Computation Versioning (No `is_latest` flag)

Analytics entities versioned by `computation_id` (UUID) use append-only writes. "Latest" is resolved at query time (`ORDER BY computed_at_ms DESC LIMIT 1`); API callers pass `computation=<uuid>` or `computation=latest` (or omit).

**Resolution reads `test_case_eval_summaries`, not `run_metric_snapshots`** — `ComputationResolver.resolve` delegates to `EvalSummaryRepository.findLatestComputationId` (backed by `idx_eval_summaries_run_computed_at`), so "latest" means "latest computation with readable rows" and a metric-less run still resolves; do not re-point it at the snapshot table.

The one legitimate snapshot-based lookup is `EvalSummariesSchemaProvider`, which asks a *metric-catalog* question ("which `metric::*` families does this computation have?"), not a has-results question.

Export decides explicit-`computation` existence the same way, via `EvalSummaryRepository.existsByRunIdAndComputationId`.

**Partition pruning (`V1.20`)**: since `test_case_eval_summaries` is now partitioned on `created_at_ms`, `findLatestComputationId`/`existsByRunIdAndComputationId`/`ComputationResolver.resolve` all take a nullable `runCreatedAtMs` parameter, applied as an additional equality predicate so the query prunes to one partition instead of scanning every one. Callers that already load the run (`EvalSummaryService`, `EvalSummaryExportService`) pass it through. `MetricScoreLatestComputationDefaulter` — the Query DSL "latest" sentinel resolver — deliberately passes `null`: it only has the run id from the filter tree, and injecting a meta-DB read into this component just to obtain the timestamp would add a cross-datasource dependency for a single indexed `LIMIT 1` lookup that isn't on a hot loop.

`RunComparisonService.requireComputation` also passes `null` despite having the run's `createdAtMs` trivially available, for an unrelated reason (a test-fixture decoupling, not an architectural one) — see [Analytics Time-Based Partitioning](analytics-time-partitioning.md) for that gap and the full partition-pruning pattern.
