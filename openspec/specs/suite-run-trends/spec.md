# Suite Run Trends

## Purpose

The System Health dashboard lists test suites with the pass rates of their recent runs so that the suites doing worst are seen first. This capability returns that list page by page, already sorted across all suites, with each suite's evaluatee, run trend, latest pass rate and change against the previous run.

## Requirements

### Requirement: Run-trends endpoint
The system SHALL expose `GET /api/v1/analytics/test-suites/run-trends` returning the standard offset page envelope with one item per test suite that has at least one recorded completed run (see `run-pass-counts`). Each item SHALL contain `suiteId`, `suiteName`, `suiteType`, `evaluatee`, `runs`, `latestPassRate`, `previousPassRate` and `delta`. `runs` SHALL hold the suite's latest completed runs, at most 10, ordered oldest first, each with `runId`, `createdAt` (epoch milliseconds), `passRate`, `passedCount` and `totalCount`. Pass rates SHALL be numbers from 0 to 1, computed as passed count divided by total count; the pass rate of a run with a total of 0 SHALL be absent. The response SHALL contain no run status and no suite version.
Status: **Implemented**

#### Scenario: Suite with three completed runs
- **WHEN** a suite has three completed runs with pass rates 0.5, 0.75 and 1.0 in order of creation
- **THEN** its item has `runs` with those three rates in that order, `latestPassRate` 1.0, `previousPassRate` 0.75 and `delta` 0.25

#### Scenario: More than ten runs
- **WHEN** a suite has 12 completed runs
- **THEN** `runs` holds the 10 newest, oldest of those first

#### Scenario: Suite without a completed run is not listed
- **WHEN** a suite has only FAILED, CANCELLED or in-flight runs, or no runs
- **THEN** it has no item and is not counted in the total

#### Scenario: No suite qualifies
- **WHEN** no suite has a recorded completed run
- **THEN** the response is HTTP 200 with an empty page and a total of 0

### Requirement: Run selection, latest rate and delta
Runs of a suite SHALL be ordered by run creation timestamp, ties broken by the larger run id, and each run SHALL be read at its latest computation. `latestPassRate` SHALL be the pass rate of the newest run and `previousPassRate` that of the run immediately before it; a run with an absent pass rate SHALL NOT be replaced by an older run. `delta` SHALL be `latestPassRate` minus `previousPassRate` and SHALL be absent when either is absent. Deleted runs SHALL NOT appear and SHALL NOT be counted.
Status: **Implemented**

#### Scenario: Single run
- **WHEN** a suite has one completed run with pass rate 0.4
- **THEN** `latestPassRate` is 0.4 and `previousPassRate` and `delta` are absent

#### Scenario: Latest run has no test cases
- **WHEN** a suite's newest completed run has a total of 0 and the one before it has pass rate 0.9
- **THEN** `latestPassRate` and `delta` are absent, `previousPassRate` is 0.9, and the run is still present in `runs` with an absent `passRate`

#### Scenario: Recomputed run
- **WHEN** a run has pass counts for two computations
- **THEN** the run appears once, with the counts of its latest computation

#### Scenario: Deleted run
- **WHEN** a suite's newest completed run is deleted
- **THEN** the run before it becomes the latest; when it was the suite's only completed run, the suite is no longer listed

### Requirement: Sorting and paging across all suites
Items SHALL be sorted by `latestPassRate` ascending, with suites whose `latestPassRate` is absent last, then by the latest run's creation timestamp descending, then by suite id. The order SHALL be applied over all qualifying suites before paging, and the total SHALL count all qualifying suites. `page` and `size` SHALL follow the same defaults, limits and validation as the other offset-paged list endpoints. The endpoint SHALL accept no `sort` parameter.
Status: **Implemented**

#### Scenario: Worst first across pages
- **WHEN** five suites have latest pass rates 0.9, 0.1, 0.5, 0.3 and 0.7 and the page size is 2
- **THEN** page 0 holds the suites with 0.1 and 0.3, page 1 those with 0.5 and 0.7, page 2 the one with 0.9, and the total is 5 on every page

#### Scenario: Absent rate sorts last
- **WHEN** one suite's latest run has a total of 0 and another suite's latest pass rate is 1.0
- **THEN** the suite with 1.0 comes first

#### Scenario: Invalid paging
- **WHEN** `size` exceeds the configured maximum or `page` is negative
- **THEN** the response is HTTP 400

### Requirement: Suite and evaluatee fields
`suiteName`, `suiteType` and `evaluatee` SHALL reflect the suite as it is now, not as it was when a run was created. `evaluatee` SHALL be an object with `id`, `name`, `type` and `toolName`, each absent when the suite's reference does not carry it: for a `DEPLOYMENT` suite `id`, `name` and `type` from its deployment reference; for an `MCP_TOOL` suite `id`, `name` and `type` from its MCP deployment reference and `toolName` from its tool reference; for a `ROUTE` suite `name` from its route reference. `toolName` SHALL be present only for `MCP_TOOL` suites. `type` SHALL be the reference's own type value, not a derived category.
Status: **Implemented**

#### Scenario: Deployment suite
- **WHEN** a `DEPLOYMENT` suite references deployment id `gpt-4o`, name `GPT-4o`, type `dial-model`
- **THEN** the item has `suiteType` `DEPLOYMENT` and `evaluatee` `{ "id": "gpt-4o", "name": "GPT-4o", "type": "dial-model" }`

#### Scenario: MCP tool suite
- **WHEN** an `MCP_TOOL` suite references MCP deployment id `docs-toolset`, name `Docs toolset`, type `dial-toolset` and the tool `search`
- **THEN** `evaluatee` is `{ "id": "docs-toolset", "name": "Docs toolset", "type": "dial-toolset", "toolName": "search" }`

#### Scenario: Route suite
- **WHEN** a `ROUTE` suite references route `routes/platform/validate-route`
- **THEN** `evaluatee` is `{ "name": "routes/platform/validate-route" }`

#### Scenario: Suite renamed after its runs
- **WHEN** a suite is renamed after its runs completed
- **THEN** the item shows the new name

### Requirement: Suite filter
The endpoint SHALL accept the `filter` parameter with the same fields, operators and validation as the test suite list. When a filter is given, only matching suites SHALL be listed, sorted and counted; the sort order and the total SHALL still cover every matching suite. An invalid filter SHALL be rejected with HTTP 400.
Status: **Implemented**

#### Scenario: Filter by suite type
- **WHEN** the request filters `suiteType eq "MCP_TOOL"` and two of five listed suites are MCP suites
- **THEN** only those two are returned, worst first, and the total is 2

#### Scenario: Filter matches no suite
- **WHEN** the filter matches no suite
- **THEN** the response is HTTP 200 with an empty page and a total of 0

#### Scenario: Invalid filter
- **WHEN** the filter names an unknown field or an operator the field does not allow
- **THEN** the response is HTTP 400

### Requirement: Datasource separation
Pass counts SHALL be read from the analytics datasource and suite data from the meta datasource. A meta query SHALL NOT execute inside an analytics transaction. When a filter is given, the matching suite ids SHALL be read from the meta datasource before the analytics transaction. Suite data for a page SHALL be read in one bulk query after it, not once per item. A suite that no longer exists in the meta datasource when the page is assembled SHALL be omitted from the page's items; the total and the paging flags SHALL still be those computed from the pass counts, so such a page can hold fewer items than its size.
Status: **Implemented**

#### Scenario: One bulk suite read per page
- **WHEN** a page of 20 items is assembled
- **THEN** the suites are read with a single meta query after the analytics transaction has completed

#### Scenario: Suite deleted while the page is assembled
- **WHEN** a suite on the page no longer exists in the meta datasource
- **THEN** it has no item, and the total is unchanged

### Requirement: OpenAPI documentation
The endpoint SHALL be documented with minimal and full `200` response examples under `src/main/resources/openapi/examples/` named for the registered path, and its `page`, `size` and `filter` parameters SHALL carry the generated query-parameter descriptions. The examples SHALL appear in `/v3/api-docs`.
Status: **Implemented**

#### Scenario: Examples appear in the API docs
- **WHEN** `/v3/api-docs` is requested
- **THEN** the operation `GET /api/v1/analytics/test-suites/run-trends` carries the `200` response examples and described `page`, `size` and `filter` parameters

**Implementation notes:** `SuiteRunTrendController` (`web.controller`), `SuiteRunTrendService` and `SuiteEvaluateeMapper` (`service.domain.analytics`), DTOs `SuiteRunTrendDto` / `RunTrendPointDto` / `EvaluateeDto` (`service.domain.dto.analytics`), `AnalyticsConstants.RUN_TREND_LENGTH`; pass counts are read through `RunPassCountService.getLatestPage` / `getLatestRecordedRuns`; suite ids and suite data through `TestSuiteService.findIds` / `findByIds`; evaluatee derived via `SuiteTargetResolver.fromEntity`; query-parameter docs registered in `OpenApiQueryParamCustomizer`; examples `api-v1-analytics-test-suites-run-trends-GET-response-200-{minimal,full}.json`.
