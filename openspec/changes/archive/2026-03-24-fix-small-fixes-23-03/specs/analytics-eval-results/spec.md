## ADDED Requirements

### Requirement: Extraction warnings in eval summary
The `test_case_eval_summaries` table SHALL have an `extraction_warnings` column (`JSONB NOT NULL DEFAULT '[]'`), added via Flyway migration V1.7. `EvalSummary` model, `EvalSummaryRowMapper`, batch-insert SQL, and `EvalSummaryDetailResponseDto` SHALL include `extractionWarnings`. The value SHALL be copied from `TestCaseRunResult.extractionWarnings` at eval summary write time.

**Status**: Planned

#### Scenario: Extraction warnings persisted
- **WHEN** a test case run result has extraction warnings `["field x not found"]`
- **THEN** the corresponding eval summary row has `extraction_warnings = '["field x not found"]'`

#### Scenario: No extraction warnings
- **WHEN** a test case run result has no extraction warnings
- **THEN** the eval summary row has `extraction_warnings = '[]'`

#### Scenario: Extraction warnings in API response
- **WHEN** `GET /api/v1/eval-summaries/{id}` is called
- **THEN** the response includes `extractionWarnings` as a JSON array

### Requirement: Request and response body in eval summary detail
`EvalSummaryDetailResponseDto` SHALL include `requestBody` and `responseBody` fields (nullable `JsonNode`). These SHALL be populated by LEFT-joining `test_case_run_results` on `test_case_run_result_id` in the `findById` query only. The list query (`findAll`, pagination, aggregation) SHALL NOT include this join.

**Status**: Planned

#### Scenario: Request and response body in detail view
- **WHEN** `GET /api/v1/eval-summaries/{id}` is called for an existing summary
- **THEN** the response includes `requestBody` and `responseBody` as JSON objects (or null if the run result has no body)

#### Scenario: List query unaffected
- **WHEN** `GET /api/v1/eval-summaries` (list) is called
- **THEN** the response items do NOT include `requestBody` or `responseBody` fields

#### Scenario: Missing run result row
- **WHEN** the referenced `test_case_run_result_id` no longer exists in `test_case_run_results` (e.g. deleted)
- **THEN** the detail response returns the eval summary with `requestBody: null` and `responseBody: null` (LEFT JOIN behavior)
