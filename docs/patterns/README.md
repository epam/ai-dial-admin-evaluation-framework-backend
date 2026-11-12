# Unique Patterns

Project-specific patterns not discoverable from a single file. Linked from [AGENTS.md](../../AGENTS.md).

| Pattern | Summary |
|---------|---------|
| [TokenPropagationHelper](token-propagation.md) | Propagate the caller's credential (bearer JWT or API key, with kind) across `CompletableFuture.supplyAsync` and other pooled-thread boundaries |
| [Evaluation tracing](evaluation-tracing.md) | Explicit native OTel job-context capture; no long-lived spans: short `eval.testcase.execute` (per repetition) and `eval.testcase.metrics` (per result row) root traces linked to `eval.run` and to each other via run-owned `EvaluationTraceSession`; never persist span contexts |
| [TransactionTimestampContext + Aspect](transaction-timestamp.md) | One shared `createdAt`/`updatedAt` per `@Transactional`; explicit-param repo signatures for `TransactionTemplate` callers |
| [Dual Datasource (Meta + Analytics)](dual-datasource.md) | Required `@Qualifier`s for DSLContext / tx manager / conditionals — getting these wrong silently uses the wrong DB; also the meta-after-analytics Flyway ordering and the SQL-only-DDL rule for Java migrations |
| [CursorCodec & Keyset Pagination](cursor-pagination.md) | Analytics layer; `LIMIT size+1` pattern + opaque Base64 cursor |
| [SchemaTypeCoercer vs SchemaChangeCoercer](schema-coercers.md) | Permissive (CSV import) vs strict (revalidation after schema-type change) — pick the right one |
| [DIAL Core File Storage](dial-file-storage.md) | `DialFileClient` + `DialFileRefResolver`; suite-scoped `@ef/suites/{suiteId}/{filename}` and dataset-scoped `@ef/datasets/{datasetId}/{filename}` references |
| [RequestBodySerializerRegistry](request-body-serializer-registry.md) | Strategy pattern for JSON / multipart / urlencoded request bodies |
| [JSONB_NUMERIC Multi-Level Path Filtering](jsonb-numeric-filtering.md) | Two-level JSONB filtering with parameterized path components |
| [Typed SQL DSL (jOOQ)](jooq-typed-sql-dsl.md) | Codegen pipeline, drift guard, DSLContext config, RecordMapper convention |
| [Dataset Entity](dataset-entity.md) | `DatasetSchemaProvider`, `dataset.id` vs `suite.id`, visibility rules, exclusion via `testCaseFilter`; schema field `id` (rename ≠ delete+create, guard after save, prune-then-rename, imports lock first and go through `DatasetService.update`) |
| [Suite Run Snapshot Phase](suite-run-snapshot.md) | Snapshot tx, `40001` retry, inconsistent-snapshot guard, version handling |
| [Run cancellation](run-cancellation.md) | Per-run executor teardown + guarded status writes; never interrupt the job thread; keep worker HTTP clients interruptible (JDK factory); thread mode (virtual default, platform opt-out) comes from `RunExecutorFactory` |
| [Selective Column Projection (TOAST)](selective-column-projection.md) | Column-tier constants to avoid TOAST decompression on bulk queries |
| [MCP Tool Invocation](mcp-tool-invocation.md) | Per-call `McpSyncClient` via DIAL Core MCP proxy |
| [Slash-containing path values](slash-path-values.md) | `/**` mapping + `WildcardPathResolver`; decode exactly once, never `URLDecoder` |
| [Suite validity = config only](suite-validity-and-run-guards.md) | `isValid` excludes test-case presence; the 5 ordered `createRun` guards |
| [Computation Versioning (no `is_latest`)](computation-versioning.md) | "Latest" resolved at query time from eval summaries, not snapshots; multi-run `"latest"` in SQL via the opt-in `LatestComputationSupport` |
| [Eval summaries = single read surface](eval-summaries-read-surface.md) | One summary per result row even at zero TSMDs; empty list ≠ "no metrics" |
| [Query DSL `ParamExpr`](query-dsl-parameters.md) | Single pre-pass resolver rewrites `StructuredQuery` params before translation |
| [Query DSL function catalog](query-dsl-function-catalog.md) | Registry-driven `QueryFunction` SPI; stored-function delegation; no `mean` fn |
| [Typed `OverallScoreDefinition`](overall-score-definition.md) | Sealed `Mean`/`WeightedMean`/`CustomFunction`; Phase 2 per-test-case `score`/`passed` computed once per test case (`test_case_eval_scores`) via `test_case_id IN (...)`/`GROUP BY test_case_id` graft over `test_case_metric_scores` (only `Mean`/`WeightedMean` for `testCaseOverallScore`, `CustomFunction` rejected at write time); CustomFunction stays on `eval_summaries` at Phase 3; metric refs → suite warnings (`ScoreReferenceValidator`, only `roc_auc(field, field)` custom shape supported), refreshed on TSMD CRUD |
| [Query DSL entity resolution](query-dsl-entity-resolution.md) | `StructuredQueryEntityResolver` SPI + registry as the single 400 check; entities opt into `LatestComputationSupport` for per-run `computation_id eq "latest"` |
| [Query result page extension](query-result-page-extension.md) | `QueryResultPageExtender` SPI + `@Primary` coordinator; open key set; per-extender failure isolation; an extender bounding an external lookup with a timed `Future.get` self-degrades (logs once, returns the received page) |
| [Query DSL subqueries](query-dsl-subqueries.md) | Subquery-valued `in` and scalar subqueries; the one lazy-bean cycle break |
| [Query DSL null polarity](query-dsl-null-polarity.md) | `nc`/`ne`/`not` are total (null satisfies); positive ops stay unwrapped/sargable |
| [`test_cases` query entity + `testCaseFilter`](test-cases-query-entity.md) | Instance-aware bindings keyed by `dataset_id`; scope-aware ALL-turns-match |
| [`test_suite_runs` query entity](test-suite-runs-query-entity.md) | Fully derived table excludes heavy JSONB cols (one `run_config` scalar, `number_of_runs`, still published); `metric_names` scalar subquery pulled up; snapshot-only refs; `computed_at_ms`/`computation_id` tiebreak |
| [Multi-turn test cases](multi-turn-test-cases.md) | Emergent from `multi_turn_data`, not a suite flag; `perTurn` scope; turn loop |
| [Multi-request suites](multi-request-suites.md) | `additionalRequests` chain; one flat response-column union; accumulated frame; `(request_index, turn_index)` |
| [Request-template JSONata seam](jsonata-evaluation-seam.md) | `content` vs `jsonataContent`; `$_request`/`$_response`; never `.` in a binding name |
| [`evaluation-runner-core` module](evaluation-runner-core-module.md) | DB-free Phase 1 engine; autoconfiguration wiring; deliberate DTO duplication |
| [Anthropic Messages API support](anthropic-messages-api.md) | Deployment-less `/anthropic/v1/messages` + `/openai/v1/responses` URL passthrough; third `StreamingResponseAccumulator` mode; `model`-in-body must equal the effective deployment (static + pre-invocation `RequestModelValidator`) |
| [MCP Server (inbound)](mcp-server.md) | `mcp` layer as a peer of `web`; request-thread caller model on two library behaviours (+ D-F2c fallback); executor never throws; error table + binding-layer limitation; session model (`STATELESS` default, `STREAMABLE` opt-in) |
| [ZIP archive round trip](zip-archive-round-trip.md) | `public/` refs verbatim, EF-owned files re-uploaded with same-name overwrite; manifest decides schema on OVERRIDE; uploads before the tx with a backup/restore journal |
| [DIAL App mode (Application Routes)](dial-app-mode.md) | Feature-flagged DIAL Application Route integration (`dial-app-proxy.enabled`) for long-running evals and PRK-based file auto-sharing; reuses `CallerCredential`/`ApiKeyAuthenticationFilter`/`ActiveRunRegistry` rather than introducing PRK-specific state; `DialRouteTriggerClient` fires eval via DIAL Core, `EvalExecuteInternalController` receives and dispatches, `TestSuiteRunSseService` emits configurable-heartbeat SSE events |
| [Run deletion tombstone](run-deletion-tombstone.md) | Soft-visibility exclusion on append-only analytics data: `run_deletions` tombstone written before the meta delete (idempotent), `RunDeletionFilter.notDeleted` ANDed into every read of a run-scoped analytics table, `StructuredQueryEntityResolver.baseCondition()` for Query DSL entities; a new run-scoped analytics table must be registered in `JooqSchemaDriftTest` |
| [`SuiteTarget` / `SuiteDefinition` hierarchy](suite-target.md) | Internal sealed `HttpTarget`(Deployment/Route) / `McpToolTarget` (where) paired with `HttpSuite`(request chain) / `McpSuite` (what) in `SuiteDefinition`; one value on `EvaluationContext`; flat DTO/DB/snapshot at the boundary; derived via `SuiteDefinitions`/`SuiteDefinitionResolver`; `SuiteTargetResolver.fromRequest` = single 400 check |
