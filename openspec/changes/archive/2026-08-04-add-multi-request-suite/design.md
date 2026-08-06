## Context

Current single-request execution path (verified):

- A suite stores its one request as five sibling JSONB/text columns on `test_suites`: `deployment_ref`, `endpoint_ref`, `request_template`, `input_bindings`, `response_columns`.
- `SuiteSnapshotBuilder` freezes them into `SuiteSnapshotDto` (`snapshotVersion = "2"`, `@JsonIgnoreProperties(ignoreUnknown = true)`, Jackson builder deserialization).
- `TestSuiteEvaluationJob` reads the snapshot into `EvaluationContext` (`snapshotEndpointRef`, `snapshotRequestTemplate`, `snapshotInputBindings`, `snapshotResponseColumns`, `snapshotTestCaseSchema`, `snapshotDeploymentRef`).
- `EvaluationWorker.execute(input, context, runIndex, responseColumns)` branches on `context.getSuiteType()`: `MCP_TOOL` → `executeMcp(...)` returning exactly one row; `DEPLOYMENT` → `turnLoopExecutor.execute(input, context, runIndex, responseColumns, traceId, execStartedAtMs)` returning **one row per executed turn**.
- `TurnLoopExecutor` builds a `TurnPlan` via `PerTurnBindingDetector.referencesPerTurnField(context.getSnapshotInputBindings(), context.getSnapshotTestCaseSchema())`: `stampTurnIndices = true` and N = `multiTurnData.length` only when a per-turn binding exists; otherwise N = 1 and `turnIndex`/`totalTurns` stay at their `@Builder.Default` `0`/`1`, with the input's raw `testCaseData` persisted verbatim.
- Per turn it calls `RequestResolver.resolveForRun(template, bindings, data, frameBindings)` where `frameBindings` is the **previous turn's** reconciled extracted columns, then `DeploymentTurnInvoker.invoke(...)`, then `ResponseColumnExtractor.extract(responseColumns, responseBody, requestBodyJson)`.
- Analytics rows are `com.epam.aidial.evaluation.runner.model.TestCaseRunResult` (there is **no** separate `data.db.analytics.model.TestCaseRunResult`; the analytics layer reuses the runner model) and `data.db.analytics.model.EvalSummary`. Both already carry `turnIndex` (default 0) / `totalTurns` (default 1).

Everything in this design is **Planned**.

## Goals / Non-Goals

**Goals**

- Let a suite define an ordered chain of requests against one deployment, with the existing suite-level request as an immutable request #0.
- Keep every response column in one flat, globally-unique namespace so downstream consumers (metric bindings, export headers, query schema) need no prefix awareness.
- Let each request's JSONata read everything extracted earlier in the chain.
- Add a first-class `request` dimension to analytics rows, mirroring the existing `turn` dimension exactly.
- Preserve byte-identical behavior and stored rows for every existing single-request suite.

**Non-Goals**

- MCP chaining. `additionalRequests` on an `MCP_TOOL` suite is rejected (400). The DTO/persistence model is nonetheless shape-complete so the follow-up change is additive.
- Per-request `deploymentRef`. The suite↔deployment relationship stays 1-to-1.
- Branching, conditionals, retries or loops **between** requests. The chain is strictly sequential and fail-fast.
- Namespacing response columns by request (`response::<request>::<column>`). Rejected — see Decisions.
- Per-request metric selection. Metrics run on every row; per-request scoping is the author's job via a TSMD `condition`.
- A `additionalRequests` override on `POST /api/v1/test-suites/{id}/clone`. Clone inherits the source's chain verbatim (with file-ref rewrite); adding an override field is out of scope.
- Adding a `requestIndex` / `turnIndex` filter key to `FilterWhitelists.EVAL_SUMMARIES` (it has no `turnIndex` key today either).

## Decisions

### D1. Request #0 is the existing suite-level fields, not a list element

`additionalRequests` holds only requests **1..N**. Rejected alternative: migrate the suite-level fields into `requests[0]` of a single list. That would require a data migration of every existing suite, break every existing API client, and force `SuiteSnapshotDto` to a new `snapshotVersion`. The chosen shape makes the whole feature additive: `additional_requests` defaults to `'[]'`, and a suite that never sets it behaves exactly as today.

Consequence: the chain is assembled at execution time as `[request#0 from the snapshot's singular fields] ++ snapshot.additionalRequests`, so `totalRequests = 1 + additionalRequests.size()`.

### D2. `RequestDefinitionDto` lives in the runner module, single copy

New `com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto`:

| field | type | validation |
|---|---|---|
| `name` | `String` | `@Size(max = 255)` — optional user-facing label |
| `endpointRef` | `EndpointContractDto` | `@Valid` |
| `requestTemplate` | `RequestTemplateDto` | `@Valid` |
| `responseColumns` | `List<ResponseColumnDefinitionDto>` | `@Valid` (no per-list `@Size` — the cap is a suite-wide union, see D6) |
| `inputBindings` | `List<InputBindingDto>` | `@Valid` |

It goes in `runner.dto` (not duplicated) because `TestSuiteRequestDto` already references `RequestTemplateDto`, `InputBindingDto`, `ResponseColumnDefinitionDto` and `EndpointContractDto` from that package — there is no cross-boundary duplication to add. `name` uses a literal `255` like the sibling `ResponseColumnDefinitionDto.name`, keeping the runner's `RunnerValidationConstants` copy untouched (it holds only the four regex/message constants).

### D3. `requestName` labels request #0

New nullable `request_name VARCHAR(255)` on `test_suites`, surfaced as `requestName` on `TestSuiteRequestDto` / `TestSuiteResponseDto` / `SuiteSnapshotDto`. Without it, request #0 would be the only unlabellable link in a chain and `request.name` in a condition could never target it.

### D4. Chain state is one accumulated frame map

`RequestChainExecutor` owns a single `Map<String, Object> accumulatedFrame`, seeded empty. For each request in order it calls `TurnLoopExecutor` with that map as the request's **initial** frame; the executor returns the frame it ended with (initial map + every turn's reconciled extracted values merged in, later turns overwriting same-named keys). That returned map becomes the next request's initial frame.

Within a request, turn `t`'s resolution frame is `accumulatedAtRequestStart ⊕ turn0..t-1 extractions` — i.e. today's "previous turn's columns" behavior, widened to fall back to earlier requests' columns. Turn 0 of request `i>0` therefore already sees requests `0..i-1`'s columns, which is the whole point of the chain.

`extracted_columns` persisted on a row is the **accumulated union visible at that row** (prior requests' columns + this request's turns up to and including this one), not just this request's own columns. This is what makes `BindingResolver` and `response::<name>` export columns work unchanged on late-chain rows.

### D5. Flat shared namespace, global uniqueness

All response columns from request #0 and every additional request share one namespace. `TestSuiteRequestValidator` walks request #0's columns then each additional request's columns through **one** `seenNames` set; the first repeat is a 400.

Rejected alternative: prefix by request (`response::0::answer`). It would change every `metric` binding reference, every export header, and every `eval_summaries` detailed-schema field name — a breaking change to already-shipped surfaces, for no expressive gain given uniqueness is enforceable.

Consequence: a duplicate name is an author error caught at write time, so no runtime precedence rule is needed. Same-name overwrite in the frame therefore only ever happens **across turns of one request** (turn `t+1` overwrites turn `t`), never across requests.

### D6. The 50-column cap becomes a suite-wide union cap

`TestSuiteRequestDto.responseColumns` currently carries a hardcoded `@Size(max = 50)`. Extract it to `ValidationConstants.MAX_RESPONSE_COLUMNS = 50`, keep the annotation on request #0's list (a valid subset bound), and add a runtime check in `TestSuiteRequestValidator` on `count(request#0) + Σ count(additionalRequests[i])`. A `@Size` annotation cannot express a cross-field union, so the authoritative check must be imperative.

### D7. Multi-turn is detected per request

`PerTurnBindingDetector` is unchanged; `TurnLoopExecutor` calls it with the **current request's** `inputBindings` and the (still suite-level) `snapshotTestCaseSchema`. So in one chain, request #0 may run once (no per-turn binding) while request #1 loops over `multiTurnData`, or both may loop. Total rows for a case-repetition = `Σ over requests of turnsForThatRequest`.

### D8. `TurnLoopExecutor` is generalized, not duplicated

Current signature:

```java
public List<TestCaseRunResult> execute(
        TestCaseRunInput input,
        EvaluationContext context,
        int runIndex,
        List<ResponseColumnDefinitionDto> responseColumns,
        String traceId,
        long execStartedAtMs)
```

Per-request parameters (`endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `requestIndex`, `totalRequests`, initial frame) plus the two return values (rows + final frame) push the parameter count past the point where a flat list is maintainable. Following the project's *"prefer a context object over growing param lists"* practice, introduce two carriers in `runner.job`:

- `RequestExecutionSpec` (record): `requestIndex`, `totalRequests`, `name`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `initialFrame`.
- `RequestExecutionResult` (record): `List<TestCaseRunResult> rows`, `Map<String, Object> accumulatedFrame`, `boolean aborted`.

New signature: `RequestExecutionResult execute(TestCaseRunInput input, EvaluationContext context, int runIndex, RequestExecutionSpec spec, String traceId, long execStartedAtMs)`.

Internals — `TurnPlan` construction, per-turn resolution, extraction/reconciliation, streaming, error outcome mapping, `buildEmptyTurnsErrorRow` — are unchanged apart from reading the spec's fields instead of the context's `snapshot*` fields, and stamping `requestIndex`/`totalRequests` on built rows.

`EvaluationContext.snapshotEndpointRef` / `snapshotRequestTemplate` / `snapshotInputBindings` / `snapshotResponseColumns` remain — they are request #0's definition, and `RequestChainExecutor` reads them to build the chain's first spec. `EvaluationContext` gains `snapshotAdditionalRequests: List<RequestDefinitionDto>` and `snapshotRequestName: String`.

### D9. Index stamping mirrors turn stamping exactly

`TurnLoopExecutor` already gates turn stamping on `stampTurnIndices`. Add the same gate for requests: `RequestChainExecutor` passes `totalRequests`, and stamping happens **only when `totalRequests > 1`**. A single-request chain leaves `requestIndex`/`totalRequests` at their `@Builder.Default` `0`/`1`, producing rows byte-identical to today's. The MCP path never stamps (it never stamps turn columns either).

### D10. `EvaluationWorker` keeps its signature; the DEPLOYMENT branch delegates to `RequestChainExecutor`

```java
// before: turnLoopExecutor.execute(input, context, runIndex, responseColumns, traceId, execStartedAtMs)
// after:  requestChainExecutor.execute(input, context, runIndex, traceId, execStartedAtMs)
```

The `responseColumns` parameter on `EvaluationWorker.execute` becomes request #0's columns only and is therefore redundant with `context.getSnapshotResponseColumns()`; `RequestChainExecutor` derives every request's columns from the chain itself. Keep `EvaluationWorker.execute`'s public signature as-is (the MCP branch still uses the parameter) to avoid churn in `TestCaseRunner`/`InProcessEvaluationExecutor`.

`RequestChainExecutor`:

1. Build `List<RequestExecutionSpec>`: spec 0 from `context.getSnapshot{EndpointRef,RequestTemplate,InputBindings,ResponseColumns}` + `snapshotRequestName`; specs 1..N from `context.getSnapshotAdditionalRequests()`.
2. `totalRequests = specs.size()`.
3. Fold: for each spec, set `spec.initialFrame = accumulatedFrame`, call `turnLoopExecutor.execute(...)`, append `result.rows()`, set `accumulatedFrame = result.accumulatedFrame()`.
4. If `result.aborted()` → stop; return the rows accumulated so far (which include the failing row).

### D11. Fail-fast propagates out of the turn loop

`TurnLoopExecutor` already aborts remaining turns via its internal `TurnControl.ABORT`. `RequestExecutionResult.aborted` surfaces that decision one level up so `RequestChainExecutor` can stop the chain. Rows already produced (earlier requests, earlier turns, and the failing call itself) are returned and persisted — no rollback, consistent with today's per-turn behavior.

### D12. `ConditionContext` grows a request tuple; `ConditionExpressionEvaluator` grows a namespace

`ConditionContext` is today `record ConditionContext(String dataJson, String responseJson, int turnIndex, int totalTurns)`. Add `int requestIndex`, `int totalRequests`, `String requestName`.

`ConditionExpressionEvaluator.buildDictionaryJson` gains, alongside the existing `turn` node:

```java
final ObjectNode request = objectMapper.createObjectNode();
request.put(REQUEST_INDEX, context.requestIndex());
request.put(REQUEST_TOTAL, context.totalRequests());
request.put(REQUEST_LAST, context.requestIndex() == context.totalRequests() - 1);
if (context.requestName() == null) {
    request.putNull(REQUEST_NAME);
} else {
    request.put(REQUEST_NAME, context.requestName());
}
root.set(REQUEST_NAMESPACE, request);
```

`putNull` (not the shared `NON_NULL` mapper's drop-the-entry behavior) so the explicit null is preserved and `request.name = null` is the honest unlabelled test — matching the existing "dictionary MUST preserve explicit JSON nulls" rule. Note that `$exists(request.name)` is **not** that test: because the key is always emitted, it returns true for a present-null value and is therefore always true (see the `conditional-metric-execution` delta).

`InProcessMetricEvaluationExecutor` populates the three new fields from `result.getRequestIndex()` / `getTotalRequests()`. The **name** is not on the result row, so it is resolved from the run's snapshot: `MetricEvaluationContext` carries the ordered request-name list (`[requestName, additionalRequests[*].name]`) and the executor indexes it by `requestIndex`, falling back to `null` out of range. Rationale: adding a `request_name` column to the analytics tables would denormalize a label that is already snapshotted per run.

### D13. Soft validation: existing warning paths are frozen; new ones are indexed

`SuiteValidationService`'s warning paths are hardcoded literals and are **not** uniformly prefixed today — e.g. a missing url template reports path `"$.urlTemplate"`, not `"$.requestTemplate.urlTemplate"`, while body warnings do report `"$.requestTemplate.body"`. Request #0's paths MUST stay exactly as they are (FE consumers and existing `validationWarnings` blobs depend on them). Additional requests get a new, internally consistent indexed form: `$.additionalRequests[i].requestTemplate.urlTemplate`, `$.additionalRequests[i].requestTemplate.body`, `$.additionalRequests[i].requestTemplate.headers`, `$.additionalRequests[i].endpointRef`, `$.additionalRequests[i].inputBindings`.

Implementation: extract the per-request body of `validateDeploymentSuite` into a private method taking a path prefix (`""` for request #0 preserving today's literals, `"$.additionalRequests[i]"` otherwise) and loop it. `BindingValidator` emits `"$.inputBindings"`; it needs a path-prefix parameter for the same reason.

Both `SuiteValidationService.validateSuite` overloads must iterate: the DTO overload (used by `TestSuiteService.create`/`update`, `TestSuiteCloneService`) and the entity overload (used by `RevalidationService.runPhase2`), which rebuilds a transient `TestSuiteRequestDto` through `JsonbMapper` and must now also populate `additionalRequests`.

The `validation.maxWarningsPerCase` cap is unchanged and now covers the whole chain's warnings.

### D14. `isResponseColumnsChanged` diffs the union

`TestSuiteService.isResponseColumnsChanged(existing, normalized)` today builds a throwaway `TestSuite`, runs `testSuiteMapper.update(temp, normalized)`, then `jsonEquals(existing.getResponseColumns(), temp.getResponseColumns())`. Extend it to also compare `additional_requests`' response-column projection: seed the temp entity with both `responseColumns` and `additionalRequests`, and return changed when **either** the top-level columns JSON differs **or** the ordered list of `(name, type)` pairs extracted from `additional_requests` differs. Only response-column-relevant differences must trigger revalidation — a change to an additional request's `urlTemplate` must not.

The revalidation call `testSuiteMetricDefinitionService.revalidateAllForSuite(id, datasetSchemaJson, updated.getResponseColumns())` takes the response-columns **JSON string**; it must now receive the serialized **union** list. Add a helper (e.g. `ResponseColumnUnionResolver` `@Component` in `service.domain`) that produces the union from a `TestSuiteRequestDto`, from a `TestSuite` entity, and from a `SuiteSnapshotDto` — the same union is needed by `MetricDefinitionValidationService`, `EvalSummariesSchemaProvider`, and `EvalSummaryExportColumnPlanner`, so it must be one injectable component, not three private methods.

### D15. Analytics DDL: drop + re-create the two natural keys

Verified: `turn_index` appears in exactly two DDL objects across the analytics migration set —

- `test_case_run_results`: constraint `uq_results_run_case_index UNIQUE (test_suite_run_id, test_case_id, run_index, turn_index, created_at_ms)` (last touched by `V1.13`).
- `test_case_eval_summaries`: unique **index** `uq_eval_summaries_natural_key (test_suite_run_id, test_case_id, run_index, turn_index, computation_id, created_at_ms)` (last touched by `V1.14`).

Both are extended by inserting `request_index` immediately after `run_index` (before `turn_index`), matching the request-then-turn nesting of the execution model. `V1.15__AddEvalSummariesRunComputedAtIndex.sql`'s `idx_eval_summaries_run_computed_at` does not include `turn_index` and is left alone.

The `ON CONFLICT` targets in `PostgresTestCaseRunResultRepository.saveAll` and `PostgresEvalSummaryRepository.saveAll` must be extended in the same task group as the DDL — a mismatched conflict target turns the intended `DO NOTHING` idempotency into a constraint violation.

Note: `docs/database-schema.md` currently mislabels the eval-summaries key as `uq_eval_summaries_run_case_comp` on one line while correctly naming `uq_eval_summaries_natural_key` on another. Fix that while updating the doc.

### D16. Export manifest gains both `requestIndex` and `turnIndex`

`EvalSummaryExportColumnPlanner` emits identity columns as inline literals: `id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `computationId`. **There is no `turnIndex` column in the export manifest today**, so the plan's "mirror the existing turn column treatment" had nothing to mirror. Decided (see Resolved Questions R1): add **both** identity columns, in the order `runIndex`, `requestIndex`, `turnIndex`, immediately after `runIndex`. Adding `turnIndex` deliberately fixes the pre-existing gap in which a multi-turn run's rows are indistinguishable in the CSV — a gap that would become more visible once the request dimension makes each repetition emit even more rows.

Both columns keep camelCase and carry no family-separator, so they join the identity/execution carve-out list rather than any `<family>::<name>` family. Neither is a breaking change for a consumer that selects columns by name; a consumer that positionally indexes the header row will see two extra columns after `runIndex`.

`response::<column>` columns are unchanged in shape but must now be derived from the suite-wide **union** (snapshot `responseColumns` + `additionalRequests[*].responseColumns`), otherwise a chained run's later requests' columns would be silently dropped from the CSV.

### D17. Query schema needs no base-field work

`EvalSummariesSchemaProvider`'s base schema is `JooqTableSchemaResolver.resolve(TEST_CASE_EVAL_SUMMARIES)`, which enumerates the generated jOOQ table's columns in DDL order. `request_index` and `total_requests` therefore become queryable **automatically** once the migration + `generateJooq` land — no provider change, no spec change to the query grammar. The only provider change needed is `responseFields(SuiteSnapshotDto)`, which must iterate the union instead of `snapshot.getResponseColumns()` alone. (`FLATTENABLE_JSONB_FIELDS` is unaffected.)

### D18. Run comparison: request index joins the match key

`PostgresEvalSummaryRepository` builds the key in three places that must change together: `matchCondition(Table<?> probe)` (adds `.and(probe.field(TURN...REQUEST_INDEX).eq(...REQUEST_INDEX))`), `otherRunKeys(...)`'s `selectDistinct(lower(name), RUN_INDEX, REQUEST_INDEX, TURN_INDEX)`, and `findUnmatchedIds`'s `ORDER BY lower(name), RUN_INDEX, REQUEST_INDEX, TURN_INDEX, ID`. `RunComparisonService` and the response DTO are unchanged.

### D19. Try-It-Out preview takes an optional `requestIndex`

`GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request` gains `@RequestParam(name = "requestIndex", required = false, defaultValue = "0") int requestIndex`. `ResolvedRequestService.resolveRequest(UUID testSuiteId, UUID testCaseId, int requestIndex)` selects request #0's template/bindings for `0`, else `additionalRequests[requestIndex - 1]`; out of range → `ValidationException` → 400. Preview still calls `RequestResolver.resolve` with an **empty** frame, so a chained request's `$prior` frame variables resolve as JSONata undefined and downgrade to validation warnings — exactly today's behavior for an unresolvable frame variable. Keep a 2-arg overload delegating with `0` so `TryItOutService`'s existing call site is untouched.

### D20. Transaction boundaries unchanged

Suite create/update/clone stay inside `@Transactional("metaTransactionManager")` in `TestSuiteService`/`TestSuiteCloneService`; `additional_requests` is one more column in the same row write. The snapshot phase keeps `ISOLATION_REPEATABLE_READ` + `40001` retry. Analytics writes stay in the existing batch-writer path under `analyticsTransactionManager`. `RequestChainExecutor` performs **no** database work (the runner module is DB-free) — it writes through the existing `ResultBatchWriter` sink.

### D21. Error handling

- Write-time structural violations → `ValidationException` → HTTP 400 `VALIDATION_ERROR` (fail-fast; the suite is not persisted).
- Per-request JSONata body syntax errors → 400 at write time (existing `TestSuiteRequestValidator` behavior, now per request with an `additionalRequests[i].` message prefix).
- Run-time request-body evaluation failure on any request → `RequestBodyEvaluationException` → an `ERROR` row for that call (`REQUEST_BODY_EVALUATION_ERROR`) and chain abort. Never a suite-validation failure.
- Missing frame variable at run time → JSONata undefined, per existing semantics; a column that failed extraction binds JSONata's explicit-null sentinel.
- A `Response` metric binding referencing a column that a **later** request produces resolves to missing on earlier rows → existing metric-error behavior. Authors avoid it with a `condition` on `request.index` / `request.last`. Documented, not special-cased.

## Risks / Trade-offs

- **DDL on hot analytics tables.** Two uniqueness keys are dropped and re-created. On a large `test_case_eval_summaries` the `CREATE UNIQUE INDEX` blocks writes for its duration. Trade-off accepted rather than living with a key that can't distinguish chain rows; the alternative (`CREATE UNIQUE INDEX CONCURRENTLY`) cannot run inside Flyway's transactional migration and would need a separately-managed non-transactional migration.
- **Conflict-target drift.** If the migration lands without the matching `ON CONFLICT` update (or vice versa), batch writes either violate the new key or silently stop being idempotent. Mitigated by keeping DDL + jOOQ regen + writer updates in one task group with a functional test asserting re-write idempotency.
- **Metric fan-out.** Metrics run on every chain row, so an N-request suite multiplies provider calls up to N×. Deliberate: it keeps `test_case_eval_summaries` the single read surface for all rows (an eval summary per result row for any metric count). Authors control cost with `condition: "request.last"`.
- **Cross-request column collisions surface late for authors.** Global uniqueness means renaming a column in one request can now conflict with a different request. Caught at write time with a precise 400, but it is a new class of author error.
- **Frame accumulation grows monotonically.** A long chain of multi-turn requests accumulates every column into one map, held in memory per test-case execution and serialized into every row's `extracted_columns`. Bounded by the suite-wide 50-column cap; row size grows for chained suites.
- **`TurnLoopExecutor` signature churn.** Introducing `RequestExecutionSpec`/`RequestExecutionResult` touches the runner's most heavily-tested class. Mitigated by keeping all internals intact and asserting the single-request path produces identical rows.
- **Two independent index dimensions.** `(request_index, turn_index)` is a 2-D row identity; every consumer that reasons about "the row for this case" (comparison, export, analytics UI) must handle both. `run-comparison-metric-scores`' duplicate-key semantics already tolerate multiple rows per key, so no matching regression, but the FE must learn the new dimension.

## Resolved Questions

Both questions raised during design review have been decided. No open questions remain.

**R1. Export identity columns — RESOLVED: add both `requestIndex` and `turnIndex`.** The plan asked for a request column "mirroring the existing turn column treatment", but `EvalSummaryExportColumnPlanner` and `eval-summary-export/spec.md` have **no** turn column at all (grep-verified: `turnIndex`/`totalTurns` appear nowhere in that spec). Decision: the manifest gains **both** columns, ordered `runIndex`, `requestIndex`, `turnIndex`. The `turnIndex` addition is an intentional fix for the pre-existing gap where a multi-turn run's rows cannot be told apart in an export. Reflected in D16, in the `eval-summary-export` delta (identity-column order, family-separator carve-out list, and scenarios asserting both headers), and in task 7.2 plus its test task.

**R2. Base-field spec home — RESOLVED: no `structured-query-model` delta.** The plan listed a `structured-query-model` delta for exposing `request_index` as a base queryable field. Verified: base fields are not enumerated in `structured-query-model` — they are auto-derived from the generated jOOQ table (`JooqTableSchemaResolver.resolve`) and documented as such in `query-schema-discovery`, with the physical column lists living in `analytics-eval-results` / `metrics-storage`. This change therefore deltas those three specs; `structured-query-model` needs no edit, and `request_index` / `total_requests` become queryable with no provider code change once the migration and `generateJooq` land.

**R3. Clone override — CONFIRMED as a non-goal.** A clone inherits the source suite's chain verbatim, with the `@ef/suites/{id}/` file-reference rewrite applied inside the `additional_requests` blob. No `additionalRequests` override field is added to `TestSuiteCloneRequestDto`. (Note: the clone *endpoint* did gain hard validation over the effective post-override suite — see F1 below. That is a validation change, not an override field.)

## Review findings & resolutions

Post-implementation review round (`opsx:verify` + code review). Four findings, all dispositioned by the user; two further observations were reviewed and deliberately left alone.

**F1. Clone bypassed hard validation — FIXED.** `POST /test-suites/{id}/clone` ran only the synchronous *soft* validation, so a clone whose `responseColumns` override collided with an inherited `additionalRequests[i]` column — or whose effective union exceeded the cap — was persisted in a state a `PUT` of the same content would have rejected. Clone now runs the same hard write-time rule set as create/update against the **effective post-override** suite, returning 400 `VALIDATION_ERROR` with no residue. Owned by the `test-suite-clone` capability (the spec that owns the clone endpoint); the `test-suites` and `multi-request-suite` deltas carry a pointer plus the chain-specific scenarios rather than a duplicate requirement.

**F2. Null chain element — FIXED.** A JSON `null` inside `additionalRequests` was not rejected. It is now a write-time 400 whose message names the offending 0-based index. Silently dropping it was rejected as an option: a dropped middle element shifts every later request's `request_index`, changing which rows a `request.index` condition targets without the author knowing.

**F3. `$ref` inlining was request-#0-only — FIXED (closes verify finding W2).** `EndpointSchemaRefResolver` ran only over the suite's own `endpointRef` at normalize time, leaving an additional request's referenced endpoint schema unresolved. It now resolves every request's `endpointRef`. This makes the `request-template` delta's "…SHALL apply to each request's template exactly as they apply to a single-request suite's template" literally true rather than aspirational; the sentence was re-read and stands, and an explicit `$ref` sentence plus scenario were added alongside it.

**F4. Vacuous test — FIXED.** A test asserted a condition that held regardless of the behavior under test. Replaced with an assertion that actually discriminates. No spec impact.

**S3. Diff asymmetry in `isResponseColumnsChanged` — REVIEWED, LEFT AS-IS.** The top-level `responseColumns` are compared as canonical JSON (order-insensitive on object keys) while the chain's columns are compared as an ordered `(name, type)` projection. The asymmetry is intentional: the projection is what TSMD revalidation actually depends on, and widening it to full JSON equality would fire revalidation on cosmetic edits (e.g. a `displayName` change on an additional request) that cannot invalidate a binding. Over-firing is cheap but noisy; under-firing is the dangerous direction and this shape cannot under-fire.

**S4. Interaction with legacy per-test-case template overrides — REVIEWED, LEFT AS-IS.** `test_case_run_inputs` still carries `request_template_override` / `input_bindings_override` columns from the pre-dataset era. They apply to request #0 only and are not extended to the chain. Deliberate: they are legacy, unreachable from the current API, and giving them chain semantics would design new behavior for a surface that is on its way out. A chained suite with a legacy override on a snapshotted input therefore overrides request #0 exactly as before, leaving additional requests untouched.

**W1. Delta status markers — FIXED.** All 38 `Status: **Planned**` markers across the bundle's delta specs were flipped to `Status: **Implemented**` now that the code has shipped, per repo precedent (commit `e5a00f5`) and `config.yaml` `rules.specs`.
