# Multi-Request Chain

A test suite may issue an ordered **chain** of independent HTTP requests per test case instead of exactly
one. This doc covers the parts you cannot infer from any single class.

## The chain is normalized, and that is the whole trick

The chain is persisted **asymmetrically**: request 0 lives in the pre-existing flat suite columns
(`endpoint_ref`, `request_template`, `input_bindings`, `response_columns`, plus `request_label`), and
requests `1..N-1` live in `additional_requests` (JSONB array). That shape means existing suites need no
backfill, the snapshot needed no version bump, and no API field changed meaning.

`ChainNormalizer` (`service.domain`) turns that into a uniform `List<RequestSpec>` of size N, element 0
synthesized from the flat fields. **Every consumer works against the symmetric list**, never against
"flat-or-array":

- suite validation (`ChainConfigurationValidator` hard rules, `SuiteValidationService` per-element soft rules)
- chain execution (`ChainExecutor`)
- `request_index` / `request_label` assignment on result rows
- the chain-union response-column set
- `EvalSummaryExportColumnPlanner` (CSV headers)
- `EvalSummariesSchemaProvider` (query-DSL schema discovery)

**A single-request suite normalizes to a one-element chain.** That is why the single-request path needs no
special case anywhere, and why "is this multi-request?" is only ever `chain.size() > 1`.

The same normalizer is applied to a **live suite** and to a **frozen run snapshot**, so the two can never
drift. `EvaluationContext.chain` is normalized once from the snapshot at run start, so a suite edited after
run creation cannot change what an in-flight run executes.

> If you find yourself writing `if (suite.getAdditionalRequests() != null)`, you are about to duplicate the
> normalizer. That `if` is exactly what this component exists to have exactly once.

## Response column names are a chain-wide flat namespace

Response column names must be unique across the **whole chain**, not merely within one request. Enforced at
suite save with a 400 naming the duplicate.

This is load-bearing. Response column names were already a flat suite-wide namespace: metrics bind by bare
`columnName`, the results grid keys columns by bare name, and the CSV export emits `response::<name>`
headers. Allowing duplicates across requests would push a qualification syntax into **all** of those
consumers. Chain-wide uniqueness keeps every one of them unchanged, and means a column is owned by exactly
one request.

Consequence: the suite's **effective** response column set is the **chain union** — request 0's columns
followed by each subsequent request's, in chain order (`ChainNormalizer.chainResponseColumns`). Anything
resolving a response column reference must use the union, never the flat `responseColumns` alone:

- TSMD reference validation (`chainResponseColumnsJson`) — otherwise a metric bound to a later request's
  column is falsely reported as an unresolved reference
- CSV export headers and query-DSL `response:` fields — otherwise later requests' columns silently vanish

## Cross-request data flow: the accumulating map

`InputBindingDto` has a third source, `responseField`, alongside `dataField` and `constantValue` (exactly
one of the three). It names — by bare column name — a column declared by a **strictly earlier** request.

`ChainExecutor` maintains an accumulating map, seeded empty, merging each request's extracted columns after
it completes. Resolution order inside `TemplateVariableResolver`:

1. `constantValue` — always wins
2. `dataField` → the test case's `data` map
3. `responseField` → the **accumulated map**
4. the placeholder's declared default (`${{var|type:default}}`)
5. otherwise unresolved

Request 3 can consume request 0's `session_id`; it is not limited to its predecessor. Write-time validation
rejects forward, self, and unknown references (400), and any `responseField` on a single-request suite.

**Each persisted row carries only its OWN request's `extracted_columns`** — never the accumulated set. The
accumulator is execution state, not row content. That is what keeps the results grid and the CSV export
attributing each value to the request that produced it, and why a multi-request run's export rows are
**sparse**: cells for columns owned by other requests are empty.

There is deliberately **no message-history threading** between chain requests. "Chain" means data flow, not
conversation; conversational accumulation is what multi-turn test cases already provide.

## Fail-fast, and why continuing would be worse

The first failing request persists one ERROR row and aborts the chain; later requests are never sent.

Continuing was rejected, not overlooked: a request built from a failed dependency would resolve its
placeholder to a default, fire a semantically nonsense call, likely get a 200, and persist a **SUCCESS row
with meaningful-looking metric values computed on garbage**. A silently wrong result is worse than a
missing one.

Runtime resolution can fail even when write-time validation passed — a request can return 200 while a
column's JSONata matches nothing. `HttpChainStepExecutor` detects that **before sending** and reports an
unresolved dependency; if the placeholder declares a default, that default is used instead, making safe
continuation opt-in and visible in the template.

## Metric targeting reuses `condition` — and the N× cost is real

The metric list stays **flat**: a TSMD is not scoped to a request. Targeting reuses the existing per-metric
`condition`, whose dictionary gains a `request` namespace:

```
request.label = "invoke"     ← PREFERRED
request.index = 1
```

`label` is preferred because it survives reordering. Inserting a request earlier in the chain shifts every
subsequent `request.index`, silently retargeting an index-based condition with **no error** — quietly wrong
metric coverage. There is no `request.total`/`request.last` (unlike `turn`): chain length is configuration,
fixed for the run and known while writing the condition.

**An unconditioned metric runs on every request's row.** Two consequences, both accepted deliberately:

1. A metric whose **response binding** names a column absent from the row fails binding resolution before
   the provider is invoked, and the row's eval summary becomes `FAILED`. That failure **is** the intended
   authoring signal to add a `condition`. No LLM call is made and no polluted value reaches the averages.
   A correctly conditioned chain yields clean SUCCESS plumbing rows: when a condition skips every metric,
   `tsmdResults` is empty and `checkForErrors` returns false.
2. **A metric bound only to test-case data and constants resolves on every row**, so it runs N× at full
   cost with **no failure signal at all**. `condition` is the only defense. A non-invalidating validation
   warning would be the better signal, but no severity channel exists — `ValidationWarningDto` has no
   severity field and `ValidationResult` couples `valid` with `warnings`.

## Rate limiting is per-HTTP-call

The gate (`RunRateLimiter`, carried on `EvaluationContext`) is acquired at **each individual HTTP call**,
not once per dispatched test-case run, and **retries consume tokens**. Consuming per dispatch counted
dispatches, not requests: admitting `R` dispatches/sec where each emits `N` calls puts `R·N` requests/sec on
the deployment. The chain reuses `DeploymentTurnInvoker`, so it needs no separate call site.

## What is deliberately not supported

| | Why |
|---|---|
| **MCP chaining** | The element is polymorphic (`type: HTTP \| MCP_TOOL`) and `ChainStepExecutorRegistry` selects the executor, but only HTTP is real. An `MCP_TOOL` element is rejected at save (400) **and** `McpChainStepExecutor` throws — the 400 is the user-facing contract, the stub is the backstop. The existing single-request MCP path is **not** routed through the registry. |
| **Multi-request × multi-turn** | Rejected at **run creation** (409), not suite save, because dataset content is mutable and stored suite validity is configuration-only. The semantics are coherent — multi-turn is a per-*request* concern, so rows would be a **sum, not a product** — but it is out of scope for the first iteration. Every multi-request row therefore carries `turn_index = 0` / `total_turns = 1`. |
| **Branching / parallel / retryable-at-chain-level** | The chain is a straight sequence. |
| **Cross-request metric inputs** | Metric evaluation stays per-row. |
| **Per-request timeout / retry policy** | Those stay run-level. |

## Row identity

`request_index` joins the unique key on both `test_case_run_results` and `test_case_eval_summaries`.
`request_label` is an ordinary column and deliberately **not** in the key: labels are mutable display
strings, so keying on one would mean renaming a request changes the row's identity. Exact precedent:
`test_case_name` sits beside the keyed `test_case_id`.

`request_label` is written on **every** row an executor produces, not only chain rows —
`EvaluationWorker` (single-request HTTP and MCP), `MultiTurnExecutor` (per turn), `ChainExecutor`
(per request), and the executor's synthetic-error path all set it, resolving via
`EvaluationContext.primaryRequestLabel()` for the non-chain paths. That is what makes the optional-label
design work: `ChainNormalizer` defaults an undeclared label to `request-{n}`, so a `condition` on
`request.label` and the CSV `requestLabel` column behave identically for a single-request suite and a
chain. Leaving it null on the non-chain paths would silently break both. The column stays nullable only
for rows imported through the batch-write API, whose labels are client-supplied.

There is **no `total_requests` column**. The `turn_index`/`total_turns` pairing does not carry over:
`turn.total`/`turn.last` exist because turn count is **data**-dependent (it varies per test case, so a
condition author cannot know N), whereas request count is **config**-dependent — fixed for the run,
identical on every row, and derivable from the snapshot. Truncation detection needs no integer either:
fail-fast means an aborted chain's final row is ERROR.

**Intra-run row order is arbitrary.** Keyset pagination orders by `(created_at_ms, id)`, and `created_at_ms`
is constant across a run while `id` is a random UUID. Clients needing chain order MUST sort by
`(runIndex, requestIndex, turnIndex)`. This is pre-existing (it already affected multi-turn) and is now
documented on the listing endpoints.

## Key classes

| Class | Role |
|---|---|
| `ChainNormalizer` | The single definition of "the chain"; also the chain-union response-column set |
| `RequestSpec` | One normalized request: index, label, type, endpointRef, template, bindings, columns |
| `ChainConfigurationValidator` | Hard save-time rules → 400 (cap, MCP element, duplicate labels/columns, bad `responseField`) |
| `ChainExecutor` | Sequential loop, accumulating map, fail-fast, one row per request |
| `ChainStepExecutor` + `ChainStepExecutorRegistry` | SPI keyed on element `type` |
| `HttpChainStepExecutor` | The only real implementation |
| `McpChainStepExecutor` | Unreachable-by-construction stub |
| `RunRateLimiter` | Per-HTTP-call token gate |
| `ResolutionScope` | Carries test-case data + accumulated chain values into template resolution |
