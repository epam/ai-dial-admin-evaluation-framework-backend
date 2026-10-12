## 1. Remove the same-suite guard

- [x] 1.1 In `query/service/metricscore/RunComparisonService`, delete `requireSameSuite` and its call in `compare`; drop any import left unused; reword the class javadoc and the `compare` `@param` ("two runs of one suite" / "two distinct runs of the same suite") to "two runs" (done: `./gradlew compileJava` passes, no same-suite wording left in the class)
- [x] 1.2 In `web/controller/RunComparisonController`: remove "Both runs must belong to the same suite" from the operation description, "The runs belong to different suites" from the 409 response description, and "of the same suite" from the `runIds` `@Parameter` description; reword the class javadoc ("two runs of one suite"); while on that description, add the missing `request_index` to the stated match key (`lower(test_case_name)` + `run_index` + `request_index` + `turn_index`) (done: `/v3/api-docs` text has no same-suite mention and states the four-part key)
- [x] 1.3 In `FilteredMetricScoreAggregator` class javadoc, reword "two runs of one suite over the same dataset" to "two runs over the same dataset" (done: javadoc updated)
- [x] 1.4 In `RunComparisonServiceTest`: replace `shouldRejectDifferentSuites` with a test asserting that runs of different suites proceed past validation to aggregation; rewrite `shouldApplySuiteGuardBeforeSnapshotGuard` to assert that a different-suite legacy run now fails with `SnapshotSuiteMissingException`; drop `OTHER_SUITE_ID` only if no longer used (done: `./gradlew test --tests "com.epam.aidial.evaluation.query.service.metricscore.RunComparisonServiceTest"` passes)

## 2. Functional coverage

- [x] 2.1 In `RunComparisonFunctionalTests`, parameterise the helpers so a second suite's run is seeded faithfully: `fixture()` takes the suite id (eval summaries must carry the foreign run's own `test_suite_id`), and `compare()` aggregates `test_case_metric_scores` for the run/computation pairs actually passed rather than the hardcoded `(runA, computationA)` / `(runB, computationB)` (done: existing tests still pass)
- [x] 2.2 Replace `shouldRejectRunsOfDifferentSuites` with "different suites sharing a dataset → 200": bind a second suite to suite A's dataset via `metaTestDataHelper.getDatasetId(suiteId)` + `createTestSuite(name, datasetId)` (PUBLIC dataset — a PRIVATE one is rejected by `tg_test_suites_private_binding_guard`), seed overlapping eval summaries under its own suite id and computation, assert matched counts and recomputed `scores` (done: test passes)
- [x] 2.3 Add "different suites, no shared names → 200 with `matchedRowCount` 0, empty `scores`, every row in `unmatchedEvalSummaryIds`", using the parameterised helpers from 2.1 (done: test passes)
- [x] 2.4 Run the full comparison suites (done: `./gradlew test --tests "*RunComparison*"` passes)

## 3. Wrap-up

- [x] 3.1 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` (done: no violations)
- [x] 3.2 Update `openspec/specs/README.md` per Spec Index Maintenance Policy: the `run-comparison-metric-scores` summary drops "of one suite" and states the four-part match key including `request_index` (done: index reflects current spec)
- [x] 3.3 At archive: sync the delta into `openspec/specs/run-comparison-metric-scores/spec.md`, flip both requirements' status to **Implemented**, and edit its `## Purpose` by hand ("two runs of one test suite" → "two test suite runs") since the delta sync does not touch Purpose (done: main spec has no same-suite wording and no different-suites 409)
