# `test_suite_runs` query entity

`test_suite_runs` is a **fully derived-table** DSL entity (`PostgresTestSuiteRunEntityResolver`, `metaDsl`, `@ConditionalOnProperty(datasource.meta.vendor=POSTGRES)`), unlike `test_suites`' bare-table-with-virtual-bindings or `eval_summaries`' bare-table-LEFT-JOIN-derived shape. See [Query DSL entity resolution](query-dsl-entity-resolution.md) for the SPI and where this fits among the three shapes.

## Why a fully derived `table()`

Row-mode with an empty `select` projects `table().fields()`. `TEST_SUITE_RUNS` carries three heavy/opaque columns — `suite_snapshot`, `run_config`, `error_details` — that must never appear in that projection and must 400 as unknown fields if referenced directly. (A scalar derived *from* one of them may still be published under its own name without putting the column itself back in the projection — see `number_of_runs` below.) Filtering them out in `StructuredQueryBuilder` would be a builder change (a Non-Goal). Instead `table()` returns

```java
DSL.select(<10 plain columns>, <number_of_runs run_config extraction>, <9 snapshot extractions>, <metric_names subquery>)
   .from(TEST_SUITE_RUNS)
   .asTable("tsr")
```

so the three columns are simply never in the projection list — no builder change needed. Every computed column is explicitly aliased (`.as(DSL.name("deployment_ref::id"))`, …); an unaliased expression gets a jOOQ-generated name and both `tsr.field(<bindingName>)` (in `bindings()`) and the empty-`select` field enumeration would break.

Postgres pulls this derived table up into the outer query — no aggregate/`LIMIT`/`DISTINCT` sits at its top level — so filters/sorts on plain columns still hit `test_suite_runs`' own indexes, and `count(*)` collapses to a count over the base table (§ EXPLAIN evidence below).

## Explicit alias + explicit type, never `schemaResolver.bindings(tsr)`

`bindings()` is built by hand, field by field, against the alias `tsr`. It deliberately does **not** call `schemaResolver.bindings(tsr)` the way `eval_summaries` infers its LEFT-JOINed columns: a computed field has no DDL default to infer from, so wholesale inference over `tsr` yields `OBJECT` for `metric_names` and silently disables JSONB containment (`co`/`nc`) in `FilterTranslator`. Instead:

- the 10 plain columns take their type from `schemaResolver.bindings(TEST_SUITE_RUNS)` (the **base** generated table, not `tsr`) — same inference as ever, just re-pointed to `tsr.field(name)`;
- `suite_type` and the 8 `deployment_ref::*`/`mcp_deployment_ref::*` fields are typed `STRING` explicitly;
- `metric_names` is typed `ARRAY` explicitly — this is what unlocks whole-element containment (`co`/`nc`) unchanged in `FilterTranslator`.

## `metric_names`: correlated scalar subquery, not LATERAL, not global `GROUP BY`

```sql
(SELECT coalesce(jsonb_agg(DISTINCT rms.tsmd_name ORDER BY rms.tsmd_name), '[]'::jsonb)
   FROM run_metric_snapshots rms
  WHERE rms.test_suite_run_id = test_suite_runs.id
    AND rms.computation_id = (SELECT latest.computation_id
                                FROM run_metric_snapshots latest
                               WHERE latest.test_suite_run_id = test_suite_runs.id
                               ORDER BY latest.computed_at_ms DESC, latest.computation_id ASC
                               LIMIT 1)) AS metric_names
```

Built with typed jOOQ (`DSL.jsonbArrayAggDistinct(...).orderBy(...)`, `DSL.coalesce(..., DSL.inline(JSONB.valueOf("[]")))`), aliased `RUN_METRIC_SNAPSHOTS.as("rms")` / `.as("latest")`.

**Why not `LEFT JOIN LATERAL`:** a lateral join node survives pull-up and is evaluated for every visited run even when nothing references `metric_names` — `count(*)` and plain projections would pay N index probes for nothing.

**Why not a `GROUP BY test_suite_run_id` derived table joined to runs:** Postgres cannot push outer run ids into an aggregated subquery; every request would aggregate the whole snapshot table.

A target-list scalar subquery, by contrast, is dropped entirely when unreferenced after pull-up, and Postgres (≥ 9.6) postpones an expensive target-list SubPlan until after `ORDER BY`/`LIMIT` when a sort is required — a paged query computes it only for the emitted page. Referenced in `filter` (`metric_names co …`) it becomes a correlated SubPlan evaluated per candidate row — inherent to the feature, and index-driven (V1.34 composite index, below).

### EXPLAIN evidence (task 3.1, Testcontainers Postgres 17.4, 200 runs × 3 computations × 2 snapshot rows)

**(a) Paged `ORDER BY created_at_ms DESC LIMIT 25`** — pulled up, indexed sort, SubPlan only for the emitted page:

```
Limit
  ->  Index Scan using idx_test_suite_runs_created_at_ms on test_suite_runs
        SubPlan 2
          ->  Aggregate (actual rows=1 loops=25)
                InitPlan 1
                  ->  Limit
                        ->  Index Only Scan using idx_run_metric_snapshots_run_computed_at on run_metric_snapshots latest (loops=25)
                ->  Sort
                      ->  Index Scan using idx_run_metric_snapshots_run_computed_at on run_metric_snapshots rms (loops=25)
```

No `Subquery Scan on tsr` anywhere; the run table's own index drives the scan.

**(b) `select count(*)`** — no snapshot access at all:

```
Aggregate
  ->  Seq Scan on test_suite_runs (rows=200)
```

Nothing referencing `metric_names`, so the whole scalar subquery is dropped from the plan.

**(c) `ORDER BY test_run_name LIMIT 25` (non-indexed sort)** — SubPlan still postponed past `Sort`+`Limit`, `loops=25` (not 200):

```
Limit
  ->  Result
        ->  Sort (Sort Key: test_suite_runs.test_run_name; top-N heapsort)
              ->  Seq Scan on test_suite_runs (rows=200)
        SubPlan 2
          ->  Aggregate (loops=25)
                InitPlan 1 -> Index Only Scan using idx_run_metric_snapshots_run_computed_at ... (loops=25)
                ->  Sort -> Index Scan using idx_run_metric_snapshots_run_computed_at ... (loops=25)
```

Even without an index on the sort key, the `Result` node sits above the `Sort`, so the SubPlan still only runs for the 25 emitted rows.

**(d) `metric_names co 'MetricA'` (filter)** — correlated SubPlan per candidate row, `loops=200`, index-served:

```
Seq Scan on test_suite_runs
  Filter: ((SubPlan 4) ? 'MetricA'::text)
  SubPlan 2  -- projected metric_names output column (loops=200)
  SubPlan 4  -- filtered condition (loops=200)
    ->  Aggregate
          InitPlan -> Index Only Scan using idx_run_metric_snapshots_run_computed_at ... latest_1
          ->  Sort -> Index Scan using idx_run_metric_snapshots_run_computed_at ... rms_1
```

Filtering on `metric_names` costs one SubPlan evaluation per candidate row — expected and accepted; each evaluation is a cheap index-only latest-computation lookup plus one indexed range scan, never a scan of the whole snapshot table. (Two SubPlans appear here only because this throwaway diagnostic query both selects and filters on `metric_names`; a real row query pays it twice only if it does both.)

Full untrimmed plans are preserved in git history (`openspec/changes/add-test-suite-runs-query-entity/explain-plans.md` as of task 3.1, deleted once folded into this doc).

## Snapshot-only refs, no fallback

`suite_type`, `deployment_ref::*`, `mcp_deployment_ref::*` are extracted only from `test_suite_runs.suite_snapshot` via `JsonPathAccessor` two-level text extraction (`suite_snapshot ->> 'suiteType'`; `suite_snapshot -> 'deploymentRef' ->> '<subKey>'`). `->`/`->>` on a NULL snapshot or a missing key yield NULL — no `CASE`, no exception path. There is **no fallback to `test_suites`**: a run's refs are run-time truth pinned at snapshot time; a later suite edit must not rewrite a completed run's history. Legacy/pre-snapshot runs (`suite_snapshot IS NULL`) simply read all nine fields as null.

## `number_of_runs`: scalar extracted from `run_config`

Unlike the snapshot-only group above, `number_of_runs` reads `test_suite_runs.run_config`, not `suite_snapshot`. `run_config` itself stays in `EXCLUDED_COLUMNS` and out of the projection and out of `filter`/`select`/`sort`/`group_by`; only the derived scalar `(run_config ->> 'numberOfRuns')::integer` — built as `jsonPathAccessor.jsonbAtAsInteger(TEST_SUITE_RUNS.RUN_CONFIG, DSL.val("numberOfRuns"))` — is published, aliased `number_of_runs`, typed `integer`. A missing key yields null (`->>` on a missing key is NULL, cast of NULL is NULL); a non-numeric value makes the cast raise and the whole query fail — deliberate fail-fast, not a `CASE`/guard, since `run_config` is the run's immutable execution contract and bean validation on the write path (`RunConfigDto.numberOfRuns` is `@NotNull @Min(1)`) makes a non-numeric value unreachable via the API. Filtering/sorting on it is not index-served — same cost class as the `suite_type` snapshot extraction; an expression index on `((run_config ->> 'numberOfRuns')::int)` is deferred until a measured need. Field order: after the plain columns, before `suite_type`. Despite the similar name, `number_of_runs` (repetitions per test case, sourced from `run_config`) and `number_of_test_cases` (a plain column) are unrelated counts.

## Latest-computation tiebreak

Two computations of the same run can share `computed_at_ms` (one `Clock` read per computation, fast recomputation). Both the `metric_names` inner subquery and `PostgresRunMetricSnapshotRepository.findLatestComputationId` order by `computed_at_ms DESC, computation_id ASC` — without the tiebreak, `metric_names` and the schema-discovery endpoint could disagree or flip between calls for the same run. `ASC` (the **smallest** id wins) is the system-wide convention, converged with `PostgresEvalSummaryRepository.findLatestComputationId`'s own tiebreak in `enrich-test-suite-runs-overall-score` — see [Computation Versioning](computation-versioning.md) for the full rationale and why the direction is `ASC` rather than `DESC`. `ComputationResolver` (eval-summaries-based "latest" for analytics) is untouched by *this file's* subquery — different table, different resolution path — but now shares the same tiebreak direction.

Migration `V1.34__ReplaceRunMetricSnapshotsRunIndex.sql` replaces `idx_run_metric_snapshots_run (test_suite_run_id)` with `idx_run_metric_snapshots_run_computed_at (test_suite_run_id, computed_at_ms DESC, computation_id DESC)`. That index's third column stayed `DESC` even after the tiebreak convergence to `ASC` — see [Computation Versioning](computation-versioning.md) on why no index migration was taken — so the tiebreak lookup now takes a small incremental sort rather than being fully index-served; the dropped index was a strict prefix of the new one regardless, so every existing `WHERE test_suite_run_id = ?` reader (`findByRunId`, `findByRunIdAndComputationId`, `findLatestComputationId`, FK cascade on run delete) stays served.

## Result-only extension-derived keys: `overall_score_value` and `total_cost`

Two keys are merged onto a `row`-mode result page after this entity's own SQL runs, by registered
`QueryResultPageExtender`s (see [Query result page extension](query-result-page-extension.md) for the
full mechanism/contract): `overall_score_value` (the run's latest computation's run-level `overall`
metric score, from the analytics `metric_score_results` entity) and `total_cost` (the run's total
dial-adas usage cost, opt-in via `query-dsl.extension.test-suite-run.cost.enabled`). Neither is a
column of `bindings()` or `TestSuiteRunsSchemaProvider.baseSchema()` above — both are absent from
`filter`/`select`/`sort`/`group_by` and from `GET /api/v1/queries/entities/schema/test_suite_runs`,
and each is simply omitted (never set to `null`) from a row for which no value could be resolved.
`TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD` / `TOTAL_COST_FIELD` document this explicitly at
the constant.

## Shared constants class as the anti-drift mechanism

`TestSuiteRunQueryFields` (excluded columns, `suite_type` + its snapshot key, the two `RefDescriptor`s for `deployment_ref`/`mcp_deployment_ref`, `metric_names` name/source, `number_of_runs` name/config-key) is the single source both `PostgresTestSuiteRunEntityResolver.bindings()` and `TestSuiteRunsSchemaProvider.baseSchema()` build from. `number_of_runs`'s `source` deliberately reuses the `run_config` `EXCLUDED_COLUMNS` constant — the same column name is both excluded and published as a source, which is intended. A unit test on each class asserts the schema's field-name set equals the resolver's binding-key set and that types agree per field — the two cannot silently diverge.
