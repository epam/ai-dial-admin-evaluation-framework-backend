## MODIFIED Requirements

### Requirement: Batch write eval summaries
The service SHALL support persisting eval summary rows both via the external REST API (`POST /api/v1/analytics/eval-summaries`) and via internal writes from the in-process metric evaluation engine. Both paths SHALL go through `EvalSummaryService.batchCreate()`, sharing the same validation, mapping, and persistence logic with idempotent `ON CONFLICT DO NOTHING`.
Status: **Implemented**

#### Scenario: Successful batch write
- **WHEN** client calls `POST /api/v1/analytics/eval-summaries` with a valid envelope containing `testSuiteId`, `testSuiteRunId`, `computationId`, `computedAtMs`, and `items` array
- **THEN** system SHALL insert all items atomically (skipping duplicates) and return HTTP 201 with `{"totalItems": N}` echoing the input count. The envelope's `computedAtMs` SHALL be applied to all inserted rows.

#### Scenario: Empty items array
- **WHEN** client submits an envelope whose `items` array is empty
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Batch item count limit exceeded
- **WHEN** `items` array exceeds `analytics.eval-summaries.batch.max-items` (configurable, default 10000)
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Run existence validation
- **WHEN** a batch write is processed
- **THEN** the service SHALL read the run from meta DB. If not found, return HTTP 404

#### Scenario: Suite ID validation
- **WHEN** the envelope's `testSuiteId` differs from the run's `testSuiteId`
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Timestamp assignment from run
- **WHEN** a batch write is processed for a valid run
- **THEN** all records SHALL receive the run's `created_at_ms` from meta DB

#### Scenario: Idempotent retry
- **WHEN** client retries a previously successful batch
- **THEN** system SHALL return HTTP 201 (duplicates silently skipped via ON CONFLICT DO NOTHING)

#### Scenario: Required fields validation
- **WHEN** required envelope fields are missing (`testSuiteId`, `testSuiteRunId`, `computationId`, `computedAtMs`) or required per-item fields are missing (`testCaseRunResultId`, `testCaseId`, `testCaseName`, `testCaseData`, `runIndex`, `executionStatus`, `execDurationMs`, `metricValues`)
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: metricValues structural validation
- **WHEN** a batch write item contains `metricValues`
- **THEN** `metricValues` SHALL be a JSON object where each key maps to a nested JSON object whose leaf values are numeric (or null). Items with non-numeric leaf values SHALL be rejected with HTTP 400 and error code `VALIDATION_ERROR`. This ensures downstream filter (`JSONB_NUMERIC`) and aggregation queries can safely cast values to numeric.

> **Implementation note — key naming**: The keys within each nested `metricValues` object are expected to be real metric output field names (not synthetic placeholders like `"error"`). The batch-write API does not enforce field name validity — that is the producer's responsibility. The metric evaluation engine ensures correct key names by extracting them from the TSMD's output schema via `OutputSchemaFieldExtractor`.

#### Scenario: Internal batch write from metric evaluation engine
- **WHEN** the in-process metric evaluation engine produces EvalSummary records
- **THEN** the `EvalSummaryBatchWriteClient` SHALL convert internal models to `EvalSummaryBatchWriteRequestDto` and delegate to `EvalSummaryService.batchCreate()`, reusing the same validation, mapping, and persistence logic as the external API. The existing batch size limit (`analytics.eval-summaries.batch.max-items`) SHALL be respected via chunking.

## ADDED Requirements

### Requirement: Data migration for error-shaped metricValues
Flyway migration `V1.8__NormalizeErrorShapedMetricValues.sql` SHALL update existing `test_case_eval_summaries` rows that contain transport-failure-shaped metric entries (TSMD entries with an `"error"` key) to use actual output field names from the corresponding `run_metric_snapshots.output_schema`.
Status: **Planned**

#### Scenario: Error entry replaced with real field names
- **WHEN** an eval summary has `metric_values` containing `{"MyMetric": {"error": null}}` and the matching `run_metric_snapshots` row (same `computation_id`, `tsmd_name = "MyMetric"`) has `output_schema` with `{"properties": {"score": {...}}}`
- **THEN** the migration SHALL update `metric_values` to `{"MyMetric": {"score": null}}`

#### Scenario: Multi-field output schema
- **WHEN** an eval summary has `{"RAG Eval": {"error": null}}` and the matching snapshot has `output_schema` with `{"properties": {"recall": {...}, "precision": {...}, "f1": {...}}}`
- **THEN** the migration SHALL update to `{"RAG Eval": {"recall": null, "precision": null, "f1": null}}`

#### Scenario: Mixed TSMD entries (some success, some error)
- **WHEN** an eval summary has `metric_values` containing `{"Accuracy": {"score": 0.95}, "Relevancy": {"error": null}}`
- **THEN** the migration SHALL only replace the "Relevancy" entry, preserving the "Accuracy" entry unchanged

#### Scenario: No matching snapshot
- **WHEN** an eval summary has an error-shaped TSMD entry but no matching `run_metric_snapshots` row exists for that `computation_id` and `tsmd_name`
- **THEN** the migration SHALL leave that TSMD entry unchanged

#### Scenario: Matching snapshot with empty output schema
- **WHEN** a matching snapshot exists but `output_schema->'properties'` is null, empty, or not an object
- **THEN** the migration SHALL leave that TSMD entry unchanged

#### Scenario: metric_infos also migrated
- **WHEN** an eval summary has `metric_infos` containing `{"MyMetric": {"error": "Connection refused"}}` (transport failure shape) and the matching snapshot has output fields `["score"]`
- **THEN** the migration SHALL update `metric_infos` to `{"MyMetric": {"score": {"error": "Connection refused"}}}` (error replicated per output field)

> **Clarifying note — transport-failure detection heuristic**: For `metric_values`, transport-failure entries have exactly one key `'error'` with a JSON null value (e.g., `{"error": null}`). For `metric_infos`, transport-failure entries have exactly one key `'error'` whose value is a text string (e.g., `{"error": "Connection refused"}`). This distinguishes transport failures from field-level error structures (where keys are output field names and values are objects like `{"error": "Invalid pattern"}`).

#### Scenario: No error-shaped entries in table
- **WHEN** no eval summary rows contain TSMD entries with the `"error"` key pattern
- **THEN** the migration SHALL complete successfully with zero rows updated

> **Implementation note — metricInfos structure**: `metricInfos` follows a per-field error structure for transport failures: `{"tsmdName": {"fieldName": {"error": "message"}}}`. This mirrors the field-level error format already used for metric provider errors (where individual output fields report `{type: "error"}`). The metric-evaluation spec is the authoritative source for the `metricInfos` structure and its edge cases (including the empty-output-schema fallback). The batch-write API does not enforce `metricInfos` structure — correctness is the producer's responsibility.
