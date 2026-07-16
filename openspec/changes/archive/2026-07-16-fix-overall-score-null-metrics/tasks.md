## 1. DSL function catalog

- [x] 1.1 Add `coalesceFunction()` `@Bean` to `BuiltInQueryFunctions` (`experimental.query.service.translate.function`): name `"coalesce"`, exactly 2 args (mirror the `binary()` arity-check convention used by `subtract`/`divide`), both operands cast to `Field<BigDecimal>`, delegating to `DSL.coalesce(value, fallback)`.

## 2. Overall-score resolver

- [x] 2.1 Change `OverallScoreDefinitionResolver.avg(String fieldName)` to wrap the raw `avg(field)` `FnExpr` as `coalesce(avg(field), 0)`, reusing the existing `decimal(BigDecimal)` helper for the `0` literal.
- [x] 2.2 Update the class-level javadoc on `OverallScoreDefinitionResolver` (the `Mean`/`WeightedMean` composition examples) to show the `coalesce(avg(fN), 0)` shape instead of raw `avg(fN)`.

## 3. Unit tests

- [x] 3.1 Update the private `avg(String fieldName)` test helper in `OverallScoreDefinitionResolverTest` to build the coalesced `FnExpr` shape, so `resolvesMeanOverTwoFields`, `resolvesMeanOverSingleField`, and `resolvesWeightedMeanWithDuplicateTerm` assert against the new expression tree.
- [x] 3.2 Run `./gradlew test --tests "com.epam.aidial.evaluation.experimental.query.service.metricscore.OverallScoreDefinitionResolverTest"` and confirm all cases pass.

## 4. Functional test

- [x] 4.1 Add a functional test case in `MetricScoreComputationFunctionalTests` seeding a run where a `weighted_mean` definition references one metric present in the run's data and one metric absent from it; assert the computed `overall` result is a real, non-null number equal to treating the missing term as `0` (not the whole score nulled out).
- [x] 4.2 Run the corresponding nested functional test suite (`./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$MetricScoreComputationFunctionalTests"`, or the correct nested-class path for this file) and confirm it boots the application context and passes.

## 5. Docs and spec sync

- [x] 5.1 Sync the delta spec at `openspec/changes/fix-overall-score-null-metrics/specs/metric-score-statistics/spec.md` into `openspec/specs/metric-score-statistics/spec.md` (or run through `/opsx:archive`, which performs delta sync).
- [x] 5.2 Update the inline convention bullet in `AGENTS.md` ("a missing metric just nulls that term") to state the corrected behavior: the missing metric's average is coalesced to `0` via the DSL `coalesce` function, not left as a `NULL` that poisons the whole `overall` expression.

## 6. Verification

- [x] 6.1 Run `./gradlew spotlessApply` and `./gradlew checkstyleMain checkstyleTest`.
- [x] 6.2 Run `./gradlew clean build` (full unit + Testcontainers suite) and confirm it passes.
