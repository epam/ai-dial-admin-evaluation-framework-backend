## Context

Issue #313 asks for a system-wide total of evaluation cost for a period, optionally scoped to deployment ids (see `proposal.md`). Cost is not stored in this backend: it lives in dial-adas `dial_usage_log` (`deployment_price` — the request's own cost —, `total_price` — the request plus everything it initiated —, `deployment`, `request_time`, `usage_request_baggage.baggage` carrying `eval.run.id=<uuid>` and `eval.phase=execution|metric-evaluation`). All existing cost endpoints (`/api/v1/costs/...`) are therefore thin layers over `DialAdasClient.executeAggregate(StructuredQuery, rowType)`:

- `AdasCostQueryBuilder` builds the `StructuredQuery` (deployment aggregate, per-run batch aggregate, `phaseCostColumn(...)` phase split, all summing `DEPLOYMENT_PRICE_FIELD` since commit `68a8d9d9`).
- `CostService` runs it and maps rows to response DTOs, returning `null` costs when `count == 0`.
- `CostController` (`/api/v1/costs`) exposes it; `DialAdasClientException` is already mapped to 502/504.

There is no database change: no meta/analytics table, no Flyway migration, no transaction (the service is not `@Transactional`, same as the rest of `CostService`).

## Goals / Non-Goals

**Goals:**
- One endpoint returning total cost, split into test-case execution and metric evaluation, for `[from, to]`, optionally filtered by deployment ids.
- One dial-adas call per request regardless of how many deployment ids are given.
- Reuse the existing builder helpers, client, error mapping and null-on-empty semantics.

**Non-Goals:**
- Per-deployment, per-day or per-suite breakdowns.
- Storing cost in the analytics DB or reconciling it with run tombstones.
- Pagination, caching or any new configuration property.
- Changing the existing cost endpoints.

## Decisions

### D1. Data source is dial-adas, not the analytics DB
The analytics tables have no price, token or deployment column, so a DB query cannot answer the story. Using dial-adas matches every existing cost endpoint and needs no migration.
*Alternative:* persist cost per result row in analytics at run time — rejected as a large, backfill-requiring change for a read-only dashboard figure.

### D2. `POST /api/v1/costs/total` with a JSON body
Deployment ids contain slashes and are stored percent-encoded, and the list can be long; a body avoids query-string length limits and slash/encoding quirks (cf. `docs/patterns/slash-path-values.md`). `POST /test-suite-runs` is the precedent for a read-only POST carrying a list. Static path `/total` does not collide with `/deployment/**` or `/test-suite-run/{id}`.
*Alternative:* `GET` with a repeated `deploymentIds` query param — rejected by the user in favour of POST.

Contract:
```
POST /api/v1/costs/total
{ "from": 1767225600000, "to": 1769903999999, "deploymentIds": ["gpt-4o", "applications/public/my-app__0.0.1"] }

200 { "totalCost": 0.0123, "testCaseCost": 0.0100, "metricEvalCost": 0.0023 }
200 { "totalCost": null, "testCaseCost": null, "metricEvalCost": null }   // no matching usage rows
```
Errors: 400 (missing `from`/`to`, `from > to`, `deploymentIds` over the cap, blank id entry), 502 / 504 (dial-adas failure, existing handler).

### D3. A single aggregate query, filtered with an `or` of `eq`
New `AdasCostQueryBuilder.buildTotalAggregateQuery(long fromMs, long toMs, Collection<String> deploymentIds)` returns a `StructuredQuery` (`QueryMode.AGGREGATE`, no group-by) on `dial_usage_log`:
- select: `count()`, `sum(<price field>)` as `total_cost`, and the two `phaseCostColumn(...)` splits (`test_case_cost`, `metric_eval_cost`) over the same `<price field>`, where the field is `deployment_price` without ids and `total_price` with ids (D3a);
- filter `AND`: `request_time ge from`, `request_time le to`, baggage `co "eval.phase="` (intentionally a substring match on `eval.phase=`, as in the existing builders; restricts to evaluation traffic, both phases), plus — only when ids are given — the deployment predicate: a bare `deployment eq id` for one id, otherwise `LogicalNode(OR, [deployment eq id, ...])`. This is the shape `buildPageTotalCostQuery` already sends to dial-adas (`or` of per-run predicates, unwrapped for a single entry); the 1000-id cap bounds the payload.

`totalCost` is summed directly (not `testCaseCost + metricEvalCost`), so any usage carrying `eval.phase=` but a future third phase value is still counted.
*Alternatives:* one query per deployment (N+1, rejected); `deployment in [ids]` via `ComparisonOp.IN` + `ArrayExpr` of string `ValueExpr`s (shorter payload, but the only in-repo precedents — `TestCaseScoreComputer`, `OverallScoreTestSuiteRunsPageExtender` — use UUID items on the internal analytics DB, so dial-adas acceptance of string `in` is unproven; may be adopted later as an optimisation once verified against dial-adas).

### D3a. Price field depends on the scope (`deployment_price` vs `total_price`)
dial-adas exposes two cost fields: `deployment_price` is the cost of this specific request only (excluding requests it directly or indirectly initiated); `total_price` is this request plus all related requests. Both are computed at request-execution time, so neither reflects later adjustments (pricing-config fixes, provider discounts). Applications carry a `total_price` but no `deployment_price`; model rows carry both with the same value.

Verified against production usage-log data (one month of evaluation traffic): per deployment, model rows had `sum(deployment_price) = sum(total_price)`, application rows had no `deployment_price`, and summing `total_price` over every tagged row gave roughly 2.4× the sum of `deployment_price`, i.e. nested requests were counted at every ancestor.

Decision: `buildTotalAggregateQuery` sums
- `deployment_price` when no ids are given (every tagged row matches, so application rows and the nested rows they initiated overlap; the leaf field counts each request once — same rule as run-scoped queries since `68a8d9d9`/`1c80ac29`);
- `total_price` when ids are given (the `deployment` filter keeps nested rows out, and `total_price` is the only field that carries an application's cost — same rule as `/costs/deployment/**` since `1c80ac29`).

`phaseCostColumn` takes the price field as a parameter so the split always uses the same field as the total.

*Known limitation:* with ids, an application listed together with a deployment it calls is counted twice (the callee's cost is inside the application's `total_price` and also its own row). The system does not de-duplicate; documented in the spec and OpenAPI description.
*Also noted:* an application's `total_price` can exceed the tagged leaf cost beneath it (untagged descendants, or the same application nested inside itself), so a filtered total for an application and the unfiltered total are not guaranteed to reconcile.

### D4. Row DTO with `count`
`AdasBatchRunCostRowDto` has no `count` and a `run_id`, so it does not fit. Add `AdasTotalCostRowDto` (`count`, `total_cost`, `test_case_cost`, `metric_eval_cost`) in `client.dialadas.dto`, mirroring `AdasDeploymentCostRowDto`. `count` drives the null semantics.

### D5. Null semantics
Null/empty response, empty row list, or `count == 0` → all three costs `null` (same as `CostService.fetchTotalCost`, and as the batch endpoint when a run has no usage). Note this differs from `GET /deployment/**`, which returns a per-phase `null`: a single query using `phaseCostColumn` yields `0` for a phase with no usage inside a non-empty period, as in `batch-run-costs`. A non-empty period with only one phase present returns the other phase as `0.0` (the `case ... else 0` in `phaseCostColumn`), not `null`.

### D6. Validation and constants
- `TotalCostRequestDto` (`@Valid`): `@NotNull Long from`, `@NotNull Long to`, optional `List<String> deploymentIds` with `@Size(max = ValidationConstants.MAX_COST_DEPLOYMENT_IDS)` and non-blank elements. `MAX_COST_DEPLOYMENT_IDS` is a non-configurable constant in `ValidationConstants` (value 1000, same as `MAX_BATCH_RUN_IDS`).
- `from > to` is checked in `CostService.getTotalCosts` and throws `ValidationException("from must be <= to")`, mirroring `getDeploymentCosts`.
- `null` or empty `deploymentIds` means "all deployments" (no `deployment` predicate).
- Deployment ids are passed as sent (percent-encoded, never decoded), matching the existing deployment-cost behaviour.

### D7. Component flow and layering
`CostController.getTotalCost` → `CostService.getTotalCosts(from, to, deploymentIds)` → `AdasCostQueryBuilder.buildTotalAggregateQuery` → `DialAdasClient.executeAggregate(query, AdasTotalCostRowDto.class)` → map to `TotalCostResponseDto`. No new packages, no repository, no transaction; the controller stays in `.web`, DTOs in `.service.domain.dto`, the row DTO in `.client.dialadas.dto`, consistent with `LayeredArchitectureTest`. New classes carry `@LogExecution`. Response DTO fields have `@Schema` examples; the operation gets `@ExampleObject`s plus the JSON files `api-v1-costs-total-POST-request-{minimal,full}.json (request) and api-v1-costs-total-POST-response-200-{minimal,no-usage}.json (response)`.

### D8. Deleted runs
Usage logs are not tombstoned (`RunDeletionFilter` only guards analytics-DB reads), so cost of deleted runs is still included. This is the correct figure for a "money spent" dashboard and is documented in the spec rather than filtered.

### D9. Error handling
Fail-fast: any `DialAdasClientException` propagates and becomes 502/504 via `DefaultExceptionHandler`; there is no partial or degraded result because the whole answer comes from one call.

## Risks / Trade-offs

- [A wide `or` of `eq` is a larger dial-adas payload than `in`] → bounded by the 1000-id cap; switching to `in` later is local to `buildTotalAggregateQuery` and its test.
- [Wide periods or many ids make the dial-adas query slow] → single call, id cap, 504 mapping; no caching for now (can be added later behind the dashboard).
- [Callers must send deployment ids percent-encoded, as stored in the usage log] → documented in the OpenAPI description, same convention as `GET /api/v1/costs/deployment/**`.
- [Floating-point `Double` sums] → same representation as all other cost endpoints; no rounding applied.
- [`eval.phase=` baggage match counts any usage tagged by this framework, including usage from deleted runs] → accepted, see D8.
