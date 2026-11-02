## 1. Validator core

- [x] 1.1 Add `UNSUPPORTED_SCORE_FUNCTION` to `ValidationWarningCode` (evaluation-runner-core); done: `./gradlew :evaluation-runner-core:compileJava` passes
- [x] 1.2 Add `findNamesByTestSuiteId(UUID)` to `TestSuiteMetricDefinitionRepository` + Postgres impl (`SELECT name`, no joins, returns `HashSet`) and `TestSuiteMetricDefinitionService.findNamesByTestSuiteId`; done: compiles, covered by functional tests in 2.3/3.3
- [x] 1.3 Implement `ScoreReferenceValidator` (`service.domain`) per design D1; done: `ScoreReferenceValidatorTest` passes — weighted (both roots, case-sensitive, duplicates, null name), roc_auc metric/data/metricInfo args, `metric::X` without output, unsupported shapes (other fn, 2 selects, non-convertible expression), unreadable column JSON, legacy custom_function in testCaseOverallScore ignored, mean/null → none

## 2. Full suite validation and clone

- [x] 2.1 Add `SuiteValidationService.withScoreReferences(ValidationResult, TestSuite, Set<String>)`, wrap create/update and the entity overload with it (names via TSMD service; empty on create / when id null) per design D2; done: updated `SuiteValidationServiceTest` passes
- [x] 2.2 `TestSuiteCloneService`: pre-insert validation passes the source suite's metric names (design D5); done: updated `TestSuiteCloneServiceTest` passes
- [x] 2.3 Functional tests: create/update suite with unresolved weighted + roc_auc references and with an unsupported custom function → expected warnings, `valid=false`; clone of a suite whose references resolve → no score warnings in response and GET; done: tests pass (boots app context)

## 3. Partial refresh on metric mutations

- [x] 3.1 Implement `SuiteScoreWarningsRefresher` per design D3; done: unit test passes — non-score warnings preserved, `valid` recomputed, no write when unchanged, prefix does not match e.g. `$.overallScoreThreshold`
- [x] 3.2 Call refresher from TSMD create/update/delete (delete loads the suite; refresh outside the duplicate-name `try`) per design D4; done: updated `TestSuiteMetricDefinitionServiceTest` passes
- [x] 3.3 Functional tests for the `test-suite-metric-definitions` scenarios (create resolves, delete/rename adds, unrelated warnings kept, unaffected mutation leaves `updatedAt`); done: tests pass

## 4. Adapt existing tests

- [x] 4.1 `TestSuiteRunFunctionalTests` (and any other functional test that creates a scored suite then inserts TSMDs via `MetaTestDataHelper`, e.g. ROC AUC / weighted-mean cases ~L1280–1560): refresh score warnings after raw TSMD inserts (`MetaTestDataHelper.refreshSuiteScoreWarnings`) so the suite is valid at run creation; done: those tests pass
- [x] 4.2 Tests using non-roc_auc custom functions (`avg(...)`, `mixedFieldMeanCustomFunction`, ~L1598/L1822): removed — that suite shape is now invalid and cannot run; roc_auc tests keep the no-per-row-score coverage; check `MetricScoreComputationFunctionalTests` (executor-level, likely unaffected); done: tests pass

## 5. Endpoint `$ref` cycle fix (separate commit)

- [x] 5.1 `git checkout stash@{0} -- src/main/java/com/epam/aidial/evaluation/service/domain/EndpointSchemaRefResolver.java` and `git checkout 'stash@{0}^3' -- src/test/java/com/epam/aidial/evaluation/service/domain/EndpointSchemaRefResolverTest.java`; done: `EndpointSchemaRefResolverTest` passes

## 6. Docs and wrap-up

- [x] 6.1 One-sentence `@Schema` description on `TestSuiteResponseDto.validationWarnings`; update `api-v1-test-suites-id-GET-response-200-full.json` with one weighted `UNRESOLVED_REFERENCE` warning and `valid=false`; done: JSON valid, `/v3/api-docs` loads in an existing OpenAPI functional test
- [x] 6.2 Docs: short "Reference validation" paragraph in `docs/patterns/overall-score-definition.md`; add score references to the `isValid` component list in `docs/patterns/suite-validity-and-run-guards.md`; done: docs updated
- [x] 6.3 `./gradlew spotlessApply checkstyleMain checkstyleTest` and full `./gradlew test`; done: green
