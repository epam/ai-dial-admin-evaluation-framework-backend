# Analytics Eval Results (Delta)

## ADDED Requirements

### Requirement: Cross-reference to eval summaries
The `test_case_run_results` table remains the raw execution log (request/response bodies, retry logs, trace IDs). The `test_case_eval_summaries` table (defined in `metrics-storage` spec) serves as the metric-enriched analytical surface, containing denormalized test case context plus metric scores. The two tables are linked by `test_case_run_result_id` (soft FK). No changes to existing `test_case_run_results` schema or API endpoints.
Status: **Planned**

#### Scenario: Existing endpoints unchanged
- **WHEN** clients call existing `GET /api/v1/analytics/test-case-results` endpoints
- **THEN** behavior SHALL remain unchanged — these endpoints return raw execution data without metric scores

#### Scenario: Eval summaries reference test case results
- **WHEN** an eval summary row is created
- **THEN** it SHALL contain a `test_case_run_result_id` referencing the original test case run result (soft FK, no DB constraint)
