## Context

See [proposal.md](proposal.md) — Why. The constraints that shape the approach:

- `test_suite_runs` resolves on `metaDsl`; the run-level `overall` value lives in `metric_score_result` on the **analytics** datasource. A cross-datasource subquery fails at the database, so the value cannot enter the entity's SQL.
- The query pipeline has exactly one entity hook, `StructuredQueryEntityResolver.rewrite`, and it runs *before* translation. There is no post-execution seam.
- `StructuredQueryExecutor` was split into an interface + package-private `JooqStructuredQueryExecutor` with a `@Primary` pass-through wrapper on this branch (commits `27290f2`, `aa0e4b8`). This design fills that wrapper in.
- The same problem recurs immediately for total run cost (dial-adas, external), so the seam must be general, not a one-off branch.

**Measurement provenance.** The `DISTINCT ON` figures in D6 come from the original investigation. The step-2 figures in D5 were re-measured on 2026-09-23 against Postgres 17.4 with a synthetic `metric_score_result` of 480 000 rows (20 000 runs × 3 computations × 8 score/metric pairs) carrying both real indexes.

## Goals / Non-Goals

**Goals:**
- A general post-execution extension seam that any entity can opt into, with failure isolation owned by the coordinator rather than by each implementation.
- One authoritative definition of "latest computation" across every lookup.
- Page-proportional cost: no per-row fan-out, no query whose cost tracks total table size.

**Non-Goals:**
- Making extension-derived keys queryable (`filter`/`sort`/`group_by`/`select`) or publishing them in schema discovery — see D2.
- The total-run-cost extender. The seam must accommodate it; this change does not add it.
- Any change to `GET /api/v1/test-suite-runs` or to the request contract of `/queries/execute`.
- Index changes. D7 explains why the tiebreak convergence deliberately does not take one.

## Decisions

### D1. A list-of-extenders SPI, not a branch in the executor

`QueryResultPageExtender` has one method, `extend(StructuredQuery, QueryResultPage)`. Each implementation decides for itself whether it applies and returns the page unchanged when it does not, so the coordinator holds no per-entity knowledge and adding the cost extender is a new bean plus nothing else.

`JooqStructuredQueryExecutorExtender` (`@Primary`) injects `List<QueryResultPageExtender>` and folds the page through each in bean order, delegating execution to the concrete `JooqStructuredQueryExecutor` (not the interface — avoids self-injection).

*Alternatives rejected.* Widening `StructuredQueryEntityResolver` with a post-hook ties extension to the entity, so one entity cannot carry two independent derived values and a cross-entity extender has nowhere to live. A direct branch in the executor puts analytics knowledge in the entity-agnostic engine.

### D2. Extension-derived keys are result-only

A derived key is merged after SQL and after paging, from another database. It therefore cannot participate in `filter`, `sort`, `group_by` or `select`, and referencing it there must keep returning 400 as an unknown field. It is not added to `PostgresTestSuiteRunEntityResolver.bindings()` or `TestSuiteRunsSchemaProvider`.

The consequence is stated once, generically: **a row's keys are the projection's keys plus zero or more extension-derived keys.** Schema discovery publishes the *queryable* field set, so a result row may legitimately carry more keys than the published schema — publishing them would advertise fields the executor rejects. Clients and tests assert containment, never exact key equality.

*Alternative rejected.* Publishing the key in schema discovery would make `GET .../schema/test_suite_runs` self-inconsistent: the endpoint would advertise a field that `/queries/execute` refuses.

### D3. The coordinator owns failure isolation

The wrapper wraps **each** extender in its own try/catch, logs at warn with the exception as the last SLF4J argument, and continues with the remaining extenders. A query that succeeded must never fail because a derived value could not be added.

This is a coordinator guarantee, not an obligation on implementations: "implementations must never throw" is unenforceable, and one badly-behaved extender would otherwise turn a working query into a 500.

Implementation contract, enforced by the coordinator where it can be and by review where it cannot: strictly additive; never overwrites a key the page already carries; never mutates jOOQ's row maps in place (`intoMaps()` hands back mutable `LinkedHashMap`s it owns); preserves key order; omits an absent value rather than inserting null; carries `totalCount` over unchanged.

### D4. Two bounded queries per page

`OverallScoreTestSuiteRunsPageExtender` runs, per page:

1. per-run latest computation for the whole page, one statement (D6);
2. one `StructuredQuery` against the `metric_score_results` entity (D5).

Both are bounded by page size, which `StructuredQueryBuilder.MAX_LIMIT` / `pagination.max-size` already caps at 1000. Nothing deployment-specific to tune, so no new configuration property.

Step 2 goes through the wrapped `JooqStructuredQueryExecutor` rather than `StructuredQueryService`, which would re-enter the `@Primary` wrapper.

*Why step 1 is not skipped.* `metric_score_result` carries `test_suite_run_id` and `computed_at_ms`, so the newest `overall` row per run looks like a one-query shortcut. It answers "latest computation *with an overall row*", not canonical "latest". A newer computation can legitimately lack the overall row — overall is skipped when a run has several numeric metrics and no `overallScore` definition, Phase 3 failure is non-fatal, and eval summaries written via the public batch `POST` get no scores. The listing would then show a stale score that the run's detail view (`"latest"` sentinel) does not.

*Skip conditions*, leaving the page untouched: `aggregate` mode; the projection carries no `id` to key on; a row's `id` is not a UUID; a row already carries the key (a client alias wins); the `metric_score_results` entity is not registered (non-Postgres analytics vendor).

### D5. The step-2 filter must lead with `test_suite_run_id`

```
test_suite_run_id in [page row ids]
  AND computation_id in [resolved ids]
  AND metric_score_name eq 'overall' AND metric_name eq 'overall'
```

The run-id list is redundant for correctness — `computation_id` is globally unique — but load-bearing for the plan. It is the leading column of both `uq_metric_score_result_natural_key` (`test_suite_run_id, computation_id, metric_score_name, metric_name`) and `idx_metric_score_result_run_computation`; without it neither index is usable on its own terms and the query's cost tracks total table size instead of page size.

| page | filter | plan | buffers | rows discarded |
|---|---|---|---|---|
| 25 | `computation_id` only | Index Scan, non-leading `Index Cond` — scans the whole index | 1 172 | 175 |
| 25 | **+ run ids** | Index Scan, leading `Index Cond` | **22** | 175 |
| 1000 | `computation_id` only | **Parallel Seq Scan** | 12 638 | 159 667 |
| 1000 | **+ run ids** | Index Scan | **730** | 7 000 |

Postgres 17 collapses the two `in` lists into a single index scan advancing through both array keys, so this stays linear in page size — no cross product of probes, and no need for a row-wise or `OR`-of-pairs formulation (measured worse: 3 632 buffers at page 1000).

*Alternative noted, not chosen.* Filtering on run ids alone and matching the computation in Java is marginally faster (994 buffers / 1.95 ms at page 1000) but returns every computation's overall row per run, so its cost scales with recomputation depth rather than page size. Keep it in reserve if a deployment accumulates many computations per run.

The unique index bounds the result to one row per (run, computation) **only because both name predicates are pinned** — the index is four columns.

### D6. Batch latest-computation via `unnest` + `LATERAL`

`EvalSummaryRepository.findLatestComputationIds(Collection<UUID>)`, resolving a page in one statement:

```sql
unnest(ids) CROSS JOIN LATERAL (
  … ORDER BY computed_at_ms DESC, computation_id ASC LIMIT 1
)
```

One index descent per run on `idx_eval_summaries_run_computed_at`, stopping at the first tuple, plus at most one heap fetch per run while the latest computation is not yet vacuumed. A run with no eval summaries is absent from the returned map, never mapped to null.

*Alternative rejected.* `DISTINCT ON (test_suite_run_id) … WHERE test_suite_run_id IN (…)` returns the same rows but feeds every eval-summary tuple of every run on the page into its `Unique` node, heap-fetching every not-yet-vacuumed one — exactly the freshly computed runs a listing's first page shows (1 000 runs × 200 rows per computation: 81 ms / 200 000 heap fetches vs 2.5 ms / 1 000).

Exposed to callers as `ComputationResolver.resolveLatest(Collection<UUID>)`, keeping `ComputationResolver` the single authority for "latest" and keeping the extender out of a foreign domain's repository.

### D7. One system-wide tiebreak, `computed_at_ms DESC, computation_id ASC`, with no index change

Two computations of one run can share `computed_at_ms` (one `Clock` read per computation), leaving `ORDER BY computed_at_ms DESC LIMIT 1` ambiguous. The three existing lookups already disagree in that tie:

| lookup | table | order |
|---|---|---|
| `PostgresEvalSummaryRepository.findLatestComputationId` | `test_case_eval_summaries` | `computed_at_ms DESC` — **no tiebreak** |
| `PostgresRunMetricSnapshotRepository.findLatestComputationId` | `run_metric_snapshots` | `… , computation_id DESC` |
| `metric_names` subquery, `PostgresTestSuiteRunEntityResolver` | `run_metric_snapshots` | `… , computation_id DESC` |

The eval-summary lookup is nondeterministic in principle and de-facto returns the *smallest* id when served by its index; the snapshot lookups return the *largest*. The divergence is invisible today because the two families never surface in one response. This change makes it visible — `overall_score_value` (eval-summary path) lands next to `metric_names` (snapshot path) in one listing row, where a tied run would show one computation's score beside another computation's metric catalog. All four lookups therefore converge on `computation_id ASC`.

*Why ASC and not DESC.* `idx_eval_summaries_run_computed_at` is `(test_suite_run_id, computed_at_ms DESC, computation_id)` — third column ascending. `ASC` is served in index order at zero cost on the hot path (list, count, aggregate, export, preview and the `"latest"` sentinel all route through it); `DESC` would force an incremental sort over the whole latest-`computed_at_ms` group (401 rows read vs 1). `computation_id` is a random UUID, so neither end of a tie is semantically preferable — the direction is chosen by which is already free on the largest table.

*Why no index change.* `idx_run_metric_snapshots_run_computed_at` is `(…, computed_at_ms DESC, computation_id DESC)` and a btree backward scan reverses every column, so the snapshot path's now-mixed `DESC, ASC` order is not index-served and takes a small incremental sort. Its tie group is one run's TSMD catalog at one millisecond — single digits to low tens of rows. Re-pointing that index is a meta migration on a small table and stays available if that stops holding.

*Behaviour note.* This is a real change on the four `ComputationResolver` call paths (`EvalSummaryService`, `EvalSummaryExportService`, `MetricScoreLatestComputationDefaulter`, `RunComparisonService`) whenever the planner does not use the index — not merely a formalization of today's de-facto ordering.

### D8. Placement, datasources and transaction boundaries

The extender lives in `query.service.repository` beside the SPI and the executor, which `LayeredArchitectureTest` folds into the `service` layer — so its dependency on `ComputationResolver` (`service.domain.analytics`) is service→service and allowed. `MetricScoreLatestComputationDefaulter` is the existing precedent for exactly this dependency from exactly this package.

*Deviation acknowledged.* The project rule is that specialized components live in `service.domain.*`. This one does not, because it must reach the package-private `JooqStructuredQueryExecutor` and belongs with the SPI it implements; the cohesion of the seam wins over the placement rule, and the precedent above shows the dependency direction is already accepted here.

Both extender queries run on the **analytics** datasource, *after* the meta query has returned. They are plain reads, join no transaction, and must not be given `@Transactional` — the meta read is already closed and a dual-datasource transaction would be wrong here.

Flow: `StructuredQueryService` → `@Primary` wrapper → `JooqStructuredQueryExecutor` (meta) → wrapper folds each extender → extender: `ComputationResolver.resolveLatest` (analytics) then `metric_score_results` via the concrete executor (analytics) → merged page → `StructuredQueryController` → `jsonbRowConverter` → `StructuredQueryResultDto`.

**Constants.** `MetricScoreConstants` gains the `metric_score_results` entity name (today a literal duplicated in `PostgresMetricScoreResultEntityResolver` and `MetricScoreResultSchemaProvider`, both repointed) plus `metric_score_name` / `metric_name`. Its class javadoc scopes it to the metric-score-statistics bounded context, whose reading so far has been the `eval_summaries`-based statistics; widen it to the metric-score context as a whole so the additions do not quietly stretch "one constants class per bounded context". `TestSuiteRunQueryFields` holds the `overall_score_value` key, documented as extension-only and deliberately absent from bindings and the schema provider.

### D10. Test invariants that must survive the edits

Four existing tests change (listed in proposal.md — Impact). Two carry reasoning worth preserving, because the obvious "fix" for each is wrong:

- `TestSuiteRunStructuredQueryFunctionalTests.emptySelectProjectsExactlyTheEntityFields` keeps passing after this change **by accident** — its fixture run has no eval summaries, so the extender adds nothing. Moving it to containment makes the invariant deliberate; leaving it alone would leave a test that silently stops testing what its name says.
- `QuerySchemaDiscoveryFunctionalTests.shouldMatchTestSuiteRunsSchemaToExecutor` asserts exact key equality over an **unfiltered** `limit 10` query in a shared test database where sibling nested classes create runs that *do* have `overall` scores, so `rows().get(0)` may be one of them. The failure is order-dependent. Do not "fix" it by adding a sort — pin the query to the run the test creates *and* drop the exact-equality assertion; a sort would hide the coupling without removing it.

### D9. Docs

New `docs/patterns/query-result-page-extension.md` (the seam, the open key set, the contract, why the value cannot be an entity field) with an AGENTS.md Unique Patterns row; updates to `docs/patterns/{computation-versioning,test-suite-runs-query-entity,query-dsl-entity-resolution}.md` for the tiebreak convergence; `openspec/specs/README.md` row for the new capability.

## Risks / Trade-offs

- **A slow or failing analytics database now costs every `test_suite_runs` listing two extra round trips.** → The coordinator catches per extender (D3) and returns the page unextended, so the listing degrades rather than fails; the queries are page-bounded and index-served (D5, D6).
- **The tiebreak change alters resolution on four existing call paths.** → The new ordering is deterministic where the old one was not, and matches today's de-facto result whenever the index serves the query; functional coverage pins the cross-path invariant (a tied run's `metric_names` and `overall_score_value` resolve to the same computation).
- **The snapshot path loses index-ordered tiebreaking.** → Tie group is a single run's metric catalog at one millisecond; a migration to restore it is held in reserve (D7).
- **Result rows are no longer a closed key set, which can surprise strict clients.** → Stated once as a requirement rather than per key (D2), and the two tests asserting exact key equality move to containment.
- **A non-finite value (`NaN`, `±Infinity`) is a `Number`, passes the writer's null gate, and would serialize as the string `"NaN"` where the FE expects a number.** → Confirm during implementation whether any path can produce one; add a guard only if reachable.
- **Single-implementation SPI risks being over-general.** → The second implementation (total run cost) is already specified and imminent; if it were not, a direct branch would be the right call.

## Migration Plan

Deployment and rollout: see proposal.md — Impact. Rollback is removing the extender bean: the seam then folds an empty list and the page is exactly what the executor returned. The tiebreak convergence (D7) is independent of the seam and can ship on its own if the extender is deferred.
