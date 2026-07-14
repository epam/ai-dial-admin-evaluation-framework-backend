# Conditional Metric Execution

## Purpose
This spec defines conditional execution of a Test Suite Metric Definition (TSMD) — an optional
`condition` string on a metric that decides, per test-case result (per turn), whether that metric runs.
The condition is a JSONata expression evaluated against a namespaced `{data, response, turn}` dictionary.

Status: **Implemented**

## Requirements

### Requirement: Optional execution condition on a metric definition
A Test Suite Metric Definition (TSMD) SHALL carry an optional `condition` string. When the condition
is null or blank, the metric SHALL be evaluated for every test-case result (unchanged behavior). When
set, the condition SHALL be evaluated once per test-case result to decide whether the metric runs for
that result. Because each turn of a multi-turn conversation is its own `TestCaseRunResult`, a condition
is evaluated once per turn (its `data`/`response` are that turn's scalar values). The condition SHALL
NOT be evaluated on non-SUCCESS rows (a failing turn or a `0/0` data-error row), which propagate
without metric evaluation.
Status: **Implemented**

#### Scenario: No condition means always run
- **WHEN** a TSMD has a null or blank `condition`
- **THEN** the metric SHALL be evaluated for every test-case result exactly as before

#### Scenario: Condition gates evaluation per test-case result
- **WHEN** a TSMD has a non-blank `condition`
- **THEN** the condition SHALL be evaluated once per test-case result and its result SHALL determine
  whether that metric runs for that result

#### Scenario: Condition runs on each successful turn
- **WHEN** a 3-turn conversation completes successfully and a TSMD carries a condition referencing `response`
- **THEN** the condition SHALL be evaluated three times, once per turn-result, each against that turn's scalar `data`/`response`

#### Scenario: Condition not evaluated on a failed turn
- **WHEN** turn `k` of a conversation is an ERROR result
- **THEN** no condition SHALL be evaluated for that turn-result and no metric SHALL be dispatched for it

### Requirement: Condition evaluates against a namespaced dictionary
The condition SHALL be evaluated against a single dictionary with three top-level namespaces: `data`
(the test case's data columns), `response` (the extracted/response columns) — reusing the same
namespace tokens as the CSV export — and `turn` (the current turn's position; see the turn-namespace
requirement). Because each turn of a multi-turn conversation is its own test-case result, the `data`
and `response` values SHALL be that turn's scalar columns (the same shape metric bindings resolve
against). The dictionary serialization passed to the JSONata evaluator SHALL preserve explicit JSON
nulls (it MUST NOT use the shared `NON_NULL` mapper path, which would drop null-valued keys and make a
present-but-null column appear absent).
Status: **Implemented**

#### Scenario: Reference a response column
- **WHEN** a condition is `$exists(response.answer)` and the test case produced an `answer` column
- **THEN** the condition SHALL resolve `response.answer` from the extracted columns

#### Scenario: Reference a data column
- **WHEN** a condition references `data.<field>`
- **THEN** the value SHALL come from the test case's data columns

#### Scenario: No collision between namespaces
- **WHEN** a data column and an extracted column share the same name
- **THEN** the condition SHALL be able to address each unambiguously via `data.<name>` and
  `response.<name>`

#### Scenario: Present-but-null column preserved
- **WHEN** a condition is `$exists(response.answer)` and the extracted `answer` column is present with
  an explicit null value
- **THEN** `response.answer` SHALL be a JSON null (not absent), so the condition can distinguish a
  present-null column from a missing one

### Requirement: Condition is a JSONata expression
Every condition SHALL be evaluated as a JSONata expression against the namespaced dictionary. The
condition SHALL be trimmed before evaluation.
Status: **Implemented**

#### Scenario: JSONata expression
- **WHEN** a condition is `$exists(response.answer)`
- **THEN** it SHALL be evaluated as JSONata against the namespaced dictionary

#### Scenario: Condition is trimmed before evaluation
- **WHEN** a condition has leading or trailing whitespace
- **THEN** the value SHALL be trimmed before evaluation

### Requirement: Turn position exposed via the `turn` namespace
The dictionary SHALL expose the current result row's turn position under the `turn` namespace with
three fields: `turn.index` (0-based turn index), `turn.total` (the conversation's planned turn count),
and `turn.last` (boolean, true when `index == total - 1`). A single-turn result SHALL be
`index=0, total=1, last=true`. This lets a condition gate on turn position — e.g. `turn.last` to run a
metric only on the final turn of a conversation.
Status: **Implemented**

#### Scenario: turn.last on the final turn
- **WHEN** a condition is `turn.last` and the result is turn `i` of a conversation where `i == N - 1`
- **THEN** the condition SHALL evaluate to `true` and the metric SHALL run

#### Scenario: turn.last on a non-final turn
- **WHEN** a condition is `turn.last` and the result is turn `i` where `i < N - 1`
- **THEN** the condition SHALL evaluate to `false` and the metric SHALL be skipped

#### Scenario: Single-turn result is the last turn
- **WHEN** a condition is `turn.last` and the result is a non-multi-turn result (`index=0, total=1`)
- **THEN** the condition SHALL evaluate to `true`

#### Scenario: turn.index and turn.total are addressable
- **WHEN** a condition references `turn.index` or `turn.total`
- **THEN** they SHALL resolve to the result row's 0-based turn index and planned turn count

### Requirement: Runtime condition outcome determines metric result
For each test-case result, the condition outcome SHALL map to exactly one behavior: a clean boolean
`true` runs the metric normally; a clean boolean `false` skips the metric and omits it entirely from
the result (no `metricValues` and no `metricInfos` entry); any other outcome (throws, non-boolean, or
null) skips the metric but records a metric-level error under `metricInfos` (surfaced as the
`metricError::<name>` export column) and MUST NOT change the test-case result's execution status.
Status: **Implemented**

#### Scenario: Condition true runs the metric
- **WHEN** a condition evaluates to boolean `true`
- **THEN** the metric SHALL be evaluated and its values SHALL appear in the result as usual

#### Scenario: Condition false omits the metric
- **WHEN** a condition evaluates to boolean `false`
- **THEN** the metric SHALL have no entry in `metricValues` or `metricInfos` for that test case

#### Scenario: Condition error is surfaced but result stays successful
- **WHEN** a condition throws, returns a non-boolean, or returns null at runtime
- **THEN** the metric SHALL NOT be evaluated, a metric-level `{error}` SHALL be recorded (rendered as
  `metricError::<name>`), `metricValues` SHALL have no entry for it, and the test-case result's
  `executionStatus` SHALL remain `SUCCESS`

## Implementation notes

Components (service.domain): `ConditionExpressionEvaluator` (single entry point;
`validate(condition)` and `evaluate(condition, ConditionContext)`), `ConditionContext` (builder-backed,
extensible carrier — future evaluation inputs can be added without changing method signatures; exposes
the `data`/`response` inputs plus `turnIndex`/`totalTurns` sourced from the `TestCaseRunResult`, `0`/`1`
for a single-turn result). Reuses `JsonataEvaluationService` for JSONata parse/eval and the
`data`/`response` namespace tokens from `EvalSummaryExportColumnConstants`; the `turn` namespace
(`index`/`total`/`last`) is built from the `ConditionContext` turn position. Runtime integration in
`InProcessMetricEvaluationExecutor.evaluateAndBuild()` with a
`TsmdEvaluationResult.ConditionError` variant handled by `MetricOutputMapper`; `checkForErrors`
ignores `ConditionError`.
