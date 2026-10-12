## 1. Guard and unit tests

- [x] 1.1 In `query/service/metricscore/RunComparisonService`, add one private guard called after both `requireSnapshot` calls and before computation resolution: pass when `testSuiteId`s are equal, or when both `suiteSnapshot.datasetRef` are non-null with equal `id`; otherwise throw `InvalidOperationException` stating the runs share neither a test suite nor a dataset (done: `./gradlew compileJava` passes)
- [x] 1.2 In `web/controller/RunComparisonController`, add "the runs share neither a test suite nor a dataset" to the 409 response description, and one sentence to the operation description stating runs must share a suite or a snapshot dataset (done: no line over 120 chars, `./gradlew :checkstyleMain` passes)
- [x] 1.3 In `RunComparisonServiceTest`, give the snapshot helper an optional dataset id and cover each branch: different suites + same dataset → aggregates (update `shouldAcceptDifferentSuites`); different suites + different datasets → 409; different suites + one `datasetRef` null → 409; same suite + different datasets → aggregates; keep `shouldFailDifferentSuiteLegacyRunOnSnapshotGuard` asserting 422 (done: `./gradlew :test --tests "com.epam.aidial.evaluation.query.service.metricscore.RunComparisonServiceTest"` passes)

## 2. Functional coverage

- [x] 2.1 In `RunComparisonFunctionalTests`, replace `shouldCompareRunsOfDifferentSuitesSharingNoNames` with "different suites over different datasets → 409 `INVALID_OPERATION`" (second suite via `createTestSuite(name)`, which creates its own dataset; assert via `compareRaw`); keep `shouldCompareRunsOfDifferentSuitesSharingDataset` passing unchanged (done: `./gradlew :test --tests "*RunComparison*"` passes)

## 3. Wrap-up

- [x] 3.1 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` (done: no violations)
- [x] 3.2 Update `openspec/specs/README.md`: `run-comparison-metric-scores` summary says "two runs of the same suite or of suites sharing a dataset" (done: index reflects current spec)
- [x] 3.3 At archive: sync the delta via `/opsx:sync`, flip both requirements to **Implemented**, then by hand in the main spec (a) rename scenario "Runs of different suites over unrelated datasets are compared" to "... are rejected" (the validator forbids renaming inside a MODIFIED block) and (b) edit `## Purpose` "of the same or of different suites" → "of the same suite or of suites sharing a dataset" (done: main spec consistent, `openspec validate --specs` shows no new failures)
