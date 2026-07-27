## MODIFIED Requirements

### Requirement: Condition is JSONata over a namespaced dictionary
The `condition` SHALL be a JSONata expression evaluated against a dictionary with four namespaces: `data` (the turn's **effective view** — the merge of the case's shared `data` map with that turn's per-turn map, per-turn keys taking precedence on overlap), `response` (the row's extracted/response columns), `turn` with fields `index` (0-based), `total` (turn count), and `last` (boolean, true when `index == total - 1`), and `request` with fields `index` (0-based chain position of the request that produced this row) and `label` (that request's resolved label). The dictionary MUST preserve explicit JSON nulls so `$exists(response.x)` distinguishes present-null from missing. For a single-turn case the effective view is simply its `data` map, and for a single-request suite `request.index` is `0` with `request.label` the resolved default — so behavior is unchanged.

`request.label` is the preferred form for targeting a request because it survives reordering: inserting a request earlier in the chain shifts every subsequent `request.index`, silently retargeting an index-based condition without any error. `request.index` remains available.
Status: **Planned**

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

#### Scenario: request.label targets one chain request
- **WHEN** a condition is `request.label = "invoke"` and a multi-request chain produces rows for `configure`, `invoke`, and `teardown`
- **THEN** the condition is true only on the `invoke` row, and the metric is skipped and omitted on the other two

#### Scenario: request.index targets one chain request
- **WHEN** a condition is `request.index = 1`
- **THEN** the condition is true only on rows whose `request_index` is 1

#### Scenario: Single-request suite sees a stable request namespace
- **WHEN** a condition on a single-request suite references `request.index`
- **THEN** it evaluates against `0`, and `request.label` evaluates against that request's resolved default label

#### Scenario: Namespaces combine
- **WHEN** a condition is `request.label = "invoke" and $exists(response.answer)`
- **THEN** both namespaces are evaluated against the same row and the metric runs only when both hold

## ADDED Requirements

### Requirement: Unconditioned metrics run on every chain request row
A metric with a null or blank condition SHALL run on every SUCCESS result row, including every row of a multi-request chain. Where a chain's requests have differing roles, this means an unconditioned metric is dispatched once per request per test case. Authors target metrics at specific requests using `condition`; there is no implicit narrowing by request.
Status: **Planned**

#### Scenario: Unconditioned metric is dispatched per chain request
- **WHEN** a metric has no condition and a three-request chain produces three SUCCESS rows for a test case
- **THEN** the metric is dispatched three times for that test case, once per row

#### Scenario: Metric with only test-case and constant bindings resolves on every row
- **WHEN** an unconditioned metric binds only `testcase` and `constant` sources
- **THEN** its bindings resolve successfully on every chain row and it produces a value on each, with no failure raised

## Implementation notes

`ConditionContext` gains `requestIndex` and `requestLabel`; `ConditionExpressionEvaluator.buildDictionaryJson` adds the `request` namespace object alongside `turn`. Values are read from the result row's `request_index` / `request_label`.
