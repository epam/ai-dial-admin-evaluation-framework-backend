## MODIFIED Requirements

### Requirement: ALL-turns-match filtering for multi-turn cases
When a suite `testCaseFilter` is applied to runnable-case selection, filtering SHALL be scope-aware. A predicate on a **shared** (`perTurn=false`) field SHALL bind to the case's shared `data` map (a row-level predicate, constant across turns). A predicate on a **per-turn** (`perTurn=true`) field SHALL bind to the individual turn element and be evaluated under a universal quantifier: a multi-turn case matches the per-turn predicate if and only if **every** turn satisfies it. A single-turn case is the trivial one-turn case (identical to current behavior). A turn whose per-turn predicate is not satisfied SHALL count as failing, and per-operator null semantics decide satisfaction for a missing or null field: a **positive** predicate (`co`, `eq`, `lt`, `gt`, `le`, `ge`, `in`) SHALL NOT be satisfied by a missing/null field, while a **negated** predicate (`nc`, `ne` with a non-null operand, `not(...)`) SHALL be satisfied by it. A filter MAY combine shared and per-turn predicates; the shared parts are evaluated once at row level and the per-turn parts under the all-turns quantifier.
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
- **WHEN** a turn lacks the per-turn field referenced by a positive filter predicate (e.g. `category eq "A"`)
- **THEN** that turn is treated as failing and the case is excluded

#### Scenario: Missing per-turn field satisfies a negated predicate
- **WHEN** a filter is a negated predicate on a per-turn field (e.g. `expected nc "London"`) and some turns of a multi-turn case have a missing or null value for that field while no turn holds a value that violates the predicate
- **THEN** every turn is treated as satisfying the predicate and the case is selected as runnable

#### Scenario: A violating turn still excludes the case under a negated predicate
- **WHEN** a filter is `expected nc "London"` and one turn of a multi-turn case has `expected = "London"`
- **THEN** that turn fails and the entire case is excluded, regardless of the other turns' null values

## Implementation notes
- Null polarity lives entirely in the DSL translation layer
  (`experimental/query/service/translate/FilterTranslator.java`; see the `structured-query-model` spec's
  null-handling requirement), so `QueryDslRunnableTestCaseSelector`'s
  `NOT EXISTS (… WHERE (<pred>) IS NOT TRUE)` all-turns lateral and `TestCaseFieldBindingsBuilder`'s
  scope-aware bindings are unchanged: a total leaf predicate never reaches the quantifier as UNKNOWN.
- The same per-operator null semantics apply to single-turn cases, which the quantifier treats as the
  trivial one-element turn array.
