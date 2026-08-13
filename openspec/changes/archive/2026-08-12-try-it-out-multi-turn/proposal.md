## Why

Try-it-out (`POST /test-suites/{id}/test-cases/{id}/try-it-out`) resolves a test case's `data` exactly once and never looks at `TestCase.multiTurnData`. For a multi-turn test case this silently produces a misleading "turn 1 only, shared-fields-only" preview instead of an error or a faithful preview — the exact `$history`/`$append($history, …)` accumulation authors rely on in request templates (`multi-turn-test-case` spec, `runner.job.TurnLoopExecutor`) never runs. Users authoring multi-turn suites currently have no way to preview a real multi-turn conversation before committing to a full run.

## What Changes

- Try-it-out with a test case (`tryWithTestCase`) now executes **all turns** of a multi-turn test case sequentially, reusing the same turn-planning and history-accumulation mechanics as a real suite run (`PerTurnBindingDetector` for turn-count decision, `RequestResolver.resolveForRun` per turn, `ResponseColumnExtractor` to compute each turn's response columns and feed them forward as the next turn's JSONata frame bindings) — no new templating or extraction mechanism is introduced.
- The response continues to be exactly today's `TryItOutResponseDto` shape (`resolvedRequest`/`response`/`durationMs`/`traceId`/`grafanaTraceUrl`), now representing the **last executed turn**. No new fields: when the suite's request template accumulates history via `$append($history, [...])` (the common case), that final turn's `resolvedRequest` already contains every prior turn's messages, so a separate per-turn payload would just duplicate it.
- On a turn failure mid-sequence, try-it-out fails fast — stops at the first failed turn (same as `TurnLoopExecutor`) and returns that failed turn's request/response.
- **MCP_TOOL suites reject multi-turn test cases at try-it-out** (HTTP 409, same `INVALID_OPERATION` semantics as the existing run-creation guard in the `multi-turn-test-case` spec) — multi-turn is HTTP/DEPLOYMENT-only, consistent with the rest of the system.
- `tryWithVariables` (the variables-only, no-test-case mode) is unchanged — `multiTurnData` only exists on a persisted `TestCase`, so there is no multi-turn source for that mode.

No breaking changes — the response contract is unchanged; only the underlying execution now runs every turn instead of just the first.

## Capabilities

### New Capabilities
(none — this extends an existing capability's requirements)

### Modified Capabilities
- `try-it-out`: the "Try it out with test case data" requirement now covers multi-turn test cases (turn loop, fail-fast, MCP rejection). `TryItOutResponseDto` structure is unchanged.

## Impact

- **Code**: `ResolvedRequestService` (new turn-planning method), `TryItOutService` (`tryWithTestCase` turn loop + MCP guard).
- **API**: No contract change — `TryItOutResponseDto` is unchanged. New HTTP 409 case for MCP_TOOL + multi-turn on the test-case try-it-out endpoint.
- **Dependencies**: reuses existing `evaluation-runner-core` components (`RequestResolver.resolveForRun`, `ResponseColumnExtractor`, `PerTurnBindingDetector`) — no new library dependency, no DB schema change.
- **Docs**: `openspec/specs/try-it-out/spec.md` delta.
