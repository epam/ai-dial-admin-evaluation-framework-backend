## Why

Users currently have no way to see how much a test suite run cost — neither the cost of the deployment/model calls made while executing test cases, nor the cost of the judge-model calls made while scoring metrics. That data exists today only in dial-adas (an external usage-log analytics service), correlated to a run via the OTel baggage (`eval.run.id`, `eval.phase`) the app already attaches to outgoing DIAL Core calls. Exposing it through the EF backend's own API lets users see run economics without needing direct dial-adas/Grafana access.

## What Changes

- Add `GET /api/v1/test-suite-runs/{id}/costs`, returning the average per-call price for test-case execution calls (`avgTestCaseCost`) and metric-evaluation calls (`avgMetricEvalCost`) for the given run, computed from dial-adas usage-log data. A phase with zero matching usage-log rows returns `null` for that average (an average of zero rows is undefined).
- Add a new external HTTP client (`client.dialadas` package) for dial-adas's structured query DSL (`POST {dial-adas-base-url}/v1/queries/execute`, `"mode": "aggregate"`), following the existing `client.dialcore`/`client.metricprovider` client conventions (typed `RestClient` bean, `@ConfigurationProperties`-driven base URL/timeouts, user-JWT propagation via the existing `AuthorizationTokenHolder`, tracing interceptor reuse).
- Add a dedicated exception (`DialAdasClientException`) and a `DefaultExceptionHandler` mapping to the existing `UPSTREAM_ERROR` / `UPSTREAM_TIMEOUT` error codes (no new `ErrorCode` values needed).
- Add new configuration properties under `dial.adas.*` (base URL, connect/read timeouts) with `docs/configuration.md` updates.

This assumes dial-adas correctly filters `dial_usage_log` rows by `eval.run.id` via a `co` (contains) filter on `request_tags.baggage` — the user has flagged this as currently unreliable on the dial-adas side and will validate end-to-end once that is fixed there; this change proceeds against the intended/contracted behavior.

## Capabilities

### New Capabilities
- `test-suite-run-costs`: Reports average test-case execution cost and average metric-evaluation cost for a test suite run, sourced from dial-adas usage-log aggregate queries correlated by the run's OTel baggage (`eval.run.id`, `eval.phase`).

### Modified Capabilities
(none — this is a new, additive read-only endpoint; no existing capability's requirements change)

## Impact

- **New API**: `GET /api/v1/test-suite-runs/{id}/costs` (web layer: `TestSuiteRunController`).
- **New service method**: `TestSuiteRunService.getRunCosts(UUID runId)` — read-only, existence-checks the run (`EntityNotFoundException` on 404), then queries dial-adas for both phases.
- **New client package**: `com.epam.aidial.evaluation.client.dialadas` — `DialAdasProperties`, `DialAdasClientConfiguration`, `DialAdasClient`, `DialAdasClientException`, request/response DTOs for the aggregate query.
- **New domain helper**: a query-builder component (name TBD in design) that constructs the dial-adas aggregate-query JSON for a given run id + phase, reusing `TracingConstants.PHASE_EXECUTION` / `PHASE_METRIC_EVALUATION` from `evaluation-runner-core` instead of new string literals.
- **New DTO**: `RunCostsResponseDto` (`avgTestCaseCost`, `avgMetricEvalCost`).
- **Config**: new `dial.adas.base-url` / `dial.adas.connect-timeout-ms` / `dial.adas.read-timeout-ms` properties; `docs/configuration.md` section 5.5 added.
- **Exception handling**: `DefaultExceptionHandler` gains a handler for `DialAdasClientException`, mapping timeouts to 504/`UPSTREAM_TIMEOUT` and other failures to 502/`UPSTREAM_ERROR`.
- **No DB schema changes** — the run's existing `id` (already used as the `eval.run.id` baggage/trace tag, see `GrafanaLinkBuilder`) is the only correlation key needed; no new columns or migrations.
- **External dependency**: introduces a new outbound dependency on dial-adas being reachable from the EF backend; the endpoint returns 502/504 if dial-adas is down or slow, and never blocks other run endpoints.
