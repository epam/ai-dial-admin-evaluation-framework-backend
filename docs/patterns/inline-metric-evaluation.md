# Inline metric evaluation — scoring a row mid-Phase-1, the `$_metrics` frame, and the total seam

A suite whose request-template bodies or TSMD bindings reference `$_metrics` runs in **inline mode**: metrics
are scored against each SUCCESS row immediately, inside `TurnLoopExecutor`'s turn loop, instead of waiting for
Phase 2's post-hoc pass — so a metric's `value`/`details` can feed a later request's JSON body or a later
TSMD's `Expression` binding within the same run.

## D1 — Mode is derived, never configured

`InlineModeDetector` (`service/domain/job`) scans the run's frozen `SuiteSnapshotDto` — root `requestTemplate`
and every `additionalRequests[i].requestTemplate` (`content` and `jsonataContent`) — plus the live
enabled+valid TSMD list's `configBindings`/`inputBindings` raw JSON, for the substring `"$_metrics"`. A hit
anywhere makes the whole run inline; a `suiteType = MCP_TOOL` run is always non-inline (no request chain for a
metric output to feed). The snapshot is used, never the live suite, so an edit made between run creation and
dispatch cannot silently change an in-flight run's mode. Only `JsonRequestBodyDto` bodies are scanned —
multipart/URL-encoded bodies, URLs, headers, and query params can never bind `$_metrics` in the first place. A
false positive (the token present but never actually reached) is accepted: it only costs paying for the inline
code path, never a behavior difference.

There is no suite-level toggle. `importResultsAndEvaluate` (`skipDeploymentPhase = true`) never runs the
detector at all — there is no Phase 1 to be inline about — so an imported suite always evaluates every SUCCESS
row via Phase 2, in full, even if its content would otherwise classify as inline.

## D2 — The `$_metrics` frame shape

```
$_metrics.<tsmdName>.<outputField>  →  {"value": <number|null>, "details": <object|null>}  |  {"error": "<msg>"}
$_metrics.<tsmdName>.error          →  wholesale metric error (Failure / ConditionError, no field breakdown)
```

`METRICS_FRAME_BINDING = "_metrics"` lives in `runner/constants/JsonataReservedNames`, alongside `_request`/
`_response`, in `FRAME_RESERVED_NAMES` — so a response column named `_metrics` is rejected at write time for
free, no new validator code. A TSMD name is not always a bare JSONata identifier (spaces and a single `:` are
legal), so a reference must backtick-quote it: `` $_metrics.`judge`.score.value ``. **A TSMD name that itself
contains a backtick is thereby unaddressable** — JSONata's backtick-quoted field syntax has no escape for an
embedded backtick — so that TSMD is still scored, buffered, and written to `test_case_eval_summaries` exactly
like any other, it simply cannot be referenced from `$_metrics` in any expression.

Accumulation is monotonic, last-writer-wins per `(tsmdName, outputField)`, threaded as a second accumulator
next to the response-column one: `RequestExecutionResult.accumulatedMetrics`, threaded by
`RequestChainExecutor` and `TurnLoopExecutor.execute`'s `initialMetrics` parameter. It is built with
`ObjectNode`/`putNull` and re-read via `objectMapper.readValue(json, Object.class)` — never a `Map` containing
Java `null`s handed to the shared `NON_NULL` `ObjectMapper` — so a present-but-null field still makes
`$exists(...)` return `true`. That value still cannot be *transmitted* as an explicit JSON `null` in an
emitted request body, though: the body's own serialization goes through the same `NON_NULL` mapper on its way
out, silently dropping the key. `extracted_columns` is never touched by any of this — `$_metrics` is a frame
binding only, never a persisted response column. Same-row sibling TSMDs are not ordered (parallel dispatch, no
`ORDER BY`) and are not made to see each other's output; split same-row metrics across requests/turns if one
needs another's result.

The `_metrics` key itself is bound in the per-turn frame **only when `context.getInlineMetricEvaluator() !=
null`** — a non-inline run's frame carries no `_metrics` key at all, not an empty map, so `$exists($_metrics)`
is `false` for that run and `TurnLoopExecutor.buildRequestFrame` is a byte-identical no-op.

## D3 — The seam, and why it must be total

Per turn, `TurnLoopExecutor`'s CONTINUE branch: send → extract columns → **build the turn row** (id minted,
`testCaseData`/`extractedColumns`/identity all populated) → inline-evaluate against that row → fold the
returned frame entry into `accumulatedMetrics` → append the row unconditionally → on failure, `aborted = true;
break`. The row is appended before the abort check, so a metric failure never replaces or drops the real
SUCCESS row — it only stops later turns/requests from running.

`TurnLoopExecutor` already has a `try { ... } catch (RuntimeException)` that synthesizes a
`REQUEST_RESOLUTION_ERROR` row for the *current turn* on any escaping exception. If the evaluator call were
allowed to throw, a metric-evaluation bug would silently replace a genuine 200-OK SUCCESS row with a synthetic
ERROR row, destroying real response data for a defect that has nothing to do with the deployment call. The
seam is therefore **total**: `InlineMetricEvaluator.evaluate()` MUST NOT throw — not a `RuntimeException`, not
a checked exception, not an unhandled `InterruptedException`. `TurnLoopExecutor.evaluateInlineMetrics` is the
last line of defense even if the SPI contract is violated: it catches `RuntimeException`, re-sets the thread's
interrupt flag when the cause is an `InterruptedException`, and folds the failure into
`new InlineMetricResult(Map.of(), true)` instead of letting anything escape to the outer catch. This is the
single most important invariant in this design.

The evaluator only ever sees rows reaching the CONTINUE branch. The ABORT branch, the chain-level catch, the
empty-turn-plan early return, `EvaluationWorker`'s own chain-level catch, and `TestCaseRunner`'s synthetic
error row never call it — those rows' EvalSummaries come from Phase 2's propagate-only pass instead (D5), which
is why Phase 2 is never fully retired even for an inline run.

## D4 — SPI lives in runner-core, implementation stays backend-only

`runner/job/InlineMetricEvaluator` (interface, one method: `InlineMetricResult evaluate(InlineMetricRequest)`),
`InlineMetricRequest(TestCaseRunResult row, Map<String,Object> accumulatedMetrics)`, and
`InlineMetricResult(Map<String,Object> frameEntry, boolean failed)` are runner-local/JDK types only — no
dependency on the EF backend — so `RunnerModuleConstraintsTest.mustNotDependOnTheEfBackend` keeps passing.
`EvaluationContext` gains `@Nullable InlineMetricEvaluator inlineMetricEvaluator` via its existing `@Builder`;
`eval-cli`'s `EvaluationContextFactory` simply never sets it, leaving it `null` — the correct non-inline
default for a CLI run (inline evaluation is out of scope for `eval-cli`, which has no Phase 2/metric-provider
dispatch machinery at all).

## D5 — Backend split, thread-safety, flush timing, job ordering

`MetricRowEvaluator` (`service/domain/job`, extracted from `InProcessMetricEvaluationExecutor`) is the shared
per-row worker: dispatch, condition evaluation, timeout, and `EvalSummaryBatchWriteItemDto` construction, used
by both Phase 2's batch executor and inline evaluation. `evaluateAndBuild` returns
`MetricRowEvaluationResult{item, tsmdResults, hasError}` — the extra `tsmdResults` map is what lets the inline
caller build a `$_metrics` frame entry, something Phase 2 never needed.

`InlineMetricEvaluatorImpl` (`service/domain/job`, package-private, one instance per run) is created by
`InlineMetricEvaluatorFactory` — the same shape as `PostgresResultBatchWriterFactory`. It holds the run's
`MetricEvaluationContext`, reuses `context.getCancellationSignal()` (never a fresh `AtomicBoolean` — a fresh
signal would make Stop unable to interrupt an in-flight inline evaluation), provider semaphores built once at
construction, a dedicated virtual-thread executor, and a summary buffer.

**Thread-safety is mandatory.** `TestCaseRunner` dispatches up to `concurrencyLevel` test cases concurrently on
virtual threads, so `evaluate()` is called concurrently once `concurrencyLevel > 1` (the default,
`default-concurrency-level: 1`, hides this entirely unless a dedicated multi-thread test exercises it — such a
test exists precisely because "it worked in manual testing" proves nothing about a race above concurrency 1).
The buffer is guarded by a `ReentrantLock` around add/drain, with the flush I/O performed outside the lock
after copying — the same discipline `PostgresResultBatchWriter` uses for its own row buffer. On a batch-write
failure the impl catches the exception and sets the cancellation signal itself, mirroring
`InProcessMetricEvaluationExecutor.doFlush`'s existing shape (the two writers do not share an implementation,
only the same intent). `test_case_eval_summaries.test_case_run_result_id` has no FK, so a summary batch
legitimately landing before its row batch flushes is accepted, not guarded against.

**Flush timing is load-bearing.** The impl's final `flush()` runs in `TestSuiteEvaluationJob` immediately after
`evaluationExecutor.execute(context)` returns, strictly before the Phase 2/3 block — inverting that order would
let Phase 3's structured queries over `test_case_eval_summaries` aggregate a truncated set for any inline run
whose last batch had not yet flushed: silently wrong score statistics, not a crash. The job's existing
`finally` additionally calls `close()` (flush + executor shutdown) as a no-op safety net for any path that
bypasses the normal flush call.

**Job ordering is branch-specific.** On the normal (`!skipDeploymentPhase`) branch, `TestSuiteEvaluationJob`
builds `MetricEvaluationContext` (TSMD load + `computationId`/`computedAtMs`) and writes `run_metric_snapshots`
immediately after the inconsistent-snapshot guard passes, still inside that guard's `if` block, *before* Phase
1 — so a run failing the guard writes no snapshot row. Only this branch runs `InlineModeDetector`. On the
`skipDeploymentPhase` branch, the same context-build-and-snapshot-write happens once, unconditionally, right
after that `if` block closes, with `detectInline = false` hardcoded — the detector is never invoked there. One
consequence of hoisting the TSMD load ahead of Phase 1 on both branches: a TSMD enabled/disabled/edited
mid-Phase-1 is not picked up by that run, for every run, inline or not.

**Phase 2 becomes propagate-only for inline runs.** `MetricEvaluationContext.inlineMode` gates
`InProcessMetricEvaluationExecutor`'s row loop: SUCCESS rows are skipped (already scored inline), non-SUCCESS
rows still go through `buildPropagatedItem` exactly as today. Non-inline runs are completely unchanged. Two
rare failure paths are documented, not fixed: `EvaluationWorker`'s chain-level catch can discard already-produced
rows whose inline EvalSummary was already written (dangling summary), and a row-batch flush failure can lose
rows whose EvalSummaries already committed (the symmetric orphan). Both require a cross-datasource transaction
this change does not attempt.

**Duration and concurrency reuse existing dials on purpose.** Inline evaluation measures and sums per-TSMD
provider-call duration into `metricEvalDurationMs` exactly as `MetricRowEvaluator.computeMetricEvalDurationMs`
does for Phase 2 — never added to the row's own `execDurationMs`. `metric-evaluation.default-concurrency-per-provider`
now also sizes the semaphores gating concurrent inline `/evaluate` calls, and
`metric-evaluation.per-result-timeout-ms` now also bounds one row's dispatched TSMD futures at inline time —
the same properties, just applied earlier; there is deliberately no separate inline timeout, since *when* the
wait happens changes, not how long a provider call should be tolerated.

## D6 — Fail-fast; `executionStatus` stays SUCCESS

A metric that fails transport after retries, returns `type:"error"`, times out, or whose `condition`
throws/returns non-boolean/null sets `InlineMetricResult.failed = true`, which aborts the chain — remaining
turns and later requests are skipped, but every row produced so far persists as-is. The failing TSMD's
EvalSummary for that row is FAILED with the same error entries Phase 2 already produces. The row's
`executionStatus` itself stays SUCCESS: it is a user-facing filter describing whether the *deployment call*
succeeded, and it did — conflating that with "every metric scored cleanly" would make it useless as a
deployment-health signal. A clean `condition = false` is not a failure; the metric is simply omitted and the
chain continues, same as non-inline.

This is a mode-scoped divergence from `conditional-metric-execution`'s general guarantee that a broken
condition never fails the row: under inline evaluation it still doesn't fail the row, but it does abort the
chain, which non-inline evaluation never does.

## D7 — The `Expression` binding source

`ExpressionBindingSourceDto` (`service/domain/dto`, `expression: String`, required) is the fourth
`@JsonSubTypes.Type(name = "Expression")` on `MetricBindingSourceDto`, alongside `TestCase`/`Response`/
`Constant`. Its `expression` is a JSONata source evaluated over a per-row frame
`{_metrics, data, response}` — `data` is the test case's fields, `response` the row's extracted columns,
`_metrics` the same accumulated map `$_metrics` binds elsewhere. Because the frame is per-row, it cannot ride
on the per-run `MetricEvaluationContext`; it threads as an explicit parameter through `BindingResolver`,
`MetricEvaluationWorker`, and the dispatch site in `MetricRowEvaluator`. Phase 2's propagate-only pass and any
non-inline evaluation pass `_metrics = {}` — `data`/`response` stay populated, so an `Expression` binding over
those two works identically whether or not `$_metrics` happens to be empty. An `undefined` result, an explicit
JSON `null` (`DashjoinJsonataEvaluationService` maps the engine's `NULL_VALUE` to Java `null`, so a present-but-
null metric field is indistinguishable from an unbound one at this point), or a thrown evaluation all fold into
`IllegalArgumentException` — the same failure mode `TestCase`/`Response` bindings already use, which is D6's
fail-fast for free. **A metric field that may legitimately hold a `null` value must be guarded in the expression
itself** — e.g. `$exists($_metrics.\`judge\`.score.value) ? $_metrics.\`judge\`.score.value : someFallback` — if
the binding should succeed instead of throwing; a bare reference to a null-valued field throws with "evaluated
to undefined or null". `expression` is syntax-checked at write time exactly like `condition`
(HTTP 400 on bad syntax); there is deliberately no cross-TSMD reference check, since proving a `$_metrics` path
will exist at runtime is equivalent to solving general dataflow analysis over an author-supplied expression.

**`$_metrics` is unavailable in three places, on purpose:**
- **TSMD `condition`** — `ConditionExpressionEvaluator` still uses its existing `{data, response, turn,
  request}` dictionary; adding `_metrics` there would blur "deciding whether to run" with "consuming another
  metric's output." A reference resolves to plain `undefined`, same as any other unbound name. Whether that
  breaks the condition depends on the enclosing expression: a bare `` $_metrics.`judge`.score.value `` makes
  the whole condition `undefined` (non-boolean ⇒ the broken-condition outcome — `metricError::<name>`, row
  stays SUCCESS, and under inline mode the chain also aborts), while
  `` $exists($_metrics.`judge`.score.value) `` reduces that same `undefined` to a clean boolean `false` (the
  metric is simply omitted, chain continues) — `$exists(undefined)` is a clean boolean, not a broken-condition
  trigger.
- **Response-column expressions** — `ResponseColumnExtractor` binds only `_request`/`_response`/prior columns;
  columns are extracted before any metric on that row has run, so `$_metrics` would always be empty there
  regardless.
- **Try-it-out** — `TryItOutService` has no `MetricProviderClient` (or any other metric-binding) collaborator
  anywhere in its chain, so no `InlineMetricEvaluator` is ever wired into a try-out run; a `$_metrics`
  reference in a previewed request body resolves to plain JSONata `undefined`.

## See also

- [Multi-request suites](multi-request-suites.md) — the accumulated-frame and chain-abort mechanics this
  design reuses for `$_metrics`.
- [Request-template JSONata seam](jsonata-evaluation-seam.md) — the frame-binding conventions (`_request`/
  `_response`) `_metrics` follows.
- [`evaluation-runner-core` module](evaluation-runner-core-module.md) — the module the `InlineMetricEvaluator`
  SPI lives in.
- [metric-evaluation](../../openspec/specs/metric-evaluation/spec.md),
  [conditional-metric-execution](../../openspec/specs/conditional-metric-execution/spec.md), and
  [tsmd-validation](../../openspec/specs/tsmd-validation/spec.md) specs.
