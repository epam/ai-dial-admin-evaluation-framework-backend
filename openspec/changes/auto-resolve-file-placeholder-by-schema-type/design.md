## Context

A FILE column in a dataset schema stores a short EF reference (`@ef/suites/{id}/x.pdf`, `@ef/datasets/{id}/x.pdf`, `public/...`). Before it can be embedded in a request payload it must become a DIAL data ref (`files/<real-bucket>/...`) via `DialFileRefResolver.resolveToDialRef`. Today that conversion happens in three places, each keyed **only** on the template text's `|file` hint:

| Place | Code | Scope |
|---|---|---|
| `TemplateContentResolver.resolveObject` | full-value branch, `typeHint` equalsIgnoreCase `FILE` | multipart `TEXT` part values + legacy Map `content` body (URL-encoded values use `resolveString`, which ignores hints, so they never convert) |
| `JsonataSourcePreprocessor.resolveTypedValue` | same check | `jsonataContent` bodies and (via `RequestBodyEvaluator`) the JSONata path |
| `McpRequestResolver.resolveFileRef` | same check, `typeHint.trim()` | MCP_TOOL `argumentTemplate` |

None of them sees the test-case schema, so `${{file}}` bound to a FILE column sends the raw `@ef/...` string and DIAL Core answers 400. Meanwhile `TemplateVariableService` already reports `effectiveType: FILE` for that variable (request-template spec, "Type inference priority": declared hint > `endpointRef` > binding's `testCaseSchema` column > `STRING`), and `BindingValidator` reports no problem, so the suite is "valid".

The schema is already available on every run-path call site: `EvaluationContext.snapshotTestCaseSchema` (used by `TurnLoopExecutor` and set by both `TestSuiteEvaluationJob` and `eval-cli`'s `EvaluationContextFactory`), and `DatasetSchemaProvider.getSchema(datasetId)` on the preview/try-it-out side (`ResolvedRequestService`).

Call sites that must receive the schema: `TurnLoopExecutor` (`requestResolver.resolveForRun`), `EvaluationWorker` (`mcpRequestResolver.resolve`), `TryItOutService` (`resolveForRun`, `resolve`, MCP `resolve`), `ResolvedRequestService` (`requestResolver.resolve`).

## Goals / Non-Goals

**Goals:**
- A full-value placeholder bound via `dataField` to a `FILE` column resolves to a DIAL ref with no `|file` hint, in all three resolver paths, identically.
- One decision point for "is this placeholder a file reference?" so the three paths cannot drift again.
- Explicit `|file` (with/without default, nested, MCP) behaves exactly as before.

**Non-Goals:**
- Interpolated placeholders (`"see ${{doc}}"`) stay stringify-only — same as today.
- No change to `DialFileRefResolver`, prefix/ownership rules, `FileRefValidator`, or `constantValue` validation.
- No new DB, config, endpoint, or DTO fields; no OpenAPI contract change.
- Not fixing `|file` on a multipart `FILE` part (see Decision 4) — only making sure the new behaviour does not regress it.
- Not inferring a file type for a variable with **no** binding (MCP's "direct variable-name lookup in data" fallback stays untyped).

## Decisions

### 1. One injectable helper: `runner.service.FilePlaceholderDetector`

New top-level `@Component @LogExecution` in `evaluation-runner-core` (AGENTS.md: conversion/validation logic goes in a specialized injectable, not a private method). Two methods:

- `Optional<SchemaFieldType> boundFieldType(InputBindingDto binding, List<FieldDefinitionDto> schema)` — type of the column named by `binding.dataField`, empty when binding/dataField/schema is null/blank/unknown.
- `boolean isFileReference(String typeHint, InputBindingDto binding, List<FieldDefinitionDto> schema)` — the rule below.

**Rule** (mirrors the documented effective-type order, restricted to what runtime can know):
1. Explicit hint present → hint decides (`file` ⇒ true; any other hint, e.g. `|string` ⇒ false). An author's explicit choice always wins, which is also today's behaviour.
2. No hint → true iff `boundFieldType(...)` is `FILE`.
3. `constantValue` bindings have no column, so rule 2 yields false; a constant file ref still needs `|file` (unchanged; validated by the existing `constantValue` check).

The conversion itself stays `resolved instanceof String s → dialFileRefResolver.resolveToDialRef(s)`; non-String values pass through untouched (existing "non-string not passed to resolveToDialRef" behaviour).

*Alternatives:* (a) three copies of the schema lookup — rejected, that is the drift that caused the bug; (b) rewrite the template/bindings up-front to inject `|file` — rejected, it mutates user data and breaks the "full-value only" scoping; (c) put the rule in `TemplateVariableResolver` — rejected, it returns values, not conversions, and `McpRequestResolver` does not use it.

### 2. Thread the schema as an explicit parameter; remove the old overloads

`List<FieldDefinitionDto> testCaseSchema` is added to `RequestResolver.resolve/resolveForRun`, `RequestBodyEvaluator.evaluate`, `JsonataSourcePreprocessor.preprocess`, `TemplateContentResolver.resolveObject`, and `McpRequestResolver.resolve`. `McpRequestResolver.resolveWithVariables` (a pure alias of `resolve`) is deleted; variables-mode try-out calls `resolve`. Old signatures are **deleted**, not kept as delegating overloads, so the compiler lists every caller; a forgotten call site would silently keep the bug. `null`/empty schema ⇒ only explicit hints convert (eval-cli with no schema).

*Alternative:* a `ResolutionContext` carrier bundling bindings + schema — cleaner long-term but a larger refactor across signatures this change does not otherwise touch; deferred.

### 3. No validator warning for `|file` on a non-FILE column

`BindingValidator` is unchanged. An explicit `|file` hint on a variable bound to a non-FILE column (e.g. a STRING column holding `@ef/...` refs) is a legitimate pattern that works today, because the explicit hint wins (Decision 1, rule 1). Warning on it would make `SuiteValidationService` report such suites invalid (`valid = warnings.isEmpty()`) and block run creation although nothing is broken. The only new behaviour is at run time: a hintless placeholder bound to a FILE column resolves as a file reference.

*Alternative:* an inverse `TYPE` warning for `|file` on a non-FILE column — rejected: it invalidates working suites and flags a valid, explicit author choice.

### 4. Multipart: FILE parts opt out of auto-resolution

`MultipartFormDataRequestBodySerializer.addFilePart` takes the resolved value and calls `dialFileRefResolver.resolveToRealPath(fileRef)` itself, which **throws** for a `files/` prefix, and uploads the downloaded bytes. So for a `FormPartType.FILE` part the correct template is the hintless `${{doc}}`, resolved to the raw `@ef/...`. Naively applying "FILE column ⇒ convert" would turn that working case into `files/...` and break it.

Therefore `RequestResolver.resolveMultipartBody` passes **no schema** (auto-resolution off) when `part.getType() == FILE`; `TEXT` parts get the schema and behave like JSON values. URL-encoded body values are out of scope: they are stringify-only (`resolveString`) and ignore even an explicit `|file`, so hintless detection is deliberately not added there either (rule: hintless resolving applies only where the explicit hint already works). Explicit `|file` on a FILE part is unchanged by this change (it already conflicts with the serializer; recorded as an Open Question, not widened here).

### 5. Try-out with variables keeps the suite's FILE-column bindings

`tryWithVariables` turns each typed value into a `constantValue` binding that replaces the suite's bindings wholesale, which would lose the column and make the detector report "not FILE". To keep variables mode equal to a real run without changing any resolver signature or `InputBindingDto` (which allows exactly one of `dataField`/`constantValue`), `TryItOutVariableBindings.bindToFileColumns` takes the request's constant bindings, the suite's own `inputBindings` for that request and the dataset schema: when the suite binds the variable via `dataField` to a `FILE` column, the constant binding becomes a `dataField` binding on that column and the value moves into the data map (`{column: value}`) passed to `resolve`/`resolveForRun`. The dataset schema is passed to all three variables-mode paths (HTTP single request, HTTP chain per request, MCP). Variables bound to non-FILE columns, to constants or to nothing stay constant bindings (explicit `|file` still needed); a second variable bound to an already-used column also stays constant.

*Alternatives considered and rejected:* carrying `dataField` next to `constantValue` on one binding (violates the DTO's exactly-one rule); a typed column field on `InputBindingDto` or a FILE-variable set threaded through every resolver (signature/DTO growth); a try-out-only warning.

### 6. Docs/specs

Update `docs/patterns/jsonata-evaluation-seam.md` and `docs/patterns/dial-file-storage.md` (rule + helper), the three delta specs (`dial-file-ref`, `mcp-tool-invocation`, `request-template`), and add the helper to `docs/key-packages.md` if it lists `runner.service` classes. `file-ref-validation` is unaffected (its FILE form-part skip is consistent with Decision 4). The `try-it-out` baseline sentence on variables is amended directly (Decision 5), without a delta. No `config.yaml`/`AGENTS.md` change: this follows existing patterns (new component in an existing package, no new convention).

## Component flow

```
run/preview/try-it-out ──schema──▶ RequestResolver / McpRequestResolver
                                     │ full-value placeholder, hint?, binding
                                     ▼
                         FilePlaceholderDetector.isFileReference(hint, binding, schema)
                                     │ true & value is String
                                     ▼
                         DialFileRefResolver.resolveToDialRef ──▶ files/<bucket>/...
```

Transaction boundaries: none touched (resolution is DB-free; validation runs inside existing service transactions). Error handling: unchanged — `ValidationException` from `DialFileRefResolver` (bad/disallowed ref) propagates exactly as for explicit `|file` and becomes an ERROR row via `TurnLoopExecutor`'s existing handling.

## Risks / Trade-offs

- [Explicit `|file` on a STRING column] → no warning and no behaviour change; the explicit hint wins and the suite stays valid.
- [A call site keeps the old behaviour] → old overloads removed so it is a compile error; one functional test covers the run path end to end; unit tests per resolver.
- [Multipart FILE part regression] → Decision 4 plus a dedicated unit test (hintless FILE-bound placeholder in a FILE part still yields the raw `@ef/...`).
- [Snapshot/eval-cli schema `null`] → treated as "no schema": explicit hints only; no NPE.
- [`endpointRef` says a non-string type for a FILE column] → ignored at runtime; a file ref is a string, and the column type is the stronger signal. Mismatch is not warned (out of scope).
- [Try-out with variables disagrees with a real run for a hintless FILE placeholder] → mitigated by `bindToFileColumns` (Decision 5); remaining gap: a variable not bound to a FILE column in the suite, or a shared column, needs explicit `|file`.
- [Binding refers to a renamed/removed column] → `boundFieldType` is empty ⇒ no conversion; existing `UNKNOWN` warning covers it.

## Migration Plan

Single release, no flag, no data migration. Rollback = revert; no persisted state changes. Suites relying on explicit `|file` are unaffected.

## Open Questions

None — resolved:

1. Explicit `|file` on a multipart `FILE` part (currently rejected by the serializer because of the `files/` prefix) is out of scope (Decision 4); no validator warning is added here.
2. An explicit non-file hint on a FILE column (`${{doc|string}}`) does not warn — explicit author choice (Decision 3).
