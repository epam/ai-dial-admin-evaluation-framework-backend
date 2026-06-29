## Context

Today the evaluation engine executes **one request per (test case, run index)**. The flow:

1. `TestSuiteEvaluationJob.executeRunAsync` snapshots the suite (`SuiteSnapshotBuilder.build` → `SuiteSnapshotDto`, persisted as JSON on `test_suite_runs.suite_snapshot`), transitions the run to `RUNNING`, then delegates to the executor.
2. `InProcessEvaluationExecutor` pages test cases and submits one `EvaluationWorker.execute(input, context, runIndex, responseColumns)` task per (test case, run index) on a virtual thread, gated by a concurrency semaphore.
3. `EvaluationWorker.execute` resolves the template (`ResolvedRequestService.resolve`), invokes DIAL Core (`DialCoreDeploymentInvoker`, with `invokeWithRetries`), extracts response columns (`ResponseColumnExtractor.extract` → JSON **object** `{col: value}`), and builds **one** `TestCaseRunResult` row.
4. After deployment phase, `TestSuiteEvaluationJob` runs the metric phase: `InProcessMetricEvaluationExecutor` reads each result's `extractedColumns` via `BindingResolver.parseJsonMap` (→ `Map`), resolves metric input/config bindings, invokes the metric deployment, and writes an `EvalSummary` row (copying `extractedColumns` into the summary, which feeds CSV export and query filtering).

The request body is **template-driven and arbitrary** (`JsonRequestBodyDto` / multipart / url-encoded) — there is no built-in `messages` concept. The result row is `1 : (runId, testCaseId, runIndex)`, enforced by `uq_results_run_case_index`. `extractedColumns` is a free-form JSONB column.

This change adds a **POC** for multi-turn (multi-step) conversation evaluation against chat-completions deployments, deliberately minimizing blast radius by reusing existing columns and confining new behavior to one new component plus additive fields.

## Goals / Non-Goals

**Goals:**
- Drive a fixed, author-scripted sequence of conversation turns per test case, accumulating chat-completions `messages` history and re-sending the full history each turn.
- Reuse the existing `TestCaseRunResult` schema unchanged: `responseBody` = accumulated `messages`, `extractedColumns` = per-step array.
- Score metrics against the **last** turn's extracted columns, with zero changes to the summary/export/query layer.
- Keep the single-step path byte-for-byte unchanged when `multiStep == false`.
- Confine new logic to an independently testable injectable component (`MultiStepConversationExecutor`).

**Non-Goals (deferred to the real feature):**
- Bindings that reference **prior steps' responses** (POC bindings read only test-case data + constants).
- Streaming (SSE) multi-step responses.
- Configurable / non-OpenAI assistant-reply extraction path.
- First-class per-step result rows, per-step metrics, per-step `traceId`.
- Cross-step retry aggregation; dynamic (data-driven) turn counts; UI.

## Decisions

### D1 — Chat-completions `messages` contract; framework owns history merge
Multi-step requires the JSON body to carry a top-level `messages` array. The engine maintains a running `messages` list `H` (initially empty) and, per step _i_:
1. Resolve the **single, unchanged** `requestTemplate` with `multistepInputBindings[i]` + test-case data → `body_i` (reusing `ResolvedRequestService.resolve`).
2. Append `body_i.messages` (the new turn — normally one `user` message) to `H`.
3. **Overwrite** `body_i.messages` with the full `H`; leave other body fields (`model`, etc.) as resolved for step _i_; send non-streaming.
4. Read `choices[0].message.content` from the response; append `{"role":"assistant","content":<that>}` to `H`.
5. Run `ResponseColumnExtractor.extract` on the response; push that step's map onto the per-step array.

**Rationale:** Full-history resend is the simplest correct model for stateless chat-completions and matches how OpenAI-style APIs work. The template stays single; only bound *values* change per step. **Alternative considered:** a per-step template list — rejected as heavier config surface with no POC benefit. **Alternative considered:** server-side conversation/session IDs — rejected (deployment-specific, not portable).

**Edge owned by the author:** whatever is in `body_i.messages` is appended verbatim, so a `system` message placed in the template would be re-appended every turn. For the POC the contract is "template `messages` = the new turn"; no step-0 special-casing.

### D2 — Hardcoded `choices[0].message.content`; non-streaming only
The assistant reply is read from a hardcoded OpenAI path. If it is absent/unextractable on a 2xx response, history cannot continue → treated as a hard failure (see D4). Multi-step always invokes non-streaming regardless of any streaming hint (forced at execution, not validated).

**Rationale:** Symmetric with the request-side `messages` contract; zero new config. **Alternative considered:** a configurable JSONata `assistantReplyExpression` — deferred to the real feature.

### D3 — Reuse existing result columns; `multiStep` boolean is the shape switch
No new `test_case_run_results` columns. For a multi-step result:
- `responseBody` = the accumulated `messages` array as of the last turn (serialized JSON array).
- `extractedColumns` = a JSON **array** of per-step maps `[{...}, {...}]` (single-step stays a JSON **object**).

Readers use the `multiStep` flag (available from the snapshot) — or, where the flag is not in scope, **shape detection** — to interpret the column.

**Rationale:** `extracted_columns`/`response_body` are already free-form JSONB; changing the *shape inside* needs no migration and preserves cardinality (`uq_results_run_case_index` untouched). **Alternative considered:** a new `steps` JSONB column or per-step rows — rejected for the POC (per-step rows ripple into summaries, metric snapshots, cursor pagination, results API).

### D4 — Fail-fast on step failure
Per-step retries reuse the existing `invokeWithRetries` (each step retries independently). On a step that fails after retries — or a 2xx with no extractable assistant content — the loop stops. The persisted row reflects partial progress: `responseBody` = history through the failed turn, `extractedColumns` = the steps completed, `executionStatus` = the failing step's status, `responseStatusCode` = the failing step's code, `execDurationMs` = total across attempted steps. `retryCount`/`logDetails` capture the failing step only.

**Rationale:** Without an assistant reply the conversation is undefined; continuing is meaningless. **Alternative considered:** best-effort continue — rejected (ill-defined history).

### D5 — `MultiStepConversationExecutor` component; one permit per conversation
A new injectable `service.domain.job.MultiStepConversationExecutor` owns the turn loop. `EvaluationWorker.execute` branches on `multiStep`: `true` → delegate to the executor (which returns a single `TestCaseRunResult`); `false` → today's path. Note: `EvaluationWorker` does **not** read `SuiteSnapshotDto` directly — it reads discrete `context.getSnapshotX()` getters off `EvaluationContext`. So `EvaluationContext` gains `snapshotMultiStep` (boolean) and `snapshotMultistepInputBindings` (`List<List<InputBindingDto>>`), and `TestSuiteEvaluationJob.buildContext` populates both from the resolved `SuiteSnapshotDto` (alongside the existing `snapshotRequestTemplate`/`snapshotInputBindings` mappings). The worker branches on `context.getSnapshotMultiStep()` and passes `context.getSnapshotMultistepInputBindings()` to the executor. The whole conversation runs inside **one** worker task holding **one** semaphore permit; steps are sequential. The persisted `traceId` is the **last** step's (documented limitation, deferred).

**Rationale:** `EvaluationWorker` is already ~800 lines; AGENTS.md prefers specialized injectable components over fat methods, and this isolates the loop for unit testing. **Alternative considered:** an inline branch in `execute` — rejected on size/testability. **Concurrency rationale:** one-permit-per-conversation preserves the existing rate-limit budgeting (a conversation behaves like one logical unit of work); DIAL Core's own 429 handling + per-call retries govern call-level pacing.

### D6 — Metrics normalize to last step via shape detection
At the result→metric boundary, when reading a result's `extractedColumns`: if the parsed JSON is an **array**, reduce to its **last element** (`array[n-1]`); if the array is **empty** (`n == 0`, e.g. a conversation that failed at step 0), yield an empty JSON object `{}` (never an exception, never an array); if an **object**, use as-is.

The same rule must be applied at **two distinct call sites** — there is no single existing chokepoint:
1. **Metric binding resolution** — `MetricEvaluationWorker.buildRequest` calls `bindingResolver.parseJsonMap(result.getExtractedColumns())` (then `resolveBindings`). Only this `extractedColumns` call is normalized; the sibling `parseJsonMap(result.getTestCaseData())` is always an object and MUST NOT be touched.
2. **EvalSummary copy** — `InProcessMetricEvaluationExecutor.buildItem` copies columns via its own private `parseJsonNode(result.getExtractedColumns())` (it never calls `parseJsonMap`). This path runs for both SUCCESS results and the propagated non-SUCCESS path (`buildPropagatedItem` → `buildItem`), so failed results (including `[]`) flow through it too.

To avoid divergence between the two paths, introduce a single shared injectable `@Component` (e.g. `service.domain.job.ExtractedColumnsNormalizer`) that performs the shape detection / last-element reduction / empty-array→`{}` rule, and call it from both sites (scoped to the `extractedColumns` value only). Consequently `EvalSummary` stays **object-shaped (last step only)**, so `EvalSummaryExportRow`, `EvalSummaryExportColumnPlanner`, and `JsonbFieldResolver`'s `response:<col>` flattening need **no changes**.

**Rationale:** Shape detection is self-describing, needs no `multiStep` plumbing into `MetricEvaluationContext`, and correctly handles a 1-step multi-step suite (array length 1) and the zero-step failure (empty array → `{}`). A shared normalizer keeps both call sites consistent. **Alternative considered:** thread the `multiStep` boolean into the metric context — rejected as extra plumbing for no robustness gain; the result row carries no `multiStep` column anyway. **Alternative considered:** normalize only inside `BindingResolver.parseJsonMap` — rejected because the EvalSummary copy uses a separate `parseJsonNode` path and `parseJsonMap` is also used for `testCaseData`.

### D7 — Additive snapshot; no version bump
`SuiteSnapshotDto` gains `multiStep` (default `false`) and `multistepInputBindings` (default null). `CURRENT_VERSION` stays `"2"`. Old snapshots omit the fields → deserialize as single-step via `@JsonIgnoreProperties(ignoreUnknown=true)` + `@Builder.Default`.

**Rationale:** The change is purely additive/backward-compatible; bumping the version would needlessly trip `UnsupportedSnapshotVersionException` for in-flight `"2"` snapshots and force a backfill migration.

### D8 — Persistence & validation
- `test_suites` gains `multi_step` (BOOLEAN NOT NULL DEFAULT false) and `multistep_input_bindings` (JSONB, nullable) via Flyway `V{next}__AddMultiStepToTestSuites.sql`; regenerate jOOQ; update `docs/database-schema.md`.
- `TestSuite` model: `boolean multiStep` + `String multistepInputBindings` (JSON string, consistent with `inputBindings`). `JsonbMapper` gains a `List<List<InputBindingDto>>` ser/deser pair. `TestSuiteRecordMapper` + `TestSuiteMapper` (MapStruct) updated. DTOs (`TestSuiteRequestDto`, `TestSuiteResponseDto`, `SuiteSnapshotDto`) gain both fields.
- Validation (`SuiteValidationService.validateDeploymentSuite`, simple — any violation → invalid): when `multiStep == true`, body must be JSON with a top-level `messages` array; `multistepInputBindings` non-empty and size ≤ `ValidationConstants.MAX_CONVERSATION_STEPS` (= 10); each step's bindings validated against template placeholders + test-case schema via the existing `BindingValidator`. When `multiStep == true`, `inputBindings` is ignored (mutually exclusive). Run-creation runnable-test-case guard unchanged.

## Risks / Trade-offs

- **Author can produce a malformed conversation** (e.g. `system` re-appended each turn, or template `messages` not representing a single new turn) → Mitigation: documented contract + validation that the body has a `messages` array; full correctness is author responsibility for the POC.
- **`extractedColumns` shape now varies (object vs array)** → Mitigation: single normalization point (D6) for metrics/summary; the only consumers that see the array are the raw `TestCaseRunResult` and its response DTO (which already expose it as an opaque `JsonNode`).
- **Latency / cost multiply by step count** (N sequential calls per test case, one permit held for the whole conversation) → Mitigation: 10-step cap; conversations run concurrently across test cases as before; acceptable for a POC.
- **Last-step-only `traceId`** loses per-step trace correlation → Mitigation: documented; addressed post-POC.
- **Truncation mid-conversation**: an oversized turn response is forced to `ERROR` today; under fail-fast that aborts the conversation → acceptable and consistent with single-step behavior.

## Migration Plan

1. Add Flyway meta migration `V{next}__AddMultiStepToTestSuites.sql` (two additive columns, safe defaults — no backfill needed; existing rows default to `multi_step=false`, `multistep_input_bindings=NULL`).
2. `./gradlew generateJooq`; commit generated diff; update `docs/database-schema.md`.
3. Deploy code (additive DTO/snapshot fields, new executor, validation, metric normalization). No snapshot migration (D7).
4. **Rollback:** code rollback is safe (new fields ignored by old readers; `multiStep` defaults false). The columns can remain in place harmlessly; if reverting the migration is required, drop the two columns (no other table depends on them).

## Open Questions

None blocking — all POC decisions were resolved during design review. Items intentionally deferred are enumerated under Non-Goals and the proposal's "Out of Scope" list, to be revisited when the full multi-step feature (response-referencing bindings, per-step metrics, streaming) is scoped.
