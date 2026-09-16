## Why

`GET /api/v1/test-suite-runs/{id}/costs` answers "what did this run cost" with two sequential dial-adas
calls per run (one per `eval.phase`). An enriched run list (issue #196) needs total cost for up to a
page's worth of runs in effectively one round trip — the N+1 pattern doesn't scale to a page of runs.
Two dial-adas query shapes (`regexp_extract` + `group_by`; `sum_if`) were spiked against the real
deployment and both were rejected with `400`. A third shape — an `or` filter across per-run baggage
predicates paired with a `case`/`when`/`then`/`else` select expression that re-derives each row's run id
as a grouping key — was verified working against the real deployment and is the shape this change builds
around.

## What Changes

- Add a 7th kind, `case`, to the shared `Expr` query-model sealed interface (`CaseExpr` +
  `WhenClause` records), so `dial_usage_log` queries can express a `CASE WHEN ... THEN ... ELSE ...`
  select expression through the typed model instead of hand-rolled JSON. Internal entity queries
  (`test_cases`, `eval_summaries`, etc.) explicitly reject `case` — this kind exists only to support
  `dial_usage_log` aggregate queries built directly by `AdasCostQueryBuilder`.
- Add `AdasCostQueryBuilder.buildPageTotalCostQuery(Collection<UUID> runIds)` — one dial-adas aggregate
  query for N runs, filtered by an `or` of per-run baggage `co` predicates (no `eval.phase` predicate —
  total cost spans both phases), grouped by a `case`-derived `run_id` alias, selecting `count()` and
  `sum(total_price)` per group.
- Split the single, ever-growing `AdasAggregateRowDto` into three row DTOs, one per
  `AdasCostQueryBuilder` query shape (`AdasRunAvgCostRowDto`: `count`/`avg_cost`;
  `AdasDeploymentCostRowDto`: `count`/`total_cost`; `AdasBatchRunCostRowDto`: `run_id`/`total_cost`),
  per explicit user feedback that one row type accreting every field any cost query has ever needed
  was becoming unwieldy. `AdasAggregateResponseDto` becomes generic (`AdasAggregateResponseDto<T>`), and
  `DialAdasClient.executeAggregate` takes the row `Class<T>` alongside the query so each `CostService`
  call site gets back the specific row type it expects.
- Add a private `CostService.fetchTotalCosts(Collection<UUID> runIds)` helper (`Map<UUID, Double>`) — no
  separate `RunCostFetcher` component; folded directly into `CostService` per explicit user feedback that
  a dedicated fetcher class wasn't earning its keep once the status/record types it originally returned
  were dropped (see `batch-run-costs` spec / design.md Decision 3). A requested run id absent from the
  grouped response is simply absent from the map, surfaced as `totalCost: null`. A
  `DialAdasClientException` from the single underlying call is not caught — it propagates, so the whole
  request fails with 502/504 (the call is all-or-nothing, so there is no partial-failure case to represent
  per run).
- Add a `CostService` batch method and a new `CostController` endpoint
  (`POST /api/v1/costs/test-suite-runs`, run ids in a JSON body rather than a query string — a page's
  worth of ids would otherwise make for an unwieldy URL) — this is beyond issue #196's literal scope
  (which frames the batch fetcher as an isolated component for a "later consumer" with no endpoint), added
  per explicit user request so the capability is reachable and testable now rather than dead code.
- ~~Add config property `test-suite-run.enriched-list.cost.enabled`~~ — this was added initially (per
  issue #196 naming it), then removed before this change shipped, per explicit user feedback that it
  wasn't needed without a real consumer to inform its shape. See design.md Decision 5.

No breaking changes — this is purely additive (new `Expr` kind rejected everywhere except one internal
builder; new endpoint).

## Capabilities

### New Capabilities
- `batch-run-costs`: page-scoped total-cost lookup for multiple test suite runs in one dial-adas call —
  the `CostService` batch method (fetching folded in directly, no separate fetcher component) and the new
  `CostController` endpoint.

### Modified Capabilities
- `structured-query-model`: the `Expr` sealed hierarchy grows a 7th kind (`case`/`CaseExpr`), with
  `ExprTranslator` explicitly rejecting it for internal entity queries and `QueryParameterResolver`
  supporting parameter substitution inside it.

## Impact

- **New code**: `query/model/CaseExpr.java`, `query/model/WhenClause.java`,
  `service/domain/dto/TotalRunCostResponseDto.java`, `service/domain/dto/TotalRunCostRequestDto.java`
  (originally named `RunCostResponseDto`/`RunCostsBatchRequestDto`, renamed per explicit user feedback to
  disambiguate from the pre-existing single-run `RunCostsResponseDto`),
  `client/dialadas/dto/AdasRunAvgCostRowDto.java`, `AdasDeploymentCostRowDto.java`,
  `AdasBatchRunCostRowDto.java`, `web/controller/CostController.java` additions.
- **Modified code**: `query/model/Expr.java`, `query/service/translate/ExprTranslator.java`,
  `query/service/translate/QueryParameterResolver.java`, `service/domain/AdasCostQueryBuilder.java`,
  `client/dialadas/dto/AdasAggregateResponseDto.java` (now generic), `client/dialadas/DialAdasClient.java`,
  `service/domain/CostService.java`.
  `client/dialadas/dto/AdasAggregateRowDto.java` was deleted, replaced by the three row DTOs above.
- **Config**: none — the `test-suite-run.enriched-list.cost.enabled` property was added then removed
  within this same change (see Decision 5); `TestSuiteRunProperties`/`application.yml`/
  `docs/configuration.md` carry no trace of it.
- **API**: new `POST /api/v1/costs/test-suite-runs` endpoint (run ids in the request body); OpenAPI
  examples added under `src/main/resources/openapi/examples/`.
- **Spec docs**: `openspec/specs/structured-query-model/spec.md` updated (six kinds → seven).
- **No DB schema changes.**
