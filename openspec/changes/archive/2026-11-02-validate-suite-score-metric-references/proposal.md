## Why

A suite's `overallScore` / `testCaseOverallScore` can name metrics the suite does not have (typo, rename, delete). Today that is silent: the missing term is coalesced to `0` at computation time and the score is quietly wrong. Users need to see broken score references as suite validation warnings.

## What Changes

- Suite validation reports score metric references that do not match a suite metric definition name (exact, case-sensitive; disabled/invalid definitions count):
  - `weighted_mean`: every `weights[i].metricName` → `UNRESOLVED_REFERENCE` at `$.<field>.weights[i].metricName`.
  - `custom_function`: the only supported shape is a single `roc_auc(field, field)` select; each `metric::<tsmd>::<output>` argument is checked → `UNRESOLVED_REFERENCE` at `$.overallScore.expression.select[0].expr.args[i].name`. `data::` / `metricInfo::` arguments and the filter are ignored.
  - Any other `custom_function` shape → new warning code `UNSUPPORTED_SCORE_FUNCTION` at `$.overallScore.expression`. No HTTP 400.
- Metric definition create / update / delete refreshes only the suite's score-reference warnings, synchronously in the same transaction (no full suite revalidation).
- Clone validates score references against the source suite's metric names (the clone receives exactly those).
- Bug fix (separate commit): `EndpointSchemaRefResolver` detects circular `$ref` chains (HTTP 400) instead of recursing unboundedly.
- Not in scope: concurrency coordination, output-field validation, query executability, new write-time rejections.

Behavioural note: a suite with a broken score reference or an unsupported custom function becomes `isValid = false` and is therefore blocked from running by the existing config-invalid run guard. Accepted.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `test-suites`: `overallScore` / `testCaseOverallScore` now contribute to suite validity via score-reference warnings.
- `metric-score-statistics`: wording that score references are not validated against configured metrics is replaced by a pointer to score-reference warnings.
- `test-suite-metric-definitions`: create / update / delete refresh the owning suite's score-reference warnings.

## Impact

- **API**: new `ValidationWarningCode.UNSUPPORTED_SCORE_FUNCTION` (FE must handle it). No request/response shape change. One OpenAPI example updated (suite GET-by-id full).
- **Code**: new `ScoreReferenceValidator` and `SuiteScoreWarningsRefresher` (service.domain); `SuiteValidationService`, `TestSuiteMetricDefinitionService`, `TestSuiteCloneService`, `EndpointSchemaRefResolver`; new repository read `findNamesByTestSuiteId` on `TestSuiteMetricDefinitionRepository`.
- **DB / config**: none.
- **Tests**: unit tests for the validator, refresher and resolver fix; functional tests for suite create/update, metric create/update/delete, clone. Existing run functional tests that insert TSMDs via raw SQL after suite creation, or use non-roc_auc custom functions, must be adapted.
