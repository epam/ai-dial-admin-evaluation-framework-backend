## MODIFIED Requirements

### Requirement: ALL-turns-match filtering for multi-turn cases
When a suite `testCaseFilter` is applied to runnable-case selection, filtering SHALL be scope-aware. A predicate on a **shared** (`perTurn=false`) field SHALL bind to the case's shared `data` map (a row-level predicate, constant across turns). A predicate on a **per-turn** (`perTurn=true`) field SHALL bind to the individual turn element and be evaluated under a universal quantifier: a multi-turn case matches the per-turn predicate if and only if **every** turn satisfies it. A single-turn case is the trivial one-turn case (identical to current behavior). A turn for which a per-turn predicate is unknown/null (e.g. a missing field) SHALL count as failing. A filter MAY combine shared and per-turn predicates; the shared parts are evaluated once at row level and the per-turn parts under the all-turns quantifier.
Status: **Implemented**

#### Scenario: Shared-field filter selects at case level
- **WHEN** a filter references a shared field (e.g. `tags co "a"`) and the case's shared `data` satisfies it
- **THEN** the case is selected regardless of turn count, and a case whose shared `data` fails the predicate is excluded

#### Scenario: All turns match a per-turn filter
- **WHEN** a filter references a per-turn field and every turn of a multi-turn case satisfies it
- **THEN** the case is selected as runnable

#### Scenario: One non-matching turn excludes the case
- **WHEN** at least one turn of a multi-turn case fails a per-turn predicate
- **THEN** the entire case is excluded from runnable selection

#### Scenario: Missing per-turn field fails
- **WHEN** a turn lacks the per-turn field referenced by the filter
- **THEN** that turn is treated as failing and the case is excluded

### Requirement: Filter compiles once over a coalesced turns array
The filter SHALL be compiled once using scope-aware bindings: shared-field bindings resolve against the outer row's `data`, and per-turn-field bindings resolve against a per-turn element. The per-turn portion SHALL be wrapped as a universal quantifier over `COALESCE(multi_turn_data, jsonb_build_array(data))` using a `NOT EXISTS (... WHERE (<filter>) IS NOT TRUE)` lateral, correlated to the outer row so shared-field references remain valid inside the lateral. The lateral is added only when a filter is present, so unfiltered selection is unchanged.
Status: **Implemented**

#### Scenario: No filter leaves selection unchanged
- **WHEN** a suite has no `testCaseFilter`
- **THEN** runnable selection issues today's query with no coalesce/lateral

#### Scenario: Shared reference resolves inside the lateral
- **WHEN** a filter mixes a shared-field predicate and a per-turn predicate
- **THEN** the compiled SQL evaluates the shared predicate against the outer row's `data` and the per-turn predicate per turn element, with the shared reference correlated correctly inside the `NOT EXISTS` lateral
