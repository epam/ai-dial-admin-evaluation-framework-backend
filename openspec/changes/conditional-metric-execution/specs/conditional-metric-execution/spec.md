## ADDED Requirements

### Requirement: Optional execution condition on a metric definition
A Test Suite Metric Definition (TSMD) SHALL carry an optional `condition` string. When the condition
is null or blank, the metric SHALL be evaluated for every test case (unchanged behavior). When set,
the condition SHALL be evaluated once per test-case result to decide whether the metric runs for that
test case.
Status: **Planned**

#### Scenario: No condition means always run
- **WHEN** a TSMD has a null or blank `condition`
- **THEN** the metric SHALL be evaluated for every test-case result exactly as before

#### Scenario: Condition gates evaluation per test case
- **WHEN** a TSMD has a non-blank `condition`
- **THEN** the condition SHALL be evaluated once per test-case result and its result SHALL determine
  whether that metric runs for that test case

### Requirement: Condition evaluates against a namespaced dictionary
The condition SHALL be evaluated against a single dictionary with two top-level namespaces: `data`
(the test case's data columns) and `response` (the extracted/response columns), reusing the same
namespace tokens as the CSV export. For multi-step conversations the `response` values SHALL be the
column-major per-turn arrays (the same shape metric bindings resolve against).
Status: **Planned**

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

### Requirement: JSONata vs custom-function detection
The system SHALL treat a condition whose whole trimmed value matches a bare identifier followed by
`()` (e.g. `isLastTurn()`) as a custom-function call, and every other condition as a JSONata
expression. JSONata's own functions are `$`-prefixed and therefore MUST never match the bare `name()`
form.
Status: **Planned**

#### Scenario: JSONata expression
- **WHEN** a condition is `$exists(response.answer)`
- **THEN** it SHALL be evaluated as JSONata against the namespaced dictionary

#### Scenario: Custom-function call
- **WHEN** a condition is exactly `someFn()` and `someFn` is a registered custom function
- **THEN** the registered custom function SHALL be invoked instead of JSONata

### Requirement: Extensible custom-function registry (initially empty)
The system SHALL provide an extensible registry of custom condition functions (an SPI where each
function declares a name and evaluates against a condition context), collected at startup and
rejecting duplicate names. No built-in functions SHALL be provided by this capability.
Status: **Planned**

#### Scenario: Duplicate function names rejected at startup
- **WHEN** two custom condition functions declare the same name
- **THEN** application startup SHALL fail

#### Scenario: Unregistered custom function is unavailable
- **WHEN** a condition is a bare `name()` whose name is not registered
- **THEN** the condition SHALL be rejected (see write-time validation) and never run

### Requirement: Condition context is an extensible carrier
The condition evaluator and every custom function SHALL receive a single `ConditionContext` object
carrying the evaluation inputs, so future fields (e.g. per-turn metadata) can be added without
changing method signatures.
Status: **Planned**

#### Scenario: Context carries the evaluation dictionary
- **WHEN** the evaluator or a custom function runs
- **THEN** it SHALL receive a context exposing the `data` and `response` inputs

### Requirement: Runtime condition outcome determines metric result
For each test-case result, the condition outcome SHALL map to exactly one behavior: a clean boolean
`true` runs the metric normally; a clean boolean `false` skips the metric and omits it entirely from
the result (no `metricValues` and no `metricInfos` entry); any other outcome (throws, non-boolean, or
null) skips the metric but records a metric-level error under `metricInfos` (surfaced as the
`metricError::<name>` export column) and MUST NOT change the test-case result's execution status.
Status: **Planned**

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

Planned components (service.domain): `ConditionExpressionEvaluator` (single entry point;
`validate(condition)` and `evaluate(condition, ConditionContext)`), `ConditionContext` (builder-backed
carrier), `ConditionFunction` (SPI), `ConditionFunctionRegistry`. Reuses `JsonataEvaluationService`
for JSONata parse/eval and the `data`/`response` namespace tokens from
`EvalSummaryExportColumnConstants`. Runtime integration in
`InProcessMetricEvaluationExecutor.evaluateAndBuild()` with a new
`TsmdEvaluationResult.ConditionError` variant handled by `MetricOutputMapper`; `checkForErrors`
ignores `ConditionError`.
