## Context

A test suite issues exactly one HTTP request per test case. Suite config is flat: `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`. `EvaluationWorker.execute` dispatches on suite type (MCP → `executeMcp`) and on data (`multiTurnData != null` → `MultiTurnExecutor`), returning `List<TestCaseRunResult>`.

Multi-turn already established the precedent this change builds on: one test case can emit **several** result rows, keyed by `turn_index`, with the natural key widened accordingly (`V1.13`/`V1.14`). Multi-request follows the same shape on a second, independent axis (`request_index`) — but where multi-turn is *conversational* (accumulated `messages`, full-history resend), multi-request is a **data-flow pipeline**: independent calls where a later request consumes an earlier one's extracted response columns.

Constraints that shaped the design:

- **Dual datasource.** Suite config and the frozen run snapshot live in **meta**; result rows and eval summaries live in **analytics**. No joins across them. Any value an analytics-side consumer (results grid, CSV export, query DSL) needs must be denormalized onto the row.
- **JDBC/jOOQ only**, typed DSL, generated sources regenerated via `./gradlew generateJooq`.
- **No non-invalidating validation warning channel.** `ValidationWarningDto` has no severity field; `ValidationResult` couples `valid` with `warnings`. A "smell" cannot be surfaced without failing the suite, which directly shaped the metric-targeting decision below.
- **Suite validity is config-only** (AGENTS.md): test-case *presence* is never part of `isValid`, so dataset-dependent checks belong at run creation, not suite save.

## Goals / Non-Goals

**Goals:**

- Evaluate applications that require a short sequence of calls (create → configure → invoke → teardown) against one deployment.
- Let a later request consume an earlier request's extracted response columns.
- Emit one result row per request so the UI shows per-request status, timing, and extracted values.
- Target metrics at specific requests **without** adding a second targeting mechanism.
- Keep every existing single-request suite byte-identical in behavior.
- Leave a real seam for MCP chaining without implementing it.
- Fix two pre-existing defects this feature would otherwise multiply.

**Non-Goals:**

- Conversational chaining at the suite level. No `messages` accumulation between chain requests — multi-turn test cases already provide that.
- Multi-request combined with multi-turn test cases (rejected at run creation).
- MCP chaining (interface present, implementation stubbed).
- Per-request execution settings (timeout, retry policy stay run-level).
- Branching, conditional, parallel, or retryable-at-chain-level flows. The chain is a straight sequence.
- Cross-request metric inputs (a metric comparing request 1's answer to request 3's). Metric evaluation stays per-row.

## Decisions

### 1. Chain semantics: data flow, not conversation

Requests execute sequentially and independently. Coupling is explicit and one-directional: request *N* may bind template variables to response columns extracted by requests *0..N-1*.

*Alternative rejected:* reuse `MultiTurnExecutor`'s accumulated-`messages` model at suite level. That is already achievable via multi-turn test cases, would be a second way to express the same thing, and collides head-on with the `turn_index` axis.

### 2. Chain-wide flat response-column namespace

Response column names must be unique across the entire chain. `responseField` references a bare column name; no qualification syntax exists.

*Rationale:* response column names are already a flat suite-wide namespace — metrics bind by bare `columnName` (`ResponseBindingSourceDto`), the grid keys columns by bare name, and the export emits `response::<name>` headers. Permitting duplicates across requests would push qualification into *all* of those consumers. Chain-wide uniqueness keeps every downstream consumer unchanged and is a one-sentence rule ("response column names must be unique within the suite" — already true today).

*Alternatives rejected:* qualified references (`{sourceRequest, responseField}`) — strictly more expressive but pays for it in metric bindings, grid columns, export headers, and `metricValues` keys; previous-request-only — breaks as soon as request 3 needs request 1's session id, which is the motivating flow.

### 3. Persist asymmetric, normalize internally

The DB and public API keep `additionalRequests` with request 0 in the existing flat columns — zero migration of existing suites, fully backward compatible. At the service boundary a **chain normalizer** produces a uniform `List<RequestSpec>` of size N, element 0 synthesized from the flat fields.

Every consumer is written **once** against the symmetric list: per-element validation, chain execution, `request_index`/label assignment, the chain-union response-column helper, the export planner, and `EvalSummariesSchemaProvider`. The normalizer is also applied when reading the frozen snapshot, so there is exactly one definition of "the chain" in the codebase.

*Alternatives rejected:* asymmetric throughout — the `if` replicates into every consumer, each a place to skip request 0 in a validation sweep or misassign `request_index`; full migration to a `requests[]` array — a `test_suites` backfill, snapshot version bump, and breaking API change for elegance obtainable free. Precedent: `TestCaseFieldScopeResolver` exists so the shared/per-turn split is defined exactly once.

### 4. Per-element `endpointRef`

Each chain element carries a complete request spec: `{type, label, endpointRef, requestTemplate, inputBindings, responseColumns}`. `deploymentRef` stays suite-level.

*Rationale:* chain requests hit different paths **and methods** with different body schemas. A shared `endpointRef` cannot express that, and would validate every request's body against request 0's `requestBodySchema` — reporting a correct `configure` body as invalid against the `/chat/completions` schema and flipping `isValid` on a properly configured suite.

### 5. Labels: optional, defaulted, uniqueness on the resolved set

`label` is optional per element; a new nullable top-level `requestLabel` names request 0. The normalizer fills any absent label with `request-{n}`. Uniqueness is validated **once, on the resolved (post-defaulting) set** — a single check that catches both duplicate explicit labels and an explicit label colliding with another request's default.

*Rationale:* requiring labels cannot be delivered symmetrically — backward compatibility forces `requestLabel` nullable for existing suites. Optional-plus-default makes every request labeled and addressable by construction, and makes `request_label` non-null on result rows, removing a null case from the grid and export.

### 6. `request_index` in the key; `request_label` as payload; no `total_requests`

`request_index` joins the unique indexes on `test_case_run_results` and `test_case_eval_summaries`. `request_label` is an ordinary column.

*Rationale:* labels are mutable display strings — putting one in an idempotency key means renaming a request changes the key, and a careless duplicate would break it. Exact precedent: `test_case_name` sits *next to* `test_case_id` while only the id is in the key.

**No `total_requests` column.** The `turn_index`/`total_turns` pair does not carry over. `turn.total`/`turn.last` exist because turn count is **data**-dependent — it varies per test case, so a condition author cannot know N. Request count is **config**-dependent: fixed for the run, known while writing the condition, and derivable from the snapshot. It would also be the same integer on every row of a run. Truncation detection needs no integer either — fail-fast means an aborted chain's last row is `ERROR`.

### 7. Fail-fast

The first failing request persists one ERROR row and aborts the chain; later requests produce no rows. Mirrors multi-turn.

*Alternatives rejected:* continue — a request built from a failed dependency resolves its placeholder to a default, fires a semantically nonsense call, gets a 200, and persists a **SUCCESS row with meaningful-looking metric values computed on garbage**; a silently wrong result is worse than a missing one. Dependency-aware partial continuation is the technically correct answer but needs runtime dependency analysis and makes row counts unpredictable; it is additive later with no schema change.

### 8. Unresolvable `responseField` at runtime → placeholder default, else failure

Write-time validation enforces backward-only references to columns that exist somewhere in the chain (400). It cannot guarantee runtime resolution: a request can return 200 while a column's JSONata matches nothing.

When the placeholder declares a default (`${{var|type:default}}`), use it — that syntax already exists and *is* the author's statement of what to do when the value is missing, making safe continuation opt-in and visible in the template. Absent a default, treat it as a request failure (decision 7).

### 9. Metric targeting reuses `condition`; unconditioned metrics run everywhere

The metric list stays flat. The condition dictionary gains `request: {index, label}`. An unconditioned metric runs on **every** row.

Consequence, accepted deliberately: `BindingResolver.resolveSource` throws when a `ResponseBindingSourceDto` names a column absent from the row's `extractedColumns`; that becomes a `TsmdEvaluationResult.Failure`, and `checkForErrors` marks the summary **`FAILED`**. So an unconditioned response-bound metric marks plumbing rows FAILED — and that failure **is** the authoring signal to add a condition. The throw precedes provider dispatch, so no LLM call is made and no polluted value reaches the averages. A correctly conditioned chain yields clean SUCCESS plumbing rows: when a condition skips every metric, `tsmdResults` is empty, `checkForErrors` returns false.

*Alternative rejected:* a non-invalidating validation warning at save time would be the better signal, but no severity channel exists. Defaulting to "last request only" was rejected as a guess dressed as a default — the same silent-wrongness failure mode as decision 7's rejected branch. Requiring a condition on every metric in a multi-request suite was rejected because it forbids the legitimate case of a chain whose requests each produce a scoreable answer.

*Residual risk, unmitigated:* a metric bound only to test-case data and constants resolves on **every** row, so it runs N× at full cost with **no** failure signal. `condition` is the only defense.

### 10. `request` namespace exposes both `index` and `label`

Both values are already on the row, so exposing both costs two `put` calls in `buildDictionaryJson`. They fail differently: `label` survives inserting a request at position 1, whereas an index-based condition silently retargets the wrong request — no error, quietly wrong metric coverage. `label` is documented as preferred; `index` still works when nothing is labeled.

### 11. Multi-request excludes multi-turn

A multi-request suite bound to a dataset containing any multi-turn case is rejected at **run creation** (409 `INVALID_OPERATION`), reusing `existsMultiTurnByDatasetId`.

The combination is coherent in principle — multi-turn is a per-*request* concern, so a request binding `perTurn` fields would emit N rows while plumbing requests emit one, making rows a **sum, not a product**. It is excluded to keep the first iteration small; `total_turns` therefore keeps its current meaning and every multi-request row carries `turn_index=0`/`total_turns=1`.

Run creation, not suite save: dataset content is mutable, so a save-time check would be both wrong and evadable, and suite validity is config-only.

### 12. MCP: polymorphic element + registry + stub

The chain element is Jackson-discriminated by `type` (`HTTP` | `MCP_TOOL`) — the pattern `RequestBodyDto`/`MetricBindingSourceDto` already use. A `ChainStepExecutor` SPI is selected by a registry (`RequestBodySerializerRegistry` precedent): `HttpChainStepExecutor` real, `McpChainStepExecutor` a stub throwing `UnsupportedOperationException`.

Enforcement is **both** save-time (400, the user-facing contract) and the runtime stub (defensive backstop). Save-time alone leaves an SPI method no implementation honors; the stub alone lets an author save a suite guaranteed to fail later and surfaces it as a 500 mid-run instead of a 400 at the moment of the mistake.

The existing single-request MCP path (`EvaluationWorker.executeMcp`) is **not** refactored into the registry. Refactoring a working executor to prove an abstraction risks regressing MCP suites for no user-visible gain.

### 13. Rate limiter moves to per-HTTP-call, retries counted

`InProcessEvaluationExecutor` consumes one token per *test-case run*, before `semaphore.acquire()`. That counts **dispatches, not requests**: admitting `R` dispatches/sec where each emits `N` requests yields `R·N` requests/sec at the deployment. Sequentiality within a chain changes burst shape, not the mean — chains overlap up to `concurrencyLevel`.

A new `RunRateLimiter` gate is acquired inside each of the three single-attempt call sites — `EvaluationWorker.invokeSingle`, `EvaluationWorker.invokeMcpSingle`, `DeploymentTurnInvoker.invokeSingle` — and the dispatch-loop `consume(1)` is removed. The chain reuses the HTTP site, so no fourth site is needed. This also fixes multi-turn's existing N× overshoot.

Retries consume tokens: a retry is a real request, and retries cluster precisely when the target is already returning 429/5xx — the worst moment to bypass the limit.

*Concern evaluated and dismissed:* the gate now blocks while a concurrency permit is held. That does **not** reduce achieved request throughput below `R` — permits simply idle and fewer new chains start, which is the intended behavior. `BlockingBucket.consume()` throws `InterruptedException`, so `shutdownNow()` cancellation still works.

*Alternative rejected:* consuming `chainLength` tokens upfront gets the mean right but front-loads accounting against requests issued over the chain's lifetime, and would not fix multi-turn.

### 14. Snapshot mirrors the live shape; version stays `"2"`

`SuiteSnapshotDto` gains `additionalRequests`; the flat fields stay; the same normalizer is applied on read.

*Rationale:* one normalizer serves both live suite and snapshot, so "the chain" has a single definition. Storing a pre-normalized `requests[]` alongside retained flat fields would duplicate request 0 in one document, creating a which-is-authoritative question that two readers will eventually answer differently.

Version stays `"2"`: it signals **structural** change requiring a different interpretation (v1→v2 was the dataset-rooted shift, still branched on via absent `datasetRef`). An absent `additionalRequests` needs no branch — it *is* a single-request chain. Direct precedent: `overallScore` was added to the snapshot backward-compatibly with no bump.

### 15. Chain cap: configurable, enforced at save **and** run creation

`test-suite.multi-request.max-requests`, default `10` (matching `test-case.multi-turn.max-turns`). Validated at save (400 `VALIDATION_ERROR`) and again at run creation (409 `INVALID_OPERATION`) — because a *configurable* cap can be lowered after a suite is persisted, so a save-time check alone would let an over-cap suite run.

Guard order becomes: 1 not-found · 2 unbound · 3 config-invalid · **3b chain cap** · **3c multi-turn dataset** · 4 zero-runnable · 5 rate-limits. Both new guards sit after config-invalid: they are configuration-correctness checks, cheaper than `countRunnable`'s query, and "your chain is too long" is a better diagnostic than "no runnable test cases" when both hold.

Rejected at save rather than persisted with `isValid=false`: the multi-turn turn cap invalidates rather than rejects because it arrives via bulk CSV import where rejecting a 10,000-row file over one bad case is disruptive. A suite save is one small form submission with no partial-success semantics to preserve, and (ii) would leave an unrunnable suite in a valid-looking state.

### 16. Try-it-out stays single-endpoint

Try-out instantiates **one** request template with supplied values; an optional `requestIndex` selects which chain element's `endpointRef`/`requestTemplate`/`inputBindings` to use. In variables mode the caller supplies values directly — including any that would come from a prior request — so no prefix execution is needed and a later request *can* be tried in isolation.

`requestIndex` rather than `requestLabel`: the index is a key component, making it the reliable handle for the frontend.

In test-case mode a `responseField` variable has no source. It surfaces as a `ValidationWarningDto` in `resolvedRequest.warnings` and the request is still sent (applying the placeholder default when declared) — `ResolvedRequestDto.warnings` exists for exactly this, and a 200 naming the unresolved variable tells the author more than a 400.

### 17. Import: `requestLabel` is client-supplied

`TestCaseRunResultItemDto` gains optional `requestIndex` (`@Min(0)`, default 0) and a client-supplied `requestLabel`, with **no** bound check against the snapshot.

*Rationale:* the import path accepts results from **external** test suite runs, which have no snapshot chain to derive a label from. Deriving server-side would be impossible for exactly the case the endpoint exists to serve.

### 18. Row ordering unchanged

`Cursor(createdAt, id)` is independent of the natural key, so pagination needs no change. Ordering stays `created_at_ms DESC, id DESC` — which, because `created_at_ms` is constant per run and `id` is a random UUID, is **arbitrary within a run**. Making it deterministic would require a 4–5 component keyset, a new `CursorCodec` version, and a compatibility story for issued cursors, to replace a sort the client already performs for multi-turn. The arbitrary ordering is instead documented as an explicit clients-MUST-sort contract.

### 19. Clone copies the chain verbatim

`additionalRequests` / `requestLabel` are copied and **not** overridable, so no new TSMD revalidation trigger is needed. Making them overridable without extending the revalidation trigger set would be a correctness hole — an override dropping a response column a TSMD binds to would copy `isValid=true` verbatim.

### 20. Export gains four identity columns

`requestIndex`, `requestLabel`, plus `turnIndex`/`totalTurns`. The turn columns fix a pre-existing gap: the planner emits neither, so a multi-turn CSV today has no column distinguishing turns — rows are separable only by opaque UUIDs. Shipping `requestIndex` beside a still-missing `turnIndex` would be a conspicuously inconsistent contract. `response::` columns become the chain union with sparse cells, reusing the same union helper as `EvalSummariesSchemaProvider`.

### Component interaction flow

```
TestSuiteService (save)
  └─ ChainNormalizer.normalize(suite) → List<RequestSpec>
       ├─ label defaulting + resolved-set uniqueness      → 400
       ├─ chain-wide response-column uniqueness           → 400
       ├─ responseField backward-only + column exists     → 400
       ├─ per-element template vs endpointRef validation   → validationWarnings[requestIndex]
       ├─ chain cap                                        → 400
       └─ MCP-typed element                                → 400

TestSuiteRunService.createRun
  └─ guards 1,2,3, 3b cap, 3c multi-turn dataset, 4, 5
  └─ SuiteSnapshotBuilder → snapshot (flat + additionalRequests)

InProcessEvaluationExecutor  (permit per test-case run; no rate-limit consume)
  └─ EvaluationWorker.execute
       ├─ MCP suite            → executeMcp                    (untouched)
       ├─ multiTurnData != null → MultiTurnExecutor             (untouched)
       ├─ chain size > 1        → ChainExecutor                 (new)
       │    └─ for each RequestSpec, sequential:
       │         ChainStepExecutor registry → HttpChainStepExecutor
       │           ├─ resolve template (dataField | constantValue | responseField
       │           │    ← accumulated column map)
       │           ├─ RunRateLimiter.acquire() per attempt      (new)
       │           ├─ extract that request's response columns
       │           ├─ merge into accumulated map (later wins)
       │           └─ emit row {request_index, request_label, turn_index=0, total_turns=1}
       │         fail-fast: ERROR row, abort
       └─ else                  → single-request path           (unchanged)

InProcessMetricEvaluationExecutor  (per row, unchanged structure)
  └─ ConditionExpressionEvaluator ← ConditionContext{.., requestIndex, requestLabel}
```

### Transaction boundaries

Unchanged. Suite save and validation stay in the meta transaction; snapshot creation stays in the existing snapshot transaction (including its `40001` retry); chain execution issues HTTP calls **outside** any transaction and writes rows through the existing `ResultBatchWriter` batching against the analytics transaction manager. The chain adds no new transaction and no cross-datasource transaction.

### Error handling

- **Write-time, author-correctable** → fail fast, 400 `VALIDATION_ERROR` (cap, MCP element, duplicate labels, duplicate response columns, forward/missing `responseField`).
- **Run-creation, config-vs-data mismatch** → 409 `INVALID_OPERATION` (cap re-check, multi-turn dataset).
- **Per-request runtime failure** → ERROR row + chain abort; other test cases continue. Never fails the run.
- **Metric-level failure** → existing `TsmdEvaluationResult.Failure` → summary `FAILED`. Unchanged mechanics.
- **Unreachable-by-construction** → `McpChainStepExecutor` throws `UnsupportedOperationException`.

Per AGENTS.md every `catch` passes the exception as the last SLF4J argument, and catches name specific types.

## Risks / Trade-offs

- **Data-only metrics run N× per test case with no failure signal** → Documented in the capability spec as the accepted cost of decision 9; `condition` is the defense. Revisit if a warning-severity channel is ever added.
- **Rate-limiter fix slows existing multi-turn runs** → Correct behavior finally honoring configured RPS, but it *is* a behavior change to shipped functionality. Called out in the proposal and release notes; operators who relied on the effective (inflated) throughput must raise `rateLimitRps`.
- **Export gains four columns** → Additive and appended to the identity block, so name-based consumers are unaffected; strictly positional CSV parsers break. Called out as breaking.
- **Chains can exceed `MAX_EXPORT_COLUMNS`** → Existing behavior is a 400 naming count and cap, an adequate diagnostic. Cap left unchanged rather than raised speculatively.
- **Analytics unique-index rebuild on a large table** → Follow the `V1.13`/`V1.14` precedent, including their note to use `CREATE UNIQUE INDEX CONCURRENTLY` plus a constraint swap in a non-transactional migration on large deployments.
- **Two multiplicity axes exist in the schema but only one is usable** → `turn_index` and `request_index` both live in the key while decision 11 forbids combining them. Accepted: the schema is ready when the exclusion is lifted, and the guard keeps the meaning unambiguous meanwhile.
- **Chain-wide column uniqueness may surprise authors** who reuse a natural name like `answer` on two requests → 400 at save with a message naming both requests; it is the cost of keeping every downstream consumer unqualified.
- **`request_label` is client-supplied on import** (decision 17), so imported rows can carry labels inconsistent with any suite config → Inherent to supporting external runs; the label is display-only and not in the key, so nothing downstream breaks.

## Migration Plan

1. **Meta migration** `V1.29__AddAdditionalRequestsToTestSuites.sql` — `additional_requests JSONB`, `request_label VARCHAR(255)`, both nullable. No backfill: NULL ⇒ single-request, today's behavior.
2. **Analytics migration** `V1.15__AddRequestColumnsToResultsAndSummaries.sql` — `request_index INTEGER NOT NULL DEFAULT 0` and `request_label VARCHAR(255)` on `test_case_run_results` and `test_case_eval_summaries`; drop and recreate both unique indexes with `request_index` included. Existing rows default to `request_index=0`, so keys remain unique and idempotency is preserved.
3. `./gradlew generateJooq`; commit the generated diff. Update `docs/database-schema.md`.
4. Update the upsert conflict target in `PostgresTestCaseRunResultRepository` to match the widened index — required for the migration to be correct, not optional.
5. Add `test-suite.multi-request.max-requests: 10` to `application.yml`; add the six-column row to `docs/configuration.md`.
6. Deploy. Existing suites are unaffected (NULL chain, `request_index=0`).

**Rollback:** the feature is inert without `additional_requests` data, so rolling back application code is safe with the columns in place. Reverting the analytics migration requires restoring the narrower unique indexes and would need any multi-request rows deleted first (they would collide on the narrower key). Recommended rollback is code-only, leaving the columns.

## Open Questions

None blocking. Deliberately deferred, each additive with no schema change:

- Multi-request × multi-turn (decision 11) — semantics established as a sum; awaiting demand.
- MCP chaining (decision 12) — seam in place, stub throwing.
- Dependency-aware partial chain continuation (decision 7).
- Array-valued / all-turns `responseField` resolution, which would need array-valued template variables the `${{var|type:default}}` resolver does not support.
- Per-request execution settings (timeout, retry).
- A validation-warning severity channel, which would let decision 9's trap be surfaced at save time instead of as a FAILED row.
