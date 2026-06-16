# Grafana Deep Links

## Purpose
This spec defines the Grafana Explore URL generation feature: configurable deep links embedded in API responses that allow one-click navigation from eval results to Grafana Tempo traces.

Status: **Implemented**

## Key Terms
- **Grafana Explore deep link**: A fully-formed `{grafana-base-url}/explore?...` URL that opens Grafana Explore pre-loaded with a specific query.
- **GrafanaLinkBuilder**: Injectable service component that constructs Grafana Explore URLs. Disabled when `app.grafana.base-url` is blank.
- **Trace URL**: A deep link that opens a single trace by its trace ID.
- **Run Explore URL**: A deep link that opens a TraceQL query scoped to all test-case traces for a specific run, pre-set to the run's time range.
- **Test Case Aggregate URL**: A deep link that opens a TraceQL query aggregating all spans for a specific test case within a run (deployment call + all metric evaluation calls), scoped to the eval summary's time range.

## Requirements

### Requirement: Grafana base URL configuration
The service SHALL support an optional `app.grafana.base-url` configuration property. When set to a non-blank value, Grafana deep link generation SHALL be enabled. When blank or absent, deep link generation SHALL be disabled and no URL fields SHALL be included in responses.

Status: **Implemented**

#### Scenario: Grafana disabled by default
- **WHEN** `app.grafana.base-url` is not configured or is blank
- **THEN** all `grafanaTraceUrl` and `grafanaExploreUrl` fields in API responses SHALL be `null` (omitted via `@JsonInclude(NON_NULL)`)

#### Scenario: Grafana enabled at deploy time
- **WHEN** `app.grafana.base-url` is set to a non-blank URL (e.g., `http://grafana:3000`)
- **THEN** `GrafanaLinkBuilder` SHALL generate Grafana Explore deep links in all applicable API responses

### Requirement: Tempo datasource UID configuration
The service SHALL support an optional `app.grafana.tempo-datasource-uid` property to configure the Grafana Tempo datasource UID. The default value SHALL be `"tempo"`.

Status: **Implemented**

#### Scenario: Default datasource UID
- **WHEN** `app.grafana.tempo-datasource-uid` is not configured
- **THEN** generated URLs SHALL use datasource UID `"tempo"`

#### Scenario: Custom datasource UID
- **WHEN** `app.grafana.tempo-datasource-uid` is set to a custom value (e.g., `"my-tempo"`)
- **THEN** generated URLs SHALL use that custom UID in all deep link URLs

### Requirement: Per-trace Grafana URL on test case execution result
The service SHALL include a `grafanaTraceUrl` field on `ExecutionInfoResponseDto` (nested under `TestCaseRunResultResponseDto.executionInfo`) when `traceId` is present and Grafana is configured.

Status: **Implemented**

#### Scenario: Trace URL present when traceId and Grafana configured
- **WHEN** a test case execution result has a non-null `traceId`
- **AND** `app.grafana.base-url` is configured
- **THEN** `executionInfo.grafanaTraceUrl` SHALL be a valid Grafana Explore URL that opens the trace for that `traceId` in Grafana Tempo

#### Scenario: Trace URL absent when traceId is null
- **WHEN** a test case execution result has a null `traceId` (OTel disabled)
- **THEN** `executionInfo.grafanaTraceUrl` SHALL be `null` (omitted in JSON)

#### Scenario: Trace URL absent when Grafana not configured
- **WHEN** `app.grafana.base-url` is blank
- **AND** `traceId` is present
- **THEN** `executionInfo.grafanaTraceUrl` SHALL be `null` (omitted in JSON)

### Requirement: Per-trace Grafana URL on try-it-out response
The service SHALL include a `grafanaTraceUrl` field on `TryItOutResponseDto` when `traceId` is present and Grafana is configured. Follows the same enabling conditions as the test case execution result.

Status: **Implemented**

#### Scenario: Trace URL present on try-it-out when configured
- **WHEN** a try-it-out invocation completes with a valid OTel trace
- **AND** `app.grafana.base-url` is configured
- **THEN** `TryItOutResponseDto.grafanaTraceUrl` SHALL be a Grafana Explore URL for that `traceId`

#### Scenario: Trace URL absent on try-it-out when OTel disabled
- **WHEN** OTel is disabled (`OTEL_SDK_DISABLED=true`)
- **THEN** `TryItOutResponseDto.traceId` is `null`, `TryItOutResponseDto.grafanaTraceUrl` SHALL be `null`

### Requirement: Run-scoped Grafana Explore URL on test suite run response
The service SHALL include a `grafanaExploreUrl` field on `TestSuiteRunResponseDto` when Grafana is configured. This URL SHALL be a Grafana Explore TraceQL query (`{.eval.run.id="<runId>"}`) scoped to the run's time window.

Status: **Implemented**

#### Scenario: Run explore URL present when Grafana configured and run has started
- **WHEN** `GET /api/v1/test-suite-runs/{id}` is called
- **AND** `app.grafana.base-url` is configured
- **AND** the run has a non-null `startedAt` (status is RUNNING, COMPLETED, or FAILED)
- **THEN** `grafanaExploreUrl` SHALL be a Grafana Explore URL with TraceQL query `{.eval.run.id="<run-uuid>"}` and time range set to the run's `startedAt - 5 min` to `completedAt + 5 min` (or `now` when run is still in progress)

#### Scenario: Run explore URL absent when run is PENDING
- **WHEN** the run has a null `startedAt` (status is PENDING — no traces exist yet)
- **THEN** `grafanaExploreUrl` SHALL be `null` (omitted in JSON) regardless of Grafana configuration

#### Scenario: Run explore URL absent when Grafana not configured
- **WHEN** `app.grafana.base-url` is blank
- **THEN** `grafanaExploreUrl` SHALL be `null` (omitted in JSON)

### Requirement: Per-test-case aggregate Grafana URL on eval summary responses
The service SHALL include a `grafanaTraceUrl` field on `EvalSummaryResponseDto` (list) and `EvalSummaryDetailResponseDto` (detail) that links to a Grafana Explore TraceQL query aggregating all spans for that test case within the run (deployment call + all metric evaluation calls).

Status: **Implemented**

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

Status: **Implemented**

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

## Implementation Notes
- `GrafanaLinkBuilder`: `com.epam.aidial.evaluation.service.domain.GrafanaLinkBuilder`
- `GrafanaProperties`: `com.epam.aidial.evaluation.configuration.properties.grafana.GrafanaProperties` (`prefix = "app.grafana"`)
- Docs: `docs/configuration.md` (new Grafana configuration section)
