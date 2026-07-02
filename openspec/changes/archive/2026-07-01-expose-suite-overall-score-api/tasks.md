## 1. Expose `overallScore` on the suite DTOs

- [x] 1.1 Add `private Map<String, Object> overallScore;` to `service/domain/dto/TestSuiteRequestDto.java` with a `@Schema` description + example (an aggregate `StructuredQuery` selecting `avg(field metric::<name>::score)` as `value`, run-scoped by `:runId`/`:computationId`; use the double-colon `::` separator). (done: field present with OpenAPI example)
- [x] 1.2 Add `private Map<String, Object> overallScore;` to `service/domain/dto/TestSuiteResponseDto.java`. (done: field present, exposed as JSON object)

## 2. Wire conversion + mapping

- [x] 2.1 Add `public String mapOverallScore(Map<String, Object> value)` to `service/domain/mapper/JsonbMapper.java` (delegate to `write(value, "overallScore")`), and update the `mapOverallScore(String)` javadoc to drop the "not yet exposed via the API" note. (done: write method present, javadoc current)
- [x] 2.2 Map `overallScore` in `service/domain/mapper/TestSuiteMapper.java`: `toEntity` (`.overallScore(jsonbMapper.mapOverallScore(dto.getOverallScore()))`), `update` (`entity.setOverallScore(...)`), `toDto` (`.overallScore(jsonbMapper.mapOverallScore(entity.getOverallScore()))`), and `toRequestDto` for round-trip symmetry. (done: all four methods map the field; clone path already preserves it)

## 3. Functional tests

- [x] 3.1 In `functional/tests/TestSuiteFunctionalTests.java`, add `shouldPersistAndReturnOverallScoreOnUpdate()`: create a suite, `PUT` with an `overallScore` object (referencing one metric via `metric::<name>::score`) using the existing `If-Match` pattern, assert HTTP 200 + response `overallScore` equals the submitted map, then `GET` (or read via repository) and assert it persisted. (done: test added and passing)
- [x] 3.2 In `functional/tests/MetricScoreComputationFunctionalTests.java`, add `computesCustomOverallForOneOfTwoMetrics()`: reuse `seedTwoMetricRun(...)` (Relevancy avg 0.5, Accuracy avg 0.7), define a shared custom overall constant `avg(field "metric::Relevancy::score")` run-scoped by `:runId`/`:computationId`, run `executor.execute(context(suiteId, runId, computationId, CUSTOM_OVERALL))`, and assert the `overall`/`overall` result `== 0.5` (not 0.7, not the 0.6 mean). Replace the now-stale comment at lines ~86–90 that claims a custom authored overall "cannot translate end-to-end". (done: test added and passing; stale comment removed)

## 4. Verify

- [x] 4.1 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` (global gradle.properties already points to JDK 25 — no override needed). (done: formatting + checkstyle clean)
- [x] 4.2 Run the touched suites: `--tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests$TestSuiteTests"` and `--tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests$MetricScoreComputationTests"` (both boot the app context on Testcontainers Postgres). (done: both green)

## 5. Docs & spec sync

- [x] 5.1 Add a note to `docs/database-schema.md` that `test_suites.overall_score` is now settable/readable via the suite API (`overallScore`). (done: doc updated)
- [x] 5.2 On archive, sync the delta specs into `openspec/specs/test-suites/spec.md` and `openspec/specs/metric-score-statistics/spec.md`. No `openspec/specs/README.md` change (no new spec folder, no status change, summaries still accurate); no `config.yaml`/`AGENTS.md` change (feature follows existing DTO/mapper patterns). (done: main specs reflect the change)
