## MODIFIED Requirements

### Requirement: Multi-turn dispatch and per-turn result emission
The worker that executes one run input SHALL return a list of results. Dispatch SHALL select exactly one execution path per input: MCP inputs use the MCP path; an input carrying `multi_turn_data` is delegated to the multi-turn turn loop, which emits one result per executed turn; an input belonging to a **multi-request** suite (normalized chain size greater than one) is delegated to the chain loop, which emits one result per executed chain request; otherwise the existing single-request path is used and returns a single result. Multi-request and multi-turn are mutually exclusive by the run-creation guard, so the chain path never sees `multi_turn_data`. Each result carries `turn_index` and `total_turns` (single-turn = `0/1`) and `request_index` (single-request = `0`).
Status: **Planned**

#### Scenario: Multi-turn input yields per-turn results
- **WHEN** a run input has `multi_turn_data`
- **THEN** execution runs the turn loop and returns one `TestCaseRunResult` per executed turn

#### Scenario: Single-turn input is unchanged
- **WHEN** a run input has no `multi_turn_data`
- **THEN** the existing single-turn path runs and returns exactly one result with `turn_index=0, total_turns=1`

#### Scenario: Multi-request input yields per-request results
- **WHEN** the run's normalized chain has N > 1 requests
- **THEN** execution runs the chain loop and returns one `TestCaseRunResult` per executed chain request, with `request_index` `0..k` and `turn_index=0, total_turns=1`

#### Scenario: Single-request suite is unchanged
- **WHEN** the run's normalized chain has exactly one request
- **THEN** the existing single-request path runs and returns exactly one result with `request_index=0`

### Requirement: One concurrency permit per test-case run
The execution unit SHALL be the whole test-case run: turns of one multi-turn case, and requests of one multi-request chain, run sequentially under a single concurrency permit, and progress is counted one unit per test-case run regardless of how many rows it writes.
Status: **Planned**

#### Scenario: Progress counts test-case runs, not turns
- **WHEN** a multi-turn case writes N turn rows
- **THEN** run progress advances by one unit for that case, and the runnable-case count treats the multi-turn case as one unit

#### Scenario: Progress counts test-case runs, not chain requests
- **WHEN** a multi-request chain writes N request rows for one test case
- **THEN** run progress advances by one unit for that case, and the chain's requests run sequentially under one permit

### Requirement: Rate limiting
When `rateLimitRps` is configured, the executor SHALL limit the rate of outgoing HTTP calls to the specified requests-per-second across all concurrent workers in the same run, using Bucket4j token bucket algorithm. The token SHALL be acquired at the point each individual HTTP call is issued — **not** once per dispatched test-case run — so that a test case issuing several HTTP calls (multi-turn turns, multi-request chain requests, or retries) consumes one token per call. Acquisition SHALL be interruptible so that run cancellation is not delayed by a pending token wait.
Status: **Planned**

#### Scenario: No rate limit (default)
- **WHEN** `rateLimitRps` is null
- **THEN** the executor SHALL NOT apply rate limiting — calls are dispatched as fast as concurrency allows

#### Scenario: Rate limit applied via Bucket4j
- **WHEN** `rateLimitRps` is configured (e.g., 5.0)
- **THEN** the executor SHALL create a Bucket4j `Bucket` with `Bandwidth.builder().capacity(tokens).refillGreedy(tokens, Duration.ofSeconds(1)).build()` (Bucket4j 8.x API) matching the configured RPS, and workers SHALL call `bucket.asBlocking().consume(1)` before each HTTP call

#### Scenario: Rate limit interacts with concurrency
- **WHEN** `concurrencyLevel = 10` and `rateLimitRps = 2.0`
- **THEN** even though 10 workers are available, new calls SHALL be throttled to 2 per second (most workers will be idle waiting for rate limit tokens)

#### Scenario: Retries acquire rate limit tokens
- **WHEN** a worker retries a failed call and `rateLimitRps` is configured
- **THEN** the retry attempt SHALL acquire a rate limit token before making the HTTP call, same as a first attempt. This prevents retry storms from bypassing the rate limiter after a burst of failures (e.g., 429 responses).

#### Scenario: Multi-turn turns each acquire a token
- **WHEN** a multi-turn case executes N turns and `rateLimitRps` is configured
- **THEN** N tokens SHALL be consumed — one per turn's HTTP call — so the observed request rate at the deployment does not exceed the configured RPS

#### Scenario: Chain requests each acquire a token
- **WHEN** a multi-request chain of N requests executes and `rateLimitRps` is configured
- **THEN** N tokens SHALL be consumed — one per chain request's HTTP call

#### Scenario: Token wait does not block cancellation
- **WHEN** a run is cancelled while a worker is blocked waiting for a rate limit token
- **THEN** the wait SHALL be interrupted and the worker SHALL terminate without issuing the call

## ADDED Requirements

### Requirement: Chain loop with accumulating response columns and fail-fast
The chain loop SHALL execute a multi-request test case's requests strictly in chain order under one permit, maintaining an accumulating map of response columns extracted so far. For each request it SHALL resolve that request's template and bindings — resolving `responseField` bindings from the accumulated map — issue the HTTP call using that request's `endpointRef` method and resolved URL, extract that request's own response columns, merge them into the accumulated map, and persist one result row. The loop SHALL be fail-fast: the first failing request persists one ERROR row and aborts; later requests are not sent. A resolved dependency that is missing with no declared placeholder default SHALL be treated as a request failure.
Status: **Planned**

#### Scenario: Accumulated map feeds a later request
- **WHEN** request 0 extracts `session_id` and request 2 binds a template variable to it
- **THEN** request 2's resolved request carries request 0's extracted value

#### Scenario: Later extraction overwrites on name reuse
- **WHEN** the accumulated map already holds a column name and a later request extracts the same name
- **THEN** the later value replaces it in the map; this situation is unreachable through the API because chain-wide name uniqueness is enforced at save, and the behavior exists only as a defensive tiebreak

#### Scenario: Chain aborts on request failure
- **WHEN** request `k` returns a non-2xx after retries
- **THEN** requests `0..k-1` persist as SUCCESS rows, request `k` persists as one ERROR row, and requests `k+1..N-1` are not sent

#### Scenario: Missing dependency with no default fails the request
- **WHEN** a `responseField` cannot be resolved and its placeholder declares no default
- **THEN** that request persists one ERROR row and the chain aborts

#### Scenario: No message history is threaded between chain requests
- **WHEN** two chain requests both resolve bodies containing a `messages` array
- **THEN** each request's body carries only its own resolved messages, with no accumulation of prior messages or assistant replies

### Requirement: Chain step executor registry
Chain step execution SHALL be dispatched through a registry of step executors keyed by the chain element's `type` discriminator (`HTTP`, `MCP_TOOL`). Only the `HTTP` executor SHALL be functional; the `MCP_TOOL` executor SHALL throw `UnsupportedOperationException`. The existing single-request MCP execution path SHALL NOT be routed through this registry and SHALL remain unchanged.
Status: **Planned**

#### Scenario: HTTP element dispatches to the HTTP executor
- **WHEN** a chain element declares `type: HTTP` or omits the discriminator
- **THEN** the registry SHALL select the HTTP step executor

#### Scenario: MCP element is unreachable but guarded
- **WHEN** an `MCP_TOOL`-typed chain element somehow reaches execution despite the save-time rejection
- **THEN** the stub executor SHALL throw `UnsupportedOperationException`

#### Scenario: Existing MCP path untouched
- **WHEN** a single-request `MCP_TOOL` suite executes
- **THEN** it SHALL run through the existing MCP worker path, not the chain registry

## Implementation notes

`EvaluationWorker.execute` dispatch branch; new chain executor and `ChainStepExecutor` registry in `service.domain.job`; new rate-limit gate acquired inside `EvaluationWorker.invokeSingle`, `EvaluationWorker.invokeMcpSingle`, and `DeploymentTurnInvoker.invokeSingle`, with the per-dispatch `consume(1)` removed from `InProcessEvaluationExecutor`. The rate-limiting requirement's existing text already mandated per-HTTP-call acquisition including retries; this change brings the implementation into conformance and makes the multi-call cases explicit.
