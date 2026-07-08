## ADDED Requirements

### Requirement: Metric evaluation honors the metric's execution condition
The system SHALL, before dispatching a metric for a test-case result, evaluate that metric's
`condition` (when non-blank) against the namespaced dictionary and gate evaluation on the result: run
on clean boolean `true`, omit on clean boolean `false`, and on any other outcome
skip-with-surfaced-error. A blank/null condition SHALL always run.
Status: **Planned**

#### Scenario: Metric runs when condition is true
- **WHEN** a metric's condition evaluates to `true` for a test-case result
- **THEN** the metric SHALL be dispatched and its output SHALL appear in that result's `metricValues`

#### Scenario: Skipped metric is omitted from the eval summary
- **WHEN** a metric's condition evaluates to `false` for a test-case result
- **THEN** that metric SHALL have no entry in the result's `metricValues` or `metricInfos`, and the
  metric SHALL NOT be included in aggregate statistics for that test case

#### Scenario: Condition error does not fail the test-case result
- **WHEN** a metric's condition throws, returns a non-boolean, or returns null at runtime
- **THEN** the metric SHALL NOT be evaluated, a metric-level `{error}` SHALL be recorded under
  `metricInfos` (rendered as `metricError::<name>`), no `metricValues` entry SHALL be written for it,
  and the test-case result's `executionStatus` SHALL remain `SUCCESS`

#### Scenario: Blank condition preserves prior behavior
- **WHEN** a metric has a null or blank condition
- **THEN** it SHALL be evaluated for every test-case result as before
