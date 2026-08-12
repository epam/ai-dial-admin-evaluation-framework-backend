## Context

See `proposal.md` — Why. Design-relevant current state:

- `FilterTranslator.toComparison` returns an opaque jOOQ `Condition` per comparison node; `toLogical` maps
  `and`/`or`/`not` onto `DSL.and`/`DSL.or`/`DSL.not`.
- `QueryDslRunnableTestCaseSelector.compile` (line 88-102) takes that `Condition` and wraps it as
  `NOT EXISTS (SELECT 1 FROM jsonb_array_elements(coalesce(multi_turn_data, jsonb_build_array(data))) t(elem)
   WHERE (<pred>) IS NOT TRUE)`. The `IS NOT TRUE` is the only place UNKNOWN is folded into a decision, and it
  folds it toward "turn failed".
- Everywhere else a DSL filter lands in a plain `WHERE`, where UNKNOWN excludes the row.

So a leaf predicate that never yields UNKNOWN is decided identically by both consumers. That is the lever:
fix polarity at the leaves, change nothing downstream.

## Goals / Non-Goals

**Goals:**
- Negated leaves (`nc`, `ne`-with-non-null) and the `not` node become total (TRUE/FALSE only).
- One production file changed; `QueryDslRunnableTestCaseSelector`, `TestCaseFieldBindingsBuilder`, the entity
  resolvers, and every repository stay byte-identical.
- No regression in the SQL emitted for positive operators.

**Non-Goals:**
- Making positive operators total (see Decision 2).
- Aligning `POST /api/v1/queries/execute` on `test_cases` with the ALL-turns quantifier — that path uses
  non-scoped bindings and gives a different answer for multi-turn cases independently of null polarity. It is
  a real, separate inconsistency; out of scope here.
- Any new API surface exposing per-test-case "would this be included" to the FE grid.

## Decisions

**1. Wrap negated leaves in `IS NOT FALSE`, not `left IS NULL OR <negation>`.**
A small private helper emits `DSL.condition("({0}) is not false", condition)` — the same plain-SQL
`QueryPart` templating already proven in this codebase at `QueryDslRunnableTestCaseSelector.java:101`.
Alternatives considered:
- `DSL.or(left.isNull(), negation)` — duplicates the left expression in the emitted SQL, misses a null on the
  *right* side, and needs a separate shape for a function-wrapped left (`lower(x)`).
- `DSL.coalesce(DSL.field(condition, Boolean.class), DSL.inline(true))` — same effect, but casting a
  `Condition` to a boolean `Field` and back reads worse than the `BooleanTest` it compiles to anyway.
Both alternatives are equally non-sargable, so the simpler, operand-agnostic form wins.

**2. Leave positive operators emitting three-valued SQL.**
Their UNKNOWN already behaves as "not matching" in a `WHERE` and under `IS NOT TRUE`, so wrapping them in
`IS TRUE` would be semantically neutral in every current consumer — while putting a `BooleanTest` node around
predicates that are otherwise index-usable (`run_id = ?` on `eval_summaries`, `dataset_id = ?` on
`test_cases`). Not worth the planner risk. The one place their UNKNOWN was observable — under `not` — is
addressed by Decision 3 instead.

**3. Make `not` total at the node, not by making its children total.**
`toLogical`'s `NOT` branch emits `(child) IS NOT TRUE` instead of `DSL.not(child)`. This gives
`not(co(field, "x"))` on a null field the same answer as `nc(field, "x")` (Decision 1) without touching
positive-leaf SQL. It also composes: since `and`/`or` over total operands stay total, the only residual
UNKNOWN source is a positive leaf, and `IS NOT TRUE` resolves it exactly where a negation observes it.

**4. Put null polarity on `ComparisonOp`, not in a translator-local switch.**
Add `boolean negated()` to the enum (`true` for `NC`/`NE`). The translator branches on it. Rationale: a
future operator (e.g. a `not_in`) then declares its own polarity at the point of definition rather than
requiring someone to remember a second switch in the translator.

**5. Component interaction / transaction boundaries: unchanged.**
No new beans, no new packages, no service or repository signature change, no transaction boundary touched.
The change is confined to SQL text generation inside an existing `@Component`.

## Risks / Trade-offs

- [Behavioral change reaches every DSL entity, not just `test_cases`] → Intended, and stated as BREAKING in
  the proposal. Verified no code-defined production query uses `NC`/`NE` (`BuiltInMetricStatistics`,
  `OverallScoreDefinitionResolver` build arithmetic/aggregates only), so only client-authored filters are
  affected — and for those the new answer is the intuitive one.
- [`IS NOT FALSE` around `<>` / `NOT LIKE` blocks index usage] → Those predicates were never index-driven
  (a btree index does not serve `<>` or a leading-wildcard `LIKE`), so the plan shape is unchanged in
  practice. Positive operators, which *are* index-driven, are deliberately left alone.
- [A user relying on the old "NOT CONTAIN hides null rows" behavior] → Reachable with an explicit
  `and(ne(field, null), nc(field, "x"))`, which is what such a user should have written. Worth a line in the
  release notes.
- [Stale `is_valid`/count drift] → None: `countRunnable` and the snapshot selection both compile through the
  same `FilterTranslator`, so the zero-runnable guard and the materialized snapshot cannot disagree.

## Migration Plan

Pure code change: deploy, no migration, no backfill, no config. Rollback is a revert — nothing is persisted
in the new semantics. Existing runs are unaffected; only future run-selection and future filter queries see
the corrected predicate.
