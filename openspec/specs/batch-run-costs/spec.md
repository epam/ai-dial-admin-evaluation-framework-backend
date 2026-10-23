# Batch Run Costs

## Purpose

Answering "what did this run cost" via `GET /api/v1/test-suite-runs/{id}/costs` requires two sequential
dial-adas calls per run (one per `eval.phase`), which does not scale to computing total cost for a page's
worth of runs (an N+1 pattern). This capability provides a page-scoped total-cost lookup and per-phase
split for multiple test suite runs in exactly one dial-adas call, via a
`case`/`when`/`then`/`else`-derived grouping key (see `structured-query-model`'s "Expression grammar"
requirement) rather than N per-run calls.

## Requirements

### Requirement: Batch total-cost lookup for multiple test suite runs
The system SHALL expose `POST /api/v1/costs/test-suite-runs`, accepting a JSON request body with a
`runIds` array of run UUIDs (required, at least 1 and at most `ValidationConstants.MAX_BATCH_RUN_IDS`
entries), and returning, for each requested run id, its unchanged overall `totalCost`, its execution-phase
`testCaseCost`, and its metric-evaluation-phase `metricEvalCost`. All three fields SHALL be null when
dial-adas has no matching usage data for that run id. For a matched run, a phase field SHALL be zero when
the run has no usage in that phase. The response SHALL preserve the order of the requested `runIds`. POST
(with `runIds` in the body) rather than
GET (with `runIds` as a query parameter) — a page's worth of run ids would otherwise make for an unwieldy
query string. There is no per-run status field: null cost fields communicate "no usage data for this
run," and a failure of the single underlying dial-adas call fails the whole request (see the
dial-adas-failure scenario below) rather than being represented as a per-run value.
Status: **Implemented**

#### Scenario: Runs with usage data return overall and per-phase costs
- **WHEN** a client requests `POST /api/v1/costs/test-suite-runs` with body `{"runIds": ["R1", "R2"]}`
  where dial-adas has usage rows for both `R1` and `R2`
- **THEN** the response contains one entry per run id, each with `totalCost` equal to the existing
  unfiltered sum of `total_price`, `testCaseCost` equal to the sum for rows tagged
  `eval.phase=execution`, and `metricEvalCost` equal to the sum for rows tagged
  `eval.phase=metric-evaluation`

#### Scenario: A matched run with no usage in one phase returns zero for that phase
- **WHEN** a requested run has matching usage rows but none tagged with one of the two supported phases
- **THEN** the missing phase's cost field is zero while `totalCost` and the other phase field retain their
  computed values

#### Scenario: A run with no matching usage data returns a null total cost
- **WHEN** a requested run id has no dial-adas usage rows at all (including an unknown or typo'd run id)
- **THEN** that run's entry has null `totalCost`, `testCaseCost`, and `metricEvalCost`, while other
  requested run ids in the same request are unaffected

#### Scenario: dial-adas failure fails the whole request
- **WHEN** the single underlying dial-adas call fails (timeout, connection error, or non-2xx response)
- **THEN** the request itself fails with 502 (unreachable/error response) or 504 (timeout) — there is no
  per-run partial result and no 200 response, since the batch is computed by exactly one dial-adas call

#### Scenario: Empty runIds is rejected
- **WHEN** a client requests `POST /api/v1/costs/test-suite-runs` with body `{"runIds": []}` or an absent
  `runIds` field
- **THEN** the system returns 400 with `ErrorCode.VALIDATION_ERROR` and does not query dial-adas

#### Scenario: runIds exceeding the batch limit is rejected
- **WHEN** a client supplies more than `ValidationConstants.MAX_BATCH_RUN_IDS` run ids in the request
  body's `runIds` array
- **THEN** the system returns 400 with `ErrorCode.VALIDATION_ERROR` and does not query dial-adas

### Requirement: One dial-adas call computes overall and per-phase costs for many runs
The system SHALL compute the overall and per-phase costs for all requested run ids with exactly one
dial-adas aggregate query against `dial_usage_log`, filtered by an `or` of per-run baggage-contains predicates on
`eval.run.id` (no `eval.phase` predicate, since the total spans both phases), grouped by a `run_id`
value derived per row via a `case`/`when`/`then`/`else` select expression that maps each row back to the
run id whose baggage predicate matched it.
Status: **Implemented**

#### Scenario: Single dial-adas query regardless of run count
- **WHEN** the system computes total costs for N requested run ids
- **THEN** it issues exactly one `"mode": "aggregate"` dial-adas query, selecting a `case`-derived
  `run_id` alias, `count()`, `sum(total_price)` aliased `total_cost`, and conditional sums aliased
  `test_case_cost` and `metric_eval_cost`, grouped by `run_id`

#### Scenario: Total spans both cost phases
- **WHEN** the system computes a requested run's total cost
- **THEN** the underlying query applies no `eval.phase` filter, so the total includes usage rows from
  both the `execution` and `metric-evaluation` phases

#### Scenario: A single requested run id skips the OR wrapper
- **WHEN** exactly one run id is requested
- **THEN** the underlying query's filter is the single run's baggage-contains predicate, not an `or` node
  wrapping one child

## Implementation notes

- Query construction: `service.domain.AdasCostQueryBuilder.buildPageTotalCostQuery(Collection<UUID> runIds)`
  builds the single aggregate `StructuredQuery` — an OR of per-run `baggageContains(baggageField(),
  "eval.run.id=" + id)` predicates (unwrapped to a single predicate for one run id), no `eval.phase`
  filter, a `run_id`-aliased `CaseExpr` (one `WhenClause` per run id, `else` a constant `"other"`
  sentinel), plus `count()`, `sum(total_price)` aliased `total_cost`, and
  `sum(case when baggage contains eval.phase=<phase> then total_price else 0 end)` aliased
  `test_case_cost` / `metric_eval_cost`, grouped by the `run_id` alias.
  See `structured-query-model`'s `case` expression kind.
- Response row shape: `client.dialadas.dto.AdasBatchRunCostRowDto` (`run_id`, `total_cost`,
  `test_case_cost`, `metric_eval_cost`), one of three
  focused row DTOs `AdasCostQueryBuilder`'s query shapes each get (alongside `AdasRunAvgCostRowDto`,
  `AdasDeploymentCostRowDto`); `client.dialadas.dto.AdasAggregateResponseDto<T>` and
  `DialAdasClient.executeAggregate(StructuredQuery, Class<T>)` are generic over the row type.
- Fetching: `service.domain.CostService.getTotalRunCosts(List<UUID> runIds)` validates the request
  (non-empty, size `<= ValidationConstants.MAX_BATCH_RUN_IDS`) via inline checks, then calls the
  injectable `service.domain.BatchRunTotalCostLookup.fetchCosts(Collection<UUID> runIds,
  DialAdasClient dialAdasClient)` with the normal shared `DialAdasClient` — the query-execution and
  response-parsing logic that used to be a private `CostService.fetchTotalCosts` helper. The lookup calls
  `AdasCostQueryBuilder.buildPageTotalCostQuery` + `DialAdasClient.executeAggregate` exactly once, groups
  returned rows by their `run_id` parsed via `UUID.fromString` (skipping unparseable rows, including the
  `"other"` bucket, rather than matching the literal string), and builds a map of the full cost rows; a
  requested id absent from that map surfaces as three null cost fields. The retained `fetchTotalCosts`
  view maps those rows to `Map<UUID, Double>` for the result-page extension. A `DialAdasClientException`
  from the underlying call is not caught and propagates as the request's error. This same reusable lookup
  is also called, with
  a dedicated short-timeout `DialAdasClient`, by `query.service.repository.TotalCostTestSuiteRunsPageExtender`
  (`enrich-test-suite-runs-total-cost`) — see the `query-result-page-extension` spec — so the batch endpoint
  and the `total_cost` result-page extension share exactly one query-building/response-parsing
  implementation rather than two copies that could silently drift.
- API: `web.controller.CostController.getTotalRunCosts` — `POST /api/v1/costs/test-suite-runs`, request
  `service.domain.dto.TotalRunCostRequestDto { List<UUID> runIds }` bound via `@Valid @RequestBody`,
  response `List<service.domain.dto.TotalRunCostResponseDto(UUID runId, Double totalCost,
  Double testCaseCost, Double metricEvalCost)>` ordered per the
  requested `runIds`. `ValidationConstants.MAX_BATCH_RUN_IDS = 1000` (matching `pagination.max-size`).
- Tests: `AdasCostQueryBuilderTest` (query shape), `BatchRunTotalCostLookupTest` (one aggregate call,
  valid/unparseable/`other`-bucket rows, missing groups, null totals), `CostServiceTest`'s
  `GetRunCostsBatch`/batch-related nested cases, `CostControllerTest` (request binding), and functional
  coverage in `TestSuiteRunFunctionalTests.shouldGetBatchRunCosts` under `PostgresFunctionalTests`.
- OpenAPI examples: `src/main/resources/openapi/examples/api-v1-costs-test-suite-runs-POST-response-200-{minimal,full}.json`.
