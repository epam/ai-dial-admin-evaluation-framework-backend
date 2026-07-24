## MODIFIED Requirements

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
