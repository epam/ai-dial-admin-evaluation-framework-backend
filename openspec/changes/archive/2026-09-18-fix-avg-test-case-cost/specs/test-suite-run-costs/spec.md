## MODIFIED Requirements

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
