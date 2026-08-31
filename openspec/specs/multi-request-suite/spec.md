# Multi-Request Test Suites

## Purpose

Defines the **request chain**: a test suite may execute more than one request per test case against its single deployment. The suite's existing request fields are request #0 of the chain; an ordered `additionalRequests` list supplies requests 1..N. Covers the chain model and DTO shape, the shared flat response-column namespace with accumulating frame bindings, per-request multi-turn emergence, the `request_index`/`total_requests` analytics dimension, chain-wide fail-fast, and the `MCP_TOOL` rejection guard.

Status: **Implemented**

## Key Terms

- **request chain**: The ordered sequence of requests a suite executes for one test-case repetition — `[request #0] ++ additionalRequests`. Length = `1 + additionalRequests.size()`.
- **request #0**: The request defined by the suite's own `endpointRef` / `requestTemplate` / `responseColumns` / `inputBindings` fields. Always present, always first, never an element of `additionalRequests`.
- **RequestDefinitionDto**: `com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto` — one additional request: optional `name`, `endpointRef`, `requestTemplate`, `responseColumns`, `inputBindings`.
- **request label**: The user-facing name of a request — the suite-level `requestName` for request #0, `RequestDefinitionDto.name` for additional requests; `null` when unlabelled.
- **accumulated frame**: The map of extracted response-column values available as JSONata frame bindings, carried forward across turns of a request and across requests of a chain.
- **request dimension**: The `(request_index, total_requests)` pair on an analytics row, orthogonal to the existing `(turn_index, total_turns)` turn dimension.

## Requirements

### Requirement: A suite defines an ordered request chain

A test suite SHALL execute, for each test-case repetition, an ordered chain of requests against its single `deploymentRef`. The chain SHALL be `[request #0] ++ additionalRequests` where request #0 is built from the suite's own `endpointRef`, `requestTemplate`, `responseColumns` and `inputBindings`, and `additionalRequests` is an ordered list of `RequestDefinitionDto`. `deploymentRef` SHALL remain suite-level — a chain SHALL NOT target more than one deployment. Requests SHALL execute strictly sequentially in list order under a single concurrency permit (concurrency applies across test cases, not within a chain). `totalRequests` SHALL be `1 + additionalRequests.size()`.

Status: **Implemented**

#### Scenario: Two-request chain executes in order
- **WHEN** a suite has one entry in `additionalRequests` and a test case runs
- **THEN** request #0 SHALL be invoked first and the additional request second, both against the suite's `deploymentRef`

#### Scenario: Empty additionalRequests is a one-element chain
- **WHEN** a suite's `additionalRequests` is absent, null or `[]`
- **THEN** the chain SHALL contain exactly request #0 and execution SHALL be indistinguishable from a suite created before this capability existed

#### Scenario: Requests of one chain are not parallelised
- **WHEN** a chain of three requests executes for one test case
- **THEN** the three calls SHALL be issued sequentially, each completing before the next begins

### Requirement: Request definition shape

Each element of `additionalRequests` SHALL be a `RequestDefinitionDto` carrying the **full** per-request definition: `name` (`String`, optional, max 255 — a user-facing label), `endpointRef` (`EndpointContractDto`), `requestTemplate` (`RequestTemplateDto`), `responseColumns` (`List<ResponseColumnDefinitionDto>`, defaults to empty), and `inputBindings` (`List<InputBindingDto>`, defaults to empty). A `RequestDefinitionDto` SHALL NOT carry a `deploymentRef`, MCP fields, `testCaseFilter`, `overallScore`, or any dataset reference — those remain suite-level. `additionalRequests` SHALL be persisted as a JSONB array column `additional_requests` on `test_suites`, `NOT NULL DEFAULT '[]'`.

Every element of `additionalRequests` SHALL be a non-null object. A JSON `null` element SHALL be rejected at write time with HTTP 400 (`VALIDATION_ERROR`), and the error message SHALL name the offending 0-based index within `additionalRequests`. A null element SHALL NOT be silently dropped, coerced to an empty request definition, or allowed to reach persistence — a null in the middle of a chain would otherwise shift every later request's `request_index` away from the author's intent.

Status: **Implemented**

#### Scenario: Additional request round-trips all five fields
- **WHEN** a suite is created with an additional request carrying `name`, `endpointRef`, `requestTemplate`, `responseColumns` and `inputBindings`
- **THEN** `GET /api/v1/test-suites/{id}` SHALL return all five values unchanged

#### Scenario: Omitted per-request lists default to empty
- **WHEN** an additional request omits `responseColumns` and `inputBindings`
- **THEN** the persisted and returned definition SHALL carry `[]` for both, and the request SHALL execute with no bindings and extract no columns

#### Scenario: Name is optional
- **WHEN** an additional request omits `name`
- **THEN** the suite SHALL be accepted and the request's label SHALL be `null`

#### Scenario: Name over 255 characters is rejected
- **WHEN** an additional request has a `name` longer than 255 characters
- **THEN** the request SHALL be rejected with HTTP 400 (`VALIDATION_ERROR`)

#### Scenario: Null chain element is rejected
- **WHEN** a suite is created or updated with `additionalRequests: [ {...}, null ]`
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) with a message naming index `1`, and SHALL NOT persist the suite

#### Scenario: Null element is not silently dropped
- **WHEN** a suite is submitted with a null element between two valid additional requests
- **THEN** the system SHALL reject the write rather than persist a 2-element chain, so no request's `request_index` silently shifts

### Requirement: Request #0 is labellable via `requestName`

The suite SHALL accept an optional `requestName` (`String`, max 255, nullable) labelling request #0, persisted as a nullable `request_name VARCHAR(255)` column on `test_suites` and returned on the suite response and in the run snapshot. Without it request #0 would be the only unlabellable link in a chain and could not be targeted by label from a metric condition.

Status: **Implemented**

#### Scenario: requestName round-trips
- **WHEN** a suite is created with `requestName: "configure"`
- **THEN** `GET /api/v1/test-suites/{id}` SHALL return `requestName: "configure"` and a condition evaluating `request.name = "configure"` on a request-#0 row SHALL be true

#### Scenario: requestName is optional
- **WHEN** `requestName` is omitted
- **THEN** the suite SHALL be accepted, the column SHALL be `NULL`, and `request.name` SHALL be JSON null on request-#0 rows

### Requirement: Chain length is bounded

The number of entries in `additionalRequests` SHALL be capped by `RunnerValidationConstants.MAX_ADDITIONAL_REQUESTS` (value `10`), enforced as a `@Size` bound on the field. Exceeding it SHALL be rejected with HTTP 400 (`VALIDATION_ERROR`) and the suite SHALL NOT be persisted.

Status: **Implemented**

#### Scenario: Ten additional requests are accepted
- **WHEN** a suite is created with exactly 10 entries in `additionalRequests`
- **THEN** the suite SHALL be persisted (an 11-request chain)

#### Scenario: Eleven additional requests are rejected
- **WHEN** a suite is created with 11 entries in `additionalRequests`
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) and SHALL NOT persist the suite

### Requirement: Response columns share one flat suite-wide namespace

All response columns defined anywhere in a suite's chain — on the suite itself and on every additional request — SHALL form ONE flat namespace. Column names SHALL be globally unique across the whole chain; a name repeated in any two requests (or twice within one request) SHALL be rejected with HTTP 400 (`VALIDATION_ERROR`) naming the offending column. Column names SHALL NOT be prefixed, qualified or otherwise namespaced by request — a column named `answer` on request #1 is referenced as `answer` by metric bindings, as `response::answer` in the CSV export, and as `response::answer` in the `eval_summaries` detailed query schema, exactly as if it had been defined on request #0.

Status: **Implemented**

#### Scenario: Duplicate name across two requests is rejected
- **WHEN** a suite defines a response column `answer` on the suite and another `answer` on an additional request
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) identifying the duplicate column name, and SHALL NOT persist the suite

#### Scenario: Unique names across requests are accepted
- **WHEN** a suite defines `configId` on request #0 and `answer` on request #1
- **THEN** the suite SHALL be persisted and both names SHALL be usable unprefixed by metric bindings

#### Scenario: Reserved and syntax rules apply per column regardless of request
- **WHEN** an additional request defines a response column whose name collides with a JSONata built-in function name or the reserved `request`/`response` frame names, or whose `expression` is not valid JSONata
- **THEN** the system SHALL respond HTTP 400 with the same error semantics as for a suite-level response column

### Requirement: Response-column count cap is a suite-wide union cap

The maximum-50 response-column limit SHALL apply to the **union** of all response columns in the chain, not per request. The count `size(suite.responseColumns) + Σ size(additionalRequests[i].responseColumns)` SHALL NOT exceed `RunnerValidationConstants.MAX_RESPONSE_COLUMNS` (value `50`); exceeding it SHALL be rejected with HTTP 400 (`VALIDATION_ERROR`).

Status: **Implemented**

#### Scenario: Union at the cap is accepted
- **WHEN** request #0 declares 30 columns and two additional requests declare 10 each
- **THEN** the suite SHALL be persisted (union = 50)

#### Scenario: Union over the cap is rejected
- **WHEN** request #0 declares 30 columns and an additional request declares 21
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) reporting the union count and the limit

### Requirement: Frame bindings accumulate along the chain

The JSONata frame used to resolve a request's template SHALL carry every response-column value extracted earlier in the same test-case execution — both earlier turns of the same request and every turn of every earlier request. Request `i`'s turn 0 SHALL therefore evaluate with all columns from requests `0..i-1` bound by name (e.g. a column `configId` extracted by request #0 is reachable as `$configId` inside request #1's template). Same-name overwrite SHALL only ever occur across turns of a single request (a later turn's value replaces an earlier turn's), never across requests, because global name uniqueness makes cross-request collision impossible. A column that failed extraction SHALL bind JSONata's explicit-null sentinel, not an unbound variable, consistently with existing single-request behavior. Request #0's turn 0 SHALL evaluate with an empty frame.

Status: **Implemented**

#### Scenario: Second request reads the first request's column
- **WHEN** request #0 extracts a column `configId` and request #1's body expression references `$configId`
- **THEN** request #1's body SHALL resolve with that value bound

#### Scenario: Second request reads a multi-turn first request's last value
- **WHEN** request #0 is multi-turn with 3 turns each extracting `sessionId`, and request #1 references `$sessionId`
- **THEN** request #1 SHALL see turn 2's value (the last write wins within a request)

#### Scenario: Multi-turn second request still sees prior-request columns on every turn
- **WHEN** request #1 is multi-turn and references `$configId` extracted by request #0
- **THEN** every turn of request #1 SHALL resolve `$configId`, not only turn 0

#### Scenario: First request evaluates with an empty frame
- **WHEN** request #0's turn 0 template references a name no request has extracted yet
- **THEN** it SHALL resolve as JSONata undefined, exactly as today

#### Scenario: Failed extraction binds explicit null
- **WHEN** request #0's extraction of `answer` fails and request #1 references `$answer`
- **THEN** `$answer` SHALL be bound to JSONata's explicit-null sentinel, so `$append($answer, …)` has null-append rather than undefined-append semantics

### Requirement: Persisted extracted columns are the accumulated union

Each persisted result row's `extracted_columns` SHALL be the accumulated union of response-column values visible at that row — every column extracted by earlier requests, plus this request's turns up to and including this row's turn. Downstream consumers that read a single row (metric binding resolution, `response::<column>` export cells, `eval_summaries` grid) SHALL therefore find late-chain rows fully populated without joining across rows.

Status: **Implemented**

#### Scenario: Late-chain row carries earlier requests' columns
- **WHEN** request #0 extracts `configId` and request #1 extracts `answer`
- **THEN** request #1's row's `extracted_columns` SHALL contain both `configId` and `answer`

#### Scenario: Early-chain row carries only what was extracted by then
- **WHEN** request #0 extracts `configId` and request #1 extracts `answer`
- **THEN** request #0's row's `extracted_columns` SHALL contain `configId` and SHALL NOT contain `answer`

#### Scenario: Metric binding to a later column errors on earlier rows
- **WHEN** a TSMD binds a metric parameter to the response column `answer` (extracted by request #1) and no `condition` is set
- **THEN** request #0's row SHALL yield the existing unresolved-reference metric-error behavior, and the author's remedy SHALL be a `condition` pinning the metric to request #1

### Requirement: Multi-turn is detected per request

Turn count SHALL be decided independently for each request in the chain, from **that request's own** `inputBindings` against the dataset's `testCaseSchema`. A request whose bindings reference at least one field declared `perTurn: true` SHALL run `multiTurnData.length` turns; every other request SHALL run exactly once from the case's shared `data`. Any number of requests in a chain — none, some, or all — MAY be multi-turn. The total number of result rows for one test-case repetition SHALL be the sum over requests of that request's turn count.

Status: **Implemented**

#### Scenario: Only the second request is multi-turn
- **WHEN** request #0 binds no per-turn field, request #1 binds one, and the case has 3 turns
- **THEN** the chain SHALL produce 4 rows: one for request #0 (`turn_index`/`total_turns` at defaults) and three for request #1 (`turn_index` 0..2, `total_turns` 3)

#### Scenario: Both requests are multi-turn
- **WHEN** both requests bind a per-turn field and the case has 2 turns
- **THEN** the chain SHALL produce 4 rows — 2 turns per request — with each request's turns contiguous and in order

#### Scenario: No request is multi-turn
- **WHEN** neither request binds a per-turn field, even for a case with `multiTurnData` of length 3
- **THEN** the chain SHALL produce 2 rows, each built from the case's shared `data`, with turn columns at their defaults

### Requirement: Every chain call persists a result row and is scored

Each HTTP call in the chain SHALL persist exactly one `test_case_run_results` row, and Phase 2 SHALL produce exactly one `test_case_eval_summaries` row per result row — including for setup-style requests whose response is of no analytical interest. Metrics SHALL be evaluated on **all** rows by default. Per-request metric scoping SHALL be expressed by the author as a TSMD `condition` over the `request` namespace, not by any per-request metric configuration on the suite.

Status: **Implemented**

#### Scenario: Setup request produces a scored row
- **WHEN** a 2-request chain runs with one TSMD carrying no `condition`
- **THEN** two result rows and two eval-summary rows SHALL exist, and the metric SHALL have been evaluated for both

#### Scenario: Condition pins a metric to the last request
- **WHEN** a TSMD's `condition` is `request.last`
- **THEN** the metric SHALL run only on rows of the final request; earlier requests' rows SHALL carry no value for that metric and SHALL remain `SUCCESS`

### Requirement: Request dimension on analytics rows

`test_case_run_results` and `test_case_eval_summaries` SHALL each carry `request_index INTEGER NOT NULL DEFAULT 0` and `total_requests INTEGER NOT NULL DEFAULT 1`, identifying the row's position in the chain and the chain's length. Indices SHALL be contiguous `0..totalRequests-1`. The request dimension SHALL be orthogonal to the turn dimension: a row's identity within a test-case repetition is `(request_index, turn_index)`. The values SHALL be stamped **only when the chain length is greater than 1**; a single-request chain SHALL leave both at their builder/DB defaults, so its rows are byte-identical to rows written before this capability existed. The MCP execution path SHALL never stamp them.

Status: **Implemented**

#### Scenario: Chain rows carry contiguous request indices
- **WHEN** a 3-request chain runs
- **THEN** its rows SHALL carry `request_index` 0, 1, 2 with `total_requests = 3` on every row

#### Scenario: Single-request suite leaves the defaults
- **WHEN** a suite with empty `additionalRequests` runs
- **THEN** every row SHALL have `request_index = 0` and `total_requests = 1` from the defaults, with no explicit stamping

#### Scenario: Request and turn dimensions are independent
- **WHEN** a chain's request #1 is multi-turn with 2 turns
- **THEN** its rows SHALL be `(request_index=1, turn_index=0)` and `(request_index=1, turn_index=1)`, with `total_requests` and `total_turns` both populated

#### Scenario: MCP rows never stamp request columns
- **WHEN** an `MCP_TOOL` suite runs
- **THEN** its row SHALL carry `request_index = 0` and `total_requests = 1`

### Requirement: Fail-fast across the whole chain

A failed call SHALL abort the remaining turns of its own request **and** every remaining request in the chain. Rows already produced (earlier requests, earlier turns) SHALL persist unchanged; the failing call SHALL persist as `ERROR`, `FAILED` or `TIMEOUT` per the existing status mapping. No rollback and no compensating call SHALL be attempted. A request-body evaluation failure on any request SHALL be a run-time `REQUEST_BODY_EVALUATION_ERROR` row plus chain abort, never a suite-validation failure.

Status: **Implemented**

#### Scenario: Failure in the first request skips the second
- **WHEN** request #0 fails with a 500
- **THEN** exactly one row SHALL be persisted for that repetition — request #0's error row — and request #1 SHALL NOT be invoked

#### Scenario: Failure mid-way through a multi-turn request aborts the chain
- **WHEN** request #1 is multi-turn with 3 turns and turn 1 times out
- **THEN** rows SHALL exist for request #0, request #1 turn 0 and request #1 turn 1 (the timeout row); turn 2 and any later request SHALL NOT be invoked

#### Scenario: Body evaluation failure aborts the chain
- **WHEN** request #1's JSONata body evaluation throws at run time
- **THEN** an error row SHALL be persisted for request #1 and no later request SHALL be invoked

### Requirement: MCP suites reject additional requests

A suite with `suiteType = MCP_TOOL` and a non-empty `additionalRequests` SHALL be rejected with HTTP 400 (`VALIDATION_ERROR`) at create and update, and SHALL NOT be persisted. `MCP_TOOL` suites with empty or absent `additionalRequests` SHALL be unaffected. The persistence and DTO model SHALL nonetheless be shape-complete for MCP so that MCP chaining (which requires a JSONata-capable argument template) can be enabled additively by a follow-up change.

Status: **Implemented**

#### Scenario: MCP suite with additional requests is rejected
- **WHEN** a suite is created with `suiteType: "MCP_TOOL"` and one entry in `additionalRequests`
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) and SHALL NOT persist the suite

#### Scenario: MCP suite without additional requests is unaffected
- **WHEN** an `MCP_TOOL` suite is created with `additionalRequests` omitted
- **THEN** it SHALL be persisted and executed exactly as before this capability existed

#### Scenario: Updating an MCP suite to add a chain is rejected
- **WHEN** `PUT /api/v1/test-suites/{id}` sets a non-empty `additionalRequests` on an existing `MCP_TOOL` suite
- **THEN** the system SHALL respond HTTP 400 and the stored suite SHALL be unchanged

### Requirement: Chain execution is owned by a dedicated runner component

Chain orchestration SHALL live in the `evaluation-runner-core` module as `com.epam.aidial.evaluation.runner.job.RequestChainExecutor`, so a standalone runner shares exact chain semantics with the EF backend. It SHALL build the ordered chain from the run snapshot, thread the accumulated frame through it, delegate each request to the existing turn-loop executor, stamp the request dimension, and stop on the first aborting request. `EvaluationWorker`'s `DEPLOYMENT` branch SHALL delegate to it; the `MCP_TOOL` branch SHALL be unchanged. The turn-loop executor SHALL be generalized to take a per-request specification and return both its rows and the frame it ended with; its internals (turn planning, per-turn resolution, extraction, streaming, error mapping) SHALL be otherwise unchanged. New classes SHALL satisfy the module's boundary contract (no JDBC/jOOQ/Flyway, no dependency on the EF backend, `@LogExecution` on every Spring component).

Status: **Implemented**

#### Scenario: Chain executor performs no database access
- **WHEN** the module's boundary test runs
- **THEN** `RequestChainExecutor` and its collaborators SHALL contain no JDBC/jOOQ/Flyway dependency and SHALL carry `@LogExecution`

#### Scenario: MCP path is untouched
- **WHEN** an `MCP_TOOL` suite executes
- **THEN** the chain executor SHALL NOT be involved and the single MCP row SHALL be produced by the existing path

### Requirement: Clone and snapshot preserve the chain

Cloning a suite SHALL copy `additionalRequests` and `requestName` to the clone, rewriting suite-scoped file references inside the `additional_requests` JSONB from `@ef/suites/{sourceId}/` to `@ef/suites/{newId}/` with the same mechanism already applied to `request_template`, `input_bindings` and `argument_template`. The run snapshot SHALL freeze the whole chain so a run is isolated from later suite edits.

A clone SHALL be subject to the same chain-wide hard validation as a create or update, evaluated against the **effective post-override** suite — the inherited chain combined with whatever the clone request overrides. In particular the global response-column name uniqueness rule and the suite-wide union cap SHALL be enforced across the clone's own `responseColumns` override and the inherited `additionalRequests`, so a clone can never be persisted in a state that a `PUT` of the same content would reject. See the `test-suite-clone` capability for the full rule.

Status: **Implemented**

#### Scenario: Cloned chain has rewritten file refs
- **WHEN** a suite whose additional request's template references `@ef/suites/{sourceId}/doc.pdf` is cloned
- **THEN** the clone's `additional_requests` SHALL reference `@ef/suites/{newId}/doc.pdf`

#### Scenario: Cloned chain preserves order and labels
- **WHEN** a suite with a 3-request chain is cloned
- **THEN** the clone SHALL have the same `requestName` and the same `additionalRequests` in the same order

#### Scenario: Clone override colliding with an inherited chain column is rejected
- **WHEN** a clone request overrides `responseColumns` with a column named `answer` and the source suite's inherited `additionalRequests[0]` already declares `answer`
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) and SHALL NOT create the clone

#### Scenario: Clone whose effective union exceeds the cap is rejected
- **WHEN** a clone's `responseColumns` override plus the inherited additional requests' columns exceed the suite-wide union cap
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) and SHALL NOT create the clone

### Requirement: The `$_metrics` frame accumulates along the request chain
Alongside the existing response-column frame, the JSONata frame used to resolve a request's template SHALL also carry a second accumulator, `$_metrics`, holding the output of every TSMD evaluated inline so far in the same test-case execution (see `metric-evaluation` for when a run is inline and `request-template` for the frame shape). `RequestExecutionResult` SHALL carry this accumulator as a fourth component, and `RequestChainExecutor` SHALL thread it from one request to the next exactly as it threads the response-column accumulator today. Within a single row, sibling TSMDs (multiple TSMDs both evaluated on the same row) are **not** ordered relative to each other — they are dispatched without an `ORDER BY` — so a TSMD's `Expression` binding can only reliably read a TSMD output produced on an **earlier request or turn**, never a same-row sibling. Accumulation is monotonic and last-writer-wins per `(tsmdName, outputField)`: a TSMD without a `condition` runs on every row and therefore overwrites its own prior entry on every turn; an author wanting a single, stable producer pins it with a `condition` (e.g. `request.index = 0`). This accumulator is populated only for inline runs; a non-inline run's frame carries no `$_metrics` key at all.

Status: **Implemented**

#### Scenario: Second request reads the first request's metric output
- **WHEN** request #0 has an inline-evaluated TSMD named `judge` and request #1's body expression references `` $_metrics.`judge`.score.value ``
- **THEN** request #1's body SHALL resolve with that value bound, exactly as `$configId` resolves for an accumulated response column

#### Scenario: Same-row sibling TSMDs are unordered
- **WHEN** two TSMDs, `judge` and `scorer`, are both evaluated inline on request #0's same row, and `scorer`'s `Expression` binding references `` $_metrics.`judge`.score.value ``
- **THEN** whether `judge`'s output is visible to `scorer`'s evaluation on that same row is unspecified — an author needing this ordering MUST split `judge` and `scorer` across different requests or turns

#### Scenario: Last-writer-wins across turns of a multi-turn request
- **WHEN** an inline-evaluated TSMD without a `condition` runs on every turn of a 3-turn multi-turn request, producing a different score each turn
- **THEN** the next request's `$_metrics` reference to that TSMD's output SHALL see turn 2's (the last turn's) value

#### Scenario: Non-inline run carries no $_metrics accumulator
- **WHEN** a run is non-inline
- **THEN** `RequestExecutionResult`'s `accumulatedMetrics` component SHALL remain empty throughout the chain, and no request's frame SHALL carry a `$_metrics` binding

## Implementation Notes

- New DTO: `com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto` (single copy in the shared module — `TestSuiteRequestDto` already references `RequestTemplateDto`, `InputBindingDto`, `ResponseColumnDefinitionDto` and `EndpointContractDto` from `runner.dto`).
- New runner classes: `runner.job.RequestChainExecutor`, plus the `RequestExecutionSpec` / `RequestExecutionResult` carriers used to generalize `runner.job.TurnLoopExecutor`. `RequestExecutionResult`'s fourth component, `accumulatedMetrics`, carries the `$_metrics` frame (see `runner.job.InlineMetricRequest`, `runner.job.TurnLoopExecutor`).
- New constants: `RunnerValidationConstants.MAX_ADDITIONAL_REQUESTS = 10`, `RunnerValidationConstants.MAX_RESPONSE_COLUMNS = 50` (extracted from the current hardcoded `@Size(max = 50)` literal on `TestSuiteRequestDto.responseColumns`).
- New shared component: a `ResponseColumnUnionResolver` `@Component` in `service.domain` producing the suite-wide union from a `TestSuiteRequestDto`, a `TestSuite` entity and a `SuiteSnapshotDto` — consumed by `TestSuiteRequestValidator`, `TestSuiteService.isResponseColumnsChanged`, `MetricDefinitionValidationService`, `EvalSummariesSchemaProvider` and `EvalSummaryExportColumnPlanner`.
- Meta migration `V1.29__AddAdditionalRequestsToTestSuites.sql`; analytics migrations `V1.17__AddRequestColumnsToTestCaseRunResults.sql` and `V1.18__AddRequestColumnsToEvalSummaries.sql`, followed by `./gradlew generateJooq`.
- Related specs: `test-suites`, `request-template`, `response-columns`, `multi-turn-test-case`, `suite-run-snapshot`, `conditional-metric-execution`, `analytics-eval-results`, `metrics-storage`, `eval-summary-export`, `run-comparison-metric-scores`, `query-schema-discovery`, `tsmd-validation`.
