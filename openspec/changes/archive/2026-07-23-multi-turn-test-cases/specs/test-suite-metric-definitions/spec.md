## ADDED Requirements

### Requirement: condition field on a Test Suite Metric Definition
A Test Suite Metric Definition SHALL carry an optional `condition` string (max 2000 chars), persisted in a new nullable `test_suite_metric_definitions.condition VARCHAR(2000)` column and exposed on the request and response DTOs. Null/blank means the metric always runs.

#### Scenario: Condition round-trips through the API
- **WHEN** a metric definition is created with a `condition` and read back
- **THEN** the response includes the same `condition` string; single-turn/unconditional definitions omit or return null

## Implementation notes

Planned. `TestSuiteMetricDefinitionRequestDto`/`ResponseDto`, `data.db.model.TestSuiteMetricDefinition` (+ RecordMapper), `TestSuiteMetricDefinitionMapper`, `AggregatedMetricDefinition` (carried through the aggregated JOIN). Migration `V1.26__AddConditionToTestSuiteMetricDefinitions.sql`. Runtime semantics are specified in `conditional-metric-execution`.
