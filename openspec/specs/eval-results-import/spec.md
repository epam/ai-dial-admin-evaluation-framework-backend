# Eval Results Import

## Purpose
This spec describes importing already-produced eval results (raw model responses) for an existing, dataset-bound test suite and running metric evaluation + score computation against them in a single request, without invoking a deployment.

Status: **Implemented**

## Key Terms
- **Eval-results import**: A batch of caller-supplied, already-produced model responses (one per test case) submitted for an existing suite, in place of a live deployment invocation.
- **Import run**: A `TestSuiteRun` created by the import endpoint. It is dispatched via `TestSuiteEvaluationJob.executeRunAsync(runId, token, skipDeploymentPhase=true)`, which skips Phase 1 (deployment invocation) and runs only Phase 2 (metric evaluation) + Phase 3 (score computation). The snapshot phase itself is **not** skipped: it captures a `suiteSnapshot` (used by Phase 3's `overallScoreDefinition` resolution and exposed on `GET` for retrospection) but never creates `TestCaseRunInput` rows and never overwrites `numberOfTestCases` — those are gated to runs that will execute Phase 1, which an import run never does.

## Requirements

### Requirement: Import eval results and create a run in one request
The system SHALL provide `POST /api/v1/test-suites/{testSuiteId}/runs/import` that accepts a batch of already-produced eval results for an existing, dataset-bound test suite, and in a single request creates a `TestSuiteRun`, persists the batch as `TestCaseRunResult` rows, captures a suite snapshot (see "Import run captures a suite snapshot"), and asynchronously triggers metric evaluation and score computation against the persisted results — without invoking any deployment.

Status: **Implemented**

#### Scenario: Successful import creates a run and returns immediately
- **WHEN** a client submits a non-empty, valid batch of eval results for an existing, valid, dataset-bound suite
- **THEN** the system creates a `TestSuiteRun` with status `PENDING`, persists the results, and returns `202 Accepted` with the run resource while evaluation continues asynchronously

#### Scenario: Deployment invocation is never performed
- **WHEN** an import request is processed
- **THEN** the system does not invoke any deployment/model and instead uses the caller-supplied response bodies directly as the basis for metric evaluation

### Requirement: Import run captures a suite snapshot

An import run SHALL capture a `suite_snapshot` (via the same snapshot phase normal runs use) so that Phase 3 score computation and any retrospective read of the run see a frozen record of the suite's deployment/endpoint (or MCP tool), request template, response columns, dataset reference, and `overallScore` definition as they were at import time — instead of resynthesizing them from the live suite/dataset. An import run SHALL NOT capture `test_case_run_inputs` and SHALL NOT have its `number_of_test_cases` overwritten by the snapshot phase, since Phase 1 (the only consumer of `test_case_run_inputs`) never executes for an import run and `number_of_test_cases` is already set from the actual imported result count at run creation.

Status: **Implemented**

#### Scenario: Imported run's GET response includes a non-null suite snapshot
- **WHEN** an import request completes and `GET /api/v1/test-suites/{testSuiteId}/runs/{runId}` is called for that run
- **THEN** the response's `suiteSnapshot` field SHALL be non-null and SHALL reflect the suite's deployment/endpoint (or MCP tool) configuration as of import time

#### Scenario: Imported run's number of test cases is unaffected by the snapshot phase
- **WHEN** an import request with `N` CSV rows completes
- **THEN** the run's `numberOfTestCases` SHALL equal `N` (the imported row count), not the count of currently-runnable test cases in the suite's live dataset

#### Scenario: Score computation uses the persisted snapshot, not a live resynthesis
- **WHEN** Phase 3 (`computeMetricScores`) resolves `overallScoreDefinition` for an import run
- **THEN** it SHALL read the `overallScore` value from the persisted `suite_snapshot` captured at import time, not from a transient snapshot built from the suite's live configuration at score-computation time

### Requirement: Import request is a CSV file upload, not a JSON body
The system SHALL accept the eval-results import batch as a CSV file uploaded via `multipart/form-data` (a `file` part, plus optional `testRunName` and `delimiter` form fields), not as a JSON request body. The CSV SHALL use a fixed set of 19 reserved, flat-named columns (`testCaseId`, `testCaseName`, `runIndex`, `testCaseData`, `requestBody`, `responseBody`, `responseStatusCode`, `executionStatus`, `startedAt`, `completedAt`, `traceId`, `retryCount`, `logDetails`, `extractedColumns`, `extractionWarnings`, `requestIndex`, `totalRequests`, `turnIndex`, `totalTurns`); every column is reserved — `testCaseData` is a required JSON-object column; `extractedColumns` and `extractionWarnings` are optional pre-computed JSON columns (defaulting to `{}` and `[]` when absent); `requestIndex`, `totalRequests`, `turnIndex`, and `totalTurns` are optional row-identity columns (see "Row identity columns carry request-chain and turn position"); CSV headers that do not match any reserved column are ignored. The parsed batch SHALL be validated and persisted synchronously within the request, with the same all-or-nothing semantics the batch validation already applies.

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

#### Scenario: The identity columns are optional and backward-compatible
- **WHEN** a CSV file supplies only the 15 previously reserved columns, with no `requestIndex`/`totalRequests`/`turnIndex`/`totalTurns` header
- **THEN** the system accepts the file and treats every row as a single-request, single-turn row, so files produced before these columns existed import unchanged — with one deliberate exception: a name-only file repeating the same `testCaseName` and `runIndex` is rejected as a duplicate rather than fragmented into unrelated rows (see "Rows of one test-case repetition share a stable identity")

#### Scenario: File size exceeds the configured maximum
- **WHEN** the uploaded CSV file's size exceeds `analytics.results.csv-import.max-file-size`
- **THEN** the system rejects the request with HTTP 400 and creates no run

#### Scenario: Empty or header-only CSV is rejected
- **WHEN** the uploaded file has no header row, or a header row but no data rows
- **THEN** the system rejects the request with HTTP 400 and creates no run

### Requirement: Row identity columns carry request-chain and turn position
The import contract SHALL accept four **optional** reserved CSV columns — `requestIndex`, `totalRequests`, `turnIndex`, `totalTurns` — that carry a result row's position within its test-case repetition: `(requestIndex, turnIndex)` identifies the row, `totalRequests` is the length of the suite's request chain and `totalTurns` the turn count of that row's request. The system SHALL parse each as an integer, SHALL default an absent or blank cell to the same values a single-request single-turn row already carries (`requestIndex = 0`, `totalRequests = 1`, `turnIndex = 0`, `totalTurns = 1`), and SHALL persist all four verbatim on the resulting result row. The system SHALL enforce per row that `requestIndex` and `turnIndex` are at least 0, that `totalRequests` and `totalTurns` are at least 1, that `requestIndex` is less than `totalRequests`, and that `turnIndex` is less than `totalTurns` — applying these checks to the effective values after defaulting, so a supplied index is range-checked against a defaulted total. The system SHALL NOT require `totalRequests` or `totalTurns` to agree across rows sharing a test-case identity, because a chain's requests legitimately differ in turn count.

Status: **Implemented**

#### Scenario: Multi-request and multi-turn identity survives the import boundary
- **WHEN** an import CSV supplies `requestIndex`/`totalRequests`/`turnIndex`/`totalTurns` for its rows
- **THEN** each persisted result row carries exactly those four values, and reading the run's results back exposes the same per-request/per-turn breakdown a live run of the same suite would produce

#### Scenario: A CSV without the identity columns keeps its current meaning
- **WHEN** an import CSV omits all four columns entirely (the pre-existing 15-reserved-column shape)
- **THEN** the system accepts the file unchanged and persists `requestIndex = 0`, `totalRequests = 1`, `turnIndex = 0`, `totalTurns = 1` on every row, producing byte-identical results to the behavior before these columns existed

#### Scenario: A blank identity cell falls back to the default
- **WHEN** a row is missing a value for one of the four columns while other rows supply it
- **THEN** the system applies that column's default for that row rather than rejecting the file

#### Scenario: A supplied index is range-checked against a defaulted total
- **WHEN** a row supplies `requestIndex = 2` but leaves `totalRequests` blank, so the total defaults to `1`
- **THEN** the system rejects the whole request with HTTP 400 identifying the offending row, because the effective `requestIndex` is not less than the effective `totalRequests`

#### Scenario: A non-integer identity value is rejected with its row identified
- **WHEN** a non-blank `requestIndex`, `totalRequests`, `turnIndex`, or `totalTurns` cell is not a valid integer
- **THEN** the system rejects the whole request with HTTP 400 identifying the offending row and field, and creates no run

#### Scenario: A negative index or a non-positive total is rejected
- **WHEN** a row supplies a negative `requestIndex` or `turnIndex`, or a `totalRequests` or `totalTurns` below 1
- **THEN** the system rejects the whole request with HTTP 400 identifying the offending row, and creates no run

#### Scenario: An out-of-range index is rejected
- **WHEN** a row's `requestIndex` is not less than its `totalRequests`, or its `turnIndex` is not less than its `totalTurns`
- **THEN** the system rejects the whole request with HTTP 400 identifying the offending row, and creates no run

#### Scenario: Turn counts may differ between requests in the same chain
- **WHEN** rows for one test-case identity carry different `totalTurns` values at different `requestIndex` positions
- **THEN** the system accepts them, because turn count is decided per request rather than per test case

#### Scenario: Identity-column violations are reported with all other row violations
- **WHEN** an identity-column violation occurs in the same file as other per-row constraint violations
- **THEN** the system reports all of them in a single combined rejection rather than stopping at the first

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
The system SHALL reject an import request whose result batch is empty, exceeds the configured maximum batch size (`analytics.results.batch.max-items`), contains two items with the same test-case identity, `runIndex`, `requestIndex`, and `turnIndex`, or contains an item whose `completedAt` is before its `startedAt`. On any such violation, the system SHALL create no run. Because a single test-case repetition legitimately produces one row per `(requestIndex, turnIndex)` pair, two rows sharing a test-case identity and `runIndex` SHALL NOT be treated as duplicates when they differ in `requestIndex` or `turnIndex`.

Status: **Implemented**

#### Scenario: Empty batch rejected
- **WHEN** an import request's `results` list is empty
- **THEN** the system rejects the request with HTTP 400 and creates no run

#### Scenario: Batch size exceeds configured maximum
- **WHEN** an import request's `results` list is larger than the configured maximum batch size
- **THEN** the system rejects the request with HTTP 400 and creates no run

#### Scenario: Duplicate test case and run index within the batch
- **WHEN** an import request contains two items identifying the same test case with the same `runIndex`, and those items also share the same `requestIndex` and `turnIndex`
- **THEN** the system rejects the request with HTTP 400 and creates no run

#### Scenario: A multi-request chain's rows are not duplicates
- **WHEN** an import request contains several items for the same test case and `runIndex` that differ in `requestIndex`
- **THEN** the system accepts them as distinct rows and creates the run

#### Scenario: A multi-turn case's rows are not duplicates
- **WHEN** an import request contains several items for the same test case, `runIndex`, and `requestIndex` that differ in `turnIndex`
- **THEN** the system accepts them as distinct rows and creates the run

#### Scenario: Completion time before start time
- **WHEN** an import request item's `completedAt` is earlier than its `startedAt`
- **THEN** the system rejects the request with HTTP 400 and creates no run

### Requirement: Test case identity and data are caller-supplied, not resolved against the dataset
Each imported item carries its own `testCaseId` (preferred, when present) or `testCaseName` (when `testCaseId` is absent) as an identifying label, and its own `testCaseData` (a JSON object, required). The system SHALL NOT look up or validate this identity/data against any existing `TestCase` row in the suite's bound dataset — the persisted `TestCaseRunResult` for each item carries exactly the `testCaseId`/`testCaseName`/`testCaseData` supplied in the request. When an item supplies `testCaseName` without `testCaseId`, the system generates an identifier for the persisted row rather than requiring or resolving one, sharing that identifier across every row carrying the same `testCaseName` within the file (see "Rows of one test-case repetition share a stable identity"). This keeps the import shape consistent with what a live Phase 1 invocation actually produces, and supports importing results into a *cloned* suite whose (possibly newly-cloned, PRIVATE) dataset has entirely different test-case ids than the source the results were originally produced against.

Status: **Implemented**

#### Scenario: Imported result uses the caller-supplied test case data verbatim
- **WHEN** an import item supplies `testCaseData`
- **THEN** the persisted `TestCaseRunResult` for that item carries that exact `testCaseData`, with no lookup against any existing `TestCase` row for identity or existence (the data's shape is still checked against the dataset schema — see "Test case data schema validation")

#### Scenario: Test case identity does not need to exist anywhere in EF
- **WHEN** an import item's `testCaseId`/`testCaseName` does not correspond to any `TestCase` row EF has ever stored
- **THEN** the system still accepts and persists the result — identity is a label, not a reference

#### Scenario: A name-only item still persists successfully
- **WHEN** an import item supplies `testCaseName` without `testCaseId`
- **THEN** the system persists the result with a system-generated identifier and the supplied `testCaseName`, without requiring the caller to provide or resolve an id

#### Scenario: `testCaseData` must be a JSON object
- **WHEN** an import item's `testCaseData` is not a JSON object (e.g. a string, number, or array)
- **THEN** the system rejects the whole request with HTTP 400 and creates no run

### Requirement: Rows of one test-case repetition share a stable identity
When an imported row supplies no `testCaseId`, the system SHALL derive that row's persisted identifier from its `testCaseName`, assigning **one** generated identifier per distinct `testCaseName` within a single import file, so that every row of one test case — across all its `(requestIndex, turnIndex)` positions and `runIndex` repetitions — is persisted under the same `test_case_id`. This matches what a live run produces, where all rows of a test-case repetition share the test case's own id, and it keeps imported results groupable by the eval-summary natural key `(run, test_case_id, run_index, request_index, turn_index, computation_id, created_at_ms)`. Identifiers SHALL remain locally generated labels: the system SHALL NOT resolve them against any dataset, and SHALL NOT reuse an identifier across separate import requests. A row supplying neither `testCaseId` nor `testCaseName` SHALL be left without a derived identifier, so batch validation rejects it with the identity-required error before any run is created.

Status: **Implemented**

#### Scenario: All rows of a multi-request multi-turn case share one identifier
- **WHEN** an import file contains several rows carrying the same `testCaseName` and no `testCaseId`, differing in `runIndex`, `requestIndex`, or `turnIndex`
- **THEN** every one of those rows is persisted with the same generated `testCaseId`, so the run's results can be grouped back into the repetitions that produced them

#### Scenario: Different test case names get different identifiers
- **WHEN** an import file contains rows for two distinct `testCaseName` values, none carrying a `testCaseId`
- **THEN** the two groups are persisted under two different generated identifiers

#### Scenario: A supplied test case id is used as given
- **WHEN** an import row supplies a `testCaseId`
- **THEN** the system persists that identifier verbatim and derives nothing from `testCaseName` for that row

#### Scenario: Identifiers are not shared across import requests
- **WHEN** the same `testCaseName` is imported in two separate requests
- **THEN** each request's rows get their own generated identifier, since identity is scoped to a single imported batch

#### Scenario: A row with neither identifier nor name is rejected before a run exists
- **WHEN** an import row supplies neither `testCaseId` nor `testCaseName`
- **THEN** the system rejects the whole request with HTTP 400 stating that one of the two is required, and creates no run

#### Scenario: Same-name rows colliding on all identity dimensions are rejected as duplicates
- **WHEN** a file with no `testCaseId` column carries two rows with the same `testCaseName`, the same `runIndex`, and the same (possibly defaulted) `requestIndex` and `turnIndex`
- **THEN** the system rejects the whole request with HTTP 400 as a duplicate result — the shared derived identifier makes such rows collide on the duplicate key, whereas per-row random identifiers formerly let them import as two unrelated test cases

### Requirement: Test case data schema validation
The system SHALL validate each imported item's `testCaseData` against the suite's bound dataset's schema, when that dataset has a schema configured. Validation SHALL be aware of each field's scope: required-ness is enforced only for shared-scope fields; a field declared per-turn SHALL NOT be treated as required, and SHALL be type-checked only when present in the row — a row legitimately carries shared-only data whenever its chain position binds no per-turn field, and a single-turn test case in a per-turn dataset persists shared-only `testCaseData`. The system SHALL reject the whole import request with HTTP 400 when any item's `testCaseData` violates the schema so scoped, and SHALL create no run. When the dataset has no schema configured, the system SHALL skip this validation and process the batch normally.

Status: **Implemented**

#### Scenario: Test case data violates the dataset schema
- **WHEN** an import item's `testCaseData` does not conform to the suite's bound dataset's schema
- **THEN** the system rejects the whole request with HTTP 400 mentioning "testCaseData validation failed" and identifying the offending row, and creates no run

#### Scenario: Dataset has no schema configured
- **WHEN** the suite's bound dataset has no schema configured
- **THEN** the system performs no schema validation against `testCaseData` and proceeds to persist the batch normally

#### Scenario: A shared-only row validates against a dataset with a required per-turn field
- **WHEN** the bound dataset declares a required per-turn field and an imported row's `testCaseData` supplies only the shared fields
- **THEN** the system accepts the row rather than rejecting the import for a missing required field

#### Scenario: A missing required shared field is still rejected
- **WHEN** an imported row's `testCaseData` omits a field the dataset declares as required and shared
- **THEN** the system rejects the whole request with HTTP 400 identifying the offending row, and creates no run

#### Scenario: A per-turn field present with the wrong type is rejected
- **WHEN** an imported row's `testCaseData` supplies a per-turn field whose value does not match the field's declared type
- **THEN** the system rejects the whole request with HTTP 400 identifying the offending row, and creates no run

#### Scenario: A dataset with only shared fields enforces every required field on every row
- **WHEN** the bound dataset declares no per-turn fields
- **THEN** every required field is enforced on every row and every present field is type-checked

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

### Requirement: Imported runs expose request-chain and turn context to metric evaluation
For a run created by import, metric evaluation SHALL resolve each result row's `request` namespace (`request.index`, `request.total`, `request.last`, `request.name`) and `turn` namespace (`turn.index`, `turn.total`, `turn.last`) from that row's persisted identity columns together with the run's captured suite snapshot, using the same resolution a live run uses. A metric whose condition is pinned to a chain position or a turn position SHALL therefore behave identically on an imported run and on a live run of the same suite.

Status: **Implemented**

#### Scenario: A request-pinned conditional metric matches on an imported run
- **WHEN** a suite has a metric whose condition pins it to a named or positional request in the chain, and results for that suite are imported with identity columns
- **THEN** the metric runs for exactly the rows whose `requestIndex` selects that chain position, and is omitted for the others

#### Scenario: A turn-pinned conditional metric matches on an imported run
- **WHEN** a suite has a metric whose condition pins it to a turn position (for example the last turn), and multi-turn results are imported with identity columns
- **THEN** the metric runs for exactly the rows whose `turnIndex`/`totalTurns` satisfy the condition

#### Scenario: Request labels come from the run's own captured snapshot
- **WHEN** metric evaluation resolves `request.name` for an imported run's row
- **THEN** the label is read from the request chain frozen in that run's suite snapshot at import time, so a later edit to the destination suite's chain does not change the labels of an already-imported run

### Requirement: Result persistence failure is compensated, not left inconsistent
The system SHALL NOT leave a `TestSuiteRun` in `PENDING` status if its imported results failed to persist. If result persistence fails after the run has been created, the system SHALL mark that run as `FAILED`.

Status: **Implemented**

#### Scenario: Result persistence fails after the run is created
- **WHEN** an import request's run is created successfully but persisting its result batch fails
- **THEN** the system marks the run as `FAILED` and does not proceed to trigger evaluation

## Implementation Notes
`POST /api/v1/test-suites/{testSuiteId}/runs/import` (`TestSuiteRunController`, `consumes = MULTIPART_FORM_DATA_VALUE`, a `file` part plus optional `testRunName`/`delimiter` form fields) → `EvalResultsCsvParser.parse` (new, `service.domain.analytics` — streams the CSV via Apache Commons CSV; all 19 columns are reserved (see `RESERVED_COLUMNS`), including the four optional row-identity columns `requestIndex`/`totalRequests`/`turnIndex`/`totalTurns` (absent/blank cells default to `0`/`1`/`0`/`1`; effective values range-checked by `validateIdentityColumns` — index ≥ 0, total ≥ 1, index < total — with violations collected alongside all other row errors); `testCaseData` is a required JSON-object column; `extractedColumns`/`extractionWarnings` are optional pre-computed JSON columns defaulting to `{}`/`[]`; CSV headers not matching any reserved column are ignored (no non-reserved-column-to-testCaseData mapping exists; `CsvCellParser`/`SchemaTypeCoercer` are not used); builds `TestCaseRunResult` stubs directly with inline field validation — `runIndex` null/range, `testCaseName` length, `executionStatus` null, `startedAt`/`completedAt` null; runs `SchemaValidationService.validate` per row for `testCaseData` schema validation, against a scope-aware schema built by `buildScopeAwareSchema` (splits fields via `TestCaseFieldScopeResolver.splitSchema`; per-turn fields stay type-checked in `properties` but are never `required`); when `testCaseId` is absent, derives the stub's id via `resolveTestCaseId` — one generated `UUID` per distinct `testCaseName` within the file (a `nameToGeneratedId` map), shared across that name's rows, so `test_case_run_results.test_case_id NOT NULL` is satisfied and a repetition's rows group together; bounded by `analytics.results.batch.max-items` and `analytics.results.csv-import.max-file-size`) → `TestSuiteRunService.importResultsAndEvaluate` (suite guards + `EvalResultsImportService.validateBatch(List<TestCaseRunResult> results)` for structural batch validation — empty/max-size/duplicate-key (the key is `(testCaseId-or-testCaseName, runIndex, requestIndex, turnIndex)`, so a chain's/turn-loop's rows are not duplicates)/`completedAt<startedAt`/missing-identity checks; no `TestCase`/dataset resolution — inside one `@Transactional("metaTransactionManager")` method that saves the run via a private `createAndSaveRun` helper and registers a `TransactionSynchronizationManager` `afterCommit` callback) → `EvalResultsImportService.persistResults` (`@Transactional("analyticsTransactionManager")` — fills in run-context fields (`id`, `testSuiteRunId`, `testSuiteId`, `createdAtMs`) via `.toBuilder()`; persists `extractedColumns`/`extractionWarnings` verbatim from the caller-supplied CSV stubs (no `ResponseColumnExtractor.extract` call); batches writes via `TestCaseRunResultRepository.saveAll` chunked by `csv.import.batch-size`; no `TestCaseRunResultMapper` involvement on the import path) → `TestSuiteEvaluationJob.executeRunAsync(runId, token, skipDeploymentPhase=true)` (Phase 2 `MetricEvaluationExecutor` + Phase 3 `computeMetricScores`, reused unmodified from the live-run job; the previously separate `executeImportedRunAsync` method was merged into `executeRunAsync` behind this flag — see `design.md` Decision 5, revised; the snapshot phase now always runs, including for imports, so `suiteSnapshot` is persisted at import time and is non-null for every run created after this change — `resolveSnapshot`'s live suite+dataset resynthesis fallback is reached only for runs created before this change, or if the run's suite/dataset later disappears). `EvalResultsImportService` owns both structural batch validation (`validateBatch`, no `@Transactional` — runs in the caller's ambient meta transaction) and result persistence (`persistResults`, `@Transactional("analyticsTransactionManager")`); `SchemaValidationService`/`DatasetSchemaProvider` live exclusively in `EvalResultsCsvParser`. `EvalResultsImportRequestDto` and `EvalResultsImportItemDto` do not exist — the CSV parser produces `List<TestCaseRunResult>` directly with no intermediate DTO. The `TestCaseRunResultMapper` import overload was removed; `AnalyticsResultService`'s live-run `toEntity(TestCaseRunResultItemDto, ...)` overload is untouched. `createRun` and `importResultsAndEvaluate` remain two separate methods sharing two private helpers: `enforceConcurrencyLimits` and `createAndSaveRun`; `markRunFailed` (failure compensation with SSE notify) is a private method on `TestSuiteRunService` used only by the import path. No `run_source`/kind column exists: nothing in the pipeline branches on how a run was created. See `design.md` Decision 4 (revised) for why test-case resolution was dropped in favor of caller-trusted identity/data, and Decisions 2/5/6/7 for the service-boundary and format history. Related: test-suite-runs, response-columns, analytics-results, test-suite-clone.
