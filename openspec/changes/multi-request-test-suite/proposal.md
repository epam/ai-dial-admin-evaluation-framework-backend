## Why

A test suite can currently issue exactly **one** HTTP request per test case. That limit is artificial: many real applications need a short sequence of calls against the same deployment before a scoreable answer exists — create a session, push configuration, invoke the model, optionally tear down. Today such an application cannot be evaluated at all, or is evaluated only through whichever single endpoint happens to be self-contained.

This change introduces a **multi-request test suite**: an ordered chain of independent HTTP requests against the same deployment, where a later request may consume an earlier request's extracted response columns, and each request yields its own result row so the UI can show per-request status, timing, and extracted values.

Two pre-existing defects surface directly on this path and are fixed here rather than inherited and multiplied (see *What Changes*).

## What Changes

**Core capability**

- A suite gains an ordered `additionalRequests` array. Non-empty ⇒ the suite is multi-request. Absent/empty ⇒ behavior is exactly as today.
- Each chain element is a complete request spec: `{type, label, endpointRef, requestTemplate, inputBindings, responseColumns}`. `deploymentRef` stays suite-level — the chain targets one deployment. Per-element `endpointRef` is required because chain requests hit different paths and methods with different body schemas.
- Requests execute **sequentially and independently** — no conversational `messages` accumulation. "Chain" means data flow, not conversation.
- **Cross-request chaining**: request-level `InputBindingDto` gains a third source, `responseField`, resolving against an accumulating map of every response column extracted so far. Response column names become unique **chain-wide**, so no qualification syntax is needed and every existing consumer (metric bindings, grid columns, export headers) is untouched.
- **One result row per request**, carrying `request_index` and `request_label`. Each row's `extractedColumns` holds only its own request's columns.
- **Fail-fast**: the first failing request persists an ERROR row and aborts the chain; later requests produce no rows.
- **Metric targeting reuses the existing `condition` mechanism.** The metric list stays flat; the condition dictionary gains a `request` namespace (`index`, `label`).
- Chain length is capped by a new configurable property, validated at save **and** at run creation (a configurable cap can be lowered after a suite is saved).

**Deliberately deferred, with the seam in place**

- MCP chaining: the chain element is polymorphic (`type: HTTP | MCP_TOOL`) and a `ChainStepExecutor` registry selects the implementation, but only the HTTP implementation is real. An MCP-typed element is rejected at save, with a stub executor as a defensive backstop. The existing single-request MCP path (`EvaluationWorker.executeMcp`) is **not** refactored.
- Multi-request combined with multi-turn test cases is rejected at run creation. The semantics are coherent (multi-turn is a per-request concern, so rows would *sum* rather than multiply) but the combination is out of scope until feedback arrives.

**Pre-existing defects fixed here**

- **BREAKING (behavioral, not API)** — **Rate-limiter accounting.** `InProcessEvaluationExecutor` consumes one token per *test-case run*, before dispatch, not per HTTP call. A 5-turn multi-turn case therefore already exceeds the configured RPS by 5×, and a chain would do the same by its length: over a window, admitting `R` dispatches/sec where each emits `N` requests yields `R·N` requests/sec at the deployment. The gate moves to the three `invokeSingle` call sites (single-request, MCP, multi-turn), and retries now consume tokens — a retry is a real request, and retries cluster exactly when the target is already struggling. This **slows existing multi-turn runs**, correctly.
- **BREAKING (CSV column set)** — **Eval-summary export identity columns.** `EvalSummaryExportColumnPlanner` emits no `turnIndex`/`totalTurns`, so a multi-turn CSV export today has no column distinguishing turns; rows are separable only by opaque UUIDs. `requestIndex`, `requestLabel`, `turnIndex`, and `totalTurns` are added. Additive and at the end of the identity block, so name-based consumers are unaffected; strictly positional parsers will notice.

**Explicitly accepted risks** (documented, not mitigated)

- An unconditioned metric runs on **every** request's row — N× cost per test case. A metric whose response binding is absent on a row fails, marking that row `FAILED`; that failure is the intended authoring signal to add a `condition`. A metric bound only to test-case data and constants resolves on every row and therefore runs N× with **no** failure signal; `condition` is the only defense. (A non-invalidating validation warning would be the better signal, but no such severity channel exists — `ValidationWarningDto` has no severity field and `ValidationResult` couples `valid` with `warnings`.)
- Chains can now exceed `ValidationConstants.MAX_EXPORT_COLUMNS`, surfacing as a 400 naming the count and the cap. The cap is left unchanged rather than raised speculatively.

## Capabilities

### New Capabilities
- `multi-request-test-suite`: the chain concept and its end-to-end contract — chain shape and internal normalization, chain-wide response-column namespace, `responseField` cross-request chaining and its resolution order, sequential fail-fast execution, per-request result rows, `request` condition namespace, chain-length cap, and the MCP/multi-turn exclusions.

### Modified Capabilities
- `test-suites`: `additionalRequests` + `requestLabel` fields; per-element template-vs-`endpointRef` validation; chain-wide response-column uniqueness; label uniqueness on the resolved set; chain-cap and MCP-element rejection at save; `requestIndex` on `ValidationWarningDto`.
- `request-template`: per-element `endpointRef`/`requestTemplate`/`inputBindings`; new `responseField` binding source with backward-only reference rules and placeholder-default fallback.
- `response-columns`: name uniqueness scope widens from the flat suite list to the whole chain.
- `eval-execution-engine`: chain dispatch and sequential fail-fast loop; accumulating extracted-column map; one concurrency permit per test-case run; rate-limiter gate relocated to per-HTTP-call with retries counted.
- `test-suite-runs`: two new run-creation guards (chain cap, multi-turn dataset) inserted after the config-invalid guard; updated guard order.
- `conditional-metric-execution`: dictionary gains `request: {index, label}`, with `label` documented as the preferred, reorder-safe form.
- `tsmd-validation`: response-column reference validation resolves against the chain union rather than the flat list.
- `analytics-eval-results`: `request_index` / `request_label` columns; unique index and upsert conflict target widened with `request_index`; per-request row semantics.
- `metrics-storage`: same column and natural-key widening on `test_case_eval_summaries`.
- `eval-summary-export`: four new identity columns; `response::` family becomes the chain union with sparse cells.
- `eval-results-import`: `requestIndex` (optional, default 0) and client-supplied `requestLabel` on the import item; no bound check, since external runs have no snapshot.
- `suite-run-snapshot`: snapshot mirrors the live suite shape by adding `additionalRequests`; `CURRENT_VERSION` stays `"2"` because an absent field *is* a single-request chain and needs no interpretation branch.
- `try-it-out`: optional `requestIndex` selects which chain request's `endpointRef`/template/bindings to instantiate; try-out stays single-endpoint by design; unresolvable `responseField` in test-case mode warns rather than rejects.
- `multi-turn-test-case`: multi-request suites reject multi-turn datasets at run creation; export gains the missing turn identity columns.
- `test-suite-clone`: `additionalRequests` / `requestLabel` copied verbatim and **not** overridable, so no new TSMD revalidation trigger is required.
- `query-schema-discovery`: `eval_summaries` `response::` family becomes the chain union (shared helper with the export planner); the base schema picks up the new columns from jOOQ automatically.

## Impact

**Migrations** — meta `V1.29__AddAdditionalRequestsToTestSuites.sql` (`additional_requests JSONB`, `request_label VARCHAR(255)`); analytics `V1.15__AddRequestColumnsToResultsAndSummaries.sql` (`request_index INTEGER NOT NULL DEFAULT 0`, `request_label VARCHAR(255)` on `test_case_run_results` and `test_case_eval_summaries`, plus unique-index swap). The analytics unique-index rebuild follows the `V1.13`/`V1.14` precedent, including its note about `CREATE UNIQUE INDEX CONCURRENTLY` on large deployments. Requires `./gradlew generateJooq` and committing the generated diff; `docs/database-schema.md` must be updated.

**Configuration** — new `test-suite.multi-request.max-requests` (default `10`, matching `test-case.multi-turn.max-turns`). Default defined in `application.yml`; requires a `docs/configuration.md` row with all six columns.

**New classes** — a chain normalizer producing the uniform `List<RequestSpec>` (single definition of "the chain", consumed by validation, execution, the export planner, and the query-DSL schema provider); `ChainStepExecutor` SPI + registry with `HttpChainStepExecutor` and an `McpChainStepExecutor` stub; `RunRateLimiter` gate.

**Modified classes** — `TestSuite` / `TestSuiteRequestDto` / `TestSuiteResponseDto` / `TestSuiteMapper` / `TestSuiteRecordMapper` / `PostgresTestSuiteRepository` / `JsonbMapper`; `InputBindingDto`; `SuiteSnapshotDto` + `SuiteSnapshotBuilder`; `TestCaseRunResult` / `EvalSummary` + record mappers + `PostgresTestCaseRunResultRepository` (conflict target); `TestCaseRunResultResponseDto` / `EvalSummaryResponseDto` / `EvalSummaryDetailResponseDto` / `TestCaseRunResultItemDto`; `EvaluationWorker` / `InProcessEvaluationExecutor` / `DeploymentTurnInvoker`; `ConditionContext` / `ConditionExpressionEvaluator`; `MetricDefinitionValidationService`; `EvalSummaryExportColumnPlanner`; `EvalSummariesSchemaProvider`; `TestSuiteRunService` (guards); try-out controllers; `ValidationWarningDto`; `ValidationConstants`.

**API** — all additive. No endpoint or field removals; no request shape a current client sends becomes invalid. OpenAPI examples need updating for the suite request/response, try-out, and import payloads.

**Not affected** — `overallScore` / `metric-score-statistics` (`avg()` ignores nulls; plumbing rows contribute nulls that `coalesce(avg(f),0)` already absorbs), `overallScoreThreshold` (not enforced server-side), `numberOfTestCases` (counts test cases, not rows), cursor pagination (`Cursor(createdAt, id)` is independent of the natural key), and `testCaseFilter` / `RunnableTestCaseSelector`.

**Documented contract clarification** — result rows are returned in arbitrary order within a run (`created_at_ms` is constant per run and `id` is a random UUID, so `ORDER BY created_at_ms DESC, id DESC` does not order by anything meaningful). Clients MUST sort by `(runIndex, requestIndex, turnIndex)`. This is pre-existing and currently undocumented; a UI implementer would reasonably assume otherwise.
