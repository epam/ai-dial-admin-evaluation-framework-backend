## Why

The evaluation framework stores test case execution results (request/response, timing, status) in the analytics database but has no storage for metric computation outputs. Users need to see evaluation results enriched with metric scores in a unified grid — with filtering, aggregation, and cross-run comparison — to assess model quality. User-configurable sorting is deferred (keyset pagination requires a fixed sort order). The metrics system spec (metrics-system) defines MetricResult as a planned entity; this change implements the storage and retrieval layer for it.

## What Changes

- **New analytics table `test_case_eval_summaries`**: A wide, denormalized, append-only table in the analytics database. One row per (test case, run, run_index, computation). Each row contains: copied test case context (facts, extracted columns, execution info) plus all metric scores for that test case. Metric values are split into two JSONB columns — compact `metric_values` (for fast filtering/sorting) and detailed `metric_infos` (lazy-loaded for drill-down).
- **New dimension table `run_metric_snapshots`**: Stores per-(computation, TSMD) binding and version snapshots captured at metric computation time. Small table, used for config display and "latest computation" resolution.
- **Batch write API for eval summaries**: `POST /api/v1/analytics/eval-summaries` — accepts a batch of eval summary rows for a given run and computation. Envelope contains `testSuiteId`, `testSuiteRunId`, `computationId`, `computedAtMs` (matching existing analytics batch write pattern). Idempotent via ON CONFLICT. Used by the metric computation pipeline (out of scope for this change).
- **Cursor-paginated read API for eval summaries**: `GET /api/v1/analytics/eval-summaries` — keyset pagination with filtering on identity fields, execution fields, and metric values (JSONB path). Supports `computation` query param (UUID or "latest" sentinel).
- **Aggregation API**: `GET /api/v1/analytics/eval-summaries/aggregate` — returns grouped metric aggregations (avg, min, max, count) per metric per run or per computation. Designed for summary cards and trend views.
- **Run metric snapshots API**: `GET /api/v1/analytics/run-metric-snapshots` — returns binding configs and computation history for a run. Used by the UI to map grid columns to metric inputs and to list available computations.
- **Computation versioning**: Each metric computation batch is identified by a `computation_id`. No `is_latest` flag — the latest computation is resolved at query time from `run_metric_snapshots`. Enables append-only recalculation with full history for comparison.

## Capabilities

### New Capabilities
- `metrics-storage`: Eval summary table schema, batch write API, cursor-paginated read API with JSONB metric value filtering, aggregation endpoint, computation versioning model, and run metric snapshots.

### Modified Capabilities
- `metrics-system`: Update the metrics-system spec to mark MetricResult storage requirements as implemented (currently Planned/Vision). Add requirements for the eval summary model, metric_values/metric_infos split, and computation versioning.
- `analytics-eval-results`: Add cross-reference to eval summaries as the metric-enriched analytical surface (no behavioral changes to existing test-case-results endpoints).

## Impact

### Summary

| Area | Impact |
|------|--------|
| **Goals** | Unified grid view of test case results + metric scores; rich filtering and aggregation; OLAP-ready denormalized model. Sort order is fixed (keyset pagination constraint) — user-configurable sorting deferred to a future change. |
| **Non-goals** | Metric computation pipeline/job orchestration (separate change); metric provider invocation; UI implementation; AutoDDL for per-suite columns |

### Current State
- `test_case_run_results` in analytics DB stores raw execution data (request/response bodies, timing, status, extracted columns)
- MetricDeclaration, MetricDeclarationVersion, TestSuiteMetricDefinition exist in meta DB (CRUD implemented)
- No MetricResult storage exists
- metrics-system spec lists MetricResult as Planned/Vision

### Proposed Change
- The eval summary table is the **primary analytical surface** — optimized for grid UI, filtering, aggregation, and future OLAP migration (ClickHouse)
- Heavy denormalization: each row is self-contained (no JOINs for grid rendering)
- `metric_values` column kept compact (~1-2 KB) for inline PG storage; `metric_infos` kept separate for TOAST/lazy loading
- Pure append-only — recalculation creates new rows with a new `computation_id`, never updates existing rows
- `run_metric_snapshots` captures binding/version config per computation; resolves "latest" without a mutable flag

### API Impact
- New endpoints under `/api/v1/analytics/eval-summaries` (list, aggregate)
- New endpoint `/api/v1/analytics/run-metric-snapshots` (list by run)
- Batch write endpoint for eval summaries (used by metric computation pipeline)
- No changes to existing `/api/v1/analytics/test-case-results` endpoints

### Data/Migration Impact
- New Flyway migration in `db/migration/analytics/POSTGRES/`:
  - `V1.5__CreateTestCaseEvalSummariesTable.sql` — eval summary table with composite PK `(created_at_ms, id)`, UNIQUE constraint, indexes on `(test_suite_run_id, computation_id)`, `(computation_id)`, `(id)`
  - `V1.6__CreateRunMetricSnapshotsTable.sql` — snapshot dimension table
- No changes to existing `test_case_run_results` table
- No meta DB schema changes

### Security/Permissions Impact
- Same security model as existing analytics endpoints (JWT/OIDC)
- No new permission model required

### Risks
- JSONB `metric_values` column performance for filtering/sorting at large scale (100K+ rows). Mitigated by keeping values compact and supporting expression indexes.
- Storage growth from denormalized context (facts + extracted_columns copied from test_case_run_results). Mitigated by the fact that this data is needed for the grid and avoids expensive JOINs.
- Recalculation duplicates context rows. Acceptable — recalculation is rare and duplication is ~2-5 KB per row.

### Rollout
- Deploy behind the existing analytics datasource configuration — no new infrastructure required
- Eval summary table is empty until a metric computation pipeline writes to it (separate change)
- APIs are immediately available but return empty results until computation data exists

### Test Plan
- Functional tests for batch write (happy path, validation, idempotency, conflict handling)
- Functional tests for list with cursor pagination, filtering (identity, execution, metric value JSONB paths), computation_id resolution
- Functional tests for aggregation endpoint
- Functional tests for run metric snapshots
- Unit tests for MapStruct mappers, JSONB serialization/deserialization of metric_values and metric_infos
