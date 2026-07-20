## MODIFIED Requirements

### Requirement: Turn position exposed via the `turn` namespace
The dictionary SHALL expose the current result row's turn position under the `turn` namespace with
three fields: `turn.index` (the authored 0-based turn index, preserved even when a multi-turn's
surviving turns are non-contiguous), `turn.total` (the multi-turn's surviving turn count that ran),
and `turn.last` (boolean, true when `index == lastTurnIndex`, where `lastTurnIndex` is the maximum
authored `turn_index` among the multi-turn's surviving turns — sourced from the result row's
`last_turn_index`). A single-turn result SHALL be `index=0, total=1, last=true` (`last_turn_index=0`).
`turn.last` SHALL NOT be derived from `index == total - 1`, which is incorrect once surviving turns are
non-contiguous. This lets a condition gate on turn position — e.g. `turn.last` to run a metric only on
the final turn of a multi-turn.
Status: **Implemented**

#### Scenario: turn.last on the final turn
- **WHEN** a condition is `turn.last` and the result is the turn whose authored `turn_index` equals the multi-turn's `last_turn_index` (the last surviving turn)
- **THEN** the condition SHALL evaluate to `true` and the metric SHALL run

#### Scenario: turn.last on a non-final turn
- **WHEN** a condition is `turn.last` and the result is a turn whose authored `turn_index` is less than the multi-turn's `last_turn_index`
- **THEN** the condition SHALL evaluate to `false` and the metric SHALL be skipped

#### Scenario: turn.last correct under non-contiguous surviving turns
- **WHEN** a multi-turn's surviving turns have authored `turn_index` `0` and `3` (so `total = 2`, `last_turn_index = 3`) and a condition is `turn.last`
- **THEN** the condition SHALL evaluate to `false` on the `turn_index = 0` row and `true` on the `turn_index = 3` row (NOT keyed off `total - 1`, which would wrongly flag the `turn_index = 1` position that does not exist)

#### Scenario: Single-turn result is the last turn
- **WHEN** a condition is `turn.last` and the result is a non-multi-turn result (`index=0, total=1, last_turn_index=0`)
- **THEN** the condition SHALL evaluate to `true`

#### Scenario: turn.index and turn.total are addressable
- **WHEN** a condition references `turn.index` or `turn.total`
- **THEN** they SHALL resolve to the result row's authored 0-based turn index and surviving turn count
