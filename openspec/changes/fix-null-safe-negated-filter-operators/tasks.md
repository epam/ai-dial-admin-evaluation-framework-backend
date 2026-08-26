## 1. Null-total negated operators in the DSL translator

- [x] 1.1 Add `boolean negated()` to `query/model/ComparisonOp.java` — `true` for `NC`/`NE`,
      `false` for the rest (done: enum carries the polarity, no translator-local operator list).
- [x] 1.2 Add a failing DB-free render test
      `src/test/java/com/epam/aidial/evaluation/query/service/translate/FilterTranslatorNullSemanticsTest.java`,
      reusing the harness in `FilterTranslatorArrayContainmentTest` (`DSL.using(SQLDialect.POSTGRES)` +
      `renderInlined`): scalar `nc`, array-field `nc`, and `ne`-with-non-null render an `is not false`
      wrapper; `not(co(...))` renders `is not true`; `co`/`eq` render unchanged with no `BooleanTest`;
      `ne null` still renders `is not null` (done: test written and observed failing).
- [x] 1.3 Implement in `query/service/translate/FilterTranslator.java`: private
      `nullSatisfies(Condition)` helper emitting `DSL.condition("({0}) is not false", condition)`; apply to
      the array-containment `NC` branch, the scalar `case NC`, and `case NE`; leave the `ne null` →
      `isNotNull` path and every positive operator untouched (done: 1.2 passes,
      `FilterTranslatorArrayContainmentTest` still passes).
- [x] 1.4 Make the `NOT` logical node total in `FilterTranslator.toLogical` —
      `DSL.condition("({0}) is not true", toCondition(child, bindings))` instead of `DSL.not(...)`
      (done: the `not(co(...))` assertion from 1.2 passes).
- [x] 1.5 Run `./gradlew test --tests "com.epam.aidial.evaluation.query.service.translate.*"`
      plus `--tests "*StructuredQueryBuilderTest" --tests "*EvalSummaryQueryRenderTest"` (done: green — no
      unintended SQL drift for positive operators).

## 2. End-to-end run-selection coverage (GH #141)

- [x] 2.1 Add an `nc`-filter test to
      `src/test/java/com/epam/aidial/evaluation/functional/tests/MultiTurnFilterFunctionalTests.java`
      reproducing #141: a multi-turn case whose intermediate turns omit the filtered per-turn field IS
      runnable and produces one result row per turn, while a case holding a violating value stays excluded;
      cover a single-turn case with a null field too (done: test asserts per-test-case row counts
      deterministically, mirroring the existing `rowsPerTestCase` helper).
- [x] 2.2 Run
      `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$MultiTurn*"`
      (confirm the nested-class name in `PostgresFunctionalTests` first) — done: the new test fails on a
      stash of task 1.3-1.4 and passes with it, and the pre-existing `eq`-filter tests
      (`allTurnsMatchFiltersRunnableCases`, `noFilterRunsAllValidCases`,
      `filterExcludingAllRejectsRunCreation`) are unchanged and green.
- [x] 2.3 Run `./gradlew test --tests "*RunnableTestCaseCounterTest" --tests "*TestSuiteServiceTest"`
      (done: green — filter validation and the zero-runnable count guard unaffected).

## 3. Spec sync and final verification

- [x] 3.1 Apply the change's delta specs to the main specs: the new null-handling requirement into
      `openspec/specs/structured-query-model/spec.md` and the revised ALL-turns-match requirement into
      `openspec/specs/suite-test-case-filter/spec.md` (done: `/opsx:sync` run, `openspec validate
      --strict` clean).
- [x] 3.2 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` (done: no violations, no reformatting
      left uncommitted).
- [x] 3.3 Run `./gradlew build` (done: full suite green, including `LayeredArchitectureTest` and
      `LoggingConventionTest`).
