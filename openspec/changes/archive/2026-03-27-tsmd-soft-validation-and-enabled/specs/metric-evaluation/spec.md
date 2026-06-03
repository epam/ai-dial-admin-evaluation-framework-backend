## MODIFIED Requirements

### Requirement: Aggregated TSMD bulk loading
The repository SHALL support loading aggregated TSMDs for a test suite. Two variants SHALL exist:
- `findAllAggregatedByTestSuiteId(testSuiteId)` — loads ALL TSMDs regardless of `is_enabled` / `is_valid` state (used by revalidation and the aggregated-definition endpoint)
- `findAllEnabledAndValidAggregatedByTestSuiteId(testSuiteId)` — loads only TSMDs where `is_enabled = true AND is_valid = true` (used by the metric evaluation phase)

#### Scenario: findAllAggregatedByTestSuiteId — all TSMDs
- **WHEN** `findAllAggregatedByTestSuiteId(testSuiteId)` is called
- **THEN** it SHALL execute a 3-table JOIN (test_suite_metric_definitions + metric_declarations + metric_declaration_versions) and return `List<AggregatedMetricDefinition>` with all fields populated including `declarationProviderId` and `metricDeclarationName` — regardless of `is_enabled` or `is_valid`

#### Scenario: findAllEnabledAndValidAggregatedByTestSuiteId — filtered
- **WHEN** `findAllEnabledAndValidAggregatedByTestSuiteId(testSuiteId)` is called
- **THEN** it SHALL return only TSMDs where `is_enabled = true AND is_valid = true`

#### Scenario: No TSMDs for suite
- **WHEN** the test suite has no TSMDs
- **THEN** both methods SHALL return an empty list

#### Scenario: Disabled TSMD excluded from evaluation load
- **WHEN** a TSMD has `is_enabled = false` and `is_valid = true`
- **THEN** `findAllEnabledAndValidAggregatedByTestSuiteId` SHALL NOT include it in the result

#### Scenario: Invalid TSMD excluded from evaluation load
- **WHEN** a TSMD has `is_enabled = true` and `is_valid = false`
- **THEN** `findAllEnabledAndValidAggregatedByTestSuiteId` SHALL NOT include it in the result

### Requirement: Metric evaluation executor orchestration
`MetricEvaluationExecutor` is an interface; `InProcessMetricEvaluationExecutor` is the in-process implementation. The implementation SHALL capture RunMetricSnapshots, iterate all `TestCaseRunResult` records for the run using cursor-based pagination, dispatch metric evaluations concurrently per provider, assemble EvalSummary records, and batch-write them to the analytics DB. The set of TSMDs loaded into `MetricEvaluationContext` SHALL be limited to those that are both enabled and valid (`is_enabled = true AND is_valid = true`).
Status: Implemented (partially — TSMD filtering to be added)

#### Scenario: Successful metric evaluation for all test cases
- **WHEN** the metric evaluation phase starts for a completed run with TSMDs configured
- **THEN** the executor SHALL iterate all TestCaseRunResults for the run, evaluate all enabled+valid TSMDs for each SUCCESS result, merge outputs into EvalSummary records, and batch-write them to the analytics DB

#### Scenario: No TSMDs configured for suite
- **WHEN** the metric evaluation phase starts and the suite has no TSMDs
- **THEN** the executor SHALL skip metric evaluation entirely and return without writing any records

#### Scenario: All TSMDs disabled or invalid
- **WHEN** the suite has TSMDs but all are either `is_enabled = false` or `is_valid = false`
- **THEN** the executor SHALL skip metric evaluation entirely (empty TSMD list in context) and return without writing any records

#### Scenario: Cursor-paginated result iteration
- **WHEN** the executor iterates TestCaseRunResults
- **THEN** it SHALL use cursor-based pagination (filtering by runId) to avoid loading all results into memory

#### Scenario: Cross-result parallelism
- **WHEN** multiple test case results are being processed
- **THEN** the executor SHALL dispatch metric evaluations across results concurrently — the provider semaphore controls the total concurrent `/evaluate` calls per provider
