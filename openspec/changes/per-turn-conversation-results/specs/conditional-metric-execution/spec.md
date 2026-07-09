## MODIFIED Requirements

### Requirement: Condition context is an extensible carrier
The condition evaluator and every custom function SHALL receive a single `ConditionContext` object carrying the evaluation inputs, so future fields can be added without changing method signatures. The context SHALL expose the `data` and `response` inputs and, additionally, the current turn's position: `turnIndex` (0-based) and `totalTurns` (count). These are populated from the `TestCaseRunResult` being evaluated; for a single-turn result they are `0` and `1`. Populating these fields is the enabling step for a future last-turn condition function (e.g. `isLastTurn()`), which remains out of scope here.
Status: **Planned**

#### Scenario: Context carries the evaluation dictionary
- **WHEN** the evaluator or a custom function runs
- **THEN** it SHALL receive a context exposing the `data` and `response` inputs

#### Scenario: Context carries turn position
- **WHEN** the evaluator or a custom function runs for a result at turn `i` of a conversation of `N` turns
- **THEN** the context SHALL expose `turnIndex = i` and `totalTurns = N`

#### Scenario: Single-turn context turn position
- **WHEN** the evaluator runs for a non-multi-turn result
- **THEN** the context SHALL expose `turnIndex = 0` and `totalTurns = 1`

## ADDED Requirements

### Requirement: Conditions evaluate per turn
Because each turn of a multi-turn conversation is its own `TestCaseRunResult`, a metric's `condition` SHALL be evaluated once per turn-result — the `data` and `response` inputs are that turn's scalar values, and `turnIndex`/`totalTurns` identify the turn. No condition function is required for this granularity; it is a consequence of per-turn result rows. The condition SHALL NOT be evaluated on non-SUCCESS rows (a failing turn or a `0/0` data-error row), which propagate without metric evaluation.
Status: **Planned**

#### Scenario: Condition runs on each successful turn
- **WHEN** a 3-turn conversation completes successfully and a TSMD carries a condition referencing `response`
- **THEN** the condition SHALL be evaluated three times, once per turn-result, each against that turn's scalar `data`/`response`

#### Scenario: Condition not evaluated on a failed turn
- **WHEN** turn `k` of a conversation is an ERROR result
- **THEN** no condition SHALL be evaluated for that turn-result and no metric SHALL be dispatched for it
