## 1. Database

- [x] 1.1 Add `src/main/resources/db/migration/meta/POSTGRES/V1.25__AddOverallScoreThresholdToTestSuites.sql`: `ALTER TABLE test_suites ADD COLUMN overall_score_threshold DOUBLE PRECISION;` (nullable, no default)
- [x] 1.2 Run `./gradlew generateJooq` and commit the regenerated sources under `src/main/java-generated/` (adds `TEST_SUITES.OVERALL_SCORE_THRESHOLD`)

## 2. Data layer

- [x] 2.1 Add `private Double overallScoreThreshold;` to `TestSuite` (`src/main/java/com/epam/aidial/evaluation/data/db/model/TestSuite.java`), after `overallScore`
- [x] 2.2 Add `.overallScoreThreshold(r.getOverallScoreThreshold())` to `TestSuiteRecordMapper.map(TestSuitesRecord)`, after the `overallScore` line
- [x] 2.3 Add `.set(TEST_SUITES.OVERALL_SCORE_THRESHOLD, testSuite.getOverallScoreThreshold())` to each of the three `.set(TEST_SUITES.OVERALL_SCORE, ...)` call sites in `PostgresTestSuiteRepository` (create, update, clone-create)

## 3. Mapper

- [x] 3.1 In `TestSuiteMapper.toDto`: add `.overallScoreThreshold(entity.getOverallScoreThreshold())`
- [x] 3.2 In `TestSuiteMapper.toEntity`: add `.overallScoreThreshold(dto.getOverallScoreThreshold())`
- [x] 3.3 In `TestSuiteMapper.update`: add `entity.setOverallScoreThreshold(dto.getOverallScoreThreshold());`
- [x] 3.4 In `TestSuiteMapper.toCloneEntity`: add `.overallScoreThreshold(source.getOverallScoreThreshold())`
- [x] 3.5 In `TestSuiteMapper.toRequestDto`: add `.overallScoreThreshold(entity.getOverallScoreThreshold())`

## 4. DTOs

- [x] 4.1 Add `overallScoreThreshold : Double` field with `@Schema` (description + example `0.8`) to `TestSuiteRequestDto`, after `overallScore`
- [x] 4.2 Add `overallScoreThreshold : Double` field with `@Schema` description to `TestSuiteResponseDto`, after `overallScore`
- [x] 4.3 Add `MIN_OVERALL_SCORE_THRESHOLD` (`"0.0"`), `MAX_OVERALL_SCORE_THRESHOLD` (`"1.0"`), and `OVERALL_SCORE_THRESHOLD_RANGE_MESSAGE` constants to `ValidationConstants`
- [x] 4.4 Add `@DecimalMin(ValidationConstants.MIN_OVERALL_SCORE_THRESHOLD)` / `@DecimalMax(ValidationConstants.MAX_OVERALL_SCORE_THRESHOLD)` to `TestSuiteRequestDto.overallScoreThreshold` (inclusive 0.0–1.0 range; out-of-range → HTTP 400 `VALIDATION_ERROR`)

## 5. Tests

- [x] 5.1 Unit test: extend `TestSuiteMapperCloneTest` (the existing dedicated `TestSuiteMapper` unit test, covering `toCloneEntity`/`toRequestDto`) to assert `overallScoreThreshold` round-trips, including a `null` case
- [x] 5.2 Functional test: extend `TestSuiteFunctionalTests` to assert `POST`/`PUT`/`GET` preserve `overallScoreThreshold` (set and omitted/null cases), and that `isValid`/`validationWarnings` are unaffected
- [x] 5.3 Functional test: assert `overallScoreThreshold` outside `[0.0, 1.0]` is rejected with HTTP 400 on create, and that the boundary values `0.0`/`1.0` are accepted — passed

## 6. Docs

- [x] 6.1 Add `overall_score_threshold` row to the `test_suites` table in `docs/database-schema.md` (near `overall_score`), plus a `V1.25` migration changelog entry
- [x] 6.2 Update `openspec/specs/test-suites/spec.md` by syncing the delta spec from this change (`openspec/changes/add-overall-score-threshold/specs/test-suites/spec.md`) — add the new requirement and its Implementation Notes bullet

## 7. Verification

- [x] 7.1 Run `./gradlew spotlessApply`
- [x] 7.2 Run `./gradlew checkstyleMain checkstyleTest`
- [x] 7.3 Run the new/updated unit test: `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.mapper.TestSuiteMapperCloneTest"` — passed
- [x] 7.4 Run the updated functional test suite covering test suites: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestSuiteTests"` — passed
- [x] 7.5 Verify request/response round-trip: covered by the functional tests in 7.4, which drive the real `TestSuiteController` endpoints (`POST`/`PUT`/`GET /api/v1/test-suites`) over HTTP with a real Postgres (Testcontainers) and assert `overallScoreThreshold` serializes/deserializes correctly through the OpenAPI-annotated `TestSuiteRequestDto`/`TestSuiteResponseDto` — the same code path Swagger UI would exercise
