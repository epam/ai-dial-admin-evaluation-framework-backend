## Context

A FILE column in a dataset schema stores a short EF reference (`@ef/suites/{id}/x.pdf`, `@ef/datasets/{id}/x.pdf`, `public/...`). Before it can be embedded in a request payload it must become a DIAL data ref (`files/<real-bucket>/...`) via `DialFileRefResolver.resolveToDialRef`. Today that conversion happens in three places, each keyed **only** on the template text's `|file` hint:

| Place | Code | Scope |
|---|---|---|
| `TemplateContentResolver.resolveObject` | full-value branch, `typeHint` equalsIgnoreCase `FILE` | URL-encoded + multipart part values + legacy Map `content` body |
| `JsonataSourcePreprocessor.resolveTypedValue` | same check | `jsonataContent` bodies and (via `RequestBodyEvaluator`) the JSONata path |
| `McpRequestResolver.resolveFileRef` | same check, `typeHint.trim()` | MCP_TOOL `argumentTemplate` |

None of them sees the test-case schema, so `${{file}}` bound to a FILE column sends the raw `@ef/...` string and DIAL Core answers 400. Meanwhile `TemplateVariableService` already reports `effectiveType: FILE` for that variable (request-template spec, "Type inference priority": declared hint > `endpointRef` > binding's `testCaseSchema` column > `STRING`), and `BindingValidator` reports no problem, so the suite is "valid".

The schema is already available on every run-path call site: `EvaluationContext.snapshotTestCaseSchema` (used by `TurnLoopExecutor` and set by both `TestSuiteEvaluationJob` and `eval-cli`'s `EvaluationContextFactory`), and `DatasetSchemaProvider.getSchema(datasetId)` on the preview/try-it-out side (`ResolvedRequestService:142`).

Call sites that must receive the schema: `TurnLoopExecutor:333` (`requestResolver.resolveForRun`), `EvaluationWorker:164` (`mcpRequestResolver.resolve`), `TryItOutService` (`resolveForRun`, `resolve`, MCP `resolve`/`resolveWithVariables`), `ResolvedRequestService:93` (`requestResolver.resolve`).

## Goals / Non-Goals

**Goals:**
- A full-value placeholder bound via `dataField` to a `FILE` column resolves to a DIAL ref with no `|file` hint, in all three resolver paths, identically.
- One decision point for "is this placeholder a file reference?" so the three paths cannot drift again.
- `BindingValidator` flags `|file` bound to a non-FILE column (inverse mismatch).
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

`List<FieldDefinitionDto> testCaseSchema` is added to `RequestResolver.resolve/resolveForRun`, `RequestBodyEvaluator.evaluate`, `JsonataSourcePreprocessor.preprocess`, `TemplateContentResolver.resolveObject`, and `McpRequestResolver.resolve/resolveWithVariables`. Old signatures are **deleted**, not kept as delegating overloads, so the compiler lists every caller; a forgotten call site would silently keep the bug. `null`/empty schema ⇒ only explicit hints convert (eval-cli with no schema, `resolveWithVariables`).

*Alternative:* a `ResolutionContext` carrier bundling bindings + schema — cleaner long-term but a larger refactor across signatures this change does not otherwise touch; deferred.

### 3. Validator: inverse mismatch warning in `BindingValidator`

In the existing `Template → Binding` loop, add: `declaredType == FILE`, binding has non-blank `dataField`, the field exists in `schema`, and its type ≠ `FILE` ⇒ `ValidationWarningCode.TYPE` warning on `$.inputBindings` (or the additional-request prefix), message like `"Variable 'x' has a '|file' type hint but is bound to field 'y' of type STRING"`. Skipped when the schema is empty/field unknown (the existing `UNKNOWN` warning already covers that), and for `constantValue` bindings. `BindingValidator` obtains the column type from `FilePlaceholderDetector.boundFieldType`, so validator and runtime read the schema one way.

**Validity impact:** `SuiteValidationService` computes `valid = warnings.isEmpty()`, so *any* warning makes the suite invalid and (per the run-guard rules) blocks run creation. The new warning therefore turns suites that run today — `|file` on a STRING column that holds an `@ef/...` string — into invalid ones. That is what the issue asks for ("validation flags the config"), and the fix is a one-word template edit, but it is a deliberate behaviour change called out in the proposal and the Open Questions.

*Alternative:* auto-resolve only, no warning — rejected by the change request; and silently converting STRING-column values contradicts the column type.

### 4. Multipart: FILE parts opt out of auto-resolution

`MultipartFormDataRequestBodySerializer.addFilePart` takes the resolved value and calls `dialFileRefResolver.resolveToRealPath(fileRef)` itself, which **throws** for a `files/` prefix, and uploads the downloaded bytes. So for a `FormPartType.FILE` part the correct template is the hintless `${{doc}}`, resolved to the raw `@ef/...`. Naively applying "FILE column ⇒ convert" would turn that working case into `files/...` and break it.

Therefore `RequestResolver.resolveMultipartBody` passes **no schema** (auto-resolution off) when `part.getType() == FILE`; `TEXT` parts get the schema and behave like JSON/URL-encoded values. Explicit `|file` on a FILE part is unchanged by this change (it already conflicts with the serializer; recorded as an Open Question, not widened here).

### 5. Try-out with variables is unchanged (explicit `|file` required)

`tryWithVariables` converts each typed value into a `constantValue`-only binding that replaces the suite's bindings wholesale, so there is no bound column and the detector reports "not FILE" (rule 3). A hintless `${{file}}` therefore still needs `|file` in variables mode; only the test-case try-out (`tryWithTestCase`) and real runs use the schema-type rule. Accepted limitation, to be documented in the pattern docs and try-out spec note.

*Alternatives considered and rejected:* carrying the original binding's `dataField` onto the constant binding so the column type survives (extra coupling in `TryItOutVariableBindings`); a try-out-only warning.

### 6. Docs/specs

Update `docs/patterns/jsonata-evaluation-seam.md` and `docs/patterns/dial-file-storage.md` (rule + helper), the four delta specs, and add the helper to `docs/key-packages.md` if it lists `runner.service` classes. No `config.yaml`/`AGENTS.md` change: this follows existing patterns (new component in an existing package, no new convention).

## Component flow

```
run/preview/try-it-out ──schema──▶ RequestResolver / McpRequestResolver
                                     │ full-value placeholder, hint?, binding
                                     ▼
                         FilePlaceholderDetector.isFileReference(hint, binding, schema)
                                     │ true & value is String
                                     ▼
                         DialFileRefResolver.resolveToDialRef ──▶ files/<bucket>/...
suite save/validate ──schema──▶ BindingValidator ──▶ FilePlaceholderDetector.boundFieldType ──▶ TYPE warning on |file ↔ non-FILE
```

Transaction boundaries: none touched (resolution is DB-free; validation runs inside existing service transactions). Error handling: unchanged — `ValidationException` from `DialFileRefResolver` (bad/disallowed ref) propagates exactly as for explicit `|file` and becomes an ERROR row via `TurnLoopExecutor`'s existing handling.

## Risks / Trade-offs

- [New warning invalidates working suites that use `|file` on STRING columns] → documented behaviour change; message names the variable, column and fix; confirm acceptance (Open Question 1).
- [A call site keeps the old behaviour] → old overloads removed so it is a compile error; one functional test covers the run path end to end; unit tests per resolver.
- [Multipart FILE part regression] → Decision 4 plus a dedicated unit test (hintless FILE-bound placeholder in a FILE part still yields the raw `@ef/...`).
- [Snapshot/eval-cli schema `null`] → treated as "no schema": explicit hints only; no NPE.
- [`endpointRef` says a non-string type for a FILE column] → ignored at runtime; a file ref is a string, and the column type is the stronger signal. Mismatch is not warned (out of scope).
- [Try-out with variables disagrees with a real run for a hintless FILE placeholder] → accepted and documented; variables mode has no bound column, explicit `|file` works there as today.
- [Binding refers to a renamed/removed column] → `boundFieldType` is empty ⇒ no conversion; existing `UNKNOWN` warning covers it.

## Migration Plan

Single release, no flag, no data migration. Rollback = revert; no persisted state changes. Suites relying on explicit `|file` are unaffected; suites newly flagged by the validator get a one-word fix.

## Open Questions

1. Should the `|file`-on-non-FILE warning make the suite invalid (current `warnings.isEmpty()` semantics, recommended, matches the issue) or be non-blocking? A non-blocking variant needs a new warning severity concept.
2. Explicit `|file` on a multipart `FILE` part currently fails in the serializer (`files/` prefix rejected). Fix in a follow-up, or add a validator warning here?
3. Should an explicit non-file hint on a FILE column (`${{doc|string}}`) warn? Proposed: no (explicit author choice).
