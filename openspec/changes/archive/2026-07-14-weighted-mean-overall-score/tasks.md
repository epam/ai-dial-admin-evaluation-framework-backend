## Phase 1 — Arithmetic function catalog (shipped, unaffected by Phase 2 below)

## 1. Arithmetic function catalog

- [x] 1.1 Add `add` (n-ary, ≥1 arg, left-fold `.add(...)`) and `multiply` (n-ary, ≥1 arg, left-fold
      `.mul(...)`) `QueryFunction` beans to `BuiltInQueryFunctions.java`, casting each resolved arg via
      `ctx.toField(arg).cast(BigDecimal.class)`, following the existing `.of(name, (fn, ctx) -> ...)`
      pattern used by `abs`/`sum`/`avg`.
- [x] 1.2 Add `subtract` and `divide` `QueryFunction` beans to `BuiltInQueryFunctions.java` (binary
      only — exactly 2 args, `a.sub(b)` / `a.div(b)`), throwing `ValidationException` for any other arg
      count, matching the arity-check style of `width_bucket`/`percentile_cont`.
- [x] 1.3 Restore `MeanFunction.java` as its own `@Component` in
      `experimental.query.service.translate.function` (same pattern as `RocAucFunction`): n-ary args
      (`ctx.args(fn)`, ≥1, `ValidationException` on zero), each cast to `BigDecimal` via
      `ctx.toField(arg).cast(BigDecimal.class)`, summed and divided by `DSL.val(BigDecimal.valueOf(args.size()))`.
      Do not reintroduce the old `ArrayExpr`/`ctx.substitute` shape — `substitute` no longer exists on
      `FunctionContext`.
- [x] 1.4 Update `QueryFunctionTestSupport.registry(...)` to include `builtIns.addFunction()`,
      `builtIns.multiplyFunction()`, `builtIns.subtractFunction()`, `builtIns.divideFunction()`, and
      `new MeanFunction()`.

## 2. Unit tests

- [x] 2.1 Add unit tests for `add` and `multiply`: correct combined value for 1, 2, and 3+ arguments.
- [x] 2.2 Add unit tests for `subtract` and `divide`: correct value for exactly 2 arguments; rejected
      (`ValidationException`) for 0, 1, or 3+ arguments.
- [x] 2.3 Add unit tests for `mean`: correct average for 1 and n (≥2) arguments; rejected
      (`ValidationException`) for 0 arguments.
- [x] 2.4 Run `./gradlew test --tests "com.epam.aidial.evaluation.experimental.query.service.translate.*"`
      and confirm all new and existing tests pass.

## 3. Functional verification (end-to-end)

- [x] 3.1 Add `shouldComputeCustomOverallWeightedMeanOfSpecificMetrics` to
      `TestSuiteRunFunctionalTests`, patterned on
      `shouldComputeCustomOverallRocAucFromDatasetLabelAndMetricProbability`: a suite with two numeric
      metric fields and known per-test-case values, `overallScore` JSON built as
      `divide(add(multiply(w1, avg(metric::A::f1)), multiply(w2, avg(metric::B::f2))), add(w1, w2))`;
      assert the persisted `overall` `MetricScoreResult` equals the hand-computed
      `Σ(wᵢ·avgᵢ)/Σwᵢ`. Include at least one metric referenced with a repeated/duplicate weighted
      term to confirm duplicate metric terms combine correctly (per design).
- [x] 3.2 Add `shouldComputeCustomOverallMeanOfAllMetricOutput` (same fixture or a sibling test)
      asserting `mean(avg(metric::A::f1), avg(metric::B::f2))` equals the plain average of the two
      per-metric averages.
- [x] 3.3 Run
      `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestSuiteRunTests"`
      and confirm both new functional tests pass (app boots, run completes, computed values match).
- [x] 3.4 Add `shouldNormalizeUnroundedWeightsInWeightedMeanOverallScore`: unnormalized integer weights
      (`Σw = 3`, not pre-normalized to sum to 1) engineered so the `divide` is a genuine repeating
      decimal (`1/3`); assert the computed `overall` is `≈ 1/3`, proving Postgres's `numeric` division
      (not Java `BigDecimal.divide` without a `MathContext`, which throws
      `ArithmeticException: Non-terminating decimal expansion` for this exact case) resolves it without
      error and without the caller having to pre-normalize weights to decimal literals.

## 4. Docs

- [x] 4.1 Fix `AGENTS.md`'s "Query DSL function catalog is registry-driven" inline convention bullet
      and the Key Packages Reference row for `.translate.function` to accurately list
      `add`/`subtract`/`multiply`/`divide`/`MeanFunction` alongside the existing built-ins (the current
      `MeanFunction` reference is stale — points at a deleted class).
- [x] 4.2 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` and confirm a clean result.

## Phase 2 — Typed `OverallScoreDefinition` model

## 5. Typed model

- [x] 5.1 Add `service.domain.dto.overallscore` package: `OverallScoreDefinition` (sealed interface,
      `@JsonTypeInfo`/`@JsonSubTypes` discriminated by `type`: `mean`/`weighted_mean`/`custom_function`),
      `Mean` (no-arg record), `WeightedMetric` (`metricName`, `outputField`, `weight` — `@NotBlank`/
      `@NotNull`), `WeightedMean` (`@NotEmpty @Valid List<WeightedMetric> weights`), `CustomFunction`
      (`@NotNull Map<String, Object> expression`).
- [x] 5.2 Change `TestSuiteRequestDto`/`TestSuiteResponseDto`/`SuiteSnapshotDto`.`overallScore` from
      `Map<String, Object>` to `OverallScoreDefinition`; add `@Valid` on the request DTO field; update the
      request DTO's `@Schema` description/`example` to the typed shapes (e.g. `{"type":"mean"}`).
- [x] 5.3 Update `JsonbMapper.mapOverallScore(...)` overloads to read/write `OverallScoreDefinition` via
      the mapper's existing generic `read(json, Class<T>, label)`/`write(Object, label)` helpers.
- [x] 5.4 Change `MetricScoreComputationContext.overallExpression` (`String`) to
      `overallScoreDefinition` (`OverallScoreDefinition`); simplify
      `TestSuiteEvaluationJob.resolveOverallExpression` to a direct snapshot field read, dropping the
      `objectMapper.writeValueAsString` re-stringify round trip.

## 6. Resolver

- [x] 6.1 Add `experimental.query.service.metricscore.OverallScoreDefinitionResolver` (`@Component`):
      `StructuredQuery resolve(OverallScoreDefinition definition, List<String> metricFieldNames)`.
      `Mean` → `divide(add(avg(field) for each name in metricFieldNames), n)`. `WeightedMean(weights)` →
      `divide(add(multiply(w_i, avg(metric::name_i::field_i)) for each weight), add(w_i for each
      weight))`, built directly from the stored list (no cross-check against `metricFieldNames`).
      `CustomFunction(expression)` → `objectMapper.convertValue(expression, StructuredQuery.class)`.
      Added a public `BuiltInMetricStatistics.aggregateSelecting(Expr)` to reuse the existing
      run/computation-scoped filter construction rather than duplicating it.
- [x] 6.2 Rewrite `MetricScoreComputationExecutor.computeOverall`'s non-default branch to call the
      resolver instead of `parseExpression`; removed `parseExpression` (no longer used). Kept the
      null-definition single-metric-only default path unchanged. Main source set compiles clean.
- [x] 6.3 Removed the shipped `mean` DSL function entirely (`MeanFunction.java` deleted; its
      `QueryFunctionTestSupport` registration and its dedicated unit tests in
      `EvalSummaryQueryRenderTest` removed) once `OverallScoreDefinitionResolver` existed to build the
      equivalent `divide(add(...), n)` composition server-side — the `mean` catalog entry had no other
      caller (never reachable through `overallScore`'s new typed model, and no other DSL consumer used
      it). `add`/`subtract`/`multiply`/`divide` remain in the catalog, still used by the resolver and
      still generically available. Updated `AGENTS.md`, and this change's `design.md`/
      `specs/structured-query-model/spec.md`/`specs/metric-score-statistics/spec.md` accordingly.
      Reran the full test suite — all green.

## 6b. Additional callers found during compilation (not scoped in the original task list)

- [x] 6b.1 Fix `MetricScoreComputationExecutorTest` (unit test): construct the executor with a real
      `OverallScoreDefinitionResolver` instead of a raw `ObjectMapper`; `context(...)` now takes an
      `OverallScoreDefinition`; the custom-overall JSON fixture is wrapped in `CustomFunction`.
- [x] 6b.2 Fix `MetricScoreComputationFunctionalTests` (functional test): `context(...)` now takes an
      `OverallScoreDefinition`; added a `customFunction(String json)` helper wrapping the existing
      run-scoped JSON fixtures (`CUSTOM_OVERALL_RELEVANCY`/`CUSTOM_OVERALL_ROC_AUC`) in `CustomFunction`.
- [x] 6b.3 Fix `TestSuiteFunctionalTests.shouldPersistAndReturnOverallScoreOnUpdate`: wraps the existing
      raw-expression `Map` in `new CustomFunction(...)` for both the request and the response assertions.

## 7. Unit tests

- [x] 7.1 JSON round-trip tests for `OverallScoreDefinition`'s three variants (wire shapes:
      `{"type":"mean"}`, `{"type":"weighted_mean","weights":[...]}`,
      `{"type":"custom_function","expression":{...}}`) — `OverallScoreDefinitionSerializationTest`.
      Also added `WeightedMeanValidationTest` for the Bean Validation constraints (empty weights list,
      blank metric name/field, null weight, null `CustomFunction` expression).
- [x] 7.2 Unit tests for `OverallScoreDefinitionResolver`: `Mean` over N field names renders
      `divide(add(avg(...), ...), n)`; `WeightedMean` renders the expected nested
      `divide`/`add`/`multiply` tree (including a duplicate-metric-term case); `CustomFunction`
      round-trips a raw expression Map and logs+returns null on unparseable input. Found
      `objectMapper.convertValue` throws `tools.jackson.core.JacksonException` (specifically
      `MismatchedInputException`) on malformed input, not `IllegalArgumentException` — the resolver's
      catch clause was corrected accordingly. (Tests updated again after 6.3 removed the `mean` DSL
      function in favor of this `divide`/`add` composition.)
- [x] 7.3 Ran `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.dto.overallscore.*"
      --tests "com.epam.aidial.evaluation.experimental.query.service.metricscore.*"
      --tests "com.epam.aidial.evaluation.service.domain.job.MetricScoreComputationExecutorTest"` —
      all pass.

## 8. Functional tests (rewrite existing DSL-JSON tests to use the typed model)

- [x] 8.1 Rewrote `shouldComputeCustomOverallRocAucFromDatasetLabelAndMetricProbability`'s suite setup to
      set `overallScore = new CustomFunction(Map.of(...))` (same `roc_auc(...)` expression) built as a
      nested Java `Map`/`List` literal instead of a JSON string; renamed the now-generic suite helper
      `createTestSuiteWithRocAucOverallScore` → `createTestSuiteWithOverallScore(name,
      OverallScoreDefinition)`. Same expected AUC = 0.75.
- [x] 8.2 Rewrote `shouldComputeCustomOverallWeightedMeanOfSpecificMetrics` to set
      `overallScore = new WeightedMean(List.of(new WeightedMetric("MetricA","score",new
      BigDecimal("0.1")), new WeightedMetric("MetricA","score",new BigDecimal("0.1")), new
      WeightedMetric("MetricB","score",new BigDecimal("0.8"))))`; deleted the now-unused
      `weightedMetricTerm`/`decimalLiteral`/`metricAverage` JSON-string-builder helpers. Same expected
      0.68.
- [x] 8.3 Rewrote `shouldComputeCustomOverallMeanOfAllMetricOutput` to set
      `overallScore = new Mean()`. Same expected 0.5.
- [x] 8.4 Rewrote `shouldNormalizeUnroundedWeightsInWeightedMeanOverallScore` to set `overallScore` via
      `WeightedMean` with integer-valued `BigDecimal` weights (`1`, `1`, `1`). Same expected `1/3`.
- [x] 8.5 Ran
      `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestSuiteRunTests"
      --tests "...\$TestSuiteTests" --tests "...\$MetricScoreComputationTests"` — 37+41+4 = 82 tests,
      0 failures.

## 9. Wrap-up

- [x] 9.1 Grepped for any remaining caller of the old `Map<String, Object> overallScore` shape (DTOs,
      mapper, context, tests) — none found outside the `CustomFunction`'s inner expression `Map` (which
      is the correct, intended shape). Ran `./gradlew test` (full suite): 255 test result files, all
      `failures="0"`.
- [x] 9.2 Ran `./gradlew spotlessApply checkstyleMain checkstyleTest` — clean.
