## Why

When a metric evaluation encounters a transport failure (HTTP 500, timeout, all retries exhausted), the `metricValues` JSONB uses a synthetic `{"error": null}` key instead of the metric's actual output field names (e.g., `{"score": null}`). This creates an inconsistent API shape — clients must check *which keys exist* to determine success vs failure, rather than simply checking whether the value is `null`. Additionally, the output schema of metric declarations is not validated during TSMD creation, which could lead to runtime failures when the normalized format requires extracting field names from the schema.

## What Changes

- **Normalize transport-failure metricValues shape**: Replace `{"tsmdName": {"error": null}}` with `{"tsmdName": {"field1": null, "field2": null, ...}}` using output field names extracted from the metric's `output_schema.properties` keys. Error details remain in `metricInfos` (unchanged). The `metricInfos` shape for transport failures changes from `{"tsmdName": {"error": "message"}}` to `{"tsmdName": {"field1": {"error": "message"}, "field2": {"error": "message"}, ...}}` — one error entry per output field, matching the field-level error structure.
- **Add output schema validation to TSMD soft validation**: Extend `MetricDefinitionValidationService` to validate that `output_schema` has a non-empty `properties` object. TSMDs with invalid output schemas will be marked `is_valid = false` with a new validation warning, preventing them from being loaded for metric evaluation.
- **Flyway data migration**: Migrate existing `{"tsmdName": {"error": null}}` entries in `test_case_eval_summaries.metric_values` to the normalized format, using `output_schema.properties` keys from matching `run_metric_snapshots` records (JOIN on `computation_id` + `tsmd_name`).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `metric-evaluation`: The output mapping requirement for transport failures changes — instead of a synthetic `"error"` key, the system uses actual output field names from the metric's `output_schema`. `MetricOutputMapper` receives output field names per TSMD and fills all fields with `null` on transport failure. `metricInfos` transport failure shape normalizes to per-field error entries.
- `tsmd-validation`: New validation rule — output schema must have a `properties` object with at least one key. Invalid output schemas produce a warning (new code `INVALID_OUTPUT_SCHEMA`) and mark the TSMD as `is_valid = false`.
- `metrics-storage`: The `metricValues` structural contract is tightened — all TSMD entries in `metricValues` always use real output field names as keys (never the synthetic `"error"` key). Existing data migrated via Flyway V1.8.

## Impact

- **Code**: `MetricOutputMapper` (new parameter: output field names per TSMD), `InProcessMetricEvaluationExecutor` (extracts field names from `versionOutputSchema` and passes to mapper), `MetricDefinitionValidationService` (new output schema validation)
- **API**: `GET /api/v1/analytics/eval-summaries` response shape changes for transport-failure entries. **BREAKING** for clients that check for the `"error"` key in `metricValues` to detect transport failures; clients should instead check for `null` values and consult `metricInfos` for error details.
- **Database**: Flyway migration `V1.8__NormalizeErrorShapedMetricValues.sql` in `db/migration/analytics/POSTGRES/` — updates existing `metric_values` JSONB and `metric_infos` JSONB in `test_case_eval_summaries` using field names from `run_metric_snapshots.output_schema`.
- **New validation warning code**: `INVALID_OUTPUT_SCHEMA` added to `ValidationWarningCode` enum.
- **No config changes**: No new configuration properties.
