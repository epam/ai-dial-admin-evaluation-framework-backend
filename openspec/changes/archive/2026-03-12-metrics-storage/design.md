## Context

The evaluation framework has a mature analytics layer for test case execution results (`test_case_run_results` in the analytics DB) with cursor-based pagination, JSONB filtering, and batch writes. The metrics catalog is established in the meta DB (MetricDeclaration, MetricDeclarationVersion, TestSuiteMetricDefinition). The missing piece is storage and retrieval of metric computation outputs — the results of applying metrics to test case execution data.

The system is designed for a future OLAP migration (e.g., ClickHouse). This strongly influences the data model: heavy denormalization, no cross-table JOINs for primary query paths, append-only writes, and typed/scannable columns for aggregation.

## Goals / Non-Goals

**Goals:**
- Provide a denormalized, OLAP-ready table for metric results enriched with test case context (facts, extracted columns, execution info)
- Enable grid UI rendering from a single table scan (no JOINs)
- Support filtering by metric values via compact JSONB column (user-configurable sorting deferred — keyset pagination requires fixed sort order)
- Support aggregation queries (avg, min, max, count per metric per run)
- Capture binding/version snapshots per computation for reproducibility
- Enable versioned metric recalculation as pure append (no row updates)

**Non-Goals:**
- Metric computation pipeline/job orchestration (separate change)
- Metric provider invocation logic
- AutoDDL for per-suite typed columns (future optimization)
- ClickHouse adapter implementation (v2)
- UI implementation

## Decisions

### D1: Wide table with one row per (test case, run, run_index, computation)

**Decision**: Use a single wide `test_case_eval_summaries` table where each row contains the test case context AND all metric scores.

**Alternatives considered**:
- **Tall table (one row per named metric output)**: Better for OLAP aggregation on typed `value` column, but requires JOINs to build the grid and has complex pagination (paginate by test case, not by metric output).
- **JSONB bag per TSMD**: One row per (test case, TSMD) with outputs in JSONB. Middle ground but still requires pivoting for grid view.

**Rationale**: The primary UI is a grid where one row = one test case. The wide model maps directly to this. While JSONB metric values lose some columnar efficiency vs. typed columns, it's adequate for expected data volumes (millions, not billions), and it eliminates the JOIN cost that would be expensive in OLAP backends.

### D2: Split metric_values (compact) from metric_infos (detailed)

**Decision**: Two separate JSONB columns for metric data.

`metric_values` — compact (~1-2 KB for 20 metrics):
```json
{
  "Accuracy": {"score": 0.85, "f1": 0.78, "precision": 0.82},
  "Relevance": {"score": 0.92, "recall": 0.88}
}
```

`metric_infos` — detailed (~5-25 KB, lazy-loaded):
```json
{
  "Accuracy": {
    "score": {"confidence": 0.95, "method": "exact_match"},
    "f1": {"beta": 1.0}
  }
}
```

**Rationale**: In PostgreSQL, values under ~2 KB stay inline (no TOAST decompression). Grid filtering/sorting only needs `metric_values`. The `metric_infos` column lives in TOAST and is only loaded for detail views. In ClickHouse, separate columns means separate storage granules — queries that don't SELECT metric_infos never read it from disk.

**Implementation constraint**: List and aggregation queries MUST use an explicit column list that excludes `metric_infos` from the SELECT clause. Only the `findById()` query (detail view) selects `metric_infos`. The RowMapper handles the column being absent by leaving `metricInfos = null`. This prevents TOAST decompression of 5-25 KB per row on list pages (100 rows × 25 KB = 2.5 MB of wasted I/O).

### D3: Computation versioning via computation_id (no is_latest flag)

**Decision**: Each metric computation batch is identified by a `computation_id` (UUID). No mutable `is_latest` boolean. "Latest" is resolved at query time from `run_metric_snapshots`.

**Resolution query** (runs once per API call, ~1-5 rows scanned):
```sql
SELECT computation_id FROM run_metric_snapshots
WHERE test_suite_run_id = :runId
ORDER BY computed_at_ms DESC LIMIT 1
```

**Alternatives considered**:
- **`is_latest` boolean**: Requires UPDATE of all existing rows on recalculation (10K+ rows). Breaks append-only semantics. Expensive in ClickHouse (mutation).
- **Window function at query time**: `ROW_NUMBER() OVER (PARTITION BY test_case_run_result_id ORDER BY computed_at_ms DESC)`. Requires scanning all computations per query — slower.

**Rationale**: Pure append-only. The resolution query is trivially cheap (small dimension table, indexed). Comparison views use two computation_ids directly.

### D4: Bindings stored at run level in run_metric_snapshots (not per row)

**Decision**: Metric binding configs are stored in `run_metric_snapshots` — one row per (computation_id, TSMD). Not denormalized into every eval summary row.

**Rationale**: Bindings are identical for every test case in a run. Denormalizing ~200-500 bytes per metric × 20 metrics × 10K rows = 40-100 MB of duplication per run. Instead, the snapshot table holds ~20 rows per computation, loaded once per grid initialization. Even the resolved per-row values are already available in `test_case_data` and `extracted_columns` — the bindings just tell the UI which JSON paths to display.

### D5: Denormalize test case context into eval summaries

**Decision**: Copy `test_case_data`, `extracted_columns`, `execution_status`, `exec_duration_ms`, `response_status_code` from `test_case_run_results` into each eval summary row.

**Rationale**: Grid rendering requires zero JOINs. Context is ~2-5 KB per row. For recalculation (rare), context is duplicated but storage cost is trivial.

### D6: Batch write API follows existing analytics patterns

**Decision**: The batch write endpoint follows the same patterns as `POST /api/v1/analytics/test-case-results`:
- Envelope DTO with testSuiteId + testSuiteRunId + computationId + computedAtMs + items array (testSuiteId at envelope level, matching existing pattern — all items belong to the same run/suite)
- JDBC batch insert with ON CONFLICT DO NOTHING for idempotency
- `@Transactional("analyticsTransactionManager")`
- `@Qualifier("analyticsJdbcTemplate")` for the repository

### D7: Cursor-based pagination on (created_at_ms, id) with computation_id filter

**Decision**: Reuse the existing keyset pagination pattern. `computation_id` is a required filter (or "latest" sentinel). The composite key `(created_at_ms, id)` serves as both PK and cursor position.

### D8: Aggregation endpoint returns flat metric summaries

**Decision**: A dedicated `GET /api/v1/analytics/eval-summaries/aggregate` endpoint that returns per-metric aggregations (avg, min, max, count) for a single resolved computation. The computation is resolved as a WHERE filter (same as the list endpoint), not as a grouping dimension — there is no GROUP BY. The response is a flat list of per-metric aggregation objects with the resolved `computationId` at the top level. The `metrics` query parameter specifies which metric paths to aggregate (max 50, each generates 4 SQL expressions).

**Rationale**: Aggregation queries scan all matching rows — they don't paginate. The API returns a compact summary, not row data. Grouping by additional dimensions (e.g., cross-computation comparison) is deferred — it increases response complexity and may require its own pagination.

## New Components

| Component | Package | Purpose |
|-----------|---------|---------|
| `EvalSummary` | `data.db.analytics.model` | Data model for eval summary row |
| `EvalSummaryRowMapper` | `data.db.analytics.mapper` | JDBC RowMapper for eval summaries |
| `EvalSummaryRepository` | `data.db.analytics.repository` | Repository interface + Postgres impl |
| `RunMetricSnapshot` | `data.db.analytics.model` | Data model for metric snapshot |
| `RunMetricSnapshotRowMapper` | `data.db.analytics.mapper` | JDBC RowMapper for snapshots |
| `RunMetricSnapshotRepository` | `data.db.analytics.repository` | Repository interface + Postgres impl |
| `EvalSummaryService` | `service.domain.analytics` | Business logic for eval summaries |
| `RunMetricSnapshotService` | `service.domain.analytics` | Business logic for snapshots |
| `EvalSummaryMapper` | `service.domain.mapper` | MapStruct mapper (model ↔ DTO) |
| `RunMetricSnapshotMapper` | `service.domain.mapper` | MapStruct mapper (model ↔ DTO) |
| `EvalSummaryController` | `web.controller` | REST endpoints for eval summaries |
| `RunMetricSnapshotController` | `web.controller` | REST endpoint for snapshots |
| `MetricAggregationResult` | `service.domain.dto.analytics` | Aggregation response DTO |
| `EvalSummaryProperties` | `configuration.properties.analytics` | Config properties (batch limits) |

## Transaction Boundaries

- **Batch write** (eval summaries): `@Transactional("analyticsTransactionManager")` — all items in one transaction, idempotent via ON CONFLICT.
- **Batch write** (run metric snapshots): `@Transactional("analyticsTransactionManager")` — snapshots are written via a separate HTTP endpoint (`POST .../run-metric-snapshots`), so they execute in a separate transaction from eval summaries. The caller (metric computation pipeline) should write snapshots first, then eval summaries. If the eval summary write fails, the "latest computation" resolution may point to a computation with snapshots but no eval summary data — the list endpoint returns an empty page (acceptable; the pipeline can retry).
- **Read operations**: `@Transactional(value = "analyticsTransactionManager", readOnly = true)`.
- **No TransactionTimestampContext**: Analytics DB does not use the meta-only timestamp aspect. Timestamps are passed explicitly (from run's `createdAt` or from the computation's `computedAt`).

## Risks / Trade-offs

| Risk | Mitigation |
|------|------------|
| JSONB metric_values filtering performance at 100K+ rows | Keep column compact (<2 KB); support expression indexes on hot metric paths |
| Storage growth from denormalized context | Context is ~2-5 KB/row; for 1M rows = ~5 GB — manageable |
| Recalculation duplicates context | Rare operation; ~30 MB per 10K test cases per recalculation |
| run_metric_snapshots becomes bottleneck for "latest" resolution | Table is tiny (~20 rows per computation); indexed; result cacheable at service layer |
| metric_values JSONB structure not enforced at DB level | Validate in service layer; MapStruct mapper enforces structure |

## Migration Plan

- **Flyway V1.5**: Create `test_case_eval_summaries` table in analytics DB
- **Flyway V1.6**: Create `run_metric_snapshots` table in analytics DB
- **No data migration**: Tables start empty; populated by metric computation pipeline (separate change)
- **Rollback**: Drop both tables (no data dependencies from existing tables)

## Open Questions

- Should `run_metric_snapshots` live in the analytics DB or the meta DB? Analytics DB keeps all computation data together; meta DB groups it with other TSMD/binding concerns. **Leaning toward analytics DB** for OLAP migration simplicity.
- Exact aggregation functions to support (avg, min, max, count, percentiles?). Start with avg/min/max/count; add percentiles as needed.
- Should the aggregation endpoint support grouping by test_case_name for per-case cross-run comparison? Useful but increases result size — may need its own pagination. Defer to implementation.
