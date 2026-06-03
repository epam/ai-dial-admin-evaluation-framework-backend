# Metrics Storage — Delta Spec

## MODIFIED Requirements

### Requirement: Batch write eval summaries
The service SHALL support persisting eval summary rows both via the external REST API (`POST /api/v1/analytics/eval-summaries`) and via internal writes from the in-process metric evaluation engine. Both paths SHALL go through `EvalSummaryService.batchCreate()`, sharing the same validation, mapping, and persistence logic with idempotent `ON CONFLICT DO NOTHING`.
Status: **Implemented** (external API) / **Planned** (internal writes)

#### Scenario: Successful batch write via external API
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

#### Scenario: Internal batch write from metric evaluation engine
- **WHEN** the in-process metric evaluation engine produces EvalSummary records
- **THEN** the `EvalSummaryBatchWriteClient` SHALL convert internal models to `EvalSummaryBatchWriteRequestDto` and delegate to `EvalSummaryService.batchCreate()`, reusing the same validation, mapping, and persistence logic as the external API. The existing batch size limit (`analytics.eval-summaries.batch.max-items`) SHALL be respected via chunking.

### Requirement: Batch write run metric snapshots
The service SHALL support persisting run metric snapshots both via the external REST API (`POST /api/v1/analytics/run-metric-snapshots`) and via internal writes from the in-process metric evaluation engine. Both paths SHALL go through `RunMetricSnapshotService.batchCreate()`, sharing the same validation, mapping, and persistence logic with idempotent `ON CONFLICT DO NOTHING`.
Status: **Implemented** (external API) / **Planned** (internal writes)

#### Scenario: Successful batch write via external API
- **WHEN** client calls `POST /api/v1/analytics/run-metric-snapshots` with a valid envelope containing `testSuiteRunId`, `computationId`, `computedAtMs`, and `snapshots` array
- **THEN** system SHALL insert all snapshots atomically and return HTTP 201. The envelope's `computedAtMs` SHALL be applied to all inserted rows.

#### Scenario: Run existence validation
- **WHEN** a batch write is processed
- **THEN** the service SHALL read the run from meta DB. If not found, return HTTP 404

#### Scenario: Internal write from metric evaluation engine
- **WHEN** the in-process metric evaluation engine captures RunMetricSnapshots before evaluation
- **THEN** the `RunMetricSnapshotBatchWriteClient` SHALL convert internal models to `RunMetricSnapshotBatchWriteRequestDto` and delegate to `RunMetricSnapshotService.batchCreate()`, reusing the same validation, mapping, and persistence logic as the external API.
