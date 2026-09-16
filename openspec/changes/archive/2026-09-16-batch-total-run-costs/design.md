## Context

`AdasCostQueryBuilder` is the single owner of `dial_usage_log`'s query shape (entity name,
`total_price`/`avg_cost`/`total_cost` aliasing, the `usage_request_baggage.baggage` extraction helper).
It builds every query directly as typed `com.epam.aidial.evaluation.query.model.*` records —
`StructuredQuery`/`Expr` are documented (`openspec/specs/structured-query-model/spec.md`, "Implementation
notes") as an intentional wire-contract reuse: this internal model is serialized verbatim as the outbound
dial-adas request body. `DialAdasClient.executeAggregate(StructuredQuery)` always throws
`DialAdasClientException` on any HTTP/timeout failure — it never returns a partial or error-shaped
response body.

The existing single-run cost query (`buildRunAggregateQuery`) filters by `eval.run.id` AND `eval.phase`,
run one call per phase. A batch total-cost query needs a structurally different shape: OR across many
run ids, no phase filter, and a way to attribute each aggregated row back to the run id that produced it
— dial-adas has no native `GROUP BY <extracted-substring>`. Two shapes that would have avoided a new
`Expr` kind were spiked against the real dial-adas deployment and rejected outright:
- `regexp_extract(usage_request_baggage.baggage, ...)` grouped by the extracted run id → `400 unsupported
  function 'regexp_extract'`.
- `sum_if(...)`-style conditional aggregation → bare `400 bad_request`.

A `case`/`when`/`then`/`else` select expression (one `WHEN <run predicate> THEN <run id literal>` per
requested run, `ELSE 'other'`), grouped by the resulting alias, **was verified working** against the real
deployment. Our `Expr` sealed interface has exactly 6 permitted kinds today (`field`, `value`, `param`,
`fn`, `array`, `subquery`) — there's no `case` kind, so this verified shape cannot be built through the
typed model as-is.

`query.model` is a documented "pure carrier package," excluded from `LayeredArchitectureTest`. Exactly
two exhaustive `switch (Expr)` statements exist over its permitted types in the whole codebase —
`ExprTranslator.toField` (translates internal-entity queries) and `QueryParameterResolver.resolveExpr`
(substitutes `param` placeholders) — both compile-fail the moment a 7th kind is added, which is exactly
the safety net that makes this change well-bounded: the compiler enumerates every call site that needs a
decision.

`StructuredQueryController` is a generic endpoint that deserializes arbitrary client-submitted
`StructuredQuery` bodies and routes them through `ExprTranslator` for internal entities — so a new `Expr`
kind is reachable from that endpoint too, which is why `ExprTranslator` must explicitly reject `case`
rather than silently ignoring it.

## Goals / Non-Goals

**Goals:**
- Compute total cost (both phases combined) for up to a page's worth of runs in one dial-adas HTTP call.
- Represent the verified CASE-based query through the typed `Expr`/`StructuredQuery` model, not
  hand-rolled JSON, consistent with every other query `AdasCostQueryBuilder` builds.
- Give the batch total-cost lookup a real caller now (`CostService` + a `CostController` endpoint)
  instead of leaving it dead code with no consumer.

**Non-Goals:**
- Wiring an enriched run-list decorator that attaches batch costs to a page of runs — that consumer
  doesn't exist yet and is out of scope for this change (see Decision 5: the config property this change
  originally added ahead of that consumer was removed per explicit user feedback that it wasn't needed
  yet either).
- Supporting `case` expressions for internal entity queries (`test_cases`, `eval_summaries`, etc.) via
  `ExprTranslator` — only `dial_usage_log`, built directly by `AdasCostQueryBuilder`, needs it.
- Existence validation of requested run ids against `TestSuiteRunRepository` — an unknown id is
  indistinguishable from a real run with no usage and resolves to `NO_DATA`, same posture as the existing
  deployment-costs endpoint's unmatched-filter-value behavior.
- Parallelizing or batching across multiple dial-adas calls — the whole point is exactly one call.

## Decisions

**1. Add `CaseExpr`/`WhenClause` as a 7th `Expr` kind, rejected for internal entities.**
`record CaseExpr(List<WhenClause> when, @JsonProperty("else") Expr elseExpr) implements Expr` (name
`case` in `@JsonSubTypes`); `record WhenClause(@JsonDeserialize(using = FilterNodeDeserializer.class)
FilterNode when, Expr then)`. The nested `FilterNode` needs its own `@JsonDeserialize` annotation because
`FilterNodeDeserializer` is wired per-field (on `StructuredQuery.filter`/`.having`), not globally on the
`FilterNode` interface — `WhenClause.when()` is a new such field. `ExprTranslator.toField` gets `case
CaseExpr _ -> throw new ValidationException(...)`, mirroring the existing `ArrayExpr` rejection.
`QueryParameterResolver.resolveExpr` gets a recursive substitution branch that reuses the existing
`resolveFilter(FilterNode, Map<String,Expr>)` helper for each `WhenClause.when()` — mechanical, since that
helper already walks arbitrary filter trees. *Alternative considered*: hand-build the CASE query as a raw
JSON tree bypassing the typed model — rejected, since `AdasCostQueryBuilder` (and the whole
`query.model`/`DialAdasClient` pipeline) has no precedent for untyped JSON construction, and it would
silently reintroduce exactly the kind of shape mismatch that made the first two spiked queries fail.

**2. `AdasCostQueryBuilder.buildPageTotalCostQuery(Collection<UUID> runIds)`, no phase filter.**
New method beside `buildRunAggregateQuery`/`buildDeploymentAggregateQuery`, same class. Filter: OR of
per-run `baggageContains(baggageField(), "eval.run.id=" + id)` (single predicate unwrapped, no `OR` node,
when only one run id is requested). Select: a `run_id`-aliased `CaseExpr` (one `WhenClause` per run id,
same baggage predicate as the filter, `then` = the run id as a string literal; `else` = a constant
`"other"` sentinel matching the verified query), plus `count()` and `sum(total_price)` aliased
`total_cost`. `group_by: ["run_id"]` (the select alias — `StructuredQuery.groupBy` is already `List<String>`,
no model change needed there). Deliberately **no** `eval.phase` predicate, since total cost spans both
`execution` and `metric-evaluation` phases — unlike every other query this builder produces.

**3. `AdasCostQueryBuilder`'s three query shapes each get their own dial-adas row DTO
(`AdasRunAvgCostRowDto`, `AdasDeploymentCostRowDto`, `AdasBatchRunCostRowDto`), not one shared,
ever-growing `AdasAggregateRowDto`; `CostService.getRunCosts(List<UUID>)` computes the batch total-cost
map directly (no separate `RunCostFetcher` component), returning a plain `Map<UUID, Double>` internally
with no `NO_DATA`/`AVAILABLE`/`UNAVAILABLE` status.** dial-adas only returns rows for run ids that matched
at least one usage row, so the private `fetchTotalCosts` helper diffs the requested id set against the
returned rows: no row (or the `"other"` bucket) for a given id → absent from the map, surfaced as
`totalCost: null`; a row present → its `total_cost`. `DialAdasClient.executeAggregate` always throws on
any failure and never partially succeeds, so a `DialAdasClientException` is **not** caught here — it
propagates to the caller, surfacing as an HTTP 502/504 for the whole request (same as every other cost
endpoint), since there is no partial-failure case to reconcile for a single HTTP call.
*Revised three times after the endpoint was implemented, all per explicit user feedback*: (1) an initial
three-state `RunCostStatus` enum (`AVAILABLE`/`NO_DATA`/`UNAVAILABLE`, with a `RunCost(Double value,
RunCostStatus status)` record) was dropped — `totalCost: null` alone communicates "no data for this run"
just as well as an explicit `NO_DATA` status would, and a whole-batch dial-adas failure is better
represented as an actual HTTP error than as a per-item status embedded in a 200 response; (2) the
fetching logic originally lived in a dedicated `RunCostFetcher` `@Component` (`fetchTotalCosts(Collection<UUID>)`,
injected into `CostService`) — folded directly into `CostService` as a private method once the
`RunCostFetcher` abstraction no longer earned its keep: with the status/record types gone, it was a single
private method's worth of logic with exactly one caller, so the extra class, interface boundary, and
constructor-injected dependency were pure indirection; (3) the single `AdasAggregateRowDto` (which had
grown to carry `count`/`avg_cost`/`total_cost`/`run_id` — a superset of fields no individual query result
actually populates, since each of the three query shapes emits only some of them) was split into three
focused row DTOs, one per query shape, each declaring only the fields that shape's `select` actually
emits. This required generifying `AdasAggregateResponseDto<T>` and `DialAdasClient.executeAggregate` (now
`<T> AdasAggregateResponseDto<T> executeAggregate(StructuredQuery query, Class<T> rowType)`, using
`ParameterizedTypeReference.forType(TypeUtils.parameterize(...))` — Commons Lang3's `TypeUtils`, already a
project dependency — to build the runtime generic type token RestClient needs to deserialize a
parameterized response body), so each `CostService` call site now passes the concrete row type it expects
rather than one row type accreting every field any cost query has ever needed.
*Alternative considered*: treat "no row" as `0.0` — rejected, since a run with zero actual cost is
indistinguishable from a run dial-adas has no data for at all; `null` still carries that distinction from
a real `0.0`, it just no longer needs a dedicated status field (or a dedicated fetcher component) to say
so.

**4. Expose the batch total-cost lookup through `CostService` + a new `CostController` endpoint now, per
explicit user decision.** Issue #196 frames the batch fetcher as an isolated component for a "later
consumer" with no endpoint. Rather than ship an unreachable capability gated behind a config flag nothing
reads, add `CostService.getTotalRunCosts(List<UUID> runIds)` (validates non-empty, `size() <=` a new
`ValidationConstants.MAX_BATCH_RUN_IDS`, inline `ValidationException` checks — same convention as the
existing `getDeploymentCosts` `from > to` check) and `POST /api/v1/costs/test-suite-runs` on
`CostController` (`getTotalRunCosts`), accepting a JSON body (`TotalRunCostRequestDto { List<UUID>
runIds }`) via `@Valid @RequestBody` rather than a query parameter — a page's worth of run ids (up to
`ValidationConstants.MAX_BATCH_RUN_IDS`) would otherwise make for an unwieldy query string. *Revised from
an initial `GET ...?runIds=...` design* (comma-separated `@RequestParam`, mirroring
`RunComparisonController#compare`'s two-id case) after the endpoint was implemented, per explicit user
feedback that a GET with a long id list is undesirable; POST with a body sidesteps the query-string-length
concern entirely and, as a side effect, lets `@Valid` bean validation on the request DTO be unit-tested
directly (unlike method-level `@Size` on a `@RequestParam`, which needs a real `ApplicationContext`'s AOP
proxy). Response: `List<TotalRunCostResponseDto(UUID runId, Double totalCost)>`, ordered per the caller's
requested `runIds`. This is explicitly beyond issue #196's literal scope, added because it's useful for
manual testing and avoids shipping dead code. *Also renamed*: the response DTO
(`RunCostResponseDto`), request DTO (`RunCostsBatchRequestDto`), and service method
(`CostService.getRunCosts(List<UUID>)`) were renamed to `TotalRunCostResponseDto`,
`TotalRunCostRequestDto`, and `CostService.getTotalRunCosts(List<UUID>)` respectively, per explicit user
feedback — the original names were easy to confuse with the pre-existing single-run
`RunCostsResponseDto`/`CostService.getRunCosts(UUID)` (average cost, not total cost), which is exactly
the ambiguity `totalRunCost` in the name resolves.

**5. Config property `test-suite-run.enriched-list.cost.enabled` — added, then removed before this
change shipped.** Issue #196 names this property as part of the batch-cost unit of work, so it was
initially added: `TestSuiteRunProperties` got a nested `EnrichedList.Cost.enabled` (`Boolean`,
`@NotNull`), default `true` in `application.yml`
(`test-suite-run.enriched-list.cost.enabled` / `TEST_SUITE_RUN_ENRICHED_COST_ENABLED`), documented in
`docs/configuration.md` with `Applied when` noting no consumer exists yet. *Reversed per explicit user
feedback* ("not sure we need it now"): with no enriched-list consumer built or imminently planned,
settling the property's shape ahead of time bought nothing but a config key nobody reads and a config-docs
row that could confuse a future reviewer — removed entirely (`TestSuiteRunProperties`, `application.yml`,
`docs/configuration.md`). If and when the enriched-list decorator is actually built, its own change can
add whatever config shape it turns out to need, informed by the real consumer rather than guessed ahead
of it.

## Risks / Trade-offs

- **[Risk] `CaseExpr` widens the shared `Expr` model, reachable from the generic
  `StructuredQueryController`** → a client could submit a `case` expression against `test_cases` or
  `eval_summaries`. **Mitigation**: `ExprTranslator.toField` explicitly throws `ValidationException` for
  `CaseExpr` on every internal-entity path — the same rejection pattern already used for `ArrayExpr` — so
  the new kind is inert everywhere except `AdasCostQueryBuilder`'s direct `dial_usage_log` construction,
  which never goes through `ExprTranslator`.
- **[Risk] The `"other"` sentinel in the CASE `else` branch is a magic string that must stay in sync
  between the query builder and the response parser** → if `CostService`'s row-grouping logic doesn't
  recognize it, an "other" row could be misattributed. **Mitigation**: unparseable/`"other"` run ids are
  skipped via a `UUID.fromString` parse failure rather than a hardcoded string match, so no literal
  `"other"` string needs to stay in sync at all; covered by `AdasCostQueryBuilderTest`'s exact wire-shape
  assertion plus a `CostServiceTest` case that injects an "other" row and asserts it's excluded from the
  result map.
- **[Risk] A very large `runIds` batch produces a large OR filter and a large CASE expression in one dial-
  adas request** → could hit dial-adas request-size or query-complexity limits. **Mitigation**: bounded by
  `ValidationConstants.MAX_BATCH_RUN_IDS` (1000, matching `pagination.max-size`, so a batch can never
  exceed a single page of runs); no further mitigation needed since this mirrors the accepted "cost is
  dial-adas's own query latency" trade-off already documented for the deployment-costs endpoint.

## Migration Plan

Purely additive — no data migration, no Flyway changes, no breaking API changes. Deploy as a normal
release; the new endpoint is inert until called. No rollback concerns beyond a standard revert (the new
`Expr` kind is only reachable in a way that changes behavior through the one new endpoint).
