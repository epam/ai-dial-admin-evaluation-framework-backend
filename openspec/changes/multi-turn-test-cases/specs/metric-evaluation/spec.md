## ADDED Requirements

### Requirement: Metrics are evaluated per turn row
Metric evaluation SHALL treat each turn's result row as an independent evaluation unit. Because each turn persists a scalar `extracted_columns` object (never arrays across turns), the existing binding-resolution path applies unchanged per turn; no multi-turn-specific reshaping occurs at the metric boundary.

#### Scenario: Each turn scored independently
- **WHEN** a multi-turn case has N SUCCESS turn rows
- **THEN** metrics are resolved and scored per turn row from that row's `test_case_data` and `extracted_columns`

### Requirement: Conditional gating integrated into per-result evaluation
For each result row, the executor SHALL build a condition context (`data`, `response`, `turn.index/total/last`) and evaluate each metric's `condition` before dispatch: skip-and-omit on false, dispatch on true, and record a metric-level error (without failing the row) on a broken condition. Only metrics actually dispatched are considered when recording per-metric timeout/failure.

#### Scenario: Skipped metric is not dispatched
- **WHEN** a metric's condition is false for a turn row
- **THEN** the metric is not dispatched and contributes no value, and the row's status stays SUCCESS

#### Scenario: Condition error does not mark the row failed
- **WHEN** a metric's condition errors for a turn row
- **THEN** a `metricError::<name>` entry is recorded, the metric is not dispatched, and the row stays SUCCESS

## Implementation notes

Planned. `InProcessMetricEvaluationExecutor` builds `ConditionContext` from the result row and evaluates via `ConditionExpressionEvaluator` before dispatching each TSMD; only `dispatchedTsmds` are reconciled after wait. `MetricOutputMapper` handles the `TsmdEvaluationResult.ConditionError` variant (omit from `metricValues`, single wholesale entry in `metricInfos`).
