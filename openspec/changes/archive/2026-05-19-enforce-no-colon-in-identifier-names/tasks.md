## 1. Shared validation constants

- [x] 1.1 Add `IDENTIFIER_NAME_NO_COLON_PATTERN = "^[^:]*$"` and `IDENTIFIER_NAME_NO_COLON_MESSAGE` to `com.epam.aidial.evaluation.constants.ValidationConstants`, with a Javadoc cross-link to `EvalSummaryExportColumnConstants.COLUMN_SEPARATOR`.

## 2. DTO annotations

- [x] 2.1 Add `@Pattern(regexp = ValidationConstants.IDENTIFIER_NAME_NO_COLON_PATTERN, message = ValidationConstants.IDENTIFIER_NAME_NO_COLON_MESSAGE)` to `FieldDefinitionDto.name`.
- [x] 2.2 Add same `@Pattern` to `ResponseColumnDefinitionDto.name`.
- [x] 2.3 Add same `@Pattern` to `TestSuiteMetricDefinitionRequestDto.name`.

## 3. Unit tests

- [x] 3.1 Add validator-driven unit tests covering each of the three DTOs: valid name → 0 violations, colon-bearing → 1 colon-pattern violation, family-prefix → 1 colon-pattern violation, blank/null → only NotBlank violation. (Added to existing `ResponseColumnDefinitionDtoValidationTest`; new `FieldDefinitionDtoValidationTest` and `TestSuiteMetricDefinitionRequestDtoValidationTest`.)
- [x] 3.2 Run unit tests and confirm green.

## 4. Functional tests

- [x] 4.1 Add functional test methods covering POST `/api/v1/test-suites` with colon-bearing `testCaseSchema[0].name` → 400.
- [x] 4.2 Add functional test methods covering POST `/api/v1/test-suites` with colon-bearing `responseColumns[0].name` → 400.
- [x] 4.3 Add functional test methods covering PUT `/api/v1/test-suites/{id}` rejecting colon-bearing updates → 400.
- [x] 4.4 Add functional test methods covering POST `/api/v1/test-suites/{suiteId}/metric-definitions` with colon-bearing `name` → 400.
- [x] 4.5 Add functional test methods covering PUT `/api/v1/test-suites/{suiteId}/metric-definitions/{id}` with colon-bearing `name` → 400.
- [x] 4.6 Run the relevant `PostgresFunctionalTests` nested classes and confirm green.

## 5. Final verification

- [x] 5.1 Run `./gradlew build` and confirm BUILD SUCCESSFUL (tests + checkstyle).
