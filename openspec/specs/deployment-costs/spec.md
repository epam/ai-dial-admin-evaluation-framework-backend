# Deployment Costs Specification

## Purpose
This spec describes the deployment-scoped cost-reporting endpoint: an on-demand total of per-call prices for a deployment's test-case execution and metric-evaluation phases over an arbitrary time range, sourced from dial-adas usage-log data correlated by the deployment id, the request-time range, and the OTel baggage phase tag. It does not persist cost data — costs are computed live from dial-adas on each request.

## Requirements

### Requirement: Get total costs for a deployment over a time range
The system SHALL expose `GET /api/v1/costs/deployment/**`, treating everything after the `deployment`
segment as the deployment ID (so IDs containing slashes are supported as-is), accepting `from` and `to`
(epoch-millisecond `Long`, required, inclusive bounds) as query parameters, and returning the total price
of test-case execution calls and the total price of metric-evaluation calls for that deployment within
`[from, to]`, sourced from dial-adas usage-log data correlated by the `deployment` field, the
`request_time` range, and the OTel baggage execution-phase tag. Status: Implemented.

#### Scenario: Deployment has both execution and metric-evaluation usage data in range
- **WHEN** a client requests `GET /api/v1/costs/deployment/{id}?from={fromMs}&to={toMs}` for a
  deployment with usage-log rows in that window for both phases
- **THEN** the system returns 200 with a body containing `totalTestCaseCost` (the sum of `total_price`
  across dial-adas usage-log rows tagged `eval.phase=execution`, filtered to `deployment={id}` and
  `request_time` between `fromMs` and `toMs` inclusive) and `totalMetricEvalCost` (the same computation
  for rows tagged `eval.phase=metric-evaluation`)

#### Scenario: No usage-log data for one phase
- **WHEN** dial-adas returns zero matching usage-log rows for one of the two phases within the requested
  window
- **THEN** the system returns 200 with that phase's total field set to `null` (not `0`), while the
  other phase's total is still populated if data exists for it

#### Scenario: No usage-log data for the deployment at all
- **WHEN** `deploymentId` does not match any dial-adas usage-log row in the requested window (e.g. a
  typo'd or unknown deployment id)
- **THEN** the system returns 200 with both `totalTestCaseCost` and `totalMetricEvalCost` set to `null` —
  the system does not validate `deploymentId` against DIAL Core and does not return 404 for this case

#### Scenario: from is after to
- **WHEN** a client requests `GET /api/v1/costs/deployment/{id}` with `from` greater than `to`
- **THEN** the system returns 400 with `ErrorCode.VALIDATION_ERROR` and does not query dial-adas

#### Scenario: deploymentId is blank
- **WHEN** a client requests `GET /api/v1/costs/deployment/**` with no path segment after `deployment`
  (an empty deployment ID)
- **THEN** the system returns 400 with `ErrorCode.VALIDATION_ERROR` and does not query dial-adas

#### Scenario: dial-adas is unreachable or times out
- **WHEN** dial-adas does not respond within the configured read timeout, or the connection fails
- **THEN** the system returns 504 with `ErrorCode.UPSTREAM_TIMEOUT` for a timeout, or 502 with
  `ErrorCode.UPSTREAM_ERROR` for a connection/response failure, and does not partially compute a
  total from an incomplete response

### Requirement: dial-adas usage-log correlation by deployment, time range, and phase
The system SHALL query dial-adas's `dial_usage_log` entity via its query DSL
(`POST {dial-adas-base-url}/v1/queries/execute`), filtering rows on a direct equality match of the
`deployment` field against the requested `deploymentId`, a direct `ge`/`le` range match of the
`request_time` field (typed `"timestamp"`, values passed as epoch-millisecond strings) against the
requested `[from, to]`, and a match on the relevant execution phase as `eval.phase=execution` or
`eval.phase=metric-evaluation` within `usage_request_baggage.baggage` — the same
baggage-phase technique used by the existing run-scoped cost query. Status: Implemented.

#### Scenario: Query is scoped to a single deployment, time range, and phase
- **WHEN** the system computes the execution-phase total for deployment `D` over `[from, to]`
- **THEN** the dial-adas query filter requires a `deployment == D` match, a `request_time >= from` match,
  a `request_time <= to` match, and an `eval.phase=execution` match within
  `usage_request_baggage.baggage`, so
  usage-log rows from other deployments, outside the time range, or from the metric-evaluation phase are
  excluded

#### Scenario: Aggregate computed server-side
- **WHEN** the system requests a total for a deployment, time range, and phase
- **THEN** it issues a single `"mode": "aggregate"` query with a `sum(total_price)` selection (aliased
  so the response can be read directly) rather than fetching individual usage-log rows and summing them
  in application code

## Implementation notes
Introduces `CostController` (`web.controller`) exposing `GET /api/v1/costs/deployment/**` (resolved via
the shared `WildcardPathResolver`, same pattern as `DeploymentController`'s by-ID lookups, so
slash-containing deployment IDs are supported), `CostService` (`service.domain`) implementing
`getDeploymentCosts`, `AdasCostQueryBuilder` (`service.domain`, renamed from `RunCostQueryBuilder`)
adding `buildDeploymentAggregateQuery` beside the existing `buildRunAggregateQuery`, and
`DeploymentCostsResponseDto` (`service.domain.dto`). See `design.md` in
`openspec/changes/archive/2026-09-15-deployment-costs/` for the original technical decisions, including
the amendment relocating `getRunCosts` from `TestSuiteRunService` into `CostService`. The route was
restructured post-archive (pre-merge) from a flat `GET /api/v1/costs?deploymentId=&from=&to=` to
`GET /api/v1/costs/deployment/**` so the `/api/v1/costs` resource can grow additional cost sub-resources
without query-param collisions.
