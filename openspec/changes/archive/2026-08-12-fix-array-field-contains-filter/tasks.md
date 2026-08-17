## 1. Failing tests first (RED)

- [x] 1.1 Add `executeCaseInsensitiveArrayContains` to
  `src/test/java/com/epam/aidial/evaluation/functional/tests/TestCaseQueryAndFilterFunctionalTests.java`:
  `/queries/execute` with `co(lower(data::tags), "tee")` over a dataset seeded with `["Tee","x"]`,
  `["tee"]`, `["tee-shirt"]`, `["y"]`; asserts exactly `tc-upper` + `tc-exact`
  (done: fails with 400 `function lower(jsonb) does not exist`)
- [x] 1.2 Add `createRunWithCaseInsensitiveArrayContainsFilter` to the same class: suite carrying that
  filter, `POST /test-suites/{id}/runs` asserts 202 and `numberOfTestCases == 2`
  (done: fails with 500 — the GH #142 stacktrace)
- [x] 1.3 Extend `seedDatasetWithCaseVaryingTags` with an off-type row (`{"category":"A","tags":"tee"}`,
  an `ARRAY`-declared field holding a scalar) and a numeric-element row (`{"category":"A","tags":[1,2]}`),
  keep 1.1's `co` expectation at exactly `tc-upper` + `tc-exact`, and add a `nc` assertion that the
  off-type and numeric rows match (done: without the type guard the query fails with
  `cannot extract elements from a scalar`, so this test is what keeps the guard honest)

## 2. Translation change (GREEN)

- [x] 2.1 In `src/main/java/com/epam/aidial/evaluation/experimental/query/service/translate/FilterTranslator.java`,
  replace the `isArrayField(leftExpr, bindings)` boolean check with a resolver that returns the resolved
  array operand — the bare `ARRAY`-bound `FieldExpr` plus an `ignoreCase` flag — or `null`: bare field →
  `ignoreCase=false`; single-argument `lower`/`upper` `FnExpr` wrapping such a field → `ignoreCase=true`;
  anything else → `null` (done: no behavior change yet for bare operands, wrapped operands now enter the
  array branch)
- [x] 2.2 Extend `arrayContains` to emit, for `ignoreCase=true` with a string operand, the
  case-insensitive whole-element predicate from design.md decision 2 — `exists (select 1 from
  jsonb_array_elements_text(case when jsonb_typeof(col) = 'array' then col else '[]'::jsonb end) as e(v)
  where lower(e.v) = lower({operand}))` — as a plain-SQL template with the operand bound; the type guard
  MUST sit inside the function argument, not as a sibling `AND` conjunct (planner reorders conjuncts).
  Keep the `?` element-existence form for `ignoreCase=false` and the `@> to_jsonb(...)` form for every
  non-string literal (done: 1.1, 1.2 and 1.3 pass)
- [x] 2.3 Verify `nc` on a wrapped array field stays total over null/absent/off-type values and that the
  multi-turn `NOT EXISTS … IS NOT TRUE` quantifier still nests the new template correctly (done:
  `executeCaseInsensitiveArrayNotContains` pins totality at DB level — the `CASE` guard, not the inert
  `nullSatisfies` wrapper, is what supplies it since `EXISTS` is never UNKNOWN — and
  `caseInsensitiveArrayContainsFilterIsAllTurnsMatch` in `MultiTurnFilterFunctionalTests` nests it over a
  `perTurn` `ARRAY` field)

## 3. Translator unit coverage

- [x] 3.1 In `src/test/java/com/epam/aidial/evaluation/experimental/query/service/translate/FilterTranslatorArrayContainmentTest.java`,
  add rendering tests for `co` on `lower(data::tags)` and `upper(data::tags)`: SQL contains
  `jsonb_array_elements_text`, `jsonb_typeof`, `lower(e.v)` and no `like`, and does NOT apply
  `lower(`/`upper(` to the JSONB path itself — assert that specifically (e.g. `doesNotContain("lower((\"data\"")`),
  since the correct predicate legitimately contains `lower(`
  (done: `./gradlew :test --tests "*FilterTranslatorArrayContainmentTest"` passes)
- [x] 3.2 Add rendering tests for `nc` on `lower(data::tags)` (negation wrapped in `is not false`) and for
  a non-string right operand on a wrapped array field (`@> to_jsonb(?)`, wrapper dropped)
  (done: same command passes)
- [x] 3.3 Keep/adjust `functionLeftFallsThroughToLike` so it still pins the fall-through for a
  `lower(<string field>)` operand, and add a case for a non-case-normalizing function over an array field
  falling through unchanged (done: assertions reflect the narrowed scenario in the specs delta)

## 4. Verification and docs

- [x] 4.1 Run the DSL translation unit tests and the two functional suites that execute filter SQL:
  `./gradlew :test --tests "*FilterTranslator*" --tests "*StructuredQueryBuilderTest"` and
  `./gradlew :test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestCaseQueryAndFilterTests"
  --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$MultiTurnFilterTests"`
  (done: all green, including the pre-existing `executeArrayContains` bare-field test)
- [x] 4.2 Run `./gradlew spotlessApply` then `./gradlew build` (spotlessCheck + checkstyleMain +
  checkstyleTest + full test run) (done: build succeeds with no formatting or checkstyle findings)
- [x] 4.3 Update `docs/patterns/test-cases-query-entity.md` — the "`co`/`nc` on an `ARRAY` field → JSONB
  containment" note must cover the `lower`/`upper`-wrapped operand and its case-insensitive whole-element
  semantics (done: doc states both operand shapes)
- [x] 4.4 Update `docs/patterns/query-dsl-function-catalog.md` — record that `lower`/`upper` are consumed
  as a case-normalization hint by the `co`/`nc` array branch instead of translating to the SQL function
  there (done: doc notes the one exception to literal function translation)
- [x] 4.5 In `openspec/specs/structured-query-model/spec.md` Implementation notes, replace the stale
  `FilterTranslator.toComparison:` bullet ("a non-`FieldExpr` left operand keeps scalar LIKE") with the
  delta's replacement bullet, keeping its `??`-escaping and `bindings.get(name)` sentences — the archive
  sync appends notes rather than replacing them (done: no bullet in the synced spec claims a
  non-`FieldExpr` left operand always keeps LIKE)
- [x] 4.6 Update `docs/experimental/structured-query-model-v8.html` (the client-facing wire contract the
  Admin UI is authored against): its operator table documents `co`/`nc` as ILIKE substring only, with no
  array containment at all — document both the pre-existing array-element containment and the new
  `lower`/`upper`-wrapper reading (done: the `co`/`nc` row and the `lower`/`upper` function entries
  describe the array behavior)
- [x] 4.7 Confirm the delta in
  `openspec/changes/fix-array-field-contains-filter/specs/structured-query-model/spec.md` still matches
  the implemented predicate before archiving, so `/opsx:archive` syncs an accurate
  `openspec/specs/structured-query-model/spec.md` (done: scenarios match the emitted SQL and test names).
  **At archive time also verify** that the sync replaced the canonical scenario "Contains on a non-array
  left operand falls through to LIKE" with the delta's narrowed wording — the notes bullet was hand-synced
  in 4.5, but that scenario still claims a function-wrapped operand always keeps LIKE, which the code now
  falsifies — and that the delta's `ALREADY SYNCED` HTML comment is treated as an instruction to the sync
  step, not copied into the canonical spec.

## 5. Independent-review corrections (post-implementation)

- [x] 5.1 Match the wrapper name **ignoring case**: `QueryFunctionRegistry` lowercases names before
  dispatch, so `LOWER(<array field>)` was an accepted filter that still routed into `lower(jsonb)` — the
  same 500 the change fixes. Added `translate/function/QueryFunctionNames` (`LOWER`/`UPPER` +
  `isCaseNormalizing`), used by both `BuiltInQueryFunctions` registration and `FilterTranslator` routing so
  the two cannot drift (also removes the duplicated literals AGENTS.md forbids)
  (done: `wrapperNameIsMatchedIgnoringCase` fails on the pre-fix routing)
- [x] 5.2 Strengthen the `nc` rendering assertion from `contains("not")` — which `is not false` already
  satisfies, so deleting `DSL.not(...)` left it green — to `contains("not (exists")`, and rename the test to
  what it actually asserts (the SQL shape, not DB-level totality, which 2.3 covers)
- [x] 5.3 Add the coverage the review found missing: an upper-case wrapper name, `co(lower(arr), null)` →
  `ValidationException` rather than a translated predicate, a `tc-absent` fixture row (no `tags` key) in the
  `nc` expectation, and `caseInsensitiveArrayContainsFilterIsAllTurnsMatch` over a `perTurn` `ARRAY` field
  (needed a `createChatSuite(name, filter, extraFields)` overload in `AbstractMultiTurnFunctionalTest`)
- [x] 5.4 Correct the factually wrong claims the review disproved, in `FilterTranslator` javadoc,
  `docs/patterns/test-cases-query-entity.md`, `docs/patterns/query-dsl-function-catalog.md`,
  `docs/experimental/structured-query-model-v8.html`, `proposal.md`, `design.md` and the delta spec:
  (a) the bare `?`/`@>` forms **do** match off-type rows, so "a non-array value never matches" holds only
  for the wrapped form — a second bare↔wrapped divergence, now documented and spec'd;
  (b) the GIN trade-off does not exist — `test_cases.data` has no GIN index and both forms filter on the
  extracted `data -> '<field>'` expression, which `jsonb_ops` cannot serve;
  (c) totality of wrapped `nc` comes from the `CASE` guard, not from `nullSatisfies` (inert here);
  (d) the failure reaches the client as **403** in oidc mode (unhandled 500 + `/error` not public +
  `anyRequest().denyAll()`), which is how GH #142 was reported
- [x] 5.5 Update the `structured-query-model` summary in `openspec/specs/README.md`, which still said
  containment applies only to a **bare** `ARRAY`-typed operand (Spec Index Maintenance Policy)
