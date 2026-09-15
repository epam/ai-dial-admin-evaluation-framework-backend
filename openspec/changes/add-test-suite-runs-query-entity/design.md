## Context

See proposal.md — Why. Relevant current state:

- A DSL entity is two beans: a `StructuredQueryEntityResolver` (`entity()`, `dsl()`, `table()`, `bindings()`) picked up by `StructuredQueryEntityRegistry`, and a `QueryableEntitySchemaProvider` picked up by `QueryEntityRegistry`. `StructuredQueryBuilder` puts `table()` in `FROM`, translates `filter`/`select`/`sort`/`group_by` through `bindings()`, and — in row mode with an empty `select` — projects `table.fields()`. `countRows` does `select count(*) from table() where filter`.
- Precedents: `test_suites` adds `jsonb ->> key` virtual bindings over a bare generated table; `eval_summaries` makes `table()` a LEFT JOIN to a narrowed derived table (`DSL.select(...).asTable(...)`).
- `run_metric_snapshots` is in meta since V1.32/V1.33 with FK CASCADE, unique `(computation_id, tsmd_id)`, index `(test_suite_run_id)`. `computed_at_ms` is shared by all rows of one computation. `PostgresRunMetricSnapshotRepository.findLatestComputationId` orders by `computed_at_ms DESC` only.
- Deployment/MCP refs of a run exist only in `test_suite_runs.suite_snapshot` (keys `suiteType`, `deploymentRef{id,name,version,type}`, `mcpDeploymentRef{id,type,name,transport}`); null for legacy/pre-snapshot runs. The REST list projection excludes `suite_snapshot` to avoid TOAST decompression; extracting keys from it still detoasts, which is accepted (page-bounded).
- `FilterTranslator` treats an `ARRAY`-typed binding specially: `co`/`nc` become whole-element JSONB containment (`?` / `@>`), `lower()/upper()` wrapping folds case via `jsonb_array_elements_text`.
- Jackson `JsonbRowConverter` turns any `JSONB` result value into parsed JSON, so a JSONB array column arrives as a real JSON array.

## Goals / Non-Goals

**Goals:**
- One meta statement per query; snapshot cost proportional to the runs the outer query actually visits; `count(*)` and projections that do not touch `metric_names` pay nothing for snapshots.
- The row-mode "empty select" projection is exactly the spec'd field set (no `suite_snapshot`/`run_config`/`error_details`), enforced by the shape of `table()` rather than by filtering in the builder.
- Schema provider and resolver cannot drift: both consume one constants class.

**Non-Goals:**
- No changes to `StructuredQueryBuilder`, `FilterTranslator`, or the SPI. If the entity needs a builder change, the design is wrong.
- No ClickHouse/analytics resolver variant (entity is meta-only; gated on `datasource.meta.vendor=POSTGRES` like `test_suites`).
- No `latest_computation_id`, `metric_count`, score or cost fields.

## Decisions

### D1. `table()` is a derived table over `test_suite_runs`, not the bare table
`PostgresTestSuiteRunEntityResolver.table()` returns
`DSL.select(<10 plain columns>, <9 snapshot extractions>, <metric_names scalar subquery>).from(TEST_SUITE_RUNS).asTable("tsr")`,
and every binding points at `tsr.field(...)`.

*Why:* row mode with empty `select` projects `table.fields()`; only a derived table can make that projection omit `suite_snapshot`, `run_config`, `error_details` (spec: they must not appear and must be rejected as unknown). Postgres pulls this derived table up into the outer query (no aggregate/limit/distinct at its top level), so filters on plain columns still hit the `test_suite_runs` indexes and `count(*)` collapses to a count over the base table. `countRows` (`select count(*) from tsr where …`) therefore costs the same as today's REST count.

*Alternative rejected:* bare `TEST_SUITE_RUNS` + LEFT JOIN — `table.fields()` would include the three excluded JSONB columns; filtering them would need a builder change (violates Non-Goal).

### D2. `metric_names` is a correlated scalar subquery in the derived table's target list, not a `LEFT JOIN LATERAL` and not a global aggregate
```sql
(SELECT coalesce(jsonb_agg(DISTINCT rms.tsmd_name ORDER BY rms.tsmd_name), '[]'::jsonb)
   FROM run_metric_snapshots rms
  WHERE rms.test_suite_run_id = test_suite_runs.id
    AND rms.computation_id = (SELECT latest.computation_id
                                FROM run_metric_snapshots latest
                               WHERE latest.test_suite_run_id = test_suite_runs.id
                               ORDER BY latest.computed_at_ms DESC, latest.computation_id DESC
                               LIMIT 1)) AS metric_names
```
Built with jOOQ (`DSL.jsonbArrayAggDistinct(...).orderBy(...)`, `DSL.coalesce(..., DSL.inline(JSONB.valueOf("[]")))`, aliased `RUN_METRIC_SNAPSHOTS.as("rms")` / `.as("latest")`); no raw SQL strings.

*Why over `LEFT JOIN LATERAL`:* a lateral join node survives pull-up and is evaluated for every visited run even when nothing references `metric_names` — `include_total` counts and plain projections would pay N index probes. A target-list scalar subquery is dropped entirely when unreferenced after pull-up, and Postgres (≥ 9.6) postpones expensive target-list SubPlans until after `ORDER BY`/`LIMIT` when a sort is required, so a paged query computes it only for the emitted page. When referenced in `filter` (`metric_names co …`) it becomes a correlated SubPlan per candidate run — inherent to the feature and index-driven.

*Why over a `GROUP BY test_suite_run_id` derived table joined to runs:* Postgres cannot push the outer run ids into an aggregated subquery; every request would aggregate the whole snapshot table.

*Why `DISTINCT`:* uniqueness is `(computation_id, tsmd_id)`, so two TSMDs of one suite could theoretically share a name; the spec promises distinct names. `jsonb_agg(DISTINCT x ORDER BY x)` is legal because the order expression equals the aggregated expression.

*Why `coalesce` inside the subquery:* an aggregate without `GROUP BY` always yields one row, so the scalar subquery is never "no row"; `coalesce` covers the `jsonb_agg` of zero rows (`NULL`) → `[]`.

### D3. Composite index replaces the single-column index (meta `V1.34__ReplaceRunMetricSnapshotsRunIndex.sql`)
```sql
CREATE INDEX idx_run_metric_snapshots_run_computed_at
    ON run_metric_snapshots (test_suite_run_id, computed_at_ms DESC, computation_id DESC);
DROP INDEX idx_run_metric_snapshots_run;
```
The inner `LIMIT 1` becomes an index-only range scan with no sort (column order and directions match the `ORDER BY` exactly). The outer aggregate and every existing `WHERE test_suite_run_id = ?` reader (`findByRunId`, `findByRunIdAndComputationId`, `findLatestComputationId`, FK cascade on run delete) use the leading column, so the old index is a pure-write-cost prefix. Same ordering rationale as `idx_eval_summaries_run_computed_at` (analytics V1.15) and the DESC unique index of V1.30. Run `./gradlew generateJooq` and commit `Indexes.java`; `JooqSchemaDriftTest` already covers `RUN_METRIC_SNAPSHOTS`.

### D4. Tiebreak on `computation_id DESC` everywhere "latest" is read from snapshots
`findLatestComputationId` gains `.orderBy(COMPUTED_AT_MS.desc(), COMPUTATION_ID.desc())`, identical to the subquery in D2. Two computations of a run can share a millisecond (one `Clock` read per computation, fast recomputation); without the tiebreak the schema-discovery endpoint and `metric_names` could disagree or flip between calls. `ComputationResolver` (eval-summaries-based) is untouched — different table, blessed by `docs/patterns/computation-versioning.md`.

### D5. Snapshot-only refs via `JsonPathAccessor`, two-level text extraction
`suite_type = jsonbAtAsText(SUITE_SNAPSHOT, 'suiteType')`; `deployment_ref::<k> = jsonbAtAsText(jsonbAt(SUITE_SNAPSHOT, 'deploymentRef'), '<k>')`; same for `mcpDeploymentRef`. `->`/`->>` on a NULL snapshot or missing key yield NULL, so no `CASE` is needed and no exception path exists. No fallback to `test_suites` (user decision: run-time truth; a later suite edit must not rewrite history).

### D6. One constants class keeps schema and bindings in lockstep
New `query.service.TestSuiteRunQueryFields` (package-private or public final constants class): the excluded column names, the `suite_type` field, the two `(fieldPrefix, snapshotKey, subKeys[])` ref descriptors, the `metric_names` name/type, and the source labels (`suite_snapshot`, `run_metric_snapshots`). `TestSuiteRunsSchemaProvider.baseSchema()` = `schemaResolver.resolve(TEST_SUITE_RUNS)` minus excluded + virtual entries; the resolver builds bindings from the same descriptors. A unit test asserts `schema field names == binding keys` (set equality) and `types` agree per field.

### D7. Types
Plain columns take `JooqTableSchemaResolver` inference (`id`/`test_suite_id` → `uuid`, `status`/`test_run_name`/`error_message` → `string`, `number_of_test_cases` → `integer`, `*_ms` → `long`). Virtual ref fields → `STRING`. `metric_names` → `ARRAY` (JSONB), which is what unlocks containment `co`/`nc` in `FilterTranslator` unchanged.

### D8. Layering
Both new classes live in `query.service` / `query.service.repository` exactly like the `test_suites` pair; the resolver carries `@Repository @LogExecution @ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")` and `@Qualifier("metaDsl")`. Execution/transaction handling stays in `StructuredQueryExecutor`/`StructuredQueryService` (already routes by `resolver.dsl()`); no new transaction boundary. Error handling: unknown/excluded fields → existing `ValidationException` 400 from `ExprTranslator`; SQL errors → existing executor mapping.

### D9. Docs
- `docs/patterns/test-suite-runs-query-entity.md` (derived-table projection trick, scalar-subquery-vs-lateral reasoning with the EXPLAIN evidence gathered during implementation, snapshot-only refs, tiebreak) + row in AGENTS.md "Unique Patterns" and `docs/patterns/README.md`.
- `docs/database-schema.md`: index table of `run_metric_snapshots`, migration history row V1.34, "Last sync" line.
- `openspec/specs/README.md`: new `test-suite-runs-query-entity` entry; `query-schema-discovery`/`metrics-storage` summaries if they enumerate entities/indexes.
- `docs/patterns/query-dsl-entity-resolution.md`: one paragraph noting `test_suite_runs` as the "fully derived table" precedent.
- No `docs/configuration.md` change (no properties). No `config.yaml` change (feature follows existing patterns).

## Risks / Trade-offs

- [Planner does not postpone the `metric_names` SubPlan past a required sort, evaluating it for every filter-matching run] → Verify with `EXPLAIN (ANALYZE)` during implementation on a paged `ORDER BY created_at_ms DESC` query and a non-indexed sort (`test_run_name`); record plans in the pattern doc. Even in the worst case each evaluation is two index probes, and the run table is small (thousands), so it degrades to the plain table's own cost class rather than a scan of snapshots.
- [Pull-up fails because of the SubLink in the derived target list, leaving a `SubqueryScan` that hides `test_suite_runs` indexes from the outer filter] → Same EXPLAIN check; if it happens, fall back to a resolver-side `SubqueryScan`-free shape by moving the extraction fields into bindings and keeping only the plain columns in the derived table. Spec unaffected.
- [`jsonb_agg(DISTINCT … ORDER BY …)` not expressible in jOOQ 3.21] → `DSL.aggregateDistinct("jsonb_agg", JSONB.class, field).orderBy(field)` is the escape hatch; still typed DSL, no string SQL.
- [Extracting from `suite_snapshot` detoasts every visited run's snapshot] → Accepted (same as the enriched-listing design in GH #197); bounded by page size and the DSL's `MAX_LIMIT = 1000`. Filtering/sorting on ref fields is a seq-scan-with-detoast over runs; acceptable at current run counts, documented in the pattern doc.
- [Dropping `idx_run_metric_snapshots_run` regresses a query] → Every reader was audited (three repository methods + FK cascade), all prefix-served. Rollback: recreate the single-column index.
- [Tiebreak changes which computation `EvalSummariesSchemaProvider` picks] → Only for same-millisecond computations, where the previous behaviour was undefined; pinned by a functional test.

## Migration Plan

1. Deploy: Flyway applies meta V1.34 (index create + drop; both fast on the current row counts, `CREATE INDEX` takes a share lock only). No data movement; app code already compatible before and after.
2. UI switches its run list to `POST /api/v1/queries/execute` with `entity: "test_suite_runs"` at its own pace; the REST list remains.
3. Rollback: revert the app; if the index must be restored, run `CREATE INDEX idx_run_metric_snapshots_run ON run_metric_snapshots (test_suite_run_id)` manually (Flyway does not undo). The composite index is harmless to leave in place.
