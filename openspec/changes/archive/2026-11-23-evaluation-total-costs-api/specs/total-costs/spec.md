## ADDED Requirements

### Requirement: Get total evaluation cost for a period
The system SHALL expose `POST /api/v1/costs/total` accepting a JSON body with `from` and `to` (epoch-millisecond `Long`, required, inclusive bounds) and an optional `deploymentIds` list, and returning `totalCost`, `testCaseCost` and `metricEvalCost` for all evaluation traffic in `[from, to]`. Costs SHALL be summed over dial-adas `dial_usage_log` rows whose `request_time` is within the range and whose baggage carries the `eval.phase=` tag, using `deployment_price` (the request's own cost) when no deployment ids are given and `total_price` (the request plus everything it initiated) when deployment ids are given; `testCaseCost` SHALL cover rows tagged `eval.phase=execution`, `metricEvalCost` rows tagged `eval.phase=metric-evaluation`, and `totalCost` the sum over all rows matched by the `eval.phase=` tag. The system SHALL compute the result with a single dial-adas aggregate call. Status: Implemented.

#### Scenario: Period with both execution and metric-evaluation usage
- **WHEN** a client sends `POST /api/v1/costs/total` with `{"from": F, "to": T}` and dial-adas has evaluation usage rows for both phases in the window
- **THEN** the system returns 200 with `testCaseCost` (sum of the scope's price field of `eval.phase=execution` rows), `metricEvalCost` (sum for `eval.phase=metric-evaluation` rows) and `totalCost` (sum of all `eval.phase=`-tagged rows, i.e. both phases here)

#### Scenario: Usage not tagged as evaluation traffic is excluded
- **WHEN** dial-adas holds usage rows in the window that lack the `eval.phase=` baggage tag
- **THEN** those rows do not contribute to `totalCost`, `testCaseCost` or `metricEvalCost`

#### Scenario: Range bounds are inclusive
- **WHEN** a usage row has `request_time` exactly equal to `from` or exactly equal to `to`
- **THEN** the row is included in the totals

#### Scenario: from equals to
- **WHEN** `from` equals `to`
- **THEN** the request is accepted and the totals cover only usage at that single instant (inclusive bounds)

#### Scenario: Only one phase has usage
- **WHEN** the window contains matching rows for only one phase
- **THEN** the system returns 200 with the other phase's cost equal to `0` (not `null`) and `totalCost` equal to the populated phase's cost

#### Scenario: Single dial-adas call
- **WHEN** a request is served, regardless of how many `deploymentIds` it carries
- **THEN** the system issues exactly one dial-adas aggregate query

### Requirement: Filter total cost by deployment ids
The system SHALL accept an optional `deploymentIds` list in the request body of `POST /api/v1/costs/total` and, when it is non-empty, restrict the totals to usage rows whose `deployment` equals one of the listed ids. A missing, `null` or empty `deploymentIds` SHALL mean all deployments. Ids SHALL be matched exactly as sent — still percent-encoded, as stored in the usage log — and SHALL NOT be decoded or validated against DIAL Core. Status: Implemented.

#### Scenario: Filter by several deployments
- **WHEN** a client sends `{"from": F, "to": T, "deploymentIds": ["gpt-4o", "applications/public/my-app__0.0.1"]}`
- **THEN** the totals include only usage rows whose `deployment` is `gpt-4o` or `applications/public/my-app__0.0.1`

#### Scenario: No deployment filter
- **WHEN** a client omits `deploymentIds`, sends `null`, or sends an empty list
- **THEN** the system applies no `deployment` predicate and totals evaluation usage across all deployments

#### Scenario: Id containing a percent-encoded character
- **WHEN** a client sends the id `my%20app` in `deploymentIds`
- **THEN** the system matches the `deployment` value `my%20app` literally and does not decode it

#### Scenario: Unknown deployment id
- **WHEN** no usage row matches any of the listed ids in the window
- **THEN** the system returns 200 with `totalCost`, `testCaseCost` and `metricEvalCost` all `null` and does not return 404

### Requirement: Price field depends on whether deployments are filtered
The system SHALL sum `deployment_price` (a request's own cost) when `deploymentIds` is omitted, null or empty, and `total_price` (the request plus every request it initiated, as computed at execution time) when `deploymentIds` is non-empty, for the total and for both phase splits. Without a filter every tagged row matches, so an application's row and the nested rows it initiated would both be counted if `total_price` were summed; `deployment_price` exists only on leaf (model) rows and counts each request once. With a filter, applications have a `total_price` but no `deployment_price`, so `total_price` is the only field that carries their cost, as in `deployment-costs`. Neither field reflects later cost adjustments (pricing-config fixes, provider discounts). Status: Implemented.

#### Scenario: No filter does not double count nested requests
- **WHEN** a client requests a total without `deploymentIds` and an application's usage row and the model rows it initiated all carry the `eval.phase=` tag
- **THEN** the dial-adas query sums `deployment_price`, so the nested model cost is counted once and the application row contributes nothing of its own

#### Scenario: Application id returns the application's cost
- **WHEN** a client requests a total with `deploymentIds` containing an application id whose rows have a `total_price` but no `deployment_price`
- **THEN** the dial-adas query sums `total_price` and the response carries the application's cost instead of null or `0`

#### Scenario: Listing an application together with a deployment it calls
- **WHEN** a client lists both an application and a deployment that application initiated in `deploymentIds`
- **THEN** the shared cost is counted in both rows' `total_price`, so the total includes it twice; this is a documented limitation and the system does not de-duplicate

### Requirement: Null costs when no usage matches
The system SHALL return 200 with `totalCost`, `testCaseCost` and `metricEvalCost` all set to `null` (not `0`) when dial-adas returns no rows or a row whose `count` is `0`, consistent with `deployment-costs` and `batch-run-costs`. Status: Implemented.

#### Scenario: No evaluation usage in the period
- **WHEN** dial-adas reports `count = 0` (or returns no rows) for the requested window and filter
- **THEN** the response body is `{"totalCost": null, "testCaseCost": null, "metricEvalCost": null}` with status 200

### Requirement: Validate the total cost request
The system SHALL reject invalid `POST /api/v1/costs/total` requests with 400 and `ErrorCode.VALIDATION_ERROR` before querying dial-adas: `from` or `to` missing, `from` greater than `to`, a `deploymentIds` list longer than 1000 entries, or any blank entry in `deploymentIds`. Status: Implemented.

#### Scenario: from or to missing
- **WHEN** the body omits `from` or `to`
- **THEN** the system returns 400 with `ErrorCode.VALIDATION_ERROR` and does not query dial-adas

#### Scenario: from is after to
- **WHEN** `from` is greater than `to`
- **THEN** the system returns 400 with `ErrorCode.VALIDATION_ERROR` and does not query dial-adas

#### Scenario: Too many deployment ids
- **WHEN** `deploymentIds` contains more than 1000 entries
- **THEN** the system returns 400 with `ErrorCode.VALIDATION_ERROR` and does not query dial-adas

#### Scenario: Blank deployment id
- **WHEN** `deploymentIds` contains a `null`, empty or whitespace-only entry
- **THEN** the system returns 400 with `ErrorCode.VALIDATION_ERROR` and does not query dial-adas

### Requirement: Surface dial-adas failures
The system SHALL fail the whole request when the dial-adas call fails, mapping an unreachable or erroring dial-adas to 502 and a timeout to 504, and SHALL NOT return a partial or degraded result. Status: Implemented.

#### Scenario: dial-adas unreachable or returns an error
- **WHEN** the dial-adas call throws a client exception for an upstream error
- **THEN** the system returns 502 with `ErrorCode.UPSTREAM_ERROR`

#### Scenario: dial-adas times out
- **WHEN** the dial-adas call times out
- **THEN** the system returns 504 with `ErrorCode.UPSTREAM_TIMEOUT`

### Requirement: Total cost includes usage of deleted runs
The system SHALL compute total cost from dial-adas usage logs only, without applying run-deletion tombstones, so usage produced by runs that have since been deleted remains counted. Status: Implemented.

#### Scenario: Deleted run's usage is counted
- **WHEN** a run produced evaluation usage in the window and was deleted afterwards
- **THEN** that usage is still included in `totalCost`

### Requirement: Total cost endpoint is documented in OpenAPI
The system SHALL document `POST /api/v1/costs/total` in the OpenAPI specification with request and response `@Schema` examples and `minimal` and `full` request examples and `minimal` and `no-usage` (all costs `null`) 200-response examples. Status: Implemented.

#### Scenario: Examples appear in the API docs
- **WHEN** a client fetches `/v3/api-docs`
- **THEN** the `POST /api/v1/costs/total` operation exposes its request body and 200 response examples

## Implementation notes

- Controller: `web/controller/CostController.java` (`getTotalCost`, `POST /api/v1/costs/total`); request body `service/domain/dto/TotalCostRequestDto.java` (`@NotNull from`/`to`, `@Size(max = ValidationConstants.MAX_COST_DEPLOYMENT_IDS)` and `@NotBlank` elements on `deploymentIds`); response `service/domain/dto/TotalCostResponseDto.java`.
- Service: `service/domain/CostService.java#getTotalCosts` (`from > to` → `ValidationException`; null response, no rows or `count == 0` → all-null result; `DialAdasClientException` propagates to 502/504).
- Query: `service/domain/AdasCostQueryBuilder.java#buildTotalAggregateQuery` — one aggregate over `dial_usage_log` summing `deployment_price` (no ids) or `total_price` (ids given), filtered by `request_time`, baggage `co "eval.phase="`, and an optional bare `deployment eq` / `or` of `deployment eq` predicates; row DTO `client/dialadas/dto/AdasTotalCostRowDto.java`.
- OpenAPI examples: `src/main/resources/openapi/examples/api-v1-costs-total-POST-{request-{minimal,full},response-200-{minimal,no-usage}}.json`.
- Tests: `AdasCostQueryBuilderTest`, `CostServiceTest`, `CostControllerTest`, functional tests in `TestSuiteRunFunctionalTests` (`shouldGetTotalCostsForPeriodAndDeployments` and siblings) and `OpenApiExamplesFunctionalTests#totalCostEndpointExposesRequestAndResponseExamples`.
