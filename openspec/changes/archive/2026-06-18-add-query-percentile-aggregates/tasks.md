# Tasks

## 1. Translator: percentile ordered-set aggregates

- [x] 1.1 In `ExprTranslator.toFunction` (`experimental.query.service.translate`) add `percentile_cont`
      and `percentile_disc` cases delegating to a new private two-arg handler.
- [x] 1.2 Implement the handler: require exactly two args (else `ValidationException`); parse arg0 as a
      numeric `ValueExpr` literal via `ValueExprToObjectMapper` and validate `0 ≤ fraction ≤ 1` (reject
      non-literal / non-numeric / out-of-range with `ValidationException`); resolve arg1 as the order
      field via `toField(...)`.
- [x] 1.3 Emit `DSL.percentileCont(fraction).withinGroupOrderBy(orderField)` /
      `DSL.percentileDisc(fraction).withinGroupOrderBy(orderField)`, binding the fraction as a parameter
      (no string concatenation). Ignore `fn.distinct()`.

## 2. Tests

- [x] 2.1 Unit (translate): render test asserting `percentile_cont(0.1, "metric:Accuracy:score")` emits
      `percentile_cont(?) WITHIN GROUP (ORDER BY <numeric-cast JSONB path>)` (extend
      `EvalSummaryQueryRenderTest` / `StructuredQueryBuilderTest`).
- [x] 2.2 Unit: reject wrong arity, non-literal `fraction`, non-numeric `fraction`, and `fraction`
      outside `[0, 1]` — each a `ValidationException`.
- [x] 2.3 Functional (`PostgresFunctionalTests$EvalSummaryStructuredQueryTests` or a new nested test):
      seed eval-summary rows with known metric scores, POST a GROUP-BY-less aggregate query selecting
      `percentile_cont(0.1, …)`/`percentile_cont(0.9, …)` aliased `p10`/`p90`, assert the single-row
      result matches the expected quantiles; add a 400 case for an out-of-range fraction.
- [x] 2.4 Run: `./gradlew test --tests "com.epam.aidial.evaluation.experimental.query.service.translate.*"`
      and the eval-summary execution functional test; all pass.

## 3. Spec, docs, and quality gates

- [x] 3.1 `./gradlew spotlessApply checkstyleMain checkstyleTest` clean.
- [x] 3.2 On archive, `/opsx:sync` the delta into `openspec/specs/structured-query-model/spec.md`
      (new **Supported function catalog** requirement; MODIFIED validation + SQL translation). NEVER
      hand-copy the delta.
- [x] 3.3 No `specs/README.md` status change needed (`structured-query-model` already Implemented);
      verify its summary still reads accurately after the function-catalog addition.
- [x] 3.4 No AGENTS.md / config.yaml change (feature follows existing translator patterns; no new
      package, convention, or tooling).
