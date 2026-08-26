## Why

GH #141: a suite run condition `expected NOT CONTAIN "London"` silently drops every test case whose
`expected` field is null or absent — including multi-turn cases where only an *intermediate* turn lacks the
field. The Test Cases grid shows such a case as "Included", the run never executes it, and the user gets no
warning. Root cause is SQL three-valued logic: `FilterTranslator` renders `nc` as
`NOT (lower(elem ->> 'expected') LIKE '%london%')`, which is **UNKNOWN** (not TRUE) for a null operand, and
`QueryDslRunnableTestCaseSelector` folds UNKNOWN into "this turn failed" via its
`NOT EXISTS (… WHERE (<pred>) IS NOT TRUE)` ALL-turns quantifier. Unknown-fails is the right rule for
positive operators, but it inverts the meaning of negated ones — an absent value trivially does not contain
"London".

## What Changes

- Structured Query DSL negated comparisons become **total** (never UNKNOWN): `nc` (both its scalar-`LIKE`
  and its array-element-containment form) and `ne` with a non-null right operand are satisfied when an
  operand is null. The existing `ne null` → `IS NOT NULL` path is already total and is unchanged.
- The `not` logical node becomes total: `not(<child>)` is TRUE when the child is FALSE **or** UNKNOWN, so a
  user-authored `not(co(field, "x"))` over a null field behaves the same as `nc(field, "x")`.
- Positive operators (`co`, `eq`, `lt`, `gt`, `le`, `ge`, `in`) are **deliberately unchanged**. Their
  UNKNOWN already behaves as non-matching in a plain `WHERE` and under the ALL-turns quantifier's
  `IS NOT TRUE`; wrapping them would add a `BooleanTest` node around otherwise sargable predicates
  (e.g. `run_id = ?` on `eval_summaries`) and risk index-scan regressions for no semantic gain.
- **BREAKING** (behavioral, no API/schema change): a `nc`/`ne`/`not` filter now *returns* rows whose field
  is null where it previously omitted them — for every DSL entity (`test_cases`, `eval_summaries`,
  `test_suites`, `metric_score_results`), and for suite `testCaseFilter` run selection at both the
  run-creation zero-runnable guard and the snapshot phase. This is the intended correction; "not contains"
  silently discarding null rows is surprising everywhere, not only in run selection.
- No new packages, no new classes, no config properties, no DB migration.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `structured-query-model`: adds a null-handling requirement to the comparison-operator and `not` semantics
  — negated operators are total (null operand satisfies them), positive operators keep three-valued
  non-matching behavior.
- `suite-test-case-filter`: refines the ALL-turns-match requirement's "Missing per-turn field fails"
  scenario — a missing/null per-turn field fails a **positive** predicate and **satisfies** a **negated**
  one, so a multi-turn case whose intermediate turns lack the filtered field stays runnable under
  `NOT CONTAIN` / `NOT EQUALS`.

## Impact

- Code: `query/service/translate/FilterTranslator.java` (the only production change),
  plus a `negated()` accessor on `query/model/ComparisonOp.java` so each operator declares its
  own null polarity instead of the translator rediscovering it.
- Unchanged by design: `QueryDslRunnableTestCaseSelector`, `TestCaseFieldBindingsBuilder`,
  `PostgresTestCaseEntityResolver`, all repositories. The fix is at the leaves and composes upward into
  both the ALL-turns lateral and plain `WHERE` usage.
- Consumers: `POST /api/v1/queries/execute`, every list endpoint that accepts a DSL filter, suite
  `testCaseFilter` validation and run selection. Verified that no code-defined production query uses
  `NC`/`NE` (`BuiltInMetricStatistics`, `OverallScoreDefinitionResolver` use arithmetic/aggregates only).
- Tests: new DB-free SQL-render test beside `FilterTranslatorArrayContainmentTest`; new `nc` scenario in
  `MultiTurnFilterFunctionalTests` reproducing #141 (must fail before the change).
- No `docs/configuration.md`, `docs/database-schema.md`, or `openspec/config.yaml` update needed — this
  follows existing architecture rather than changing them. `openspec/specs/README.md` needs no edit either:
  both modified specs keep their one-line summaries.
