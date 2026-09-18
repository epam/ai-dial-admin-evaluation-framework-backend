# Test Suite Run Costs

## Purpose
This spec describes the test suite run cost-reporting endpoint: an on-demand average cost per test case for a run's test-case execution and metric-evaluation phases, sourced from dial-adas usage-log data correlated by the run's id, OTel baggage phase tag, and each usage-log row's `testcase.id` baggage tag. It does not persist cost data — costs are computed live from dial-adas on each request.

## Requirements

### Requirement: Get average costs for a test suite run
The system SHALL expose `GET /api/v1/costs/test-suite-run/{id}` as the canonical route, and
`GET /api/v1/test-suite-runs/{id}/costs` as a backward-compatible alias of the same computation, both
returning the average cost per test case for the identified run's test-case execution phase and the
average cost per test case for its metric-evaluation phase, sourced from dial-adas usage-log data
correlated by the run's id, execution phase, and each usage-log row's `testcase.id` baggage tag. Status:
Implemented.

#### Scenario: Run has both execution and metric-evaluation usage data
- **WHEN** a client requests `GET /api/v1/costs/test-suite-run/{id}` (or the alias
  `GET /api/v1/test-suite-runs/{id}/costs`) for a run that has completed test-case execution and metric
  evaluation
- **THEN** the system returns 200 with a body containing `avgTestCaseCost` (the average, across test cases
  that had at least one usage-log row tagged `eval.phase=execution` for this run, of that test case's
  `total_price` values summed across all its execution-phase requests) and `avgMetricEvalCost` (the same
  computation over rows tagged `eval.phase=metric-evaluation`)

#### Scenario: A test case issuing multiple requests within a phase counts once
- **WHEN** a test case issues multiple requests within the same phase — e.g. a multi-request suite chain,
  a multi-turn case, or multiple test-suite metric definitions evaluated for one test case
- **THEN** those requests' `total_price` values are summed into a single per-test-case cost before that
  cost is included in the phase's average, so the test case contributes exactly one value to
  `avgTestCaseCost`/`avgMetricEvalCost` regardless of how many requests it issued in that phase

#### Scenario: Run not found
- **WHEN** a client requests either route for a run id that does not exist
- **THEN** the system returns 404 with `ErrorCode.NOT_FOUND`

#### Scenario: No usage-log data for one phase
- **WHEN** dial-adas returns zero test cases with at least one matching usage-log row for one of the two
  phases (e.g. a run with no metric evaluations configured)
- **THEN** the system returns 200 with that phase's average field set to `null` (not `0`), since an average
  of zero test cases is undefined, while the other phase's average is still populated if data exists for it

#### Scenario: dial-adas is unreachable or times out
- **WHEN** dial-adas does not respond within the configured read timeout, or the connection fails
- **THEN** the system returns 504 with `ErrorCode.UPSTREAM_TIMEOUT` for a timeout, or 502 with
  `ErrorCode.UPSTREAM_ERROR` for a connection/response failure, and does not partially compute an average
  from an incomplete response

### Requirement: dial-adas usage-log correlation by run id and phase
The system SHALL compute each phase's average via dial-adas's raw-SQL execution endpoint
(`POST {dial-adas-base-url}/v1/queries/execute-sql`), sending a SQL query that: (a) filters
`dial_usage_log` rows whose `usage_request_baggage.baggage` contains both the run's own id as
`eval.run.id=<runId>` and the relevant phase as `eval.phase=execution` or `eval.phase=metric-evaluation`,
using the same OTel baggage phase values already emitted by the evaluation engine
(`TracingConstants.PHASE_EXECUTION` / `PHASE_METRIC_EVALUATION`); (b) groups the filtered rows by their
`testcase.id=<id>` baggage tag and sums `total_price` per test case; and (c) averages those per-test-case
sums and counts the number of contributing test cases. This two-level aggregation (sum per test case, then
average across test cases) cannot be expressed by dial-adas's structured JSON query DSL
(`POST {dial-adas-base-url}/v1/queries/execute`, `"mode": "aggregate"`), which supports only a single
`GROUP BY` + aggregate `select`. Status: Implemented.

#### Scenario: Query is scoped to a single run and phase
- **WHEN** the system computes the execution-phase average for run `R`
- **THEN** the SQL query's filter requires both a match on `eval.run.id=R` and a match on
  `eval.phase=execution` within `usage_request_baggage.baggage`, and only rows carrying a `testcase.id`
  baggage tag are grouped and summed, so usage-log rows from other runs, from the metric-evaluation phase
  of the same run, or with no `testcase.id` tag are excluded

#### Scenario: Aggregate computed server-side
- **WHEN** the system requests an average for a run and phase
- **THEN** it issues a single SQL query that performs the sum-per-test-case and average-across-test-cases
  computation entirely within dial-adas, rather than fetching individual usage-log rows (or per-test-case
  sums) and aggregating them in application code

## Implementation notes
Introduces `com.epam.aidial.evaluation.client.dialadas` (client, config properties, exception), an `AdasCostQueryBuilder` domain component, `CostService.getRunCosts`, and a new endpoint on the existing `TestSuiteRunController`. See `design.md` in `openspec/changes/archive/2026-08-20-test-suite-run-costs/` for the original technical decisions, including the amendment adding the canonical `CostController` route.

Both routes call `CostService.getRunCosts(id)` directly — `TestSuiteRunController#getRunCosts` is not
called by `CostController#getRunCosts` (or vice versa); each controller depends only on the shared
service, per the project's layering rule that controllers do not call other controllers.

`CostService.fetchAvgCost` computes each phase's average via `AdasCostQueryBuilder.buildAvgCostPerTestCaseSql`
+ `DialAdasClient.executeSql` — a raw SQL string sent to dial-adas's `POST /v1/queries/execute-sql`, rather
than a `StructuredQuery` sent to `/v1/queries/execute`, because the correct per-test-case average requires
a two-level aggregation (sum `total_price` per `testcase.id`, then average those sums) that
`StructuredQuery`'s single `GROUP BY` + aggregate `select` shape cannot express. The SQL's output columns
are aliased `avg_cost`/`count` to match `AdasRunAvgCostRowDto` — the same row DTO used for
`buildDeploymentAggregateQuery`'s and `buildPageTotalCostQuery`'s `StructuredQuery`-based calls, which are
unaffected by this change since summing is identical whether computed as one flat sum or a sum of
per-test-case sums. `runId` (a `UUID`) and `phase` (a typed `EvalPhase` enum — `EXECUTION` or
`METRIC_EVALUATION` — rather than a raw `String`, so an unsupported phase is a compile error, not a
runtime `IllegalArgumentException`; each constant carries the corresponding `TracingConstants` wire value
via `EvalPhase.getValue()`) are the only interpolated values in the SQL template, so this raw-SQL
construction carries no injection surface. See the `fix-avg-test-case-cost` change's `design.md` for the
full decision record, including why a SQL builder library (e.g. jOOQ) was not used.

The two `StructuredQuery`-based builders (`buildDeploymentAggregateQuery`, `buildPageTotalCostQuery`) still
build the outbound query as a real `com.epam.aidial.evaluation.query.model.StructuredQuery` (the same typed
AST used for this service's own `POST /api/v1/queries/execute`), rather than hand-rolled `ObjectNode`s —
dial-adas's query DSL is the same wire grammar, confirmed against a real deployment, so this is the
canonical shape rather than a coincidentally similar one. `DialAdasClient.executeAggregate` accepts a
`StructuredQuery` directly; Spring's shared `JsonMapper` bean (`NON_NULL` inclusion) serializes it, dropping
the unset `having`/`sort`/`page` fields. The one field dial-adas doesn't show in examples but does accept is
`distinct: false` (a required primitive on `StructuredQuery`/`FnExpr`) — harmless, since it's a legitimate
field in their own shared schema. See [Query DSL patterns](../../../docs/patterns/README.md) for the
underlying model; this was the first reuse of that model as an *outbound* client payload rather than an
inbound request parsed by this service.
