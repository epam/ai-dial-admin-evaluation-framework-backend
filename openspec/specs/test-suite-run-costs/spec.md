# Test Suite Run Costs

## Purpose
This spec describes the test suite run cost-reporting endpoint: an on-demand average of per-call prices for a run's test-case execution and metric-evaluation phases, sourced from dial-adas usage-log data correlated by the run's id and OTel baggage phase tag. It does not persist cost data — costs are computed live from dial-adas on each request.

## Requirements

### Requirement: Get average costs for a test suite run
The system SHALL expose `GET /api/v1/test-suite-runs/{id}/costs`, returning the average per-call price of test-case execution calls and the average per-call price of metric-evaluation calls for the identified run, sourced from dial-adas usage-log data correlated by the run's id and execution phase. Status: Implemented.

#### Scenario: Run has both execution and metric-evaluation usage data
- **WHEN** a client requests `GET /api/v1/test-suite-runs/{id}/costs` for a run that has completed test-case execution and metric evaluation
- **THEN** the system returns 200 with a body containing `avgTestCaseCost` (the average `total_price` across dial-adas usage-log rows tagged `eval.phase=execution` for this run) and `avgMetricEvalCost` (the average `total_price` across rows tagged `eval.phase=metric-evaluation` for this run)

#### Scenario: Run not found
- **WHEN** a client requests `GET /api/v1/test-suite-runs/{id}/costs` for a run id that does not exist
- **THEN** the system returns 404 with `ErrorCode.NOT_FOUND`

#### Scenario: No usage-log data for one phase
- **WHEN** dial-adas returns zero matching usage-log rows for one of the two phases (e.g. a run with no metric evaluations configured)
- **THEN** the system returns 200 with that phase's average field set to `null` (not `0`), since an average of zero rows is undefined, while the other phase's average is still populated if data exists for it

#### Scenario: dial-adas is unreachable or times out
- **WHEN** dial-adas does not respond within the configured read timeout, or the connection fails
- **THEN** the system returns 504 with `ErrorCode.UPSTREAM_TIMEOUT` for a timeout, or 502 with `ErrorCode.UPSTREAM_ERROR` for a connection/response failure, and does not partially compute an average from an incomplete response

### Requirement: dial-adas usage-log correlation by run id and phase
The system SHALL query dial-adas's `dial_usage_log` entity via its query DSL (`POST {dial-adas-base-url}/v1/queries/execute`), filtering rows whose `request_tags.baggage` contains the run's own id as `eval.run.id=<runId>` and the relevant execution phase as `eval.phase=execution` or `eval.phase=metric-evaluation`, using the same OTel baggage phase values already emitted by the evaluation engine (`TracingConstants.PHASE_EXECUTION` / `PHASE_METRIC_EVALUATION`). Status: Implemented.

#### Scenario: Query is scoped to a single run and phase
- **WHEN** the system computes the execution-phase average for run `R`
- **THEN** the dial-adas query filter requires both a match on `eval.run.id=R` and a match on `eval.phase=execution` within `request_tags.baggage`, so usage-log rows from other runs or from the metric-evaluation phase of the same run are excluded

#### Scenario: Aggregate computed server-side
- **WHEN** the system requests an average for a run and phase
- **THEN** it issues a single `"mode": "aggregate"` query with an `avg(total_price)` selection (aliased so the response can be read directly) rather than fetching individual usage-log rows and averaging them in application code

## Implementation notes
Introduces `com.epam.aidial.evaluation.client.dialadas` (client, config properties, exception), a `RunCostQueryBuilder` domain component, `TestSuiteRunService.getRunCosts`, and a new endpoint on the existing `TestSuiteRunController`. See `design.md` in `openspec/changes/test-suite-run-costs/` for full technical decisions.
