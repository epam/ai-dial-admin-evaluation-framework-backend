## Why

Three bugs produce incorrect validation outcomes: optional FILE fields with empty string values generate false-positive warnings, required FILE fields with empty string values silently pass all checks, and the metric evaluation cancellation grace period (30 s) is too short — causing in-flight metric provider HTTP calls to be interrupted with `SocketException: Closed by interrupt` when evaluations take longer than the threshold.

## What Changes

- **`TestCaseValidationService.validateFileFields`**: treat blank string the same as `null` (skip ref-format validation for empty/blank values in optional FILE fields).
- **`TestCaseValidationService` required-field check**: extend the null guard to also detect blank strings specifically for FILE-typed schema fields, so required FILE fields with `""` produce the correct "field is missing" warning.
- **`TestCaseValidationService` data-vs-binding check**: extend the null guard for FILE-typed bound variables — build a `Map<String, SchemaFieldType>` from the schema and treat blank strings as absent for FILE fields only, so required template variables bound to a FILE data field with `""` also produce the correct "field is empty in data" warning.
- **`MetricEvaluationProperties`**: rename `cancellationGracePeriodMs` → `perResultTimeoutMs`; config key changes from `metric-evaluation.cancellation-grace-period-ms` to `metric-evaluation.per-result-timeout-ms`; default raised from 30 000 ms to 150 000 ms (aligned with HTTP read timeout); exposed via `METRIC_EVAL_PER_RESULT_TIMEOUT_MS`.
- **`MetricEvaluationContext`**: rename field to match.
- **`InProcessMetricEvaluationExecutor`**: use `perResultTimeoutMs` in `evaluateAndBuild()`.
- **`application.yml`**: replace removed property with new one.
- **`docs/configuration.md`**: document new env var; note removal of old one.

## Capabilities

### New Capabilities

_(none)_

### Modified Capabilities

- `file-ref-validation`: Behavior of FILE field validation in `TestCaseValidationService` changes — blank strings are now treated as "not provided" rather than invalid references; required FILE field blank-string gap is closed in both the schema required-field check and the data-vs-binding check.
- `metric-evaluation`: `cancellation-grace-period-ms` removed; replaced with `per-result-timeout-ms` (default 150 s, env var `METRIC_EVAL_PER_RESULT_TIMEOUT_MS`). Separates per-result request timeout from cancellation semantics, mirroring the Phase 1 `requestTimeoutMs` / `cancellationGracePeriodMs` split.

## Impact

- **`TestCaseValidationService`** (`service.domain`): three targeted checks updated.
- **`MetricEvaluationProperties`**, **`MetricEvaluationContext`**, **`InProcessMetricEvaluationExecutor`**, **`TestSuiteEvaluationJob`**: field/getter rename throughout metric evaluation pipeline.
- **`application.yml`**: one property replaced.
- **`docs/configuration.md`**: new env var documented; removed property noted.
- No DB schema changes, no API changes, no new dependencies.
- Existing functional tests for FILE validation will need to be reviewed; new unit test scenarios added for blank-string behavior.
