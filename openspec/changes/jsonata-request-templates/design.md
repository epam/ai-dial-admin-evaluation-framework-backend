## Context

`MultiTurnExecutor` (+ `DeploymentTurnInvoker`, `DeploymentInvocationSupport`, `TurnOutcome`) is the only
place a request/response contract is hardcoded end-to-end: turn count = `multiTurnData.length`; the
resolved body must have a top-level `messages` array; the reply is read from `choices[0].message`;
`stream: false` is force-injected; history is the raw concatenation of message objects. Single-turn
suites share the same `JsonRequestBodyDto`/`ResolvedRequestService` body-resolution path but do not
hardcode a reply shape (extraction is free-form JSONata via `response-columns`).

`com.dashjoin:jsonata:0.9.10` is already a runtime dependency, wrapped by the single allowed call site
`DashjoinJsonataEvaluationService` (per the response-columns spec's implementation note: "the ONLY class
in the codebase that imports from `com.dashjoin.jsonata`"). Its `Frame` API (named variable bindings
carried into an `evaluate` call) has zero call sites — WP0's `JsonataFrameSpikeTest` is the first code in
the repo to exercise it. JSON is a syntactic subset of JSONata: a literal JSON object/array, evaluated as
a JSONata expression against any input, evaluates to itself (modulo the numeric-fidelity caveat below).
This is the property that lets request bodies move to unconditional JSONata evaluation without breaking
any existing Map-shaped template.

## Goals / Non-Goals

**Goals:**
- Replace the hardcoded `messages`/`choices[0].message`/non-streaming multi-turn contract with an
  author-controlled JSONata expression that can target any JSON request/response shape.
- Make the turn loop's length a property of what the template actually binds, not a fixed
  `multiTurnData.length`.
- Keep every existing Map-content, non-JSONata-authored suite byte-identical in behavior (JSON ⊂ JSONata).
- Give response-column extraction visibility into the request that produced a response (`$request`) in
  addition to the response itself (`$response`), for both single-turn and MCP.
- Protect worker threads from a runaway or unbounded-recursion JSONata expression.

**Non-Goals:**
- No JSONata for `url`/`queryParams`/`headers` — `${{}}`-only, unchanged.
- No change to MCP argument-template resolution (`ArgumentTemplateDto`/`McpRequestResolver`) or the
  MCP + multi-turn rejection guard.
- No change to `ConditionExpressionEvaluator`'s dictionary shape.
- No re-evaluation of historical runs.
- No attempt to eliminate the F1 numeric-fidelity gap — documented, not solved (see Decision 8 / Flags).

## Decisions

### Decision 1 — Unconditional JSONata evaluation, no mode detection
Every `application/json` request body is JSONata-evaluated before being sent, for both single-turn and
multi-turn suites, with no "is this a JSONata template?" branch. A plain JSON body is valid JSONata source
that evaluates to itself, so existing suites see no behavior change; new suites gain full JSONata authoring
power (conditionals, `$append`, arbitrary construction) for free.
- *Why:* A mode flag would need its own validation, its own migration path for existing suites, and a
  second code path to keep in sync forever. Unconditional evaluation has exactly one path.
- *Alternative rejected:* a `templateMode: LEGACY | JSONATA` discriminator on `RequestTemplateDto` — more
  surface area for a distinction that JSON ⊂ JSONata already erases.

### Decision 2 — `JsonRequestBodyDto.content` becomes `Object` (Map or String)
`content` accepts either the legacy `Map<String, Object>` (resolved via `TemplateContentResolver`: existing
structural `${{}}` resolution → serialize to JSON via the project `ObjectMapper` → evaluate the serialized
text as JSONata source → the result **is** the request body, i.e. plain JSON echoes itself) or a `String`
(JSONata source: `${{}}` placeholders preprocessed first, then the combined text evaluated directly). Both
variants converge on the same `RequestBodyEvaluator` call — one evaluation path, one runtime contract.
- *Why:* Reusing one Java field keeps `RequestBodyDto`'s polymorphic-by-`contentType` shape (see
  `polymorphic-request-body` spec) unchanged; `Object` is the minimal widening that admits both authoring
  styles without a second DTO or a breaking rename.
- *Alternative rejected:* a new sibling field (`jsonataSource: String`) alongside the existing `content:
  Map` — forces every caller to branch on which field is populated instead of one converged evaluation call.

### Decision 3 — Placeholders resolve before JSONata evaluation, three substitution modes
`${{var}}`/`${{var|type:default}}` keep today's resolution semantics (binding priority, typed defaults) via
the existing `TemplateVariableExtractor`/binding-resolution machinery. Resolution happens as **textual
preprocessing** over the body's raw form (serialized JSON text for a Map, source text for a String) before
that text is parsed as JSONata, with three substitution modes depending on where the placeholder sits:
1. **Quoted-full-value** — `"${{var}}"` is the entire quoted string value → replaced with the **JSON
   serialization** of the resolved typed value (so a bound array/object/number/boolean lands as real
   JSONata literal syntax, not a re-quoted string).
2. **Embedded-in-literal** — `${{var}}` appears inside a larger string literal alongside other text →
   replaced with the **JSON-string-escaped** form of the resolved value (stringified, then escaped for
   safe inclusion inside the enclosing quotes).
3. **Bare** — `${{var}}` appears outside any string literal (only reachable in String-content JSONata
   source, e.g. spliced into an object-constructor expression) → replaced with the **JSON serialization**
   of the resolved value, same as quoted-full-value.
- *Why:* This is the direct generalization of the existing full-value-vs-embedded resolution rule (see
  `request-template` spec, "Full-value placeholder resolution" / "Embedded placeholder resolution") to a
  JSONata source string instead of a plain JSON body — same rule, wider textual context.
  `JsonataSourcePreprocessor` is deliberately a **textual** step (placeholder replacement in a string),
  not a JSONata-level substitution, because placeholders can appear inside JSONata source text that is not
  itself valid standalone JSON (e.g. spliced into a function call argument).
- *Risk mitigated:* WP0's placeholder-injection-precursor spike test confirms a spliced JSON literal
  evaluates correctly inside a larger JSONata object-constructor expression, and that a JSON-string-escaped
  value inside a string literal stays a plain string rather than being re-parsed as JSONata syntax — i.e.
  resolved variable text cannot smuggle in a JSONata operator by accident when it goes through JSON
  serialization/escaping first.

### Decision 4 — Unified turn loop: `N = 1` unless the template binds a per-turn field
Replace `MultiTurnExecutor`'s fixed `N = multiTurnData.length` with: `N = multiTurnData.length` **if and
only if** the effective template's input bindings reference at least one dataset field with
`FieldDefinitionDto.perTurn = true`; otherwise `N = 1`. Single-turn suites are the `N = 1` degenerate case
(`turnIndex`/`totalTurns` stay `null`, byte-identical to today). A multi-turn dataset bound to a suite with
no per-turn binding sends exactly one request built from the shared `data` — it does not resend history
`multiTurnData.length` times for no reason. A single-turn case whose suite happens to bind a `perTurn=true`
field runs with `N = 1` and gets the same unresolved-placeholder warning behavior as an unbound variable
(there is no turn array to source it from).
- *Why:* Turn count should reflect "does this suite's template actually vary per turn," which is exactly
  what a per-turn binding means. This also naturally collapses the previously-impossible-to-express case of
  a multi-turn dataset used by a suite that only reads shared fields.
- *Implementation:* `PerTurnBindingDetector` (new, `service.domain.job`) inspects the suite's
  `inputBindings` against `EvaluationContext.snapshotTestCaseSchema` (Flag F3) to answer the boolean;
  `TurnLoopExecutor` (new) replaces `MultiTurnExecutor`'s loop body, keeping the fail-fast/per-turn-row
  persistence contract unchanged.
- *Alternative rejected:* a suite-level `multiTurn: boolean` flag — reintroduces the flag multi-turn
  deliberately avoided (Multi-turn is emergent from data, not a suite flag; see `multi-turn-test-case`
  spec) and would need to be kept in sync with the bindings by hand.

### Decision 5 — Request-template frame: previous turn's extracted columns as named variables
Each turn's JSONata evaluation runs with a `Frame` carrying the **previous turn's reconciled extracted
response columns**, bound directly by column name (e.g. a response column named `history` becomes
`$history`). Turn 0 evaluates with no such bindings — those names are genuinely unbound, so referencing
them evaluates to `undefined` (Java `null`), confirmed by WP0's spike (`$history` with an empty frame →
null). The root input document for every turn's evaluation is an empty object — all state flows through
named frame variables, not through a positional `$` input document.
- *Why:* This replaces the hardcoded message-array accumulation with an author-controlled JSONata
  expression (typically `$append($history, [...new turn messages...])`), which generalizes to any reply
  shape the author's response columns choose to extract, not just OpenAI `choices[0].message`.
- *Confirmed by spike:* `$append($history, [...])` with `$history` unbound returns just the new array
  (undefined-append) — exactly the turn-0 behavior an author would want without special-casing turn 0 in
  their expression.

### Decision 6 — Response-column extraction frame: `$request` + `$response`
Extraction gains a `Frame` binding `$request` (the parsed JSON of the request body actually sent) and
`$response` (the parsed JSON of the response body) alongside the existing evaluation. **The root document
stays the raw response body** — every existing response-column expression (e.g.
`choices[0].message.content`) is untouched syntax; `$request`/`$response` are additive access points for
new expressions that need to correlate request and response (e.g. echoing back a request field, or
computing a diff). Applies uniformly to single-turn, multi-turn, and MCP.
- *Why additive, not a root-document change:* Changing the root document would be a breaking change for
  every existing response-column expression in production; adding frame variables is purely additive.

### Decision 7 — Write-time 400s: JSONata syntax validity + reserved-name collisions
Two new hard validation failures at suite create/update:
1. A `String`-content request body must parse as valid JSONata (`jsonataEvaluationService.validateExpression`,
   reusing the existing parse-only validation path) — invalid syntax → HTTP 400.
2. A response column `name` must not collide with a JSONata built-in function name (the registry the
   query-DSL function catalog already enumerates — reused here, not re-derived) or with the reserved frame
   names `request`/`response` — collision → HTTP 400. This closes off the ambiguity of `$request` meaning
   "the frame variable" vs. "a response column named request" referenced as `$request` after extraction.
- *Why hard 400, not a soft warning:* both are structural authoring errors with no sensible partial
  behavior (an unparseable JSONata source can't be "partially" evaluated; a name collision is
  unresolvable ambiguity, not a data-quality warning like the existing soft-validation warnings).

### Decision 8 — Runtime contract: object-or-ERROR, JSONata-failure-or-ERROR
The evaluated body must resolve to a JSON object (matching `JsonRequestBodyDto`'s existing "the body is an
object" assumption) or the row is ERROR; any JSONata evaluation exception (invalid runtime expression,
`setRuntimeBounds` abort) is likewise an ERROR row. This is the direct generalization of
`MultiTurnExecutor`'s existing "resolved body without a top-level `messages` array → ERROR" contract to
"resolved body that isn't an object at all → ERROR," now checked once in `RequestBodyEvaluator` instead of
per-turn in the executor. The fail-fast loop semantics (earlier turns persist as SUCCESS, the failing turn
persists as one ERROR row, later turns are not sent) are unchanged.

### Decision 9 — Streaming on every turn; DIAL custom-content accumulation
The `content.put("stream", false)` force-injection is removed — every turn streams like a single-turn
suite already does. `StreamingResponseAccumulator`'s OpenAI-mode assembly is extended to also accumulate
DIAL's `choices[i].delta.custom_content` across SSE chunks: scalar `custom_content` fields overwrite (last
write wins), `attachments`/`stages` arrays merge by index (each partial's array elements at index `k` merge
into the accumulator's index-`k` element, preserving fields present in one partial but not another), and
the assembled result lands on the final `choices[0].message.custom_content` — the same place a
non-streaming DIAL response would carry it, so response-column expressions targeting `custom_content` do
not need to know whether the underlying call streamed.
- *Why:* Multi-turn history accumulation (Decision 5) is now driven by extracted response columns, which
  can only be extracted from an assembled — not raw-chunked — response body; streaming was force-disabled
  originally because the old hardcoded `choices[0].message` accumulation had no chunk-assembly path. Now
  that a general chunk-assembly path (`StreamingResponseAccumulator`) already exists for single-turn SSE
  suites, reusing and extending it removes the reason multi-turn ever needed to force `stream: false`.
- *Implementation:* `CustomContentAccumulator` (new, `service.domain.job`) is a standalone
  merge-by-index/overwrite-scalar component invoked from `StreamingResponseAccumulator`, kept separate so
  its by-index array-merge logic is independently unit-testable from SSE chunk parsing.

### Decision 10 — Untouched surfaces
MCP tool invocation, `url`/`queryParams`/`headers` resolution (`${{}}`-only), `ConditionExpressionEvaluator`,
and the MCP + multi-turn rejection guard are explicitly out of scope — none of them touch the JSON request
body evaluation path this change reworks.

### Decision 11 — `Frame.setRuntimeBounds` via new `JsonataProperties`
A new `@ConfigurationProperties(prefix = "jsonata")` class (`evaluation-timeout-ms`, `max-recursion-depth`)
feeds `Frame.setRuntimeBounds(timeoutMs, maxDepth)` on every `Frame` used for request-template and
response-column evaluation. Defaults live in `application.yml` per the project's config-defaults
convention; both properties are documented in `docs/configuration.md`.
- *Confirmed by spike:* `setRuntimeBounds` aborts a runaway recursive expression
  (`($f := function(){$f()}; $f())`) with a `JException` well before the configured timeout, via the
  recursion-depth check (`Timebox.checkRunnaway`); a trivial expression under generous bounds is
  unaffected.

## Spike Findings (`JsonataFrameSpikeTest`, WP0)

All 10 pinned behaviors pass. Two findings refine the settled decisions beyond what was assumed going in:

- **F1 (numeric fidelity, confirmed):** dashjoin JSONata represents numbers as Java `double` internally.
  An explicit `1.0` literal echoes back as integral `1` (loses "double-ness"); a `long` above `2^53`
  (`9007199254740993`) round-trips to the nearest representable double
  (`9.007199254740992E15`, i.e. `9007199254740992`, off by one from the true input). This is a caveat to
  document (`request-template` spec), not a bug to fix — no lossless numeric path exists through this
  library version. Suites relying on exact large-integer IDs in a JSONata-evaluated body should keep them
  as strings.
- **F2 (refined — important correction to the original assumption):** the original plan text described
  "failed extraction binds null (≠ turn-0 undefined) — bind as-is." The spike shows this is **only true if
  the binding uses the JSONata explicit-null sentinel (`Jsonata.NULL_VALUE`)**. Binding a plain **Java**
  `null` via `Frame.bind(name, (Object) null)` is **indistinguishable from leaving the variable unbound** —
  `Frame#lookup` returns Java `null` in both cases, so `$append($history, x)`'s `arg1 == null` check fires
  identically whether `$history` was never bound or was bound to Java `null`. Getting real null-append
  semantics (`$append(null, [1])` → `[null, 1]`) requires binding `Jsonata.NULL_VALUE` specifically — this
  is now a concrete implementation requirement for WP4/WP6: when a previous turn's extraction genuinely
  failed (as opposed to "no such column"), the frame-population code must bind `Jsonata.NULL_VALUE`, not
  Java `null`, for that column name, or the intended "explicit null in history" semantics silently degrade
  to "as if never extracted." This also means the `DashjoinJsonataEvaluationService`-is-the-only-importer
  invariant (from the `response-columns` spec's implementation notes) needs a narrow, explicit exception
  for whatever component populates the request-template frame (it needs `Jsonata.NULL_VALUE`) — call this
  out in WP4's task so it isn't missed as an accidental invariant violation.
- **F3:** `EvaluationContext` has no test-case-schema field today (confirmed by reading the class) — WP6
  adds `snapshotTestCaseSchema` (sourced from the existing `SuiteSnapshotDto`, no new persistence) so
  `PerTurnBindingDetector` can resolve `perTurn` per bound field without a live dataset lookup mid-run.

## Data Model Changes

None. No new Flyway migration. `requestTemplate` and `responseColumns` remain JSONB on `test_suites`
(`RequestTemplateDto`/`ResponseColumnDefinitionDto` unmarshal unchanged except `JsonRequestBodyDto.content`
widening from `Map<String, Object>` to `Object`, which is a backward-compatible JSON schema widening — a
persisted Map-shaped `content` deserializes exactly as before). `EvaluationContext.snapshotTestCaseSchema`
is an in-memory field populated per run from the already-persisted `SuiteSnapshotDto`, not a new column.

## API Contract Details

- `RequestBodyDto` (`application/json` variant) OpenAPI schema: `content` becomes `oneOf` [object, string]
  (previously object-only). Existing clients sending a Map body are unaffected.
- Two new HTTP 400 cases on `POST /api/v1/test-suites` / `PUT /api/v1/test-suites/{id}`: invalid JSONata
  String body source; response column name colliding with a JSONata built-in function name or
  `request`/`response`.
- No new endpoints; `resolved-request` preview (`GET .../resolved-request`) now runs the body through the
  same `RequestBodyEvaluator`, so its output reflects the JSONata-evaluated body rather than the raw
  structurally-resolved Map.

## Transaction Boundaries

Unchanged. JSONata evaluation is in-process, CPU-bound work with no DB access — it does not introduce or
require a new `@Transactional` boundary. `EvaluationContext.snapshotTestCaseSchema` is populated during the
existing run-initialization read (already inside a transactional read of the snapshot).

## Error Handling Approach

Fail-fast, matching the project's data-integrity convention: JSONata parse/evaluation failures throw and
are caught at the same layer `MultiTurnExecutor`/`RequestBodyEvaluator` already catch turn-level failures,
producing an ERROR row with the exception logged (exception as last SLF4J argument, per AGENTS.md). Write-time
400s (Decision 7) are hard rejections, not warnings, since there is no sensible degraded behavior for
unparseable JSONata source or an unresolvable reserved-name collision.

## Component Interaction Flow (per turn, DEPLOYMENT HTTP suite)

1. `TurnLoopExecutor` determines `N` via `PerTurnBindingDetector` (Decision 4) and iterates `0..N-1`
   (or runs once for `N = 1`).
2. For each turn index, `EvaluationWorker`/`TurnLoopExecutor` builds the turn's effective test-case data
   (shared `data` merged with `multiTurnData[i]` when `N > 1`, unchanged from today) and resolves
   `${{}}` placeholders (`ResolvedRequestService`, unchanged for URL/query/headers; body placeholders now
   feed `JsonataSourcePreprocessor` instead of direct structural substitution).
3. `TemplateContentResolver` produces the preprocessed body text (Map path: resolve → serialize; String
   path: preprocess placeholders directly) and hands it to `RequestBodyEvaluator`.
4. `RequestBodyEvaluator` builds a `Frame` with the previous turn's extracted columns (Decision 5),
   applies `setRuntimeBounds` (Decision 11), evaluates, and validates the result is a JSON object
   (Decision 8) — ERROR row on any failure.
5. `DeploymentTurnInvoker` sends the request streaming (Decision 9); `StreamingResponseAccumulator`
   assembles the full response body, including any DIAL `custom_content` (Decision 9).
6. `ResponseColumnExtractor` evaluates response columns with the `$request`/`$response` frame (Decision 6)
   against the assembled response body; the reconciled extracted columns become next turn's frame
   bindings (back to step 2 for turn `i+1`).
7. The turn row persists exactly as today (`TestCaseRunResult` with `turnIndex`/`totalTurns`).

## New/Modified Components (by package)

- `service.domain` (new): `JsonataSourcePreprocessor`, `RequestBodyEvaluator`, `TemplateContentResolver`
  (refactor of existing structural-resolution code), `JsonataReservedNames` (constants class).
- `service.domain.job` (new): `PerTurnBindingDetector`, `TurnLoopExecutor` (replaces `MultiTurnExecutor`'s
  loop body — `MultiTurnExecutor` itself may be retired/renamed once the unified loop lands),
  `CustomContentAccumulator`.
- `configuration.properties` (new): `JsonataProperties`.
- `service.domain.job.EvaluationContext`: `+ snapshotTestCaseSchema`.
- `service.domain.ResponseColumnExtractor` / `DashjoinJsonataEvaluationService`: new 3-arg
  evaluate-with-frame overload.
- `service.domain.job.StreamingResponseAccumulator`: extended for DIAL custom-content accumulation.
- `service.domain.TestSuiteRequestValidator`: two new 400 checks (Decision 7).
