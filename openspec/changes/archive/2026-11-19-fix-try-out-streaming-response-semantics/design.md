## Context

See `proposal.md` for motivation and `specs/try-it-out/spec.md` for the planned contract. Design is warranted because this is a breaking wire-semantic change involving JSON type conversion, provider fallback/error behaviour and multiple invocation views.

`TryItOutService.readStream` already parses the stream once into `SseParseResult`, invokes `StreamingResponseAccumulator.assemble`, and builds parsed `SseEventDto` entries. It currently constructs an additional display envelope for `response.body`; the accumulator's serialized document survives separately as `StreamingInvocation.extractionDocumentJson` and then `TurnInvocationResult.extractionDocumentJson`. Extraction already uses the latter, so changing the display body must not change extraction.

Both endpoints and all HTTP flavours share this method and `TryItOutCoreResponseDto`. History builders reuse each invocation's response DTO. MCP uses a separate final-tool-result path. The existing `add-multi-request-try-out` change contains older display/extraction wording; its artifacts are not the authority for this new correction and must not be silently rewritten. Main-spec requirement blocks will be synchronized from this change when implemented.

## Goals / Non-Goals

**Goals:** Reuse one assembled document for public body and extraction; preserve JSON value types; keep event diagnostics and all existing execution/status semantics; update top-level/history consistently without separate assembly paths.

**Non-Goals:** New assembly rules, changing runner-core, persisting raw events, adding `assembledBody` or a compatibility switch, capturing SDK MCP transport events, restructuring chain execution or adding a general conversion framework.

## Decisions

### Correct the existing fields, rather than add a second body

Selected by the user: `body` is the actual evaluation-equivalent response, `events` is the parsed SSE array. An additive `assembledBody` would preserve compatibility but perpetuate the misleading body contract and duplicate representations. No endpoint/request/field names or Java DTO field types change; only SSE `body` semantics change. Non-SSE HTTP and MCP remain untouched.

Illustrative chat-completions response:

```json
{
  "response": {
    "statusCode": 200,
    "streaming": true,
    "body": {"choices": [{"message": {"role": "assistant", "content": "Hello"}}]},
    "events": [
      {"event": "message", "data": {"choices": [{"delta": {"content": "Hel"}}]}},
      {"event": "message", "data": {"choices": [{"delta": {"content": "lo"}}]}}
    ]
  }
}
```

This is an illustrative subset; assembly output remains defined by the existing accumulator, not by this example.

### Parse the already-produced serialized document once for the wire body

Flow: live stream → existing parser → one parse result → existing accumulator → serialized assembled JSON → Jackson `JsonNode` for `response.body`; the same serialized JSON remains the extraction input. The parsed event list independently populates `response.events`. Never assign the serialized JSON string directly to `body`, because that would double-encode documents and partial text.

Use the existing injected ObjectMapper and existing JSON parsing/error-handling conventions. If the accumulator produces no serialized document (Java null, for example after assembly serialization failure), expose a typed `{"events":[...]}` envelope built directly from the already-parsed events, instead of a null body. Retain the existing error status and skipped extraction; this is a diagnostic fallback, not successful assembly. Explicit serialized JSON null remains `NullNode`, and empty partial strings remain strings: neither triggers fallback. String, number, boolean, object and array nodes retain their types. Do not serialize through `Map<String,Object>`: explicit null properties must survive global NON_NULL inclusion.

Keep existing lightweight invocation records and the extraction string rather than refactoring chains solely to remove that string. Keep a narrowly scoped typed events-envelope helper for absent assembled output; use it only on that fallback branch. Retain the engine's own structured-SSE fallback for generic/empty streams. Update comments that promise an unconditional display envelope.

### Preserve assembly and failure semantics exactly

Chat completions (including custom extensions), Responses API and Anthropic use existing provider assembly. Unrecognized/empty streams use the engine's structured envelope. Non-success recognized streams retain partial text as a JSON string value; the parsed response body is therefore not guaranteed to be an object.

Status, truncation metadata, skipped extraction, upstream status and chain fail-fast remain unchanged. JSON parsing of internally generated assembled output is an integrity conversion, not another stream read: malformed internal JSON should use the existing specific-exception fail-fast handling, not silently fall back to the former envelope. No new broad catch or warning-only recovery.

### Reuse the shared boundary for all invocation views

No separate history-body mapper or per-endpoint assembly. Shared response construction provides both fields for every invocation and preserves top-level duplication of the last response. Confirm with endpoint-level and multi-invocation tests, including mixed streaming/non-streaming history and early failure.

### Data model, layering and transactions

No persistence/schema/configuration/security changes; no new packages, beans or major classes expected. Work stays in the existing service/DTO boundary plus tests and OpenAPI examples. Invocation remains outside suite/case loading transactions; no transaction boundary changes. Parsing one trusted serialized JSON document is not complex conversion requiring a new injectable component.

## Risks / Trade-offs

- [Existing clients read `body.events`] → Breaking change is explicit. Consumers read `events` for diagnostics and handle variable JSON body types. Update both endpoint examples/descriptions in the same release.
- [String double encoding or null loss] → Use `JsonNode`; assert actual wire JSON types and nested explicit nulls, not only Java object types.
- [Partial streams mistaken for success] → Keep status/truncation/fail-fast tests and assertions that extraction remains skipped.
- [Unknown streams still have events in body] → Document this as the evaluation fallback, not a legacy-body guarantee; test recognized versus fallback modes distinctly.
- [Spec overlap with an existing change] → Synchronize against current main requirements, preserving all existing scenario names. During final drift/sync, identify older active-change wording as superseded by this explicit contract; do not modify unrelated artifacts without scope review.
- [Dependency on custom-fields assembly committed in `6f2a4c11`] → Preserve that existing assembly behaviour without altering its merge implementation.

## Migration Plan

1. Implement the shared assignment/type conversion and regressions, preserving non-SSE/MCP and execution behaviour.
2. Update DTO/OpenAPI descriptions and examples for both endpoints, relevant pattern documentation and the try-it-out main spec through delta sync. Correct the main spec Purpose's obsolete unconditional-envelope wording during sync (a delta Purpose is intentionally omitted).
3. Communicate the breaking semantics to API consumers before deploying. Frontend integration is outside this backend change; no automated external communication is authorized by these artifacts.
4. Verify targeted service/API/OpenAPI tests, existing MCP/non-SSE regressions and the final build per project cadence.
5. Rollback, if needed, restores the previous SSE body assignment and matching documentation; clients must be coordinated with the body-contract rollback. No data migration is required.
