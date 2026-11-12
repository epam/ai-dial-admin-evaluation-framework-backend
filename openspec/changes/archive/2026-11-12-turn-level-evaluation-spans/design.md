## Context

See proposal.md - Why. Execution tracing is one `eval.testcase.execute` root per `(testCaseId, runIndex)`; the request chain and turn loop created no spans, so deployment calls hung directly off the root. Metrics spans carried `result.id` but no request/turn position.

## Goals / Non-Goals

**Goals:** readable per-turn structure inside the existing execution trace; metrics traces distinguishable by turn/request; no change to trace boundaries, links, persisted `traceId`, or the Grafana links' result lists.

**Non-Goals:** splitting a repetition into per-turn traces; MCP tracing changes; nesting a separate request-level span above turns.

## Decisions

- **Child span per turn, not a trace per turn.** Turns run sequentially and share the accumulated frame, so the repetition stays the unit of work; keeping one trace preserves the persisted `traceId` contract and the metrics → execution link keyed by `(testCaseId, runIndex)`. Alternative (trace per row) would re-key links and change which trace each UI row opens.
- **One level (`eval.testcase.turn`), not request → turn.** A request span would add a level of noise for the common single-request case; `request.*` attributes on the turn span carry the same information. Span name stays low-cardinality.
- **No `eval.run.id`/`testcase.id` on turn spans.** Grafana links search by those attributes; adding them would multiply the listed spans per trace (the confusion this change addresses). Turns are reachable structurally, e.g. `{span.eval.run.id="…"} >> {span.turn.index="1"}`.
- **String-valued indices.** Matches the existing `run.index` convention so TraceQL filters look the same across attributes.
- **Injectable `TurnTracer` component** (in `evaluation-runner-core`, picked up by its component scan) instead of injecting `OpenTelemetry` into `TurnLoopExecutor`: keeps the turn loop free of tracing plumbing and lets tests supply an SDK or no-op tracer. It runs the turn body with the span current, maps an aborted turn result to ERROR with a short fixed description (verbose log details go to an `eval.turn.failure` span event, so status text does not vary with the retry path), records and rethrows exceptions, and always ends the span.
- **Metrics position from the result row.** `MetricEvaluationWorker` reads `requestIndex`/`totalRequests`/`turnIndex`/`totalTurns` from the row and the label from the run's request labels; no new storage.

## Risks / Trade-offs

- [More spans per repetition — one per request turn] → bounded by turns × requests, same order as the existing deployment spans.
- [Single-turn rows now get a turn span too] → keeps structure uniform; cost is one span.
- [Zero-based indices vs UI's 1-based turn display] → documented in the tracing pattern doc.

## Migration Plan

None: tracing-only, no persisted or API change. Rollback is a revert.
