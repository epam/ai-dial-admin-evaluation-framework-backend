## ADDED Requirements

### Requirement: Per-test-case aggregate Grafana URL on eval summary responses
The service SHALL include a `grafanaTraceUrl` field on `EvalSummaryResponseDto` (list) and `EvalSummaryDetailResponseDto` (detail) that links to a Grafana Explore TraceQL query aggregating all spans for that test case within the run (deployment call + all metric evaluation calls).

#### Scenario: Aggregate URL present when Grafana configured
- **WHEN** `GET /api/v1/analytics/eval-summaries` or `GET /api/v1/analytics/eval-summaries/{id}` is called
- **AND** `app.grafana.base-url` is configured (non-blank)
- **THEN** each eval summary response SHALL include `grafanaTraceUrl` containing a Grafana Explore URL with TraceQL query `{.eval.run.id="<testSuiteRunId>" && .testcase.id="<testCaseId>"}` and time range `createdAtMs - 5min` to `computedAtMs + 5min`

#### Scenario: Aggregate URL absent when Grafana not configured
- **WHEN** `app.grafana.base-url` is blank or absent
- **THEN** `grafanaTraceUrl` SHALL be `null` (omitted via `@JsonInclude(NON_NULL)`)

#### Scenario: Aggregate URL absent when testCaseId is null
- **WHEN** an eval summary has a null `testCaseId` (defensive edge case)
- **THEN** `grafanaTraceUrl` SHALL be `null`

### Requirement: GrafanaLinkBuilder testCaseAggregateUrl method
`GrafanaLinkBuilder` SHALL provide a `testCaseAggregateUrl(UUID runId, UUID testCaseId, Long createdAtMs, Long computedAtMs)` method that builds a Grafana Explore URL with a TraceQL query targeting all spans matching both `eval.run.id` and `testcase.id`.

#### Scenario: Valid aggregate URL generation
- **WHEN** `testCaseAggregateUrl` is called with non-null `runId`, `testCaseId`, and `createdAtMs`
- **THEN** it SHALL return a Grafana Explore URL with TraceQL query `{.eval.run.id="<runId>" && .testcase.id="<testCaseId>"}` and time range `(createdAtMs - TIME_BUFFER_MS)` to `(computedAtMs + TIME_BUFFER_MS)`, or `now` if `computedAtMs` is null

#### Scenario: Time range `to` defaults to `now` when `computedAtMs` is null
- **WHEN** `testCaseAggregateUrl` is called with non-null `runId`, `testCaseId`, and `createdAtMs` but `computedAtMs` is null
- **THEN** the time range `to` SHALL default to `now`

#### Scenario: Returns null when disabled
- **WHEN** `testCaseAggregateUrl` is called and Grafana is not configured
- **THEN** it SHALL return `null`

#### Scenario: Returns null when runId, testCaseId, or createdAtMs is null
- **WHEN** `testCaseAggregateUrl` is called with null `runId`, null `testCaseId`, or null `createdAtMs`
- **THEN** it SHALL return `null`
