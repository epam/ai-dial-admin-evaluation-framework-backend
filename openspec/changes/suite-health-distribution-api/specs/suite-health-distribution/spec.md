## Purpose

The System Health dashboard shows, week by week, how many test suites were healthy and how many were failing, so a platform-wide change in quality is visible at a glance. This capability returns those weekly counts for all suites, or for the suites matching a filter, from the stored run pass counts.

## ADDED Requirements

### Requirement: Health-distribution endpoint
The system SHALL expose `GET /api/v1/analytics/test-suites/health-distribution` returning an object with `threshold` (the threshold applied) and `weeks`, a list with exactly one entry per requested week, ordered oldest first. Each entry SHALL contain `weekStart` (epoch milliseconds), `healthyCount`, `failingCount` and `suiteCount`, where `suiteCount` equals `healthyCount` plus `failingCount`. A week in which no suite is counted SHALL still have an entry, with all three counts 0. The response SHALL NOT be paged. The endpoint has no `sort` parameter; a supplied `sort` SHALL have no effect on the response.
Status: **Planned**

#### Scenario: Default request
- **WHEN** the endpoint is called without parameters
- **THEN** the response is HTTP 200 with `threshold` 0.75 and 8 entries in `weeks`, oldest first, the last one being the current week

#### Scenario: No recorded runs at all
- **WHEN** no completed run has recorded pass counts
- **THEN** the response is HTTP 200 with one entry per requested week, every count 0

#### Scenario: Counts add up
- **WHEN** in one week three suites are healthy and two are failing
- **THEN** that week's entry has `healthyCount` 3, `failingCount` 2 and `suiteCount` 5

### Requirement: Weeks
A week SHALL start on Monday 00:00:00.000 UTC and last seven days. A run SHALL belong to the week containing its creation timestamp. `weekStart` SHALL be the first millisecond of the week. The last entry SHALL be the week containing the current time, even when that week has only just begun; the entries before it SHALL be the immediately preceding weeks without gaps. The number of entries SHALL be the `weeks` parameter: default 8, minimum 1, maximum 52. A `weeks` value outside that range or not an integer SHALL be rejected with HTTP 400.
Status: **Planned**

#### Scenario: Run at the first millisecond of a week
- **WHEN** a run was created on a Monday at 00:00:00.000 UTC
- **THEN** it is counted in the week whose `weekStart` equals that timestamp, not in the week before

#### Scenario: Run at the last millisecond of a week
- **WHEN** a run was created on a Sunday at 23:59:59.999 UTC
- **THEN** it is counted in the week that started the Monday before

#### Scenario: Run older than the requested range
- **WHEN** `weeks` is 2 and a suite's only run was created three weeks ago
- **THEN** the suite is counted in no entry

#### Scenario: Custom number of weeks
- **WHEN** `weeks` is 3
- **THEN** `weeks` in the response has 3 entries: the current week and the two before it

#### Scenario: Number of weeks out of range
- **WHEN** `weeks` is 0, 53 or not an integer
- **THEN** the response is HTTP 400

### Requirement: Weekly suite pass rate and health
For each week, a suite's pass rate SHALL be the sum of passed counts divided by the sum of total counts over the suite's recorded completed runs created in that week (see `run-pass-counts`), each run read at its latest recorded computation. A suite SHALL be counted in a week only when that sum of total counts is greater than 0; a suite with no recorded run in the week, or whose runs in the week have no test cases, SHALL NOT be counted in that week and SHALL NOT be assigned the state of another week. A counted suite SHALL be healthy when its weekly pass rate is greater than or equal to the threshold and failing otherwise. One suite SHALL be counted independently in every week in which it has runs. Deleted runs SHALL NOT contribute.
Status: **Planned**

#### Scenario: Pooled rate over several runs
- **WHEN** in one week a suite has a run with 1 of 10 test cases passed and a run with 90 of 90 passed, and the threshold is 0.75
- **THEN** the suite's weekly rate is 91 of 100 and it is counted as healthy, although one of its runs is below the threshold

#### Scenario: Rate equal to the threshold
- **WHEN** a suite's weekly pass rate is exactly 0.75 and the threshold is 0.75
- **THEN** the suite is counted as healthy

#### Scenario: Suite did not run in a week
- **WHEN** a suite has runs in the current week and none in the week before
- **THEN** it is counted in the current week only

#### Scenario: Runs without test cases
- **WHEN** a suite's only runs in a week have a total count of 0
- **THEN** the suite is not counted in that week

#### Scenario: Same suite in two weeks
- **WHEN** a suite has a weekly rate of 0.5 last week and 0.9 this week, with the threshold 0.75
- **THEN** it is counted as failing last week and as healthy this week

#### Scenario: Recomputed run
- **WHEN** a run has two recorded computations, the older with 2 of 10 passed and the newer with 9 of 10 passed
- **THEN** only the newer computation contributes to the suite's weekly rate

#### Scenario: Deleted run
- **WHEN** a suite's only run in a week has been deleted
- **THEN** the suite is not counted in that week

### Requirement: Threshold parameter
The endpoint SHALL accept `threshold`, a number from 0 to 1 inclusive, default 0.75, and SHALL echo the value applied in the response. A value outside that range or not a number SHALL be rejected with HTTP 400.
Status: **Planned**

#### Scenario: Custom threshold
- **WHEN** `threshold` is 0.9 and a suite's weekly rate is 0.8
- **THEN** the suite is counted as failing and the response `threshold` is 0.9

#### Scenario: Threshold of zero
- **WHEN** `threshold` is 0
- **THEN** every counted suite is healthy

#### Scenario: Threshold out of range
- **WHEN** `threshold` is -0.1, 1.5, `NaN`, `Infinity` or not a number
- **THEN** the response is HTTP 400

### Requirement: Suite filter
The endpoint SHALL accept the `filter` parameter with the same fields, operators and validation as the run-trends endpoint (see `suite-run-trends`). Without a filter, every suite with recorded runs SHALL be considered, regardless of which suites the run-trends endpoint returns on any page. With a filter, only matching suites SHALL be counted. An invalid filter SHALL be rejected with HTTP 400.
Status: **Planned**

#### Scenario: Filter by suite type
- **WHEN** the request filters `suiteType eq "MCP_TOOL"` and, in the current week, two MCP suites and three other suites have runs
- **THEN** the current week's `suiteCount` is 2

#### Scenario: Filter matches no suite
- **WHEN** the filter matches no suite
- **THEN** the response is HTTP 200 with one entry per requested week, every count 0

#### Scenario: Invalid filter
- **WHEN** the filter names an unknown field or an operator the field does not allow
- **THEN** the response is HTTP 400

### Requirement: Datasource separation
Pass counts SHALL be read from the analytics datasource. When a filter is given, the matching suite ids SHALL be read from the meta datasource before the analytics read; a meta query SHALL NOT execute inside an analytics transaction. Without a filter, the request SHALL NOT read the meta datasource.
Status: **Planned**

#### Scenario: Unfiltered request
- **WHEN** the endpoint is called without a filter
- **THEN** the counts are produced without a meta datasource query

### Requirement: OpenAPI documentation
The endpoint SHALL be documented with minimal and full `200` response examples under `src/main/resources/openapi/examples/` named for the registered path, its `filter` parameter SHALL carry the generated query-parameter description, and `threshold` and `weeks` SHALL be described with their defaults and ranges. The examples SHALL appear in `/v3/api-docs`.
Status: **Planned**

#### Scenario: Examples appear in the API docs
- **WHEN** `/v3/api-docs` is requested
- **THEN** the operation `GET /api/v1/analytics/test-suites/health-distribution` carries the `200` response examples and described `filter`, `threshold` and `weeks` parameters
