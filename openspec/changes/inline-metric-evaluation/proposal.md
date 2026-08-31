## Why

In multi-request and multi-turn suites, metric evaluation runs today as a wholly separate Phase 2 *after* every
Phase 1 row is written (`TestSuiteEvaluationJob.executeRunAsync` → `EvaluationWorker`/`TurnLoopExecutor` →
`InProcessMetricEvaluationExecutor`). A metric's output — its numeric `value` and its rich `details` object —
therefore cannot influence anything downstream in the same run: it cannot steer the next request's JSON body
(e.g. route to a different follow-up prompt based on a judge's verdict) and it cannot feed the next request's
own metric bindings (e.g. score request #1 using request #0's judge rationale). Evaluating a metric immediately
after the row it scores — inline, inside Phase 1 — makes that output available to every later step of the same
chain via a new `$_metrics` JSONata frame, without waiting for Phase 2.

## What Changes

- Add a `$_metrics` JSONata frame (`{tsmdName}.{outputField} → {value, details} | {error}`, plus
  `{tsmdName}.error` for a wholesale metric failure) that accumulates along a suite's request chain and across
  turns, readable from any later request's JSON body (`content`/`jsonataContent`) and from a new `Expression`
  metric-binding source.
- Derive "inline mode" per run from the run's `SuiteSnapshotDto` + live TSMD list (substring scan for
  `"$_metrics"`); no suite flag, no config, no DB column. `MCP_TOOL` suites are never inline.
- Evaluate each TSMD immediately after its row is built inside `TurnLoopExecutor`'s CONTINUE branch (SUCCESS
  rows only), writing its EvalSummary directly instead of waiting for Phase 2. A metric failure aborts the
  chain (remaining turns/requests skipped) but the row itself stays `SUCCESS`.
- Hoist `run_metric_snapshots` writing out of `InProcessMetricEvaluationExecutor` into `TestSuiteEvaluationJob`,
  ahead of Phase 1, so it happens once for every run regardless of mode. **BREAKING (internal contract)**: the
  Requirement "RunMetricSnapshot capture before evaluation" and the orchestration paragraph in `metric-evaluation`
  no longer describe `InProcessMetricEvaluationExecutor` as the snapshot writer.
- For inline runs, Phase 2 becomes a propagate-only pass: SUCCESS rows are skipped (already scored inline),
  non-SUCCESS rows still get `buildPropagatedItem` exactly as today. Non-inline runs are unaffected.
- Add a fourth metric-binding source, `Expression` (`$type: "Expression"`), letting a TSMD's `configBindings`/
  `inputBindings` transform `$_metrics` (plus `data`/`response`) via an author-supplied JSONata expression,
  validated for syntax at write time (HTTP 400 on malformed expressions; no cross-TSMD reference validation).
- New SPI in `evaluation-runner-core` (`InlineMetricEvaluator`/`InlineMetricRequest`/`InlineMetricResult`) so
  the DB-free runner module can invoke a per-run evaluator without depending on the EF backend; the backend
  supplies the implementation via a new per-run factory.
- Reserve `_metrics` as a response-column name (alongside existing `_request`/`_response`), rejecting a
  colliding column name with HTTP 400.
- Document (no code): `$_metrics` resolves to JSONata `undefined` in Try-It-Out preview; `$_metrics` is not
  available in TSMD `condition`s or response-column expressions; two known orphan-summary edge cases shared
  with the existing Phase-1/Phase-2 split; JSON-body `NON_NULL` serialization means a present-null metric field
  cannot actually be transmitted in the next request's emitted body even though it is visible to JSONata.

**Non-goals** (explicitly out of scope, per the reviewed plan): no suite-level flag or `RunConfigDto` field, no
Flyway migration, no `eval-cli` changes, no separate inline-specific timeout property (reuses
`metric-evaluation.per-result-timeout-ms`), no declared output-column aliases, no `$_metrics` support inside
TSMD `condition`s. These are noted as explicit follow-ups.

## Capabilities

### New Capabilities
_(none — this change extends existing capabilities only)_

### Modified Capabilities
- `metric-evaluation`: the Requirement "RunMetricSnapshot capture before evaluation" (spec.md:356) and the
  orchestration paragraph in "Metric evaluation executor orchestration" (spec.md:46) are rewritten — snapshot
  writing moves to `TestSuiteEvaluationJob` for **all** runs; add inline-mode requirements (derived-mode
  detection, inline EvalSummary writing, Phase-2 propagate-only pass for inline runs, final-flush-before-Phase-2
  ordering, TSMD load timing, known orphan cases).
- `conditional-metric-execution`: document the inline-mode divergence — a broken `condition` result still
  aborts the whole chain under inline evaluation's fail-fast rule (D6), and `$_metrics` is not available inside
  a `condition` expression.
- `eval-execution-engine`: `EvaluationContext` gains a nullable `InlineMetricEvaluator` field; note the textual
  collision with the pending `openspec/changes/ef-as-dial-app` delta, which rewrites the same carry-list
  sentence to drop the JWT token field.
- `tsmd-validation`: add the fourth `Expression` metric-binding source — shape, per-row frame construction, and
  write-time JSONata syntax validation (HTTP 400); explicitly no cross-TSMD reference validation.
- `test-suite-metric-definitions`: the "Parameter binding model" requirement's three-source list (`TestCase`,
  `Response`, `Constant`) gains a fourth source, `Expression` (`$type: "Expression"`), documented alongside the
  existing three; the actual runtime/validation behavior is specified in `tsmd-validation` and `metric-evaluation`.
- `multi-request-suite`: the `$_metrics` frame accumulates along the request chain the same way response-column
  frame bindings do today (last-writer-wins per `(tsmd, field)`, threaded through `RequestChainExecutor`).
- `request-template`: `$_metrics` becomes a bindable frame variable for a request's JSON body, subject to the
  same runtime-object-contract rules as today; document that an explicit-null metric field cannot survive
  serialization into the next request's emitted body (shared-mapper `NON_NULL` inclusion).
- `response-columns`: reserve `_metrics` as a response-column name, alongside `_request`/`_response`.
- `try-it-out`: document that `$_metrics` resolves to JSONata `undefined` in the try-it-out preview path (no
  inline evaluation runs there).
- `evaluation-runner-core-module`: add the new `InlineMetricEvaluator` SPI (interface + two small DTOs) to the
  module's owned-classes list, while keeping the backend-only strategy — `MetricEvaluationWorker` and
  `InProcessMetricEvaluationExecutor` continue to stay out of the shared module.

## Impact

- **Code**: `evaluation-runner-core` — new `runner/job/InlineMetricEvaluator.java`, `InlineMetricRequest.java`,
  `InlineMetricResult.java`; `TurnLoopExecutor`, `RequestExecutionResult`/`RequestChainExecutor`,
  `EvaluationContext`, `JsonataReservedNames` all gain the metrics-frame plumbing. EF backend — new
  `service/domain/job/InlineModeDetector.java`, `MetricRowEvaluator.java`, `InlineMetricEvaluatorImpl.java`,
  `InlineMetricEvaluatorFactory.java`, `service/domain/dto/ExpressionBindingSourceDto.java`; changes to
  `InProcessMetricEvaluationExecutor`, `MetricEvaluationContext`, `TestSuiteEvaluationJob`, `BindingResolver`,
  `MetricEvaluationWorker`, `MetricBindingSourceDto`.
- **API**: new `$type: "Expression"` metric-binding-source subtype (OpenAPI discriminator example required);
  no other endpoint contract changes. `_metrics` becomes a reserved response-column name (HTTP 400 on
  collision) — a latent tightening for any pre-existing suite that happens to use that name, same class as the
  existing `_request`/`_response` reservation.
- **Data**: no schema change, no Flyway migration, no jOOQ regeneration. `run_metric_snapshots` write timing
  moves earlier in the job but the written rows are unchanged.
- **Security**: none.
- **Dependencies**: none new.
- **eval-cli**: untouched; a CLI-driven run of an inline-mode suite is out of scope (follow-up).
- **Docs**: new `docs/patterns/inline-metric-evaluation.md` + AGENTS.md pattern-table row; `docs/configuration.md`
  `Applied when` column update for the two reused `metric-evaluation.*` properties; `openspec/specs/README.md`.
- **Risks**: the `ef-as-dial-app` change modifies the same `eval-execution-engine` requirement paragraph this
  change modifies — whichever change archives second must rebase that paragraph by hand. Thread-safety of the
  per-run inline evaluator buffer under `concurrencyLevel > 1` is load-bearing and covered by a dedicated
  concurrency test. Two pre-existing-shaped orphan-summary edge cases (chain-level exception discarding
  already-produced rows; a batch-flush failure after summaries for that batch already committed) are accepted,
  not fixed, and documented in the `metric-evaluation` delta.
- **Rollout**: no feature flag; behavior is derived per-run from suite content, so existing suites without
  `$_metrics` anywhere execute byte-identically to today (verified by a dedicated regression check). No
  migration or backfill needed.
- **Test plan**: see `tasks.md` — runner-core unit tests for frame accumulation/propagation, backend unit tests
  for the detector/evaluator/factory, split `InProcessMetricEvaluationExecutorTest` (propagate-only + snapshot
  hoist), and end-to-end `MultiRequestChainRunFunctionalTests`/`TestSuiteRunFunctionalTests` covering the
  cross-request metric read, failure-aborts-chain semantics, cancellation, and the non-inline regression case.

## Follow-ups

Not part of this change; each is a candidate for its own openspec change stub, to be created during
`/opsx:archive` of this change per the project's archive checklist in `openspec/config.yaml` `rules.archive`:

- **Try-it-out `$_metrics` support**: give the try-it-out preview path a way to actually evaluate `$_metrics`
  (or an explicit opt-in cost-bearing preview mode) instead of always resolving it to `undefined`.
- **Declared output-column aliases**: let an author declare a stable, shorter alias for a `$_metrics.<tsmdName>.
  <outputField>` path instead of always spelling out the full backtick-quoted reference.
- **`eval-cli` inline support or refusal**: either implement inline metric evaluation for `eval-cli`'s
  standalone run path, or make the CLI detect and refuse an inline-mode suite with a clear error instead of
  silently running it as if it were non-inline.
- **`required: false` on `Expression` bindings**: let an `Expression` binding opt out of the fail-fast-on-
  `undefined` behavior, e.g. for a metric that legitimately may not have produced output yet.
