# Conditional Metric Execution

## Purpose
This spec defines the optional per-metric `condition` on a Test Suite Metric Definition (TSMD): a JSONata expression evaluated per test-case result row (i.e. per turn) that decides whether the metric runs. It covers the condition dictionary shape, the three runtime outcomes (run / skip-and-omit / metric-level error), and the guarantee that a broken condition never fails the result row. Write-time syntax validation is specified in `tsmd-validation`; the DTO/column surface is specified in `test-suite-metric-definitions`.

Status: **Implemented**

## Requirements

### Requirement: Optional per-metric condition
A Test Suite Metric Definition SHALL support an optional `condition` string that decides, per test-case result row (i.e. per turn), whether that metric runs. A null or blank condition means the metric always runs (backward compatible).
Status: **Implemented**

#### Scenario: Blank condition always runs
- **WHEN** a metric has no condition
- **THEN** it is evaluated on every SUCCESS result row, as today

#### Scenario: Condition gates the metric per turn
- **WHEN** a metric has a condition and a result row is evaluated
- **THEN** the metric runs on that row only if the condition evaluates to boolean true

### Requirement: Condition is JSONata over a namespaced dictionary
The `condition` SHALL be a JSONata expression evaluated against a dictionary with three namespaces: `data` (the turn's **effective view** — the merge of the case's shared `data` map with that turn's per-turn map, per-turn keys taking precedence on overlap), `response` (the turn's extracted/response columns), and `turn` with fields `index` (0-based), `total` (turn count), and `last` (boolean, true when `index == total - 1`). The dictionary MUST preserve explicit JSON nulls so `$exists(response.x)` distinguishes present-null from missing. For a single-turn case the effective view is simply its `data` map, so behavior is unchanged.
Status: **Implemented**

#### Scenario: turn.last selects the final turn
- **WHEN** a condition is `turn.last` and a result row is the last turn of its conversation
- **THEN** the condition is true and the metric runs; on non-final turns it is false and the metric is skipped

#### Scenario: Single-turn is its own last turn
- **WHEN** a single-turn result is evaluated with condition `turn.last`
- **THEN** `turn.index=0, turn.total=1, turn.last=true` and the metric runs

#### Scenario: Condition reads a shared field
- **WHEN** a condition references a shared field (e.g. `data.category = "billing"`) on a multi-turn case
- **THEN** the shared value from the case's `data` map is visible on every turn's evaluation via the merged effective view

#### Scenario: Present-null is distinguishable from missing
- **WHEN** a condition uses `$exists(response.answer)` and the column is present with a JSON null value
- **THEN** `$exists` returns true (the null is preserved, not dropped)

### Requirement: Runtime condition outcome
At runtime the condition SHALL map to one of three outcomes: clean boolean true → the metric runs; clean boolean false → the metric is skipped and omitted entirely (no `metricValues`/`metricInfos` entry); any other outcome (thrown, non-boolean, or null) → the metric is skipped but surfaced as a metric-level error (`metricError::<name>`), and the result row's execution status stays SUCCESS. Conditions are only evaluated on SUCCESS result rows.
Status: **Implemented**

#### Scenario: False omits the metric
- **WHEN** a condition evaluates to false
- **THEN** the metric contributes no entry to `metricValues` or `metricInfos`, and the eval summary remains SUCCESS

#### Scenario: Broken condition surfaces as a metric error without failing the row
- **WHEN** a condition throws or returns a non-boolean
- **THEN** the metric is skipped, a `metricError::<name>` entry is recorded, and the result row stays SUCCESS

## Implementation Notes
- `service.domain.ConditionExpressionEvaluator` (validate + evaluate), carrier `ConditionContext`, result `ConditionDecision`
- New `TsmdEvaluationResult.ConditionError` variant handled by `MetricOutputMapper`
- Wired into `service.domain.job.InProcessMetricEvaluationExecutor`
- Reuses `JsonataEvaluationService` and the eval-summary export column namespace tokens
