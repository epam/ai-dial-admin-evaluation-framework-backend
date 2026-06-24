## 1. Core constant and validation

- [x] 1.1 In `constants/EvalSummaryExportColumnConstants.java`, change `COLUMN_SEPARATOR` from `":"` to `"::"` and rewrite the class Javadoc to describe the `<family>::<name>` convention and the new rationale (single `:` and `_` remain usable in names; only `::` is reserved).
- [x] 1.2 In `constants/ValidationConstants.java`, re-document `IDENTIFIER_NAME_NO_COLON_PATTERN` (`^[^:]*$`) / `_MESSAGE` as the **filter operator separator** constraint (used by field names), and add `NAME_NO_TWO_COLON_PATTERN` (`(?!.*::).*`) + `NAME_NO_TWO_COLON_MESSAGE` ("Name must not contain '::' (reserved as CSV export column separator)") for the export-separator constraint.
- [x] 1.3 Point `service/domain/dto/ResponseColumnDefinitionDto.java` and `service/domain/dto/TestSuiteMetricDefinitionRequestDto.java` `@Pattern` at the `NAME_NO_TWO_COLON_*` constant/message.
- [x] 1.4 Confirm `service/domain/dto/FieldDefinitionDto.java` still references the no-colon (filter) constant — no behavior change, only the constant's documented rationale.

## 2. API examples and schema annotations

- [x] 2.1 Update the `@Schema` example in `service/domain/dto/analytics/EvalSummaryExportRequestDto.java` to the `::` form (`data::prompt`, `metric::Accuracy::score`, `metricInfo::Accuracy::score`, `metricError::Accuracy`).
- [x] 2.2 Update OpenAPI example JSON files to the `::` form: `openapi/examples/api-v1-analytics-eval-summaries-export.csv-POST-request-subset.json` and `openapi/examples/api-v1-analytics-eval-summaries-export-preview-GET-response-200-minimal.json`.

## 3. Tests

- [x] 3.1 Update column-name literals (`:` → `::`) in `EvalSummaryExportColumnPlannerTest` and `EvalSummaryExportColumnSelectorTest`.
- [x] 3.2 Update column-name literals in `functional/tests/EvalSummaryExportFunctionalTests` (including the concatenated header assertion at ~line 605).
- [x] 3.3 Update `TestSuiteMetricDefinitionRequestDtoValidationTest`, `ResponseColumnDefinitionDtoValidationTest`, and the corresponding functional scenarios (`TestSuiteFunctionalTests`, `TestSuiteMetricDefinitionFunctionalTests`): reject `"...::..."` with the `'::'` message; add a case asserting a single-colon name now passes.
- [x] 3.4 Confirm `FieldDefinitionDtoValidationTest` is unchanged (single `:` still rejected for field names, `':'` message retained).

## 4. Docs and specs sync

- [x] 4.1 Update the inline manifest description in `openspec/specs/README.md` (the `data:<field> … metric:<metric>:<field> …` line) to the `::` form.
- [x] 4.2 Grep `docs/` for `metric:`/`data:`/`metricInfo:`/`metricError:` column-header references — none found; nothing to update.
- [ ] 4.3 Sync the delta specs into the main specs (eval-summary-export, response-columns, test-suite-metric-definitions) — performed during the archive/sync step.

## 5. Verification

- [x] 5.1 `./gradlew spotlessApply` then `./gradlew compileJava compileTestJava` — clean.
- [x] 5.2 Run unit tests: `EvalSummaryExportColumnPlannerTest`, `EvalSummaryExportColumnSelectorTest`, and the three `*ValidationTest` classes — pass.
- [x] 5.3 Run the functional suites `PostgresFunctionalTests$EvalSummaryExportTests`, `$TestSuiteMetricDefinitionTests`, `$TestSuiteTests` — pass (97 tests).
- [x] 5.4 Manual sanity covered by functional assertions: headers render as `data::prompt`, `metric::Accuracy::score`, `metricError::Accuracy`; `Acc::uracy` rejected (400), single-colon names accepted.
- [x] 5.5 `openspec validate eval-export-column-separator --strict` — passes.
