## Why

The system-health dashboard needs the **total cost of evaluations across the whole system for a period** (GitHub issue #313, release-0.24). Today cost can only be read per run (`GET /api/v1/costs/test-suite-run/{id}`, `POST /api/v1/costs/test-suite-runs`) or for a single deployment (`GET /api/v1/costs/deployment/**`). There is no system-wide aggregate, and no way to scope one to a chosen set of deployments.

## What Changes

- **Implemented** — new endpoint `POST /api/v1/costs/total` on `CostController`. Body: `from`, `to` (epoch ms, inclusive) and optional `deploymentIds` (list). Response: `totalCost`, `testCaseCost`, `metricEvalCost`.
- **Implemented** — one dial-adas `aggregate` query over `dial_usage_log` per request, no N+1 per deployment:
  - sums `deployment_price` (the request's own cost; counts nested requests once) when no deployment ids are given, and `total_price` (the request plus everything it initiated; the only field that carries an application's cost) when ids are given — see design D3a;
  - filters by `request_time` in `[from, to]` and by the baggage tag `eval.phase=` so only evaluation traffic (execution + metric-evaluation) is counted;
  - when `deploymentIds` is non-empty, adds an `or` of `deployment eq <id>` predicates (the shape already used in dial-adas queries by `buildPageTotalCostQuery`); ids are matched as sent, still percent-encoded.
- **Implemented** — `totalCost` is the sum across both phases; `testCaseCost` / `metricEvalCost` are the per-phase split from the existing `phaseCostColumn(...)` helper. All three are `null` when dial-adas returns no rows (as in `deployment-costs` and `batch-run-costs`); a phase with no usage inside a non-empty period is `0`, as in `batch-run-costs`.
- **Implemented** — validation: `from`/`to` required, `from > to` → 400, `deploymentIds` capped by a new constant `ValidationConstants.MAX_COST_DEPLOYMENT_IDS` (→ 400 when exceeded); omitted/empty list = all deployments.
- **Implemented** — OpenAPI annotations and examples for the new endpoint; `docs/key-packages.md` update.
- No **BREAKING** changes. Existing cost endpoints are untouched.

### Current state

- **Implemented**: dial-adas client (`DialAdasClient.executeAggregate`), `AdasCostQueryBuilder` (deployment aggregate, per-run batch aggregate, `phaseCostColumn`), `CostService`, `CostController`, 502/504 mapping for `DialAdasClientException`.
- **Implemented**: everything listed under *What Changes* (see tasks.md for the one optional follow-up, 4.4).

### Goals / Non-goals

- Goals: one system-wide (optionally deployment-scoped) total with phase split for a period; single dial-adas call; same error and null semantics as existing cost endpoints.
- Non-goals: per-deployment or per-day breakdown; persisting cost in the analytics DB; hiding the cost of deleted runs (dial-adas has no run tombstone, so their usage still counts — documented as known behaviour); pagination.

## Capabilities

### New Capabilities
- `total-costs`: system-wide total evaluation cost (test-case execution + metric evaluation) for a period, optionally filtered by deployment ids, backed by dial-adas usage logs.

### Modified Capabilities
<!-- None: existing deployment-costs / batch-run-costs requirements are unchanged; total-costs cross-references deployment-costs for the shared query conventions. -->

## Impact

- **Code** (all under `com.epam.aidial.evaluation`): `service.domain.AdasCostQueryBuilder` (new `buildTotalAggregateQuery`), `service.domain.CostService` (`getTotalCosts`), `web.controller.CostController` (new `POST /total`), new request/response DTOs in `service.domain.dto` (`TotalCostRequestDto`, `TotalCostResponseDto`), a new `AdasTotalCostRowDto` in `client.dialadas.dto` (`AdasBatchRunCostRowDto` has no `count`), `constants.ValidationConstants`.
- **API**: one new endpoint; OpenAPI examples `api-v1-costs-total-POST-request-{minimal,full}.json and api-v1-costs-total-POST-response-200-{minimal,no-usage}.json`.
- **Data**: none — no Flyway migration, no analytics-DB change, no `docs/database-schema.md` change.
- **Config**: none — no new properties, `docs/configuration.md` unchanged.
- **Security**: same authentication as the other `/api/v1/costs/**` endpoints; the endpoint exposes system-wide aggregate cost, not per-user data.
- **Risks**: dial-adas latency/timeouts on wide periods (surface as 502/504, no partial result); large `deploymentIds` lists (mitigated by the cap); `deployment` values are stored percent-encoded, so callers must send ids in that form; an `or` of up to 1000 `eq` predicates makes the payload larger (bounded by the cap).
- **Rollout**: additive endpoint, no feature flag, no migration; safe to deploy independently of the frontend.
- **Test plan**: unit tests for `AdasCostQueryBuilder` (query shape + wire serialization), `CostService` (null/empty rows, validation) and `CostController`; a functional test with `@MockitoBean DialAdasClient` that boots the context; a test asserting the OpenAPI examples appear in `/v3/api-docs`.
