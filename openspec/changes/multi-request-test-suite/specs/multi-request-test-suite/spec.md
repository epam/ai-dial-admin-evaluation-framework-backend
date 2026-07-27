## ADDED Requirements

### Requirement: Multi-request is an ordered chain declared by additionalRequests
A test suite SHALL support an optional ordered `additionalRequests` array. A suite is **multi-request** when `additionalRequests` is non-empty and **single-request** when it is absent or empty. Each element SHALL be a complete request spec carrying `type`, `label`, `endpointRef`, `requestTemplate`, `inputBindings`, and `responseColumns`. `deploymentRef` SHALL remain suite-level: every request in the chain targets the same deployment. There SHALL be no separate multi-request flag — the chain's presence is the discriminator.
Status: **Planned**

#### Scenario: Non-empty additionalRequests makes the suite multi-request
- **WHEN** a suite is saved with two elements in `additionalRequests`
- **THEN** the suite is multi-request and its chain has three requests: request 0 from the flat `endpointRef`/`requestTemplate`/`inputBindings`/`responseColumns` fields, then the two array elements in order

#### Scenario: Absent additionalRequests leaves behavior unchanged
- **WHEN** a suite is saved with `additionalRequests` absent, null, or `[]`
- **THEN** the suite is single-request and executes exactly as before this capability existed

#### Scenario: Chain requests share one deployment
- **WHEN** a multi-request suite is saved
- **THEN** all chain requests resolve against the suite-level `deploymentRef`, and no chain element carries its own deployment reference

### Requirement: Chain is normalized to a uniform request list
The system SHALL normalize a suite's configuration into a uniform ordered list of request specs of size N, where element 0 is synthesized from the suite's flat fields and elements `1..N-1` are the `additionalRequests` entries in order. This normalization SHALL be the single definition of "the chain" and SHALL be applied both to a live suite and to a frozen run snapshot. Validation, execution, `request_index` assignment, the chain-union response-column set, export column planning, and query-schema discovery SHALL all operate on this normalized list rather than branching between the flat fields and the array.
Status: **Planned**

#### Scenario: Single-request suite normalizes to a one-element chain
- **WHEN** a single-request suite is normalized
- **THEN** the result is a one-element list whose element 0 carries the suite's flat `endpointRef`, `requestTemplate`, `inputBindings`, and `responseColumns`

#### Scenario: Normalized indices are contiguous and zero-based
- **WHEN** a suite with three `additionalRequests` entries is normalized
- **THEN** the result has four elements at indices `0..3`, with index 0 being the flat request and indices `1..3` preserving the array order

#### Scenario: Snapshot normalizes identically to the live suite
- **WHEN** a frozen snapshot is normalized
- **THEN** it yields the same chain shape as normalizing the live suite that produced it, so execution and export planning see one consistent representation

### Requirement: Response column names are unique across the whole chain
Response column names SHALL be unique across every request in the chain, not merely within one request's `responseColumns`. A suite whose chain declares the same response column name on two different requests SHALL be rejected with HTTP 400 `VALIDATION_ERROR`, and the message SHALL identify the duplicated name. Because names are chain-unique, a response column is owned by exactly one request and SHALL be referenced by bare name everywhere — metric bindings, result-row `extractedColumns` keys, export headers, and query-DSL field names require no request qualification.
Status: **Planned**

#### Scenario: Duplicate response column name across requests is rejected
- **WHEN** a suite is saved whose request 0 declares response column `answer` and whose request 2 also declares `answer`
- **THEN** the request is rejected with HTTP 400 `VALIDATION_ERROR` and the message names the duplicated column

#### Scenario: Distinct names across requests are accepted
- **WHEN** request 0 declares `session_id` and request 1 declares `answer`
- **THEN** the suite is accepted and both names are addressable without qualification

#### Scenario: Uniqueness within a single request is still enforced
- **WHEN** one chain element declares `answer` twice in its own `responseColumns`
- **THEN** the request is rejected with HTTP 400 `VALIDATION_ERROR`

### Requirement: Request labels are optional, defaulted, and unique
Each chain element MAY carry a `label`, and the suite MAY carry a top-level `requestLabel` naming request 0. Both are optional. During normalization, any absent label SHALL be defaulted to `request-{n}` where `n` is the 1-based request position. Label uniqueness SHALL be validated on the **resolved** label set — after defaulting — and a duplicate SHALL be rejected with HTTP 400 `VALIDATION_ERROR`. Every normalized request therefore has exactly one non-null, unique label.
Status: **Planned**

#### Scenario: Absent labels are defaulted
- **WHEN** a three-request chain is saved with no labels anywhere
- **THEN** the normalized requests carry labels `request-1`, `request-2`, and `request-3`

#### Scenario: Duplicate explicit labels are rejected
- **WHEN** two chain elements both declare `label: "invoke"`
- **THEN** the request is rejected with HTTP 400 `VALIDATION_ERROR`

#### Scenario: Explicit label colliding with a default is rejected
- **WHEN** request 3 declares `label: "request-2"` and request 2 has no label (defaulting to `request-2`)
- **THEN** the resolved label set contains a duplicate and the request is rejected with HTTP 400 `VALIDATION_ERROR`

#### Scenario: Existing suites remain valid
- **WHEN** a suite saved before this capability existed is read
- **THEN** its single request resolves to label `request-1` and the suite stays valid

### Requirement: Later requests may bind template variables to earlier responses
Request-level `inputBindings` SHALL support a third binding source, `responseField`, alongside the existing `dataField` and `constantValue`. Exactly one of the three SHALL be set per binding. A `responseField` SHALL name a response column declared by a **strictly earlier** request in the chain. At write time the system SHALL reject with HTTP 400 `VALIDATION_ERROR` a `responseField` that names a column declared by the same request, by a later request, or by no request at all.
Status: **Planned**

#### Scenario: Backward reference is accepted
- **WHEN** request 0 declares response column `session_id` and request 1 binds a template variable to `responseField: "session_id"`
- **THEN** the suite is accepted

#### Scenario: Forward reference is rejected
- **WHEN** request 0 binds to `responseField: "answer"` and `answer` is declared by request 2
- **THEN** the request is rejected with HTTP 400 `VALIDATION_ERROR`, because sequential execution cannot satisfy it

#### Scenario: Self reference is rejected
- **WHEN** request 1 binds to a `responseField` naming a column that request 1 itself declares
- **THEN** the request is rejected with HTTP 400 `VALIDATION_ERROR`

#### Scenario: Unknown column is rejected
- **WHEN** a binding names a `responseField` that no request in the chain declares
- **THEN** the request is rejected with HTTP 400 `VALIDATION_ERROR`

#### Scenario: Exactly one source per binding
- **WHEN** a binding sets both `dataField` and `responseField`, or sets none of the three
- **THEN** the request is rejected with HTTP 400 `VALIDATION_ERROR`

### Requirement: Chain executes sequentially against an accumulating response-column map
A multi-request test-case run SHALL execute its requests strictly in chain order as one unit. The engine SHALL maintain an accumulating map of response columns extracted so far, seeded empty, and after each request SHALL merge that request's extracted columns into it. A `responseField` binding SHALL resolve against this accumulated map. There SHALL be **no** conversational message-history accumulation between chain requests: each request's body is resolved independently from its own template and bindings.
Status: **Planned**

#### Scenario: Later request consumes an earlier request's extracted value
- **WHEN** request 0 extracts `session_id = "abc"` and request 1 binds a template variable to `responseField: "session_id"`
- **THEN** request 1's resolved request carries the value `abc`

#### Scenario: A request may reference any earlier request, not only its predecessor
- **WHEN** request 0 extracts `session_id` and request 3 binds `responseField: "session_id"`
- **THEN** request 3 resolves it from the accumulated map, even though requests 1 and 2 ran in between

#### Scenario: No message history is carried between chain requests
- **WHEN** a chain of two requests executes and both bodies contain a `messages` array
- **THEN** request 1's body contains only its own resolved messages; request 0's messages and any assistant reply are NOT prepended

#### Scenario: Requests run under one concurrency permit
- **WHEN** a multi-request test case executes
- **THEN** its requests run strictly in order under a single concurrency permit, and run progress advances by one unit for the whole test-case run

### Requirement: Unresolvable responseField falls back to the placeholder default
When a `responseField` cannot be resolved at run time — the producing request succeeded but its extraction yielded no value for that column — the system SHALL use the template placeholder's declared default if the placeholder declares one (`${{var|type:default}}`). When no default is declared, the request SHALL be treated as failed, persisting one ERROR row and aborting the chain.
Status: **Planned**

#### Scenario: Declared default is applied
- **WHEN** request 0 returns 200 but extraction produces no `session_id`, and request 1's placeholder declares a default
- **THEN** the default is substituted and request 1 is sent

#### Scenario: Missing value with no default fails the request
- **WHEN** the same situation occurs but the placeholder declares no default
- **THEN** request 1 persists one ERROR row, the chain aborts, and later requests produce no rows

### Requirement: Chain execution is fail-fast
When a request in the chain fails — non-2xx after retries, timeout or network error, oversized response, or an unresolvable dependency with no declared default — the test-case run SHALL stop. Earlier requests MUST persist as SUCCESS rows, the failing request MUST persist as exactly one ERROR row, and later requests MUST NOT be sent. A failing chain SHALL NOT fail the run: other test cases continue.
Status: **Planned**

#### Scenario: Failure at request k
- **WHEN** request `k` of N fails
- **THEN** requests `0..k-1` persist as SUCCESS rows, request `k` persists as one ERROR row, and requests `k+1..N-1` produce no rows

#### Scenario: Other test cases are unaffected
- **WHEN** one test case's chain aborts
- **THEN** remaining test cases continue executing and the run does not fail

### Requirement: One result row per chain request
Each executed chain request SHALL persist its own result row carrying `request_index` (0-based, contiguous) and `request_label` (the resolved label). Rows of one test-case run SHALL share `test_case_id`, `test_case_name`, and `run_index`. Each row's `extracted_columns` SHALL contain **only that request's own** response columns, not the accumulated set. Because multi-request excludes multi-turn, every multi-request row SHALL carry `turn_index = 0` and `total_turns = 1`.
Status: **Planned**

#### Scenario: Chain of three yields three rows
- **WHEN** a three-request chain executes successfully for one test case
- **THEN** three result rows persist with `request_index` `0`, `1`, `2`, each carrying its resolved `request_label`

#### Scenario: Extracted columns are request-local
- **WHEN** request 0 extracts `session_id` and request 1 extracts `answer`
- **THEN** request 0's row has `extracted_columns` containing only `session_id`, and request 1's row contains only `answer`

#### Scenario: Rows carry per-request status and timing
- **WHEN** a chain executes
- **THEN** each row carries its own `response_status_code`, `exec_started_at_ms`, `exec_completed_at_ms`, `exec_duration_ms`, and `retry_count`

#### Scenario: Turn columns are inert for multi-request rows
- **WHEN** a multi-request row is written
- **THEN** it carries `turn_index = 0` and `total_turns = 1`

### Requirement: Metrics target requests through the existing condition mechanism
The metric list SHALL remain flat: a Test Suite Metric Definition is not scoped to a request. Targeting SHALL reuse the existing per-metric `condition`, evaluated per result row. A metric with no condition SHALL run on **every** request's row. The system SHALL NOT introduce a per-metric request field or any second targeting mechanism.
Status: **Planned**

#### Scenario: Condition targets one request by label
- **WHEN** a metric's condition is `request.label = "invoke"`
- **THEN** the metric runs only on rows produced by the request labeled `invoke` and is omitted on all other rows

#### Scenario: Condition targets one request by index
- **WHEN** a metric's condition is `request.index = 2`
- **THEN** the metric runs only on rows whose `request_index` is 2

#### Scenario: Unconditioned metric runs on every request row
- **WHEN** a metric has no condition and a three-request chain produces three SUCCESS rows
- **THEN** the metric is dispatched on all three rows

#### Scenario: Correctly conditioned plumbing rows stay SUCCESS
- **WHEN** every metric's condition excludes a given plumbing request's row
- **THEN** no metric is dispatched for that row and its eval summary is `SUCCESS`

### Requirement: A metric whose response binding is absent on a row fails that row
When a metric's binding names a response column that is not present in the row's `extracted_columns`, binding resolution SHALL fail before the metric provider is invoked, the metric SHALL be recorded as a failure, and the row's eval summary status SHALL be `FAILED`. This failure is the intended signal that the metric needs a `condition` targeting the request that produces the column. No metric provider call SHALL be made in this case.
Status: **Planned**

#### Scenario: Unconditioned response-bound metric fails a plumbing row
- **WHEN** a metric binds an input to response column `answer` (produced only by request 1), has no condition, and is evaluated on request 0's row
- **THEN** binding resolution fails, the eval summary for request 0's row is `FAILED`, and no metric provider request is issued

#### Scenario: Adding a condition resolves the failure
- **WHEN** the same metric is given the condition `request.label = "invoke"`
- **THEN** it is omitted on request 0's row, that row's summary is `SUCCESS`, and the metric runs only on request 1's row

### Requirement: Chain length is capped and enforced at save and at run creation
The maximum number of requests in a chain SHALL be configurable. A suite whose chain exceeds the cap SHALL be rejected at save with HTTP 400 `VALIDATION_ERROR`, and the message SHALL include both the chain length and the cap. Because the cap is configurable and can be lowered after a suite is persisted, run creation SHALL re-check it and reject an over-cap suite with HTTP 409 `INVALID_OPERATION`.
Status: **Planned**

#### Scenario: Over-cap suite is rejected at save
- **WHEN** a suite is saved whose chain length exceeds the configured maximum
- **THEN** the request is rejected with HTTP 400 `VALIDATION_ERROR` naming the length and the cap

#### Scenario: Lowered cap is enforced at run creation
- **WHEN** a suite was saved while the cap permitted its chain length, the cap is subsequently lowered below that length, and a run is created
- **THEN** run creation is rejected with HTTP 409 `INVALID_OPERATION`; no run record is persisted and no async job is dispatched

#### Scenario: At-cap chain is accepted
- **WHEN** a chain's length exactly equals the cap
- **THEN** the suite saves successfully and runs can be created

### Requirement: Multi-request suites reject multi-turn datasets
Multi-request and multi-turn SHALL NOT be combined. A run creation for a multi-request suite bound to a dataset containing at least one multi-turn test case SHALL be rejected with HTTP 409 `INVALID_OPERATION`. The check SHALL occur at run creation rather than suite save, because dataset content is mutable and suite validity is configuration-only.
Status: **Planned**

#### Scenario: Multi-request suite over a multi-turn dataset is rejected at run creation
- **WHEN** a run is created for a multi-request suite whose bound dataset contains any test case with `multi_turn_data`
- **THEN** the request is rejected with HTTP 409 `INVALID_OPERATION`; no run record is persisted and no async job is dispatched

#### Scenario: Single-request suite over a multi-turn dataset is unaffected
- **WHEN** a run is created for a single-request suite whose dataset contains multi-turn cases
- **THEN** the run proceeds and multi-turn execution behaves exactly as before

#### Scenario: Multi-request suite over a single-turn dataset is accepted
- **WHEN** a run is created for a multi-request suite whose dataset contains no multi-turn cases
- **THEN** the run proceeds

### Requirement: Chain step execution is registry-dispatched with MCP unimplemented
A chain element SHALL declare a `type` discriminator with values `HTTP` and `MCP_TOOL`, and step execution SHALL be dispatched through a registry of step executors keyed by that type. Only the `HTTP` implementation SHALL be functional. An `MCP_TOOL`-typed chain element SHALL be rejected at suite save with HTTP 400 `VALIDATION_ERROR`, and the `MCP_TOOL` step executor SHALL throw `UnsupportedOperationException` as an unreachable-by-construction backstop. The existing single-request MCP execution path SHALL remain unchanged and SHALL NOT be routed through the registry.
Status: **Planned**

#### Scenario: MCP-typed chain element is rejected at save
- **WHEN** a suite is saved with a chain element whose `type` is `MCP_TOOL`
- **THEN** the request is rejected with HTTP 400 `VALIDATION_ERROR` indicating MCP chaining is not supported

#### Scenario: HTTP chain element executes
- **WHEN** a chain element's `type` is `HTTP` (or is absent and defaults to `HTTP`)
- **THEN** the HTTP step executor runs it

#### Scenario: Existing MCP suites are unaffected
- **WHEN** a single-request `MCP_TOOL` suite runs
- **THEN** it executes through the existing MCP path with unchanged behavior

### Requirement: Each chain request validates against its own endpoint contract
Each chain element SHALL carry its own `endpointRef`, and suite validation SHALL validate each element's `requestTemplate` and `inputBindings` against **that element's** endpoint contract — its method, relative URL pattern, parameters, and request body schema. Validation warnings SHALL identify which request they originate from via a request index, so an author can attribute a warning to a specific chain element.
Status: **Planned**

#### Scenario: Each request validates against its own schema
- **WHEN** request 0 targets `POST /session` and request 1 targets `POST /chat/completions` with different body schemas
- **THEN** each request's template is validated against its own `endpointRef`, and a body valid for its own endpoint produces no warning

#### Scenario: Warnings are attributed to a request
- **WHEN** request 2's template references a template variable with no binding
- **THEN** the resulting validation warning identifies request index 2

#### Scenario: Methods may differ across the chain
- **WHEN** request 0 declares method `POST` and request 3 declares method `DELETE`
- **THEN** each request is issued with its own method

### Requirement: Result rows are returned in arbitrary order within a run
Result-row and eval-summary listing SHALL NOT guarantee ordering within a run. Clients that need chain order MUST sort by `(runIndex, requestIndex, turnIndex)`. This constraint SHALL be documented on the listing endpoints.
Status: **Planned**

#### Scenario: Clients must sort for chain order
- **WHEN** a client lists result rows for a run containing multi-request test cases
- **THEN** rows may arrive in any order, and chain order is obtained only by sorting on `(runIndex, requestIndex, turnIndex)`

## Implementation notes

Planned components:
- Chain normalizer in `service.domain` — single definition of the chain, consumed by suite validation, `SuiteSnapshotBuilder`, the chain executor, `EvalSummaryExportColumnPlanner`, and `EvalSummariesSchemaProvider`. Also exposes the chain-union response-column set.
- `ChainStepExecutor` SPI plus registry in `service.domain.job`, with `HttpChainStepExecutor` (real) and `McpChainStepExecutor` (stub).
- Chain executor in `service.domain.job`, dispatched from `EvaluationWorker.execute` alongside the existing MCP and multi-turn branches.
- `InputBindingDto` gains `responseField`; `ResolvedRequestService` resolves it from the accumulated column map.
- `ConditionContext` / `ConditionExpressionEvaluator` gain the `request` namespace.
- Run-creation guards in `TestSuiteRunService`; cap property `test-suite.multi-request.max-requests`.
