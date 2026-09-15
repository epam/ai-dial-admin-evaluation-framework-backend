## Why

The UI's run list needs, per run, the deployment (or MCP deployment) the run executed against and the names of the metrics it produced — today it must fan out to `GET /test-suite-runs/{id}` (for the snapshot) and `GET /run-metric-snapshots` per row. With `run_metric_snapshots` now in the meta database (PR #199), both facts are joinable to `test_suite_runs` in one Postgres statement, so the run list can become a `test_suite_runs` entity of the structured query DSL instead of yet another bespoke REST listing. Second step of GH #197; overall score and cost enrichment stay out of scope.

## What Changes

- New simple queryable entity `test_suite_runs` (meta datasource) at `POST /api/v1/queries/execute` and in the schema discovery endpoints. Fields: every `test_suite_runs` column **except** `suite_snapshot`, `run_config` and `error_details`, plus virtual fields extracted from the run's own `suite_snapshot`:
  `suite_type`, `deployment_ref::id|name|version|type`, `mcp_deployment_ref::id|name|type|transport` (snapshot only — no fallback to `test_suites`; null when the run has no snapshot), and `metric_names` (`array`): the `tsmd_name`s (distinct, sorted ascending under the meta database's default text collation) of the run's **latest computation** in `run_metric_snapshots`, `[]` for a run with no snapshots.
- `metric_names` is bound as an `ARRAY`-typed JSONB field, so `co`/`nc` work as whole-element containment (e.g. runs that produced `Accuracy`), consistent with `test_cases` array fields.
- Row-mode queries with an empty `select` return exactly the fields above — `suite_snapshot` never enters the projection (TOAST), and the DSL entity carries no mapper-derived fields (`grafanaExploreUrl`).
- Meta Flyway migration `V1.34__ReplaceRunMetricSnapshotsRunIndex.sql`: `CREATE INDEX idx_run_metric_snapshots_run_computed_at ON run_metric_snapshots (test_suite_run_id, computed_at_ms DESC, computation_id DESC)` and `DROP INDEX idx_run_metric_snapshots_run` (strict prefix of the new index; every existing `WHERE test_suite_run_id = ?` reader and the FK cascade are served by the leading column). `./gradlew generateJooq` regenerates `Indexes.java`.
- "Latest computation" on `run_metric_snapshots` gains a deterministic tiebreak: `ORDER BY computed_at_ms DESC, computation_id DESC`. `PostgresRunMetricSnapshotRepository.findLatestComputationId` adopts the same ordering (behaviour changes only when two computations of one run share a millisecond).
- Existing `GET /api/v1/test-suite-runs` is untouched; the UI migrates its run list to `POST /api/v1/queries/execute`.

Non-goals: `overall_score`, cost fields, a new REST endpoint, `latest_computation_id`/`metric_count` fields, exposing `run_config`/`error_details`, detailed schema (entity is simple), ClickHouse/analytics involvement (all meta).

## Capabilities

### New Capabilities
- `test-suite-runs-query-entity`: the `test_suite_runs` structured-query entity — field set, snapshot-derived ref sub-fields, `metric_names` latest-computation semantics, array filtering behaviour, exclusion of `suite_snapshot`/`run_config`/`error_details`, and the run-scoped evaluation contract (correlated scalar subquery) that keeps cost proportional to the visited runs.

### Modified Capabilities
- `query-schema-discovery`: entity catalog lists `test_suite_runs` as a simple entity; base schema requirement gains the `test_suite_runs` field table (virtual sub-fields + `metric_names`, and the three excluded columns).
- `metrics-storage`: "Index for run lookup" scenario replaced by the composite `(test_suite_run_id, computed_at_ms DESC, computation_id DESC)` index (single-column index dropped); "Metric-catalog lookups stay on run metric snapshots" gains the `computation_id DESC` tiebreak.

## Impact

- **Code (new)**: `query.service.repository.PostgresTestSuiteRunEntityResolver` (`@ConditionalOnProperty datasource.meta.vendor=POSTGRES`, `metaDsl`), `query.service.TestSuiteRunsSchemaProvider`, `query.service.TestSuiteRunQueryFields` (shared constants). `table()` is a derived table: narrowed `test_suite_runs` projection + `jsonb ->> key` extractions via `JsonPathAccessor`, plus a correlated target-list scalar subquery `coalesce(jsonb_agg(DISTINCT tsmd_name ORDER BY tsmd_name), '[]'::jsonb)` over the run's latest computation.
- **Code (modified)**: `PostgresRunMetricSnapshotRepository.findLatestComputationId` (tiebreak), generated `Indexes.java`.
- **DB**: meta `V1.34` (one new index, one dropped). No table/column changes. `docs/database-schema.md` index rows + migration history.
- **API**: additive — one new value in `GET /api/v1/queries/entities`, new `GET /api/v1/queries/entities/schema/test_suite_runs`, `entity: "test_suite_runs"` accepted by `/queries/execute`. No REST DTO changes.
- **Docs**: new `docs/patterns/test-suite-runs-query-entity.md` (scalar-subquery latest-computation lookup vs lateral vs global `GROUP BY`, EXPLAIN evidence, why snapshot-only refs) linked from AGENTS.md and `docs/patterns/README.md`; paragraphs in `docs/patterns/query-dsl-entity-resolution.md` and `docs/patterns/computation-versioning.md`; `docs/database-schema.md`; `openspec/specs/README.md` index entry.
- **Perf/risk**: the correlated scalar subquery runs two index probes per visited run (and is dropped entirely when `metric_names` is not referenced, e.g. `count(*)`); with the composite index the `LIMIT 1` latest lookup is an index-only scan with no sort. A sort on a non-indexed run column still visits every matching run (same as the plain table today). The `DISTINCT ON` global-aggregate alternative is rejected because Postgres cannot push page ids into an aggregated subquery.
- **Tests**: unit test for the resolver bindings/schema parity, functional tests for `/queries/execute` on `test_suite_runs` (snapshot null → null refs, no snapshots → `[]`, latest-computation selection with same-millisecond tiebreak, `co` on `metric_names`, aggregate mode grouped by `deployment_ref::id`), schema discovery scenario updates, `JooqSchemaDriftTest` passes after regeneration.
- **Rollout**: additive migration, no data movement; safe to deploy with the UI still on the old REST list.
