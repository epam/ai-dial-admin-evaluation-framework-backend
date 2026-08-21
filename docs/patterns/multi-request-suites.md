# Multi-request suites — request chain, accumulated frame, per-request turn detection

A suite may execute more than one request per test-case repetition against its single `deploymentRef`.

## The chain

The suite's own `endpointRef`/`requestTemplate`/`responseColumns`/`inputBindings` are **request #0**; `additionalRequests` (`List<RequestDefinitionDto>`, capped at `RunnerValidationConstants.MAX_ADDITIONAL_REQUESTS` = 10) supplies **requests 1..N**, executed strictly sequentially after it.

Both request #0 (via optional suite-level `requestName`) and every additional request (via `RequestDefinitionDto.name`) are independently labellable.

## One flat response-column namespace

Response columns across the **whole chain** form **one flat, globally-unique namespace** (no `response::<request>::` prefixing anywhere) capped at `RunnerValidationConstants.MAX_RESPONSE_COLUMNS` (50, the same constant the pre-existing single-request cap was extracted into) as a **suite-wide union**; a name repeated in any two requests → 400.

`ResponseColumnUnionResolver` (`service.domain`) is the single source of that union, consumed by the validator, `TestSuiteService.isResponseColumnsChanged`, `MetricDefinitionValidationService`, `EvalSummariesSchemaProvider` and `EvalSummaryExportColumnPlanner` — never re-derived ad hoc.

## Accumulated JSONata frame

The JSONata frame **accumulates monotonically along the chain**: request `i`'s turn 0 sees every column extracted by requests `0..i-1` bound by name (e.g. `$configId`), and each persisted row's `extracted_columns` is the **accumulated union** at that point, not just that request's own columns.

## Turns are per-request, orthogonal to chain position

Turn count is decided **per request**, from that request's own `inputBindings` (`PerTurnBindingDetector`) — any subset of a chain's requests may be multi-turn, none, some, or all. See [multi-turn test cases](multi-turn-test-cases.md).

A row's identity within one test-case repetition is therefore the pair **`(request_index, turn_index)`** — orthogonal dimensions, both persisted on `test_case_run_results`/`test_case_eval_summaries` (`request_index`/`total_requests`, stamped only when chain length > 1 so a single-request suite's rows stay byte-identical to today's).

## Conditional metrics gain a `request` namespace

Conditional-metric execution gains a `request` namespace mirroring `turn`: `request: {index, total, last, name}` (`name` is JSON null when the request is unlabelled), letting a TSMD `condition` pin a metric to one chain position, e.g. `request.last`.

## Failure semantics and guards

Chain execution is **fail-fast**: a failing call aborts its own request's remaining turns **and** every later request; rows already produced persist unchanged.

`additionalRequests` non-empty on an `MCP_TOOL` suite is rejected at write time (400) — MCP chaining is model-ready but deferred to a follow-up change.

See [multi-request-suite spec](../../openspec/specs/multi-request-suite/spec.md).
