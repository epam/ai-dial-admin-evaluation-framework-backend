# Suite Test Case Filter

## MODIFIED Requirements

### Requirement: Suite-level test-case filter defines the runnable subset
A test suite's optional `testCaseFilter` SHALL define the runnable subset of its dataset's test cases.
The `testCaseFilter` is a Structured Query DSL `filter` subtree authored over the bound dataset's
test-case fields (base columns and flattened `data::<field>` fields). When set, the runnable set SHALL
be exactly the test cases that are `is_valid = true`, NOT in the suite's `disabledTestCaseIds`, AND
match the filter. The filter is AND-combined with the existing validity
and exclusion predicates; it never widens the set. When `testCaseFilter` is null or absent, selection
behaves exactly as before (validity + `disabledTestCaseIds` only).

Filter application SHALL be conversation-aware. A single-turn test case (`conversation_id IS NULL`) is
filtered per row exactly as today. A multi-turn conversation (the set of rows sharing a non-null
`conversation_id`) is an atomic unit for filtering: the conversation SHALL be included only if EVERY
one of its turns matches the filter, and excluded otherwise. The filter SHALL never produce a
per-turn hole (it SHALL NOT include a subset of a conversation's turns). The runnable selector
enforces this by aggregating per `conversation_id` (e.g. `GROUP BY conversation_id HAVING`
all turns of the conversation satisfy the AND-combined validity + exclusion + filter predicate).
Status: **Planned**

#### Scenario: Filter narrows the runnable set
- **WHEN** a suite's dataset has valid single-turn test cases `[tc-1, tc-2, tc-3]` with `data.category`
  values `["A", "B", "A"]`, `disabledTestCaseIds` is empty, and `testCaseFilter` is
  `category IN ('A')`
- **THEN** the runnable set SHALL be `[tc-1, tc-3]`

#### Scenario: Filter is AND-combined with disabled and validity
- **WHEN** the same suite additionally has `tc-1` in `disabledTestCaseIds` and `tc-3` is
  `is_valid = false`
- **THEN** the runnable set SHALL be empty (each of validity, exclusion, and the filter is applied)

#### Scenario: Null filter preserves prior behavior
- **WHEN** a suite has `testCaseFilter = null`
- **THEN** the runnable set SHALL be the valid, non-excluded test cases with no additional predicate

#### Scenario: Conversation included only when all turns match
- **WHEN** a conversation `conv-1` has turns `[turn-0, turn-1, turn-2]` with `data.category` values
  `["A", "A", "A"]` and `testCaseFilter` is `category IN ('A')`
- **THEN** the whole conversation `conv-1` SHALL be included as one runnable unit

#### Scenario: Conversation excluded when any turn fails the filter
- **WHEN** a conversation `conv-1` has turns `[turn-0, turn-1, turn-2]` with `data.category` values
  `["A", "B", "A"]` and `testCaseFilter` is `category IN ('A')`
- **THEN** the whole conversation `conv-1` SHALL be excluded (no partial/per-turn inclusion), leaving
  no `conv-1` turn in the runnable set

## Implementation Notes
- New `service`-layer interface `service.domain.job.RunnableTestCaseSelector`; implementation in
  `experimental.query.service` (mirrors the `MetricScoreComputation` inversion), backed by
  `QueryDslRunnableTestCaseSelector`. The selector aggregates per `conversation_id` so a multi-turn
  conversation is included only when all of its turns match; single-turn rows
  (`conversation_id IS NULL`) are still evaluated per row.
- Translation reuse: `FilterTranslator`, `TestCaseFieldBindingsBuilder`; base predicate mirrors
  `PostgresTestCaseRepository.validNotExcludedCondition` (validity + not-excluded), AND-combined with
  the translated filter before the per-conversation aggregation.
- Write-time filter validation is unchanged: `RunnableTestCaseSelector.validateFilter(datasetId,
  filterJson)` invoked from `TestSuiteService` on suite create/update still rejects an unknown field or
  an unbound suite with HTTP 400; only the run-time application semantics change for conversations.
- Related capabilities: `suite-run-snapshot` (conversation-aware snapshot selection and broken-
  conversation handling), `test-suite-runs` (zero-runnable guard counts runnable conversations).
