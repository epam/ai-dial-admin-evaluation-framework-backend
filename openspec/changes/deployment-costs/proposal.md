## Why

`GET /api/v1/test-suite-runs/{id}/costs` (see `test-suite-run-costs` spec) answers "what did this run
cost" by querying dial-adas filtered by `eval.run.id`. There is no way to answer the complementary
question, "what has a given deployment cost over some period" (e.g. this month vs. last month), because
that requires filtering dial-adas by the `deployment` field and a `request_time` range instead of a run
id — a run-scoped endpoint can't express that. This adds a second, deployment/time-range-scoped cost
query so callers can track spend per deployment over any window they choose.

## What Changes

- New `GET /api/v1/costs` endpoint: given `deploymentId`, `from`, and `to` (inclusive epoch-millisecond
  bounds), returns the total test-case-execution cost and total metric-evaluation cost for that
  deployment in that window, computed via the same two-dial-adas-aggregate-queries shape as the existing
  run-costs endpoint (one per `eval.phase`), but summing `total_price` instead of averaging it, and
  filtered by dial-adas's `deployment` field and a `request_time` `ge`/`le` range instead of
  `eval.run.id`.
- New `CostController` / `CostService` / `DeploymentCostsResponseDto` to host this endpoint, separate
  from `TestSuiteRunController`/`TestSuiteRunService` since it is not run-scoped.
- The existing `RunCostQueryBuilder` is renamed to `AdasCostQueryBuilder` and gains a second query-building
  method (`buildDeploymentAggregateQuery`) alongside its existing one (renamed
  `buildRunAggregateQuery`), since it becomes the shared place that knows the `dial_usage_log` ADAS
  entity shape for both query shapes.
- Fixes the baggage-extraction field to dial-adas's direct, queryable `usage_request_baggage.baggage`
  field (previously read via `json_extract_string(dial_usage_log_payload.request_tags, "baggage")`, now
  a plain `FieldExpr` match). This is bundled into the same rename because the field is read
  by the shared `baggageField()` helper both query shapes use, so it also corrects the *existing*,
  already-shipped run-costs endpoint's outbound query to dial-adas. The run-costs endpoint's response
  contract to API clients (`{avgTestCaseCost, avgMetricEvalCost}`) is unchanged.
- `400 VALIDATION_ERROR` when `from > to` or `deploymentId` is blank. No existence check against DIAL
  Core for `deploymentId` — an unmatched deployment simply yields `null` totals, same as an unmatched
  run-id filter does today.

## Capabilities

### New Capabilities
- `deployment-costs`: querying total test-case-execution and metric-evaluation cost for a deployment
  over an arbitrary caller-supplied time range, backed by dial-adas.

### Modified Capabilities
- `test-suite-run-costs`: the dial-adas correlation requirement now specifies the correct field
  (`usage_request_baggage.baggage`, a direct queryable field) instead of the earlier
  `dial_usage_log_payload.request_tags.baggage` unwrap. The endpoint's
  response contract and scenarios are otherwise unchanged — this is a field correction to the
  outbound query, not a behavior change.

## Impact

- **New code**: `CostController` (web), `CostService` (service.domain), `DeploymentCostsResponseDto`
  (service.domain.dto). `RunCostQueryBuilder` → `AdasCostQueryBuilder` rename (service.domain), with a
  new `buildDeploymentAggregateQuery` method alongside the existing (renamed) `buildRunAggregateQuery`.
- **Existing code touched**: `TestSuiteRunService`'s call site updates to the renamed
  `AdasCostQueryBuilder`/`buildRunAggregateQuery`; the baggage field is corrected to a direct
  `usage_request_baggage.baggage` `FieldExpr`, changing the outbound dial-adas query for the existing run-costs
  endpoint (not its response contract); `RunCostQueryBuilderTest` renamed to `AdasCostQueryBuilderTest`
  and extended, with its wire-shape assertion updated to the corrected field.
- **API surface**: adds `GET /api/v1/costs?deploymentId=&from=&to=`. No changes to any existing endpoint
  contract.
- **External dependency**: reuses the existing `DialAdasClient`/`dial-adas` integration and
  `dial.adas.*` configuration as-is — no new config properties, no schema/DB changes.
- **Docs**: `docs/key-packages.md` needs updating to list the renamed/new classes.
