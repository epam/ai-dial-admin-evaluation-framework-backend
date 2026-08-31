## Context

See `proposal.md` - Why for motivation. This section covers only the current-state facts the design leans on,
verified against `development` @ `4f94fe2`.

Today, metric evaluation is a hard phase boundary: `TestSuiteEvaluationJob.executeRunAsync` runs Phase 1
(`evaluation-runner-core`: `EvaluationWorker` → `RequestChainExecutor` → `TurnLoopExecutor`) to completion,
*then* `InProcessMetricEvaluationExecutor` pages `test_case_run_results` back out of the analytics DB, calls
metric providers, writes `test_case_eval_summaries` + `run_metric_snapshots`, and only then does Phase 3
compute scores. `MetricEvaluationWorker.buildRequest` reads only `testCaseData` + `extractedColumns`, both of
which exist in memory before a row is ever persisted — this is what makes moving the evaluation earlier
tractable without a DB round-trip. `InProcessMetricEvaluationExecutor.evaluateAndBuild` (`:174-306`) already
does the entire per-row job (conditions → dispatch → timeout → `MetricOutputMapper` → summary item) against a
single in-memory `TestCaseRunResult` — today it just receives that row from a DB page instead of from the turn
loop directly.

`evaluation-runner-core` is a separate, DB-free Gradle subproject; `TurnLoopExecutor`, `RequestChainExecutor`,
and `EvaluationContext` live there and must never depend back on `com.epam.aidial.evaluation.*` outside their
own `.runner` subtree (`RunnerModuleConstraintsTest`). `eval-cli` is a third, independent subproject consuming
the same runner-core execution path; it is explicitly untouched by this change.

## Goals / Non-Goals

**Goals:**
- Let a metric's `value` + `details` feed a later request's JSON body and a later TSMD's `Expression` binding,
  within the same run, without waiting for Phase 2.
- Derive inline-mode automatically from suite content — no new suite-level toggle for authors to discover,
  set, or forget.
- Keep the change backend-only and additive: a suite that never references `$_metrics` executes byte-identical
  to today, verified by an explicit regression test rather than assumed.

**Non-Goals** (design-level, in addition to the proposal's stated scope):
- No attempt to make same-row sibling TSMDs see each other's output — only earlier-request/earlier-turn reads
  are supported. Ordering same-row TSMDs would require serializing what is currently parallel dispatch, which
  is a larger performance trade-off than this change is willing to make.
- No attempt to give Phase 2's propagate-only pass knowledge of *why* a row is non-SUCCESS beyond what
  `buildPropagatedItem` already uses — the propagate path is intentionally left untouched.
- No new `MetricDefinitionValidationService` parameter or cross-TSMD static-reference check for `Expression`
  bindings — validating whether a referenced `$_metrics` path will exist at runtime is equivalent to solving
  general dataflow analysis over an author-supplied JSONata expression; the design accepts loud runtime
  failures instead (D6).

## Decisions

### D1 — Inline mode is derived, not configured
A run is inline iff `"$" + JsonataReservedNames.METRICS_FRAME_BINDING` (i.e. `"$_metrics"`) appears as a
substring anywhere in: any request-template JSON body serialization (`JsonRequestBodyDto.content` serialized,
or `jsonataContent`) on the root template or any `additionalRequests[i].requestTemplate`; or any enabled+valid
TSMD's `configBindings`/`inputBindings` raw JSON string (`AggregatedMetricDefinition:26,28` are already raw
JSON, so no deserialization is needed for the scan). New `service/domain/job/InlineModeDetector`.

**Inputs are the run's frozen `SuiteSnapshotDto`** (what Phase 1 actually executes,
`TestSuiteEvaluationJob:429-436`) **plus the live TSMD list** (what Phase 2 would otherwise evaluate) — never
the live suite. Rationale: a suite edited between run creation and dispatch must not silently change an
in-flight run's execution mode; the snapshot is the only input that is stable for the run's lifetime.

Alternatives considered:
- *A JSONata AST walk to rule out unreachable references* — rejected. A false positive (the substring is
  present but never actually evaluated) only costs paying for the inline code path with no behavior
  difference; the complexity of parsing every template to prove non-use is not worth avoiding that cost.
- *A suite-level boolean flag set by the author* — rejected per the reviewed plan: it adds a DTO/mapper/
  repository/clone/snapshot column, a guard table entry, and a decision surface for `eval-cli` to refuse or
  support, for a value that is entirely computable from content the author already wrote.

`suiteType == MCP_TOOL` is always non-inline (no request chain for a metric output to feed). Multipart/
URL-encoded bodies, URLs, and headers are never scanned — `RequestResolver.resolveJsonBody` is the only method
that threads `frameBindings` into template resolution, so those surfaces literally cannot read `$_metrics`
even if it appeared there.

### D2 — The `$_metrics` frame shape and construction
One shape, mirroring `MetricOutputFieldDto`:
```
$_metrics.<tsmdName>.<outputField>  →  {"value": <number|null>, "details": <object|null>}  |  {"error": "<msg>"}
$_metrics.<tsmdName>.error          →  wholesale metric error (ConditionError / Failure w/o field names)
```
`METRICS_FRAME_BINDING = "_metrics"` is added to `runner/constants/JsonataReservedNames` (`:25-26`) and to
`FRAME_RESERVED_NAMES` (`:101`), so the existing reserved-set check (`TestSuiteRequestValidator:177`) rejects a
colliding response-column name for free — no new validator code, just a new constant in an existing list.

Construction follows the pattern `ResponseColumnExtractor.putParsedIfPresent:143-152` already uses for
`$_response`: build an `ObjectNode` with `putNull` for explicit nulls, serialize it, then
`objectMapper.readValue(json, Object.class)` and bind the result — never hand a `Map` containing Java `null`s
to the shared `NON_NULL`-configured `ObjectMapper` (`JsonMapperConfiguration:60-62`), which would silently drop
those entries. This preserves present-null distinguishability inside JSONata (`$exists` is `true` for a
present null) while accepting that the value still cannot be *transmitted* as an explicit `null` in an emitted
request body, because that body's own serialization goes through the same `NON_NULL` mapper on the way out —
documented in the `request-template` delta as a caveat, not fixed.

Accumulation is monotonic, last-writer-wins per `(tsmdName, outputField)`. Threaded as a second accumulator
alongside the existing response-column accumulator: `RequestExecutionResult` gains a fourth component
`Map<String,Object> accumulatedMetrics`; `TurnLoopExecutor.execute` gains an `initialMetrics` parameter;
`RequestChainExecutor:47-53` threads both accumulators the same way. `extracted_columns` is untouched —
`$_metrics` never becomes a persisted response column, only a frame binding. An unresolvable path resolves to
`undefined`, not an error — the frame is intentionally untyped, mirroring how an unbound response-column
reference already behaves. Same-row sibling TSMDs are not ordered (parallel dispatch, no `ORDER BY`); this is
accepted (see Non-Goals) and documented so an author knows to split same-row metrics across requests/turns.

The `_metrics` key itself is bound in the per-turn frame **only when `context.getInlineMetricEvaluator() != null`**
— a non-inline run's frame carries no `_metrics` key at all, not an empty map, so `$exists($_metrics)` evaluates
to `false` for that run. This keeps D3's "single null check, byte-identical" claim true at the frame level, not
just at the evaluator-invocation level: a suite that never references `$_metrics` sees no new binding of any
shape, not merely an always-empty one.

### D3 — Seam placement and the "seam must be total" hazard
The seam sits inside `TurnLoopExecutor`'s CONTINUE branch (`:180-195`). Per-turn order: send → extract columns
→ **build the turn row** (the row already exists in memory with its id minted, `testCaseData`,
`extractedColumns`, `runIndex`, `requestIndex`/`totalRequests`, `turnIndex`/`totalTurns` all populated by
`buildTurnRow` at `TurnLoopExecutor:396-432`) → inline-evaluate against that row → fold the frame entry into
`accumulatedMetrics` → append the row to `results` → on failure, `aborted = true; break;` (the first `break` in
the CONTINUE branch; `RequestChainExecutor` already stops once `aborted()` is observed, so no new
abort-propagation mechanism is needed at the chain level).

**Hazard**: `TurnLoopExecutor`'s existing `try { ... } catch (RuntimeException)` (`:155` / `:224`) synthesizes
a `REQUEST_RESOLUTION_ERROR` row for the *current turn* whenever an exception escapes the try block. If
`InlineMetricEvaluator.evaluate` were allowed to throw, an evaluator bug would silently replace a genuine
200-OK SUCCESS row with a synthetic ERROR row — destroying the real response data for a metric-evaluation
defect unrelated to the deployment call. The seam is therefore designed as **total**: the SPI contract states
`evaluate()` MUST NOT throw, including checked exceptions; the implementation wraps every internal failure
(including folding a caught `InterruptedException` into a failed `InlineMetricResult` and re-setting the
thread's interrupt flag) rather than letting anything propagate. This is the single most important invariant
in the design — a violation turns an evaluator bug into silent data loss on the deployment side.

The evaluator is invoked only on rows reaching the CONTINUE branch (SUCCESS). Four other row-producing paths
exist and none of them can reach this seam: the ABORT branch (`:196-222`), the chain-level catch (`:224-260`),
the empty-turn-plan early return (`:135-143`), `EvaluationWorker`'s own chain-level catch (`:107-132`), and
`TestCaseRunner`'s synthetic error row (`:109-136`). Their EvalSummaries are produced by the Phase-2
propagate-only pass instead (D5) — this is why Phase 2 cannot be fully retired even for inline runs. This is a
deliberate deviation from the reviewed plan's file list, which called for `TurnLoopExecutor`'s ABORT branch and
chain-level catch to each also invoke a propagate call directly: this design adds no such call to either
branch, and leaves every non-SUCCESS row's EvalSummary to be produced solely by Phase 2's propagate-only pass,
so `TurnLoopExecutor` never needs a second, propagate-shaped exit from those branches.
`context.getInlineMetricEvaluator() == null` (non-inline run) makes `TurnLoopExecutor` execute byte-for-byte
identically to its pre-change behavior — the seam is a single null check, not a branchy rewrite.

### D4 — SPI in runner-core, backend-only strategy implementation
`runner/job/InlineMetricEvaluator` (interface) + `InlineMetricRequest(TestCaseRunResult row,
Map<String,Object> accumulatedMetrics)` + `InlineMetricResult(Map<String,Object> frameEntry, boolean failed)` —
runner-local / JDK types only, so `RunnerModuleConstraintsTest.mustNotDependOnTheEfBackend` continues to pass
without exception. `EvaluationContext` gains `@Nullable InlineMetricEvaluator inlineMetricEvaluator`; it
already carries the non-serializable `AtomicBoolean cancellationSignal`, so adding another cross-cutting,
non-serializable field is consistent with the class's existing shape. `TurnLoopExecutor`'s constructor is
unchanged; the `@Builder` addition to `EvaluationContext` is source-compatible with `eval-cli`'s
`EvaluationContextFactory` (which simply never sets the new field, leaving it `null` — the correct "non-inline"
default for a CLI run).

Alternatives considered:
- *A Spring `ObjectProvider<InlineMetricEvaluator>` registry with lifecycle management* — rejected as
  over-engineering for a per-run, single-implementation object; there is exactly one evaluator instance per run,
  constructed by a factory, with no need for Spring to manage its lifecycle beyond normal object construction.

**Known collision**: `openspec/changes/ef-as-dial-app/specs/eval-execution-engine/spec.md:4,16-18` also
rewrites the same `EvaluationContext` carry-list sentence (removing the JWT token field for DIAL App mode
auth). This design's delta is written against the currently archived spec text; whichever change archives
second must rebase that one paragraph by hand to include both edits. No code conflict is expected — the two
edits touch different fields of the same class — only a spec-text merge is needed.

### D5 — Backend split: `MetricRowEvaluator` extraction, `InlineMetricEvaluatorImpl`, job ordering
Extract from `InProcessMetricEvaluationExecutor` a shared `@Component MetricRowEvaluator` covering
`evaluateAndBuild` (`:174-305`), `buildPropagatedItem` (`:335`), `buildItem` (`:341`),
`computeMetricEvalDurationMs`, `checkForErrors`, `buildProviderSemaphores` (`:153`). It returns a small record
`{EvalSummaryBatchWriteItemDto item, Map<String,TsmdEvaluationResult> tsmdResults, boolean hasError}` so the
inline implementation can build its `$_metrics` frame entry from `tsmdResults` while the Phase-2 executor keeps
using just the `item`. Verified against the current implementation: it reads only fields `buildTurnRow`
already populates and per-run config from `MetricEvaluationContext` — no DB dependency, so this really is a
behavior-preserving extraction, not a rewrite. This is the reason `InProcessMetricEvaluationExecutorTest` must
be split rather than simply left alone: duration/mapping assertions move to a new `MetricRowEvaluatorTest`,
and the snapshot-write assertion moves to `TestSuiteEvaluationJobTest` (see D5's snapshot-hoist below).

`InlineMetricEvaluatorImpl` (`service/domain/job/`, one instance per run) is created by a new
`InlineMetricEvaluatorFactory` `@Component` — the same shape as `PostgresResultBatchWriterFactory` and
`TestCaseRunnerFactory` — so `TestSuiteEvaluationJob` gains one factory dependency instead of three raw
collaborators. The impl holds: the per-run `MetricEvaluationContext`; the run's `cancellationSignal` **reused
from `context.getCancellationSignal()`**, never a fresh `AtomicBoolean` (a fresh signal would make Stop unable
to interrupt an in-flight retry loop — this is a correctness requirement, not a style choice); provider
semaphores built once at construction; a virtual-thread executor; and a summary buffer.

**Thread-safety is mandatory, not incidental.** `TestCaseRunner` dispatches up to `concurrencyLevel` test cases
concurrently on virtual threads (`:65-66,99`), so the impl's `evaluate()` is called concurrently once
`concurrencyLevel > 1`. The buffer discipline is copied verbatim from `PostgresResultBatchWriter`: a
`ReentrantLock` around add/drain, with the actual flush I/O performed *outside* the lock after a copy
(`:29-30,63-105`). The default `application.yml` `default-concurrency-level: 1` (`:121`) means this hazard is
invisible unless a dedicated `concurrencyLevel > 1` test exercises it — such a test is mandatory (see
`tasks.md`), because "it worked in manual testing" tells you nothing about a race that only manifests above
concurrency 1. `test_case_eval_summaries.test_case_run_result_id` has no FK (`V1.5:5`), so a summary batch may
legitimately land in the analytics DB before its corresponding row batch flushes — this is accepted, not
guarded against, consistent with the append-only nature of both tables. On a batch-write failure, the impl
catches the exception and sets the cancellation signal itself, the same shape
`InProcessMetricEvaluationExecutor.doFlush` (`:393-410`) already uses today for its own summary-batch flushes;
row batches' own flush, `PostgresResultBatchWriter.doFlush` (`:111-124`), has no such catch and instead lets a
flush exception propagate to its caller — the two writers do not actually share a failure-handling
implementation, only the same "set the cancellation signal on a failed flush" intent applied independently on
each side.

**Transaction boundary.** Inline summary writes go through the same call chain Phase 2 already uses:
`InlineMetricEvaluatorImpl`'s flush calls `EvalSummaryBatchWriteClient.batchWrite(...)`
(`service/domain/job/EvalSummaryBatchWriteClient.java:37-66`), which chunks the buffer to the configured
`evalSummaryProperties.getBatch().getMaxItems()` and delegates each chunk to
`EvalSummaryService.batchCreate(EvalSummaryBatchWriteRequestDto)`
(`service/domain/analytics/EvalSummaryService.java:53-97`) — the exact same `@Transactional("analyticsTransactionManager")`
service method `InProcessMetricEvaluationExecutor.doFlush` calls for Phase 2. No new transactional boundary is
introduced; inline evaluation simply calls this service earlier and from a different caller. Because each
flush from a Phase-1 virtual thread opens its own fresh Spring-managed transaction via this `@Transactional`
method — there is no ambient `@Transactional` on the calling thread, and no `TransactionTimestampContext` (that
aspect only wraps the boundary of an existing `@Transactional` call, and none is open when
`InlineMetricEvaluatorImpl` calls out) — `createdAtMs` cannot be sourced from that context. It doesn't need to
be: `EvalSummaryService.batchCreate` (`:79`) sources `createdAtMs` from `run.getCreatedAt()`, the immutable
`TestSuiteRun.createdAt` value fetched inside the same transaction, not from `clock.millis()` or any
per-transaction "now" — so this sourcing is unaffected by flushing on Phase-1 virtual threads with no ambient
transaction, whether the flush happens once at end-of-run or many times mid-run.

**Duration accounting and concurrency gating.** `InlineMetricEvaluatorImpl.evaluate()` measures each dispatched
TSMD's provider-call wall-clock time exactly as `MetricRowEvaluator.computeMetricEvalDurationMs` does for Phase
2, and sums it into that row's `EvalSummaryBatchWriteItemDto.metricEvalDurationMs` — the same field, populated
the same way, just written earlier. This latency SHALL NOT be added to the `TestCaseRunResult`'s own
`execDurationMs`, which continues to measure only the deployment call itself; the two durations stay disjoint
regardless of which phase produced the metric score. The provider semaphores `InlineMetricEvaluatorImpl` builds
once at construction (reused from `MetricRowEvaluator.buildProviderSemaphores`) are sized from
`metric-evaluation.default-concurrency-per-provider` — the same property Phase 2 has always used — so that
property now also gates the number of concurrent inline `/evaluate` calls in flight during Phase 1, not only
Phase 2's post-hoc dispatch. Per-row inline evaluation likewise enforces `metric-evaluation.per-result-timeout-ms`
for the row's dispatched TSMDs, the same property and the same semantics `InProcessMetricEvaluationExecutor`
already applies per batch in Phase 2, just evaluated against one row's futures instead of a page of rows'.

**Flush timing.** The impl's final `flush()` must run in `TestSuiteEvaluationJob` immediately after
`evaluationExecutor.execute(context)` returns, strictly before the Phase-2/Phase-3 block. If this ordering were
inverted, Phase 3's structured queries over `test_case_eval_summaries`
(`MetricScoreComputationExecutor:69-100`) would aggregate a truncated set for any inline run whose last batch
had not yet been flushed — silently wrong score statistics, not a crash, which makes this the kind of bug that
would otherwise ship undetected. The job's existing `finally` (`:193`) additionally calls `close()` as a
no-op safety net for any path that bypasses the normal flush call.

**Job ordering** is branch-specific, not a single hoist above the `if (!skipDeploymentPhase)` block — the
inconsistency guard (`:138`) lives *inside* that block and must still gate whether a snapshot gets written on
the normal branch:
- **Normal branch** (`!skipDeploymentPhase`): inconsistency guard (`:138`) → *if the guard passes* →
  `buildMetricEvaluationContext` (TSMD load + `computationId`/`computedAtMs` mint) called immediately afterward,
  still inside the `if (!skipDeploymentPhase)` block → write `run_metric_snapshots` immediately after that → run
  the `InlineModeDetector` → Phase 1 (constructing `EvaluationContext` with the factory-supplied evaluator when
  inline) → final flush of the inline evaluator. A run that fails the inconsistency guard is marked FAILED and
  returns before `buildMetricEvaluationContext` ever runs, so it writes no `run_metric_snapshots` row.
- **`skipDeploymentPhase` branch**: the `if (!skipDeploymentPhase)` block's body never executes (no inconsistency
  guard, no Phase 1), so `buildMetricEvaluationContext` and the snapshot write run once, unconditionally,
  immediately after that `if` block closes — the detector is never invoked here (see the "Inline metric
  evaluation mode is derived per run" requirement's `skipDeploymentPhase ⇒ non-inline` rule).

Both branches then converge on Phase 2 → Phase 3 (moved out of `InProcessMetricEvaluationExecutor.execute:81,
138-151`; hoisting the snapshot write this way keeps `EvalResultsImportFunctionalTests:166-168,809-811` green
because those tests assert snapshot presence independent of which phase wrote it). A consequence of hoisting the
TSMD load ahead of Phase 1 on both branches: a TSMD enabled/disabled/edited mid-Phase-1 is *not* picked up by the
in-flight run, for every run (inline or not) — this is a behavior change from today (where only Phase 2's load
time mattered) and is called out explicitly in the `metric-evaluation` delta rather than silently accepted.

**Phase 2 becomes propagate-only for inline runs.** `MetricEvaluationContext` gains `boolean inlineMode`.
Inside `InProcessMetricEvaluationExecutor`'s row loop: inline ⇒ SUCCESS rows are skipped (scored already);
non-SUCCESS rows get `buildPropagatedItem` exactly as today — this single branch covers every non-seam row
path listed in D3 with zero additional provider calls, while preserving "exactly one EvalSummary per row."
Non-inline ⇒ completely unchanged.

**Known orphan cases, documented not fixed:** (a) `EvaluationWorker:107-132`'s chain-level catch discards
already-produced chain rows for that test case — any inline EvalSummary already written for those rows
dangles with no corresponding row; (b) `PostgresResultBatchWriter.doFlush:111-113` failure loses a row batch
whose EvalSummaries were already committed — the symmetric orphan shape. Both are rare, both are the same
class of problem the system already has today between any two independently-flushed writers, and fixing either
would require a cross-table transaction spanning the meta/analytics dual-datasource boundary that this change
does not attempt. Cancelled/failed inline runs still keep rows + summaries + snapshot consistent with each
other — nothing is forfeited by cancellation itself, only by these two specific failure shapes.

`importResultsAndEvaluate` (the eval-results-import path) never runs the detector — there is no Phase 1 to be
inline about — so Phase 2 runs in full exactly as today for that path.

### D6 — Fail-fast; the row's `executionStatus` stays SUCCESS
A metric that fails transport after retries, returns `type:"error"`, times out, or whose `condition`
throws/returns non-boolean/null ⇒ `InlineMetricResult.failed = true` ⇒ the chain aborts (remaining turns and
later requests skipped; rows produced so far persist as-is). The failing TSMD's summary for that row is FAILED
with the same error entries `buildItem` already produces today. The row's `executionStatus` stays `SUCCESS` —
deliberately: `executionStatus` is a user-facing filter (`FilterWhitelists:282`) describing whether the
*deployment call* succeeded, and it did. Conflating "the deployment answered" with "every metric scored
cleanly" would make `executionStatus` unusable as a deployment-health signal. A clean `condition = false` is
not a failure — the metric is simply omitted and the chain continues, unchanged from non-inline behavior.

This is a deliberate, mode-scoped divergence from the `conditional-metric-execution` spec's existing guarantee
that "a broken condition never fails the result row" — under inline evaluation it still doesn't fail the row,
but it does abort the chain, which non-inline evaluation never does. The divergence is documented in the
`conditional-metric-execution` delta rather than hidden inside `metric-evaluation` alone, since it is a
condition-specific behavior change that condition authors need to know about directly.

### D7 — `Expression` binding source and its per-row frame
New `service/domain/dto/ExpressionBindingSourceDto` (`expression: String`, required), the fourth
`@JsonSubTypes.Type(name = "Expression")` on `MetricBindingSourceDto` (`:8-11`). `BindingResolver.resolveBindings`/
`resolveSource` (`:76-112`) gain a `Map<String,Object> frame` parameter and one new branch:
`jsonataEvaluationService.evaluate(expression, "{}", frame)` (`runner/service/JsonataEvaluationService:67`) — the
`service.domain.job → runner.service` dependency direction is already allowed by the layering rules, so no new
cross-module dependency is introduced. `frame = {_metrics: ..., data: testCaseData, response: extractedColumns}`
so the same source type can transform any of the three inputs uniformly, not just metric outputs.
`undefined`/throw ⇒ `IllegalArgumentException`, the same failure mode the existing `Response`/`TestCase`
branches already use, which folds into D6's fail-fast semantics for free.

Because the frame is *per-row*, it cannot ride on the per-run `MetricEvaluationContext` — it must be threaded
as an explicit parameter through three signatures: `MetricEvaluationWorker.evaluate(tsmd, result, semaphore,
context, frame)` (`:55-60`), the private `invokeWithRetries` (`:105`), and the private `buildRequest`
(`:168-183`), plus the dispatch site in `MetricRowEvaluator` (today `InProcessMetricEvaluationExecutor:232`).
Phase 2's propagate-only pass and any non-inline evaluation pass `_metrics = {}` — the frame's other two keys
(`data`, `response`) are still populated, so a non-inline suite's `Expression` bindings over `data`/`response`
work identically whether or not `$_metrics` happens to be empty.

`$_metrics` is deliberately **not** available inside a TSMD `condition` (`ConditionExpressionEvaluator:63` uses
the existing 2-arg `evaluate` — adding a third dictionary namespace there would blur the line between
"deciding whether to run" and "consuming another metric's output," and would need its own ordering guarantee
that this design does not want to promise) nor inside response-column expressions
(`ResponseColumnExtractor:136-140` binds only `_request`/`_response`/prior columns — response columns are
extracted before any metric on that row has even run, so `$_metrics` would always be empty there regardless).
A `condition` that references `$_metrics` sees the reference resolve to plain JSONata `undefined` — the same as
any other unbound name in that dictionary — but whether that produces the broken-condition outcome depends on
the enclosing expression, not on the reference alone: a bare `` $_metrics.`judge`.score.value `` makes the
whole condition evaluate to `undefined` (a non-boolean result ⇒ the broken-condition outcome, chain-abort under
inline mode), whereas `` $exists($_metrics.`judge`.score.value) `` reduces that same `undefined` to a clean
boolean `false` (the metric is simply omitted, chain continues) — `$exists(undefined)` is a clean boolean, not
a broken-condition trigger. Both the condition and response-column limitations are documented with one test
per distinct outcome, not new 400s — the goal is a clear, testable boundary, not a silent trap.

**Write-time validation**: the `expression` field is syntax-checked exactly like `condition`
(`conditionExpressionEvaluator.validate` pattern at `TestSuiteMetricDefinitionService:78,168`) ⇒ HTTP 400 on a
malformed expression. Deliberately **no** cross-TSMD reference validation, no new `MetricDefinitionValidationService`
parameter, no new `UNRESOLVED_REFERENCE` subtype for a metric-to-metric reference — `details` has no schema to
validate against anyway, and a static check for "does this `$_metrics` path exist yet" would need to reason
about execution order across the whole chain, which this design intentionally leaves to the runtime failure
path in D6.

## Risks / Trade-offs

- [Evaluator exception replaces a SUCCESS row with a synthetic ERROR row] → The seam-must-be-total contract
  (D3): `evaluate()` MUST NOT throw; every unit test for the SPI implementation includes an
  exception-injection case asserting the row is untouched.
- [Concurrent buffer corruption under `concurrencyLevel > 1`] → Copy the `PostgresResultBatchWriter` lock
  discipline verbatim (D5); a dedicated multi-thread test is a task-list requirement, not optional polish,
  because the default config (`concurrency-level: 1`) hides the race entirely.
- [Phase 3 aggregates a truncated summary set] → Final flush strictly before the Phase-2/3 block (D5); a
  `TestSuiteEvaluationJobTest` scenario asserts flush-before-Phase-2 ordering directly, not just end-state.
- [Two spec deltas rewrite the same `EvaluationContext` carry-list sentence] → Documented collision with
  `ef-as-dial-app` (D4); whichever change archives second rebases the one paragraph by hand — no code
  conflict expected, only a spec-text merge.
- [Same-row sibling TSMDs silently don't see each other] → Documented as unordered by design (D2), with a test
  proving the non-guarantee rather than leaving it as an unstated assumption; authors needing ordering must
  split across requests/turns.
- [Late TSMD edits mid-run are silently ignored] → Necessary consequence of hoisting the TSMD load ahead of
  Phase 1 (D5); called out explicitly as a behavior change in the `metric-evaluation` delta so it is a known
  trade-off, not a surprise bug report later.
- [Orphaned EvalSummary rows on two rare failure paths] → Accepted, documented, not fixed (D5) — fixing either
  would require a cross-datasource transaction this change does not attempt to introduce.

## Migration Plan

No Flyway migration, no jOOQ regeneration, no data backfill — this change adds no column and no table. Mode is
derived per run at dispatch time, so there is nothing to migrate for existing suites or existing runs; a
pre-existing suite that never references `$_metrics` is unaffected the moment this ships. Rollout is therefore
a normal code deploy with no feature flag and no phased enablement:
1. Ship runner-core plumbing (reserved name, accumulator threading) — inert until the SPI/seam lands, verified
   by the "null evaluator ⇒ byte-identical" regression test.
2. Ship the SPI + seam + backend wiring (detector, factory, job ordering) behind nothing but the detector's own
   `$_metrics`-substring logic — a suite is only ever inline if its own content says so.
3. Ship the `Expression` binding source last, since it is the one new *author-facing* surface (a new
   `$type` value clients can start using) and depends on the frame plumbing already being in place.

Rollback is simply reverting the deploy — there is no persisted state whose shape depends on this change, so
no rollback migration is needed.

**Explicit non-goals with rationale** (also listed in the proposal, restated here for the "why not now"):
- *No `eval-cli` change*: the CLI consumes the same runner-core execution path but has no Phase 2/metric
  provider dispatch machinery at all (it is DB-free and metric-evaluation-free by design). Giving it inline
  support would mean building a parallel evaluator implementation for a standalone tool whose primary use case
  (cross-environment functional parity checking) does not currently need metric-driven request chaining. A
  CLI run of an inline-mode suite is simply out of scope for now (follow-up F3: either implement it or make the
  CLI refuse such a suite with a clear error).
- *No separate inline timeout property*: reusing `metric-evaluation.per-result-timeout-ms` keeps one dial
  instead of two for a concept (how long to wait for a provider) that does not actually change meaning between
  inline and Phase-2 timing — only *when* the wait happens changes, not how long it should be tolerated.

## Open Questions

None — every deferred item (try-it-out `$_metrics` support, declared output-column aliases, CLI inline
support, `required:false` on `Expression` bindings) is a scoped-out follow-up with an explicit rationale above
or in the proposal, not an unresolved design question.
