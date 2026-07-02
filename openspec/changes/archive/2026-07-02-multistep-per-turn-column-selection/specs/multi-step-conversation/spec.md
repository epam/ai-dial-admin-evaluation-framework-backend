## MODIFIED Requirements

### Requirement: Fail-fast on step failure
If any step fails after retries — a non-2xx final status, a timeout/network error, an oversized (truncated) response, or an unextractable assistant reply — the engine SHALL stop the conversation at that step and SHALL NOT send subsequent steps. The persisted result SHALL reflect partial progress.
Status: **Planned**

#### Scenario: Failure at step k stops remaining steps
- **WHEN** step `k` of an `N`-step conversation fails after exhausting retries
- **THEN** steps `k+1 .. N-1` SHALL NOT be sent
- **AND** the result `executionStatus` SHALL be the failing step's status
- **AND** `responseStatusCode` SHALL be the failing step's status code
- **AND** `responseBody` SHALL contain the failing turn's raw response body (or be absent when no response was received)
- **AND** `extractedColumns` SHALL be a column-major object whose per-column arrays each contain one element per step completed before the failure

#### Scenario: Failure at step 0 yields an empty extractedColumns object
- **WHEN** a conversation fails at step 0 (before any step completes)
- **THEN** `extractedColumns` SHALL be an empty JSON object `{}`
- **AND** metric bindings SHALL resolve against that empty object with no normalization step

### Requirement: Multi-step result shape reuses existing columns
A multi-step run SHALL persist exactly one `TestCaseRunResult` per `(runId, testCaseId, runIndex)`, reusing existing columns: `responseBody` SHALL hold the last attempted turn's raw response body (preserving its technical fields, e.g. `id`/`usage`/`model`) — mirroring `requestBody`, which holds that turn's raw request — and `extractedColumns` SHALL hold a **column-major JSON object** mapping each response column name to an array of that column's per-step extracted values (one element per completed step, each element type-reconciled per step). The full conversation remains recoverable from the last request body (which carries the whole message history through the final user turn) plus the final response. Single-step runs SHALL keep the existing object-of-scalars shape for `extractedColumns`. The `multiStep` flag is the indicator readers use to interpret the shape.
Status: **Planned**

#### Scenario: Multi-step extractedColumns is a column-major object
- **WHEN** a 3-step conversation with response columns `answer` and `score` completes successfully
- **THEN** `extractedColumns` SHALL be a JSON object
- **AND** `extractedColumns.answer` SHALL be an array of length 3 whose element `i` is `answer` extracted at step `i`
- **AND** `extractedColumns.score` SHALL be an array of length 3 whose element `i` is `score` extracted at step `i`

#### Scenario: Per-step extraction failure preserves index alignment
- **WHEN** a 3-step conversation completes but extraction of column `answer` fails at step 1
- **THEN** `extractedColumns.answer` SHALL be an array of length 3 whose element at index 1 is JSON `null`

#### Scenario: responseBody holds the last turn's raw response
- **WHEN** a multi-step conversation completes
- **THEN** `responseBody` SHALL be the last turn's raw response body, with its technical fields (e.g. `id`) preserved, and `requestBody` SHALL be that turn's raw request body

#### Scenario: Single-step result shape unchanged
- **WHEN** a suite has `multiStep == false`
- **THEN** `extractedColumns` SHALL be a JSON object of scalar values and `responseBody` SHALL be the single response, exactly as before

## REMOVED Requirements

### Requirement: Metric evaluation normalizes multi-step columns to the last step
**Reason**: Multi-step `extractedColumns` is now a column-major object of per-column arrays (not a row-major array of per-step maps), and metrics select any turn/element via an optional `jsonataExpression` on Response bindings rather than being implicitly collapsed to the last step. The last-step normalization (`ExtractedColumnsNormalizer` and its two call sites) is removed; metric bindings resolve against the raw stored `extractedColumns`, and `EvalSummary.extractedColumns` stores the full arrays.
**Migration**: To score a specific turn, set `jsonataExpression` on the Response binding (e.g. `$[-1]` for the last turn, `$[0]` for the first). See the `metric-evaluation` binding-resolution requirement.
