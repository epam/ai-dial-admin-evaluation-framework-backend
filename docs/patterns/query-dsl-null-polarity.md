# Query DSL null polarity — negated operators are total, positive ones are not

`ComparisonOp.negated()` (true for `NC`/`NE`) is the single declaration of which DSL operators assert the *absence* of a match. Each operator carries its own polarity at the point of definition, so a future operator does not require a second switch in the translator.

`FilterTranslator` wraps negated comparisons in `(<pred>) IS NOT FALSE` and the `not` node in `(<child>) IS NOT TRUE`. Both are total: a null operand **satisfies** them. So `expected nc 'London'` matches a row — or a turn — that has no `expected` at all, because a missing value cannot contain "London".

**Do not "simplify" those wrappers away.** Without them, SQL three-valued logic yields UNKNOWN for a null operand: a plain `WHERE` silently drops the row, and `QueryDslRunnableTestCaseSelector`'s ALL-turns-match quantifier (`NOT EXISTS (… WHERE (<pred>) IS NOT TRUE)`) reads UNKNOWN as a *failing turn*. That is GH #141 — a single value-less intermediate turn excluded an entire multi-turn test case from a run while the UI still showed it as included.

Positive operators (`co`, `eq`, `lt`, `gt`, `le`, `ge`, `in`) are deliberately left **unwrapped**. Their UNKNOWN already means "no match" in every consumer, so wrapping them would be semantically neutral — while putting a `BooleanTest` node around otherwise sargable predicates (`run_id = ?` on `eval_summaries`, `dataset_id = ?` on `test_cases`) and risking index-scan regressions. The asymmetry is the point: an absent value cannot satisfy a positive assertion, but it trivially satisfies a negated one.

`eq`/`ne` against an explicit null literal keep their `IS NULL` / `IS NOT NULL` translation — already total, and untouched by this rule.

Because polarity lives entirely at the comparison leaves, nothing downstream needs to know about it: `QueryDslRunnableTestCaseSelector`, `TestCaseFieldBindingsBuilder`, the entity resolvers, and the repositories are all unchanged. A total leaf simply never reaches the quantifier as UNKNOWN.

Proof: `FilterTranslatorNullSemanticsTest` (rendered SQL, DB-free) and `MultiTurnFilterFunctionalTests.negatedFilterTreatsMissingPerTurnValueAsMatching` (end-to-end run selection).

See the null-handling requirement in the [structured-query-model spec](../../openspec/specs/structured-query-model/spec.md) and [suite-test-case-filter spec](../../openspec/specs/suite-test-case-filter/spec.md).
