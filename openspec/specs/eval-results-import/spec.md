# Eval Results Import

## Purpose
This spec describes importing already-produced eval results (raw model responses) for an existing, dataset-bound test suite and running metric evaluation + score computation against them in a single request, without invoking a deployment.

Status: **Implemented**

## Key Terms
- **Eval-results import**: A batch of caller-supplied, already-produced model responses (one per test case) submitted for an existing suite, in place of a live deployment invocation.
- **Import run**: A `TestSuiteRun` created by the import endpoint. It is not distinguished from a normally-created run by any data column — no `suiteSnapshot` or `TestCaseRunInput` rows are created for it, and it is dispatched via `TestSuiteEvaluationJob.executeRunAsync(runId, token, skipDeploymentPhase=true)`, which skips the snapshot phase and Phase 1 (deployment invocation) and runs only Phase 2 (metric evaluation) + Phase 3 (score computation).

## Requirements

### Requirement: Import eval results and create a run in one request
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/runs/import` that accepts a batch of already-produced eval results for an existing, dataset-bound test suite, and in a single request creates a `TestSuiteRun`, persists the batch as `TestCaseRunResult` rows, and asynchronously triggers metric evaluation and score computation against them — without invoking any deployment.

Status: **Implemented**

#### Scenario: Successful import creates a run and returns immediately
- **WHEN** a client submits a non-empty, valid batch of eval results for an existing, valid, dataset-bound suite
- **THEN** the system creates a `TestSuiteRun` with status `PENDING`, persists the results, and returns `202 Accepted` with the run resource while evaluation continues asynchronously

#### Scenario: Deployment invocation is never performed
- **WHEN** an import request is processed
- **THEN** the system does not invoke any deployment/model and instead uses the caller-supplied response bodies directly as the basis for metric evaluation

### Requirement: Import request is a CSV file upload, not a JSON body
The system SHALL accept the eval-results import batch as a CSV file uploaded via `multipart/form-data` (a `file` part, plus optional `testRunName` and `delimiter` form fields), not as a JSON request body. The CSV SHALL use a fixed set of 15 reserved, flat-named columns (`testCaseId`, `testCaseName`, `runIndex`, `testCaseData`, `requestBody`, `responseBody`, `responseStatusCode`, `executionStatus`, `startedAt`, `completedAt`, `traceId`, `retryCount`, `logDetails`, `extractedColumns`, `extractionWarnings`); every column is reserved — `testCaseData` is a required JSON-object column; `extractedColumns` and `extractionWarnings` are optional pre-computed JSON columns (defaulting to `{}` and `[]` when absent); CSV headers that do not match any reserved column are ignored. The parsed batch SHALL be validated and persisted synchronously within the request, with the same all-or-nothing semantics the batch validation already applies.

Status: **Implemented**

#### Scenario: A well-formed CSV file is imported successfully
- **WHEN** a client uploads a CSV file whose header row contains the reserved columns and whose data rows are well-formed
- **THEN** the system parses each row into a `TestCaseRunResult` stub, applies the same batch validation used for any import, and proceeds to create a run and persist the results

#### Scenario: `testCaseData` is supplied as a JSON-object column in the CSV
- **WHEN** a CSV row includes a `testCaseData` cell containing a valid JSON object string
- **THEN** the system uses that value as the row's `testCaseData` verbatim; callers must supply `testCaseData` as a reserved CSV column — there is no mechanism to derive it from other columns

#### Scenario: `extractedColumns` and `extractionWarnings` are optional caller-supplied columns
- **WHEN** a CSV row includes `extractedColumns` and/or `extractionWarnings` cells
- **THEN** the system persists those values verbatim on the corresponding `TestCaseRunResult`; when either column is absent or blank, it defaults to `{}` (for `extractedColumns`) or `[]` (for `extractionWarnings`)

#### Scenario: File size exceeds the configured maximum
- **WHEN** the uploaded CSV file's size exceeds `analytics.results.csv-import.max-file-size`
- **THEN** the system rejects the request with HTTP 400 and creates no run

#### Scenario: Empty or header-only CSV is rejected
- **WHEN** the uploaded file has no header row, or a header row but no data rows
- **THEN** the system rejects the request with HTTP 400 and creates no run

### Requirement: Reserved CSV columns are typed and validated per row
The system SHALL parse `requestBody`, `responseBody`, and `logDetails` cells as JSON when non-blank (object, array, or primitive), treating a blank cell as absent; a cell that is present but not valid JSON SHALL be rejected with HTTP 400 identifying the offending row. The system SHALL parse `executionStatus` as an `ExecutionStatus` enum name, rejecting any other value with HTTP 400 identifying the offending row. The system SHALL enforce the following per-row constraints via inline checks in the parser: `runIndex` must be present and in the range [0, 99999]; `testCaseName` must be at most 255 characters; `executionStatus`, `startedAt`, and `completedAt` must be present. All violations SHALL be surfaced as one combined validation error covering every offending row rather than failing on the first.

Status: **Implemented**

#### Scenario: A malformed JSON cell is rejected with its row identified
- **WHEN** a non-blank `requestBody`, `responseBody`, or `logDetails` cell in some row is not valid JSON
- **THEN** the system rejects the whole request with HTTP 400 that identifies which row the malformed cell was in, and creates no run

#### Scenario: An invalid executionStatus value is rejected
- **WHEN** a row's `executionStatus` cell does not match any `ExecutionStatus` enum name
- **THEN** the system rejects the whole request with HTTP 400 and creates no run

#### Scenario: A missing required reserved column value is rejected
- **WHEN** a row is missing a value for `runIndex`, `executionStatus`, `startedAt`, or `completedAt`
- **THEN** the system rejects the whole request with HTTP 400 identifying the offending row and field, and creates no run

#### Scenario: Multiple rows with independent violations are reported together
- **WHEN** more than one row in the same CSV file violates a per-row constraint
- **THEN** the system collects and reports all violations in a single rejection rather than stopping at the first one found

### Requirement: Suite-level guards mirror normal run creation
The system SHALL apply the same suite-level guards used for normal run creation before accepting an import: the suite must exist, must be bound to a dataset, and must be in a valid configuration state. The system SHALL also apply the same global and per-suite concurrent-run limits and run-name uniqueness check used for normal run creation.

Status: **Implemented**

#### Scenario: Suite not found
- **WHEN** an import request targets a suite id that does not exist
- **THEN** the system rejects the request with HTTP 404 and creates no run

#### Scenario: Suite not bound to a dataset
- **WHEN** an import request targets a suite with no bound dataset
- **THEN** the system rejects the request with HTTP 409 `SUITE_HAS_NO_DATASET` and creates no run

#### Scenario: Suite configuration invalid
- **WHEN** an import request targets a suite whose configuration is not valid
- **THEN** the system rejects the request with HTTP 409 `INVALID_OPERATION` and creates no run

#### Scenario: Concurrent run limit reached
- **WHEN** an import request would exceed the global or per-suite concurrent-run limit
- **THEN** the system rejects the request with HTTP 429 and creates no run

#### Scenario: Duplicate run name
- **WHEN** an import request specifies a run name already used by another run of the same suite
- **THEN** the system rejects the request with HTTP 409 `UNIQUE_CONSTRAINT_VIOLATION` and creates no run

### Requirement: Result batch structural validation
The system SHALL reject an import request whose result batch is empty, exceeds the configured maximum batch size (`analytics.results.batch.max-items`), contains two items with the same test-case identity and `runIndex`, or contains an item whose `completedAt` is before its `startedAt`. On any such violation, the system SHALL create no run.

Status: **Implemented**

#### Scenario: Empty batch rejected
- **WHEN** an import request's `results` list is empty
- **THEN** the system rejects the request with HTTP 400 and creates no run

#### Scenario: Batch size exceeds configured maximum
- **WHEN** an import request's `results` list is larger than the configured maximum batch size
- **THEN** the system rejects the request with HTTP 400 and creates no run

#### Scenario: Duplicate test case and run index within the batch
- **WHEN** an import request contains two items identifying the same test case with the same `runIndex`
- **THEN** the system rejects the request with HTTP 400 and creates no run

#### Scenario: Completion time before start time
- **WHEN** an import request item's `completedAt` is earlier than its `startedAt`
- **THEN** the system rejects the request with HTTP 400 and creates no run

### Requirement: Test case identity and data are caller-supplied, not resolved against the dataset
Each imported item carries its own `testCaseId` (preferred, when present) or `testCaseName` (when `testCaseId` is absent) as an identifying label, and its own `testCaseData` (a JSON object, required). The system SHALL NOT look up or validate this identity/data against any existing `TestCase` row in the suite's bound dataset — the persisted `TestCaseRunResult` for each item carries exactly the `testCaseId`/`testCaseName`/`testCaseData` supplied in the request. When an item supplies `testCaseName` without `testCaseId`, the system generates a new identifier for the persisted row rather than requiring or resolving one. This keeps the import shape consistent with what a live Phase 1 invocation actually produces, and supports importing results into a *cloned* suite whose (possibly newly-cloned, PRIVATE) dataset has entirely different test-case ids than the source the results were originally produced against.

Status: **Implemented**

#### Scenario: Imported result uses the caller-supplied test case data verbatim
- **WHEN** an import item supplies `testCaseData`
- **THEN** the persisted `TestCaseRunResult` for that item carries that exact `testCaseData`, with no lookup against any existing `TestCase` row for identity or existence (the data's shape is still checked against the dataset schema — see "Test case data schema validation" below)

#### Scenario: Test case identity does not need to exist anywhere in EF
- **WHEN** an import item's `testCaseId`/`testCaseName` does not correspond to any `TestCase` row EF has ever stored
- **THEN** the system still accepts and persists the result — identity is a label, not a reference

#### Scenario: A name-only item still persists successfully
- **WHEN** an import item supplies `testCaseName` without `testCaseId`
- **THEN** the system persists the result with a system-generated identifier and the supplied `testCaseName`, without requiring the caller to provide or resolve an id

#### Scenario: `testCaseData` must be a JSON object
- **WHEN** an import item's `testCaseData` is not a JSON object (e.g. a string, number, or array)
- **THEN** the system rejects the whole request with HTTP 400 and creates no run

### Requirement: Test case data schema validation
The system SHALL validate each imported item's `testCaseData` against the suite's bound dataset's schema, when that dataset has a schema configured. The system SHALL reject the whole import request with HTTP 400 when any item's `testCaseData` violates the schema, and SHALL create no run. When the dataset has no schema configured, the system SHALL skip this validation and process the batch normally.

Status: **Implemented**

#### Scenario: Test case data violates the dataset schema
- **WHEN** an import item's `testCaseData` does not conform to the suite's bound dataset's schema
- **THEN** the system rejects the whole request with HTTP 400 mentioning "testCaseData validation failed" and identifying the offending row, and creates no run

#### Scenario: Dataset has no schema configured
- **WHEN** the suite's bound dataset has no schema configured
- **THEN** the system performs no schema validation against `testCaseData` and proceeds to persist the batch normally

### Requirement: Caller-supplied extracted columns and extraction warnings
The system SHALL accept optional `extractedColumns` and `extractionWarnings` CSV columns carrying pre-computed response-column values. The system SHALL persist these values verbatim on each `TestCaseRunResult` without re-evaluating any response-column expressions at import time. When `extractedColumns` is absent or blank, the system SHALL default the persisted value to `{}`; when `extractionWarnings` is absent or blank, the system SHALL default the persisted value to `[]`.

Status: **Implemented**

#### Scenario: Pre-computed extracted columns are persisted verbatim
- **WHEN** an import CSV row supplies an `extractedColumns` cell containing a valid JSON value
- **THEN** the system persists that value verbatim on the corresponding `TestCaseRunResult`, without evaluating any response-column expressions

#### Scenario: Absent extracted columns default to empty
- **WHEN** an import CSV row omits the `extractedColumns` and/or `extractionWarnings` columns (or leaves them blank)
- **THEN** the system persists `{}` for `extractedColumns` and `[]` for `extractionWarnings` on the corresponding result

### Requirement: Metric evaluation and score computation run on imported results
Once an import request's results are persisted, the system SHALL asynchronously run metric evaluation and score computation against them (via `TestSuiteEvaluationJob.executeRunAsync(runId, token, skipDeploymentPhase=true)`), reusing the same `MetricEvaluationExecutor` and `MetricScoreComputation` logic used for live runs, and SHALL transition the run's status through the same lifecycle (`PENDING` → `RUNNING` → `COMPLETED`/`FAILED`/`CANCELLED`) as a normal run.

Status: **Implemented**

#### Scenario: Metric evaluation and score computation complete successfully
- **WHEN** an import run's results have been persisted and evaluation is triggered
- **THEN** the system computes metric values and eval summaries for each imported result and produces score-statistic results for the run, and the run transitions to `COMPLETED`

#### Scenario: Score computation failure does not fail an otherwise-successful run
- **WHEN** metric evaluation for an imported run succeeds but score computation fails
- **THEN** the run still transitions to `COMPLETED`, consistent with score computation being treated as a non-fatal, regenerable step for normal runs

#### Scenario: An imported run can be cancelled like a normal run
- **WHEN** a user cancels an import run while it is `PENDING` or `RUNNING`
- **THEN** the system cancels it using the same cancellation mechanism used for normal runs, with no special-casing based on how the run was created

### Requirement: Result persistence failure is compensated, not left inconsistent
The system SHALL NOT leave a `TestSuiteRun` in `PENDING` status if its imported results failed to persist. If result persistence fails after the run has been created, the system SHALL mark that run as `FAILED`.

Status: **Implemented**

#### Scenario: Result persistence fails after the run is created
- **WHEN** an import request's run is created successfully but persisting its result batch fails
- **THEN** the system marks the run as `FAILED` and does not proceed to trigger evaluation

## Implementation Notes
`POST /api/v1/test-suites/{testSuiteId}/runs/import` (`TestSuiteRunController`, `consumes = MULTIPART_FORM_DATA_VALUE`, a `file` part plus optional `testRunName`/`delimiter` form fields) → `EvalResultsCsvParser.parse` (new, `service.domain.analytics` — streams the CSV via Apache Commons CSV; all 15 columns are reserved (see `RESERVED_COLUMNS`); `testCaseData` is a required JSON-object column; `extractedColumns`/`extractionWarnings` are optional pre-computed JSON columns defaulting to `{}`/`[]`; CSV headers not matching any reserved column are ignored (no non-reserved-column-to-testCaseData mapping exists; `CsvCellParser`/`SchemaTypeCoercer` are not used); builds `TestCaseRunResult` stubs directly with inline field validation — `runIndex` null/range, `testCaseName` length, `executionStatus` null, `startedAt`/`completedAt` null; runs `SchemaValidationService.validate` per row for `testCaseData` schema validation; when `testCaseId` is absent, synthesizes a `UUID.randomUUID()` for the stub so `test_case_run_results.test_case_id NOT NULL` is satisfied; bounded by `analytics.results.batch.max-items` and `analytics.results.csv-import.max-file-size`) → `TestSuiteRunService.importResultsAndEvaluate` (suite guards + `EvalResultsImportService.validateBatch(List<TestCaseRunResult> results)` for structural batch validation — empty/max-size/duplicate-key/`completedAt<startedAt`/missing-identity checks; no `TestCase`/dataset resolution — inside one `@Transactional("metaTransactionManager")` method that saves the run via a private `createAndSaveRun` helper and registers a `TransactionSynchronizationManager` `afterCommit` callback) → `EvalResultsImportService.persistResults` (`@Transactional("analyticsTransactionManager")` — fills in run-context fields (`id`, `testSuiteRunId`, `testSuiteId`, `createdAtMs`) via `.toBuilder()`; persists `extractedColumns`/`extractionWarnings` verbatim from the caller-supplied CSV stubs (no `ResponseColumnExtractor.extract` call); batches writes via `TestCaseRunResultRepository.saveAll` chunked by `csv.import.batch-size`; no `TestCaseRunResultMapper` involvement on the import path) → `TestSuiteEvaluationJob.executeRunAsync(runId, token, skipDeploymentPhase=true)` (Phase 2 `MetricEvaluationExecutor` + Phase 3 `computeMetricScores`, reused unmodified from the live-run job; the previously separate `executeImportedRunAsync` method was merged into `executeRunAsync` behind this flag — see `design.md` Decision 5, revised; `resolveSnapshot` already synthesizes a transient snapshot from the live suite+dataset when `suiteSnapshot` is null). `EvalResultsImportService` owns both structural batch validation (`validateBatch`, no `@Transactional` — runs in the caller's ambient meta transaction) and result persistence (`persistResults`, `@Transactional("analyticsTransactionManager")`); `SchemaValidationService`/`DatasetSchemaProvider` live exclusively in `EvalResultsCsvParser`. `EvalResultsImportRequestDto` and `EvalResultsImportItemDto` do not exist — the CSV parser produces `List<TestCaseRunResult>` directly with no intermediate DTO. The `TestCaseRunResultMapper` import overload was removed; `AnalyticsResultService`'s live-run `toEntity(TestCaseRunResultItemDto, ...)` overload is untouched. `createRun` and `importResultsAndEvaluate` remain two separate methods sharing two private helpers: `enforceConcurrencyLimits` and `createAndSaveRun`; `markRunFailed` (failure compensation with SSE notify) is a private method on `TestSuiteRunService` used only by the import path. No `run_source`/kind column exists: nothing in the pipeline branches on how a run was created. See `design.md` Decision 4 (revised) for why test-case resolution was dropped in favor of caller-trusted identity/data, and Decisions 2/5/6/7 for the service-boundary and format history. Related: test-suite-runs, response-columns, analytics-results, test-suite-clone.
