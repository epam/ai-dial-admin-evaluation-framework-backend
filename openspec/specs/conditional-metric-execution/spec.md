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
The `condition` SHALL be a JSONata expression evaluated against a dictionary with four namespaces: `data` (the turn's **effective view** — the merge of the case's shared `data` map with that turn's per-turn map, per-turn keys taking precedence on overlap), `response` (the turn's extracted/response columns — the accumulated union visible at that row, so columns extracted by earlier requests of the suite's chain are present), `turn` with fields `index` (0-based), `total` (turn count) and `last` (boolean, true when `index == total - 1`), and `request` with fields `index` (0-based position in the suite's request chain), `total` (chain length), `last` (boolean, true when `index == total - 1`) and `name` (the request's label — the suite's `requestName` for request #0, the `additionalRequests[i].name` for an additional request, JSON null when unlabelled). The dictionary MUST preserve explicit JSON nulls so `$exists(response.x)` distinguishes present-null from missing. `request.name` SHALL always be emitted — the label when present, an explicit JSON null when the request is unlabelled — so an author tests unlabelled-ness with `request.name = null`. `$exists(request.name)` SHALL NOT be used for that test: consistent with explicit-null preservation, it returns true for a present-null value and is therefore always true for `request.name`. For a single-turn case the effective view is simply its `data` map, and for a suite with no `additionalRequests` the `request` namespace is always `{index: 0, total: 1, last: true, name: <requestName or null>}` — so behavior is unchanged.
Status: **Implemented**

#### Scenario: turn.last selects the final turn
- **WHEN** a condition is `turn.last` and a result row is the last turn of its test-case run
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

#### Scenario: request.last selects the final request of the chain
- **WHEN** a condition is `request.last` on a 2-request chain
- **THEN** the metric runs only on rows whose `request.index` is 1, and is skipped on request #0's rows

#### Scenario: request.index pins a metric to one request
- **WHEN** a condition is `request.index = 0` on a 3-request chain
- **THEN** the metric runs only on request #0's rows

#### Scenario: request.name targets a labelled request
- **WHEN** a condition is `request.name = "configure"` and the suite's `requestName` is `"configure"`
- **THEN** the metric runs on request #0's rows and is skipped on every additional request's rows

#### Scenario: Unlabelled request has an explicit null name
- **WHEN** a condition uses `request.name = null` on a row of an additional request whose `name` was omitted
- **THEN** the dictionary carries `request.name` as JSON null; `request.name = null` evaluates true (and `$exists(request.name)` returns true, consistent with present-null preservation)

#### Scenario: Single-request suite sees a degenerate request namespace
- **WHEN** a suite has no `additionalRequests` and a condition is `request.last`
- **THEN** `request.index=0, request.total=1, request.last=true` and the metric runs, unchanged from before the namespace existed

#### Scenario: Condition combining request and turn scopes
- **WHEN** a condition is `request.last and turn.last` on a chain whose final request is multi-turn
- **THEN** the metric runs only on the final turn of the final request

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
