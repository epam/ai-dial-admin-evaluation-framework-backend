## ADDED Requirements

### Requirement: ALL-turns-match filtering for multi-turn cases
When a suite `testCaseFilter` is applied to runnable-case selection, a multi-turn case SHALL be considered a match if and only if **every** turn satisfies the filter (universal quantifier). A single-turn case is the trivial one-turn case (identical to current behavior). A turn for which the filter is unknown/null (e.g. a missing field) SHALL count as failing.

#### Scenario: All turns match
- **WHEN** a filter is `tags co "a" or tags co "b"` and every turn of a multi-turn case has a matching tag
- **THEN** the case is selected as runnable

#### Scenario: One non-matching turn excludes the case
- **WHEN** at least one turn of a multi-turn case fails the filter
- **THEN** the entire case is excluded from runnable selection

#### Scenario: Missing-field turn fails
- **WHEN** a turn lacks the field referenced by the filter
- **THEN** that turn is treated as failing and the case is excluded

### Requirement: Filter compiles once over a coalesced turns array
The filter SHALL be compiled once against a per-turn element and wrapped as a universal quantifier over `COALESCE(multi_turn_data, jsonb_build_array(data))` using a `NOT EXISTS (... WHERE (<filter>) IS NOT TRUE)` lateral. The lateral is added only when a filter is present, so unfiltered selection is unchanged.

#### Scenario: No filter leaves selection unchanged
- **WHEN** a suite has no `testCaseFilter`
- **THEN** runnable selection issues today's query with no coalesce/lateral

## Implementation notes

Planned. New `TestCaseFieldBindingsBuilder` overload binding `data::<field>` against a supplied JSONB element `Field` (`elem`), in `experimental.query.service`. `QueryDslRunnableTestCaseSelector.compile()` (`experimental.query.service`) compiles the filter against `elem` via `FilterTranslator` and wraps the resulting `Condition` in the `NOT EXISTS` lateral over `COALESCE(multi_turn_data, jsonb_build_array(data))`; since `compile()` feeds both `countRunnable` and `loadRunnablePage`, the lateral covers both the count and load paths. The finished lateral `Condition` is passed to `PostgresTestCaseRepository` (`findValidByDatasetIdExcludingIdsMatching` / `countValidByDatasetIdExcludingIdsMatching`) as the opaque `extraCondition` — the repo does not build the lateral or see the filter tree/element bindings (that would make `data` depend on `experimental.query.service`, forbidden by `LayeredArchitectureTest`). `FilterTranslator`/`ExprTranslator` unchanged. Scope is the filter predicate only — multi-turn projection/aggregation in `POST /queries/execute` is out of scope.
