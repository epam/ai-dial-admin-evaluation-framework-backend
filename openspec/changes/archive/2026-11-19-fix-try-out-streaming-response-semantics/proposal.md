## Why

Streaming try-out previews currently expose the same parsed events twice (`response.body.events` and `response.events`), while hiding the assembled response already used internally for evaluation-equivalent extraction. Clients need the evaluated document in `body` and stream diagnostics in `events`.

## What Changes

All changes below are **Planned**; stream parsing and evaluation-equivalent assembly already exist.

- **BREAKING**: For HTTP SSE try-outs, populate `response.body` with the parsed JSON value produced by the existing evaluation response accumulator, not an unconditional events envelope. Preserve object, array and scalar JSON types rather than returning JSON-encoded text.
- Keep parsed events in `response.events`, in receipt order, with existing streaming status and truncation metadata.
- Apply the contract to both variables and test-case endpoints, DEPLOYMENT and ROUTE suites, every multi-turn/multi-request history entry, and the top-level last invocation.
- Preserve existing accumulator fallback behaviour: unknown/empty streams may assemble to an events envelope; interrupted recognized streams may assemble to partial text.
- Preserve non-streaming HTTP bodies, MCP final tool-result bodies and omission of their SSE-only fields. Preserve extraction, fail-fast, invocation history, routing, resource cleanup and timeouts.
- Update DTO documentation, OpenAPI response examples, tests and the try-it-out spec.

Non-goals: new endpoints or fields (`assembledBody`), compatibility switches, frontend implementation, provider assembly changes, MCP transport-event capture, request resolution changes, database/configuration/security changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `try-it-out`: Correct streaming body/events semantics, including partial/fallback responses, history and OpenAPI documentation.

## Impact

- Existing `TryItOutService.readStream` and `TryItOutCoreResponseDto`; no new packages or major components expected. Reuse the already-produced assembled document, parse it with Jackson, and do not reread the stream.
- Both public try-it-out endpoints change SSE `body` semantics. Consumers must read diagnostics from `response.events`, not `response.body.events`. Structured-SSE fallback remains an envelope because that is the engine's actual assembled document, not a compatibility adapter.
- No migrations, dependencies, properties, permissions or credential-handling changes.
- Risk: clients assuming every SSE body has `events` will break; communicate the semantic correction and update API examples alongside the implementation. No compatibility field or staged switch is planned, per the selected contract.
- Verification: focused service tests for each assembly mode, JSON value typing, partial/empty/fallback streams, both endpoints and history; functional API assertions for the wire contract and extraction parity; OpenAPI examples/schema assertions; existing non-SSE/MCP and stream status/resource-cleanup regression coverage; final build/checks per project test cadence.
