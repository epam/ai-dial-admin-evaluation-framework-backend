## Context

The EF backend is currently a single Gradle module. Its Phase 1 execution engine — the components that take a test case, call a deployment endpoint (or MCP tool), and return a `TestCaseRunResult` — is logically self-contained and has zero direct database dependencies in its hot path. However, it is physically co-located with job orchestration, DB repositories, SSE progress infrastructure, and metric evaluation (Phases 2–3).

To enable a standalone CI runner that shares the same execution logic, the execution engine must be moved into a separate Gradle subproject that the EF backend and the CLI runner can both depend on. The refactoring must not change any observable behavior: all existing execution semantics, retry logic, SSE accumulation, and MCP support are preserved exactly.

Key facts driving the design:
- Four classes in `service.domain.job` have DB repository dependencies: `TestSuiteEvaluationJob`, `InProcessEvaluationExecutor`, `ResultBatchWriterTransactional`, `InProcessMetricEvaluationExecutor`. All others in that package are DB-free.
- `ResolvedRequestService` has two concerns: a DB-backed Try-It-Out overload (`resolveRequest(UUID, UUID)`) and a pure in-memory execution-path overload (`resolve(template, bindings, data)`). Only the latter needs to move.
- `TestCaseRunResult` currently lives in `data.db.analytics.model` but has no jOOQ or JDBC dependencies itself — it is a pure Lombok POJO. The analytics jOOQ `Record` is a separate generated class.
- Spring Boot autoconfiguration is the right wiring mechanism: it lets both consumers (EF backend, CLI runner) pick up all shared beans by declaring a dependency, without writing any bean definitions themselves.

## Goals / Non-Goals

**Goals:**
- Establish `evaluation-runner-core` as an in-repo Gradle subproject containing the Phase 1 execution engine.
- Zero behavioral change: execution logic, retry policy, SSE parsing, MCP invocation, response extraction — all identical to current.
- The EF backend compiles and tests pass without modification to any job orchestration or repository code (only import paths change for moved classes).
- The shared module is DB-free: no JDBC, no jOOQ, no Flyway dependency.
- Spring Boot autoconfiguration provided so consumers need no manual bean wiring.
- Same code quality standards: Spotless, Checkstyle, ArchUnit (lighter ruleset).

**Non-Goals:**
- Implementing the standalone CLI runner itself — this change only creates the shared library.
- Moving Phase 2 (metric evaluation) or Phase 3 (score statistics) to the shared module.
- Introducing any new evaluation features or changing execution semantics.
- Maven publication or versioning — in-repo project dependency only.
- Changing the REST API, OpenAPI spec, or any database schema.

## Decisions

### Decision 1: New root package `com.epam.aidial.evaluation.runner`

**Decision:** All classes in `evaluation-runner-core` use the root package `com.epam.aidial.evaluation.runner`, mirroring the source sub-packages below it (e.g., `runner.job`, `runner.client.dialcore`, `runner.client.mcp`, `runner.config`).

**Why:** A distinct root package makes the module boundary explicit in import statements, prevents accidental cross-module `import *` pollution, and aligns with a future scenario where `evaluation-runner-core` is published as a standalone artifact. The EF backend's own package (`com.epam.aidial.evaluation`) is unaffected — its remaining classes keep their current packages.

**Alternative considered:** Keep the same root package `com.epam.aidial.evaluation` across both modules and rely purely on Gradle module boundaries. Rejected: the module boundary is invisible in code, making it easy to accidentally create circular imports or blur the boundary during future refactors.

### Decision 2: `TestCaseRunResult` moves to the shared module as a pure domain model

**Decision:** `TestCaseRunResult` is relocated from `data.db.analytics.model` to `com.epam.aidial.evaluation.runner.model`. The jOOQ-generated `TestCaseRunResultsRecord` stays in the EF backend's generated sources. The analytics `TestCaseRunResultRecordMapper` in the EF backend is updated to import `TestCaseRunResult` from the shared module.

**Why:** `TestCaseRunResult` is the primary output type of `EvaluationWorker`. It is a pure Lombok POJO with no jOOQ or JDBC annotations. Moving it to the shared module means the execution engine's output type is self-contained — the EF backend's persistence layer is the consumer, not the definer, of this type.

**Alternative considered:** Define a separate `TestCaseRunResultDto` in the shared module and keep `TestCaseRunResult` in the backend; map between them at the persistence boundary. Rejected: adds an unnecessary type and mapper with no behavioral benefit, since `TestCaseRunResult` already has no DB-specific annotations.

### Decision 3: Extract `RequestResolver` from `ResolvedRequestService`

**Decision:** The `resolve(RequestTemplateDto template, List<InputBindingDto> bindings, Map<String,Object> data)` method — and only that method — is extracted into a new `@Component RequestResolver` in the shared module. `ResolvedRequestService` in the EF backend retains the `@Transactional resolveRequest(UUID, UUID)` overload (the Try-It-Out path) and injects `RequestResolver` to satisfy its own resolution needs. `EvaluationWorker` is updated to inject `RequestResolver` directly.

**Why:** `ResolvedRequestService` currently mixes two independent concerns: a DB-backed API path and a DB-free execution path. The execution engine must not carry DB dependencies into the shared module, so a clean extraction is required. Keeping `ResolvedRequestService` as a thin EF-backend wrapper around `RequestResolver` avoids breaking the Try-It-Out controller path.

**Alternative considered:** Move the entire `ResolvedRequestService` to the shared module and use interface injection for its DB-backed methods. Rejected: over-engineers a simple separation; the DB-backed path has no value to the shared module.

### Decision 4: `DialCoreDeploymentInvoker` and `DialFileClient` move in full; `DialCoreClient` stays

**Decision:** `DialCoreDeploymentInvoker` (runtime invocation), `DialFileClient` (file operations needed by `McpRequestResolver`), and `DialFileRefResolver` move to the shared module. `DialCoreClient` (catalog queries: `GET /openai/models`, `GET /v1/deployments`, etc.) stays in the EF backend.

**Why:** `DialCoreDeploymentInvoker` is the innermost HTTP call in the execution path; it must be in the shared module. `DialFileClient` is needed transitively by `McpRequestResolver → DialFileRefResolver → DialFileClient.getBucket()` — moving the entire `DialFileClient` is simpler than introducing a narrowing interface. `DialCoreClient` is only used by the EF backend's deployment catalog services and has no role in the execution path.

**Alternative considered:** Extract a `DialFileBucketClient` interface in the shared module with only `getBucket()`. Rejected: adds interface and implementation boilerplate for a single method; the full `DialFileClient` is already dependency-free.

### Decision 5: `DialCoreProperties` splits across modules

**Decision:** `DialCoreProperties` (prefix `dial.components.core`) moves to the shared module because `DialCoreDeploymentInvoker` depends on it. The EF backend's `DialCoreClientConfiguration` (which creates the general `dialCoreRestClient` bean for `DialCoreClient`) and `TryItOutService` will import `DialCoreProperties` from the shared module.

**Why:** A `@ConfigurationProperties` class must live in exactly one place. Since its primary execution-path consumer (`DialCoreDeploymentInvoker`) is in the shared module, it belongs there. The EF backend already imports many types from the shared module — one more `@ConfigurationProperties` import is consistent with the boundary.

**Alternative considered:** Duplicate `DialCoreProperties` in both modules. Rejected: dual-maintenance risk and divergence potential.

### Decision 6: Spring Boot autoconfiguration via `AutoConfiguration.imports`

**Decision:** The shared module provides `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` pointing to `com.epam.aidial.evaluation.runner.config.EvaluationRunnerAutoConfiguration`. This `@Configuration` class declares `@Bean` methods for all shared components (or relies on `@ComponentScan` scoped to `com.epam.aidial.evaluation.runner`) and is annotated with `@AutoConfiguration`.

**Why:** Spring Boot's autoconfiguration mechanism is the standard way for library modules to contribute beans to a consumer application. Using it means neither the EF backend nor the CLI runner needs to add `@Import` or `@ComponentScan` annotations — the beans are automatically available when the module is on the classpath and Spring Boot auto-configuration is active.

**Alternative considered:** Use `@ComponentScan` in the EF backend's main application class to include `com.epam.aidial.evaluation.runner`. Rejected: couples the EF backend's application class to the shared module's package structure; consumers must be updated manually.

### Decision 7: `EvaluationRunProperties` sub-trees split between modules

**Decision:** The `execution.*` and `retry.*` sub-trees of `EvaluationRunProperties` that govern execution behavior (concurrency, timeouts, rate limiting, header blacklist, retry) are the parts needed by the shared module. The `runInputs.retentionDays` sub-tree is EF-backend-only (governs `test_case_run_inputs` cleanup). The entire `EvaluationRunProperties` class moves to the shared module; `runInputs` is kept as a nested class but is only used by the EF backend's housekeeping job. This avoids splitting a single `@ConfigurationProperties` class.

**Alternative considered:** Split into two `@ConfigurationProperties` classes at different prefixes. Rejected: breaking change to the property namespace visible to operators; `runInputs` is a minor sub-tree that causes no harm in the shared module.

### Decision 8: ArchUnit in the shared module — execution-engine layering only

**Decision:** The shared module's `LayeredArchitectureTest` enforces that:
1. `runner.job` may depend on `runner.client.*`, `runner.config`, `runner.model`, and `runner.service`.
2. `runner.client.*` may not depend on `runner.job`.
3. No class in the shared module may depend on `com.epam.aidial.evaluation.*` (the EF backend's root package) — the dependency is one-way only.
4. No class in the shared module may import any JDBC, jOOQ, or Flyway type.

The dual-datasource, experimental-query-layer, and DB-model rules from the EF backend's `LayeredArchitectureTest` do not apply.

**Why:** The shared module has a simpler layer structure. Enforcing the DB-free constraint via ArchUnit catches accidental drift (e.g., someone imports a jOOQ type into an execution class).

### Decision 9: `EvaluationContext` and `TurnOutcome` stay in `runner.job`

**Decision:** `EvaluationContext` and `TurnOutcome` move to `com.epam.aidial.evaluation.runner.job` — the same sub-package as `EvaluationWorker`, `MultiTurnExecutor`, etc. They are not promoted to `runner.model`.

**Why:** `EvaluationContext` is a context carrier for a single evaluation run (carries run-level config, snapshot fields, cancellation signal) — it is specific to the job execution flow, not a general domain model. `TurnOutcome` is an internal turn-loop result used only by `DeploymentTurnInvoker` and `MultiTurnExecutor`. Placing them in `runner.model` would misrepresent their scope.

### Decision 10: `JsonbMapper` splits — only the two execution-path methods move to the shared module

**Decision:** `JsonbMapper` (originally listed as moving wholesale in the Migration Plan step 3) is split instead:
- `com.epam.aidial.evaluation.runner.util.JsonbMapper` (shared module) keeps exactly two methods: `mapRequestTemplate(String) → RequestTemplateDto` and `mapInputBindings(String) → List<InputBindingDto>` (read direction only).
- `com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper` (EF backend, restored) keeps every other method: `map(DeploymentReferenceDto/EndpointContractDto/RequestTemplateDto)` write direction, `mapFieldDefinitions`, `mapResponseColumns`, `mapMetricBindings`, `mapMcpDeploymentRef`, `mapToolRef`, `mapArgumentTemplate`, `mapEndpointContract`, `mapJsonSchema`, `mapOverallScore`, `mapTestCaseFilter`, and the write direction of `mapInputBindings`. It injects the shared module's `JsonbMapper` and delegates to it for the two shared methods, so there is a single source of truth for `RequestTemplateDto`/`InputBindingDto` parsing.
- `extractDeploymentId(String)` and `extractHttpMethod(String)` are deleted outright — dead code with no callers anywhere in the codebase.
- `FieldDefinitionDto`, `MetricParameterBindingDto`, and the `overallscore` package (`OverallScoreDefinition`, `Mean`, `WeightedMean`, `WeightedMetric`, `CustomFunction`) move back out of `runner.dto` to the EF backend's `service.domain.dto` (and `service.domain.dto.overallscore`), since — once `JsonbMapper`'s non-execution methods move back — nothing else in the shared module references these types.

**Why:** Tracing every call site showed `JsonbMapper`'s two request-template/input-binding read methods are the only ones called by Phase 1 execution (`EvaluationWorker`, `MultiTurnExecutor`, for the test-case-level template/binding override). `EvaluationContext`'s own snapshot fields (`deploymentRef`, `endpointRef`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`, `responseColumns`) are assigned directly from a whole-object `SuiteSnapshotDto` deserialize (`TestSuiteEvaluationJob.resolveSnapshot()` does one `objectMapper.readValue(json, SuiteSnapshotDto.class)`) — never through `JsonbMapper`. Every other `JsonbMapper` method is called exclusively by EF-backend CRUD/mapper classes (`TestSuiteMapper`, `DatasetMapper`, `TestSuiteMetricDefinitionMapper`, `MetricDeclarationVersionMapper`, `RunMetricSnapshotMapper`), `SuiteSnapshotBuilder`/`SuiteValidationService` (suite write-time validation, not execution), or `TryItOutService`/`TemplateVariableService` (Try-It-Out, explicitly EF-backend-only per Decision 3). `mapOverallScore` specifically belongs to Phase 3 (resolved later by `OverallScoreDefinitionResolver`), which the proposal's Non-Goals explicitly excludes from this module. Moving `JsonbMapper` wholesale (the original plan) would have pulled Phase-3/CRUD-only types into a module scoped to Phase 1 execution, violating that non-goal.

**Alternative considered:** Keep `JsonbMapper` as one class in the shared module and accept the Phase-3/CRUD type leakage (as already accepted for `EvaluationRunProperties.runInputs` in Decision 7). Rejected: `runInputs` is an inert, read-only config sub-tree with zero behavioral surface; `OverallScoreDefinition`/`FieldDefinitionDto`/`MetricParameterBindingDto` are active domain types with their own validation/serialization concerns — carrying them into the shared module is a materially different (and larger) leak than the accepted precedent, worth fixing rather than tolerating.

### Decision 11: `ResultBatchWriter`'s flush destination becomes pluggable via `TestCaseResultsOutputWriter`

**Decision:** Introduce `com.epam.aidial.evaluation.runner.job.TestCaseResultsOutputWriter` (shared module, alongside `EvaluationContext`) — a single-method interface `void write(List<TestCaseRunResult> batch)`. Rename the EF backend's `ResultBatchWriterTransactional` to `AnalyticsDbResultsOutputWriter` and have it implement the interface (same `@Transactional("analyticsTransactionManager")` behavior, method renamed `saveBatch` → `write`). `ResultBatchWriter` (`service.domain.job`, unchanged otherwise) is updated to depend on the interface type instead of the concrete class.

**Why:** `ResultBatchWriter`'s batching/threshold/progress-counting logic is currently the last DB-bound link between the DB-free execution engine and result persistence — it always calls straight into Postgres via `ResultBatchWriterTransactional.saveBatch`. A future standalone CI runner (the reason this module exists) will want the identical buffering logic but a different sink (e.g., an in-memory list handed back to its caller, with no DB on the classpath at all). Unlike the `MetricScoreComputation`/`RunnableTestCaseSelector` inversions (Decision 8/9's siblings — both interface *and* implementation are inherently DB-bound; the inversion there exists purely to keep `service → experimental.query` out of bytecode), this interface itself must be DB-free, so it belongs in the shared module, not `service.domain.job`.

**Non-goal (deferred):** The actual in-memory/alternate-sink implementation is not built in this change — only the seam (interface + rename) is introduced. `ResultBatchWriter` itself does not move to the shared module: it depends on `TestSuiteRunSseService` for live-progress SSE, an EF-backend/HTTP concern with no standalone-runner equivalent.

### Decision 12: `TestCaseRunner` extracts the concurrent-dispatch logic out of `InProcessEvaluationExecutor`

**Decision:** Move `TestCaseRunResultFactory` (`errorResult(TestCaseRunInput, int runIndex, Throwable, long nowMs)`) from `service.domain.job` to `com.epam.aidial.evaluation.runner.job.TestCaseRunResultFactory`. Introduce a new `com.epam.aidial.evaluation.runner.job.TestCaseRunner` (shared module) with `void run(List<TestCaseRunInput> testCases, EvaluationContext context, List<ResponseColumnDefinitionDto> responseColumns, Consumer<List<TestCaseRunResult>> resultsConsumer)`, lifting `InProcessEvaluationExecutor`'s per-run `Semaphore`/virtual-thread-executor/Bucket4j-rate-limit/cancellation-grace-period/synthetic-error-row dispatch logic essentially verbatim. `InProcessEvaluationExecutor` keeps only DB paging (`fetchPage`, `TestCaseRunInputRepository`/`TestCaseRepository`) and the `ResultBatchWriter` buffer/flush lifecycle, calling `testCaseRunner.run(page, context, responseColumns, results -> resultBatchWriter.addResults(buffer, results))` once per fetched page.

**Why:** "Given a list of test cases already in memory, run them all concurrently against a deployment and report results" has no DB dependency — it's exactly what a future standalone CI runner needs, since it already has its test cases resolved with no DB to page from. `EvaluationContext` is reused as-is as the parameter object for run-level settings (concurrency, rate limit, grace period, cancellation, token) — it's already DB-free (no repository types) and already the type `EvaluationWorker` itself takes; its `datasetId` field is unused by `TestCaseRunner` but harmless (read only by `InProcessEvaluationExecutor`'s own paging code). `TestCaseRunResultFactory` is a pure function of its arguments (only dependency: a plain Jackson `ObjectMapper`), so it moves cleanly. `java.util.function.Consumer<List<TestCaseRunResult>>` is used for results delivery rather than a new interface — the caller (today: `InProcessEvaluationExecutor`; tomorrow: a standalone runner) decides what to do per completed test case (buffer+SSE today; append to an in-memory list tomorrow). **Superseded by Decision 13**: the `Consumer` is later replaced with a proper `ResultBatchWriter` interface.

**Accepted (negligible) behavior note:** Calling `TestCaseRunner.run` once per DB page does not change the concurrency model — the semaphore bounds in-flight tasks to `concurrencyLevel` identically regardless of whether it's invoked once per page or once for a whole run. The only difference from today: currently the main thread's next-page DB fetch happens while the current page's tasks are still draining (the paging loop doesn't wait for futures before looping); calling `run()` once per page blocks until that page fully drains before the next page's `LIMIT 100` fetch starts. A single indexed query (milliseconds) hidden behind deployment calls that take much longer — not a meaningful behavior change.

### Decision 13: Full `ResultBatchWriter` interface supersedes `TestCaseResultsOutputWriter` (Decision 11) and `TestCaseRunner`'s `Consumer` parameter (Decision 12)

**Decision:** Decision 11's narrow seam (`TestCaseResultsOutputWriter` — only the final DB write pluggable, buffering/SSE fixed in the EF backend) is superseded. `ResultBatchWriter` becomes purely an interface name, moved to the shared module (`com.epam.aidial.evaluation.runner.job.ResultBatchWriter`, `addResults(List<TestCaseRunResult>)` + `flush()`, no buffer/handle parameter — an instance is scoped to one run for its whole lifetime). `TestCaseRunner.run(...)` takes this interface directly instead of `java.util.function.Consumer<List<TestCaseRunResult>>`. The EF backend's old concrete `service.domain.job.ResultBatchWriter` class (with its `RunBuffer` nested class) and `AnalyticsDbResultsOutputWriter` are both removed, replaced by:
- `PostgresResultBatchWriterFactory` (`service.domain.job`, Spring bean, explicit constructor — not `@RequiredArgsConstructor`, since it builds a derived field): injects `TestCaseRunResultRepository`, `TestSuiteRunSseService`, and `@Qualifier("analyticsTransactionManager") PlatformTransactionManager`, builds `this.analyticsTransactionTemplate = new TransactionTemplate(analyticsTxManager)` once (same pattern `EvalSummaryExportService` already uses for its own analytics `TransactionTemplate`). `createWriter(batchSize, runId, suiteId, totalCases)` returns a new `PostgresResultBatchWriter` per run.
- `PostgresResultBatchWriter` (`service.domain.job`, plain class — **not** a Spring bean, instantiated per run by the factory): merges the old `RunBuffer`'s fields (buffer list, `ReentrantLock`, `totalFlushed`/`testCasesCompleted` counters, `batchSize`/`runId`/`suiteId`/`totalCases`) directly as instance fields. `flush()`'s persistence call is `transactionTemplate.executeWithoutResult(status -> resultRepository.saveAll(batch))` instead of delegating to a separate `@Transactional`-annotated bean.

**Why:** A raw `Consumer` on `TestCaseRunner.run(...)` communicates nothing about intent. More importantly, Decision 11's split only made the *final write* pluggable — a future non-Postgres implementation (CSV, in-memory list for a standalone runner) would still have been forced through the EF-backend's fixed buffering/threshold/SSE wrapper, which makes no sense outside the EF backend (SSE in particular has no standalone-runner equivalent). Folding the whole per-run writer responsibility behind one DB-free interface lets a future implementation own its entire buffering/flush policy, not just the persistence step.

**Why a manually-constructed instance can still be transactional:** Spring's `@Transactional` only works via AOP proxying of beans, which is exactly why Decision 11 needed a *separate* `@Transactional`-annotated bean (`AnalyticsDbResultsOutputWriter`) to avoid self-invocation. A manually-`new`'d `PostgresResultBatchWriter` instance is not a bean at all, so `@Transactional` on its own methods would silently not apply. `TransactionTemplate` sidesteps this entirely — it demarcates a transaction via explicit code (`executeWithoutResult(...)`), not proxying, so it works identically whether the object holding it is a bean or not. The factory bean builds the `TransactionTemplate` once (Spring-managed `PlatformTransactionManager` injection) and hands it to each per-run instance it creates.

**Alternative considered:** Keep a separate small `@Transactional`-annotated bean (as in Decision 11) and inject it into `PostgresResultBatchWriter`. Rejected: it would keep two indirections (`PostgresResultBatchWriter` → save-bean → repository) for no benefit now that `TransactionTemplate` cleanly solves the self-invocation problem without a second bean.

### Decision 14: `TestCaseRunner` becomes a session-scoped scheduler (corrects Decision 12's "accepted negligible" note)

**Decision:** `TestCaseRunner` stops being a stateless Spring `@Component` with a one-shot `run(List<TestCaseRunInput>, EvaluationContext, List<ResponseColumnDefinitionDto>, ResultBatchWriter)` method. It becomes a plain per-run object (same non-bean pattern as `PostgresResultBatchWriter` in Decision 13), constructed once per run and fed test cases one at a time as `InProcessEvaluationExecutor` pages them in from the DB:
```java
public class TestCaseRunner {
    // built once in the constructor: Semaphore, Context.taskWrapping(...) executor,
    // rate-limit Bucket, futures list — all held as instance state for the run's lifetime
    public void submit(List<TestCaseRunInput> testCases) { /* today's per-item/per-runIndex dispatch loop, verbatim */ }
    public void awaitCompletion() { /* today's post-dispatch shutdown/join/grace-period logic, verbatim */ }
}
```
`submit` takes a `List<TestCaseRunInput>` (not a single input) — matching the pre-existing mental model of "hand the runner a list of test cases to run" — and can be called multiple times (e.g. once per DB page) before a single `awaitCompletion()`. A new `TestCaseRunnerFactory` (`runner.job`, `@Component @LogExecution`, stays in the shared module since its dependencies — `EvaluationWorker`, `TestCaseRunResultFactory`, `Clock` — are all shared-module-visible) exposes `create(context, responseColumns, resultsWriter) → new TestCaseRunner(...)`. `InProcessEvaluationExecutor` creates one `TestCaseRunner` per run (immediately after creating its `ResultBatchWriter`), calls `.submit(page)` once per fetched page, then calls `.awaitCompletion()` exactly once after the whole loop ends.

**Why:** Decision 12 accepted "calling `TestCaseRunner.run()` once per page" as a negligible trade-off (only losing DB-fetch/tail-execution overlap). Two problems surfaced that are **not** negligible:
1. **Rate-limit bucket resets every page.** Bucket4j's token bucket is wall-clock-time-based and starts full on construction; recreating it every ~100 test cases (`PAGE_SIZE`) grants a fresh burst allowance at every page boundary instead of one continuous budget for the whole run — a real correctness regression from the pre-extraction behavior (one bucket for the whole run), with real risk of exceeding a downstream provider's actual rate limit on large suites.
2. **Concurrency drains to empty at every page boundary.** The pre-extraction code shared one `Semaphore`/`ExecutorService` for the whole run, so a finishing task's slot was immediately taken by whatever was next in the single continuous dispatch loop — concurrency stayed saturated across page boundaries. Calling `run()` once per page forces a full drain (every in-flight task in that page must finish) before the next page is even fetched, creating a recurring drain-then-refill "convoy" effect at every page boundary — a real throughput cost for large suites, not merely the DB-fetch latency Decision 12 described.

Both problems have the same root cause: `TestCaseRunner` conflated "execute one test case" (already cleanly separated as `EvaluationWorker`) with "orchestrate many submissions under shared concurrency/rate-limiting/cancellation" — an inherently stateful, run-scoped responsibility, not a one-shot call. Splitting the scheduling responsibility into a session-scoped object (constructed once, fed submissions over time, finished once) restores the pre-extraction semantics exactly, while still paging from the DB rather than loading a suite's test cases fully into memory.

**Semaphore did not strictly need this fix on its own:** since `InProcessEvaluationExecutor`'s page loop calls `submit`/(previously)`run` strictly sequentially, a fresh semaphore is always "at rest" (all permits available) by the time the previous call/page fully drains — so page-scoped vs. run-scoped semaphore construction was already behaviorally equivalent. It's restructured anyway, for symmetry with the bucket and because both naturally belong to the same session object.

**Not a real issue:** `Context.taskWrapping(executor)`'s OpenTelemetry context capture happens per-task at submission time, not at executor-construction time, so recreating the executor per page never affected context propagation — a concern raised and then ruled out during this investigation.

## Risks / Trade-offs

**[Risk] Large-scale import churn causes merge conflicts with in-flight feature branches.**
→ Mitigation: Communicate the refactoring branch to the team before merging. The move is a pure rename/relocation — no logic changes — so merge conflicts are mechanical (import line updates) and resolvable with IDE assistance.

**[Risk] Spring Boot autoconfiguration in the shared module might load beans conditionally in ways that conflict with the EF backend's own bean definitions (e.g., double registration of `DialCoreProperties`).**
→ Mitigation: `@ConfigurationProperties` beans registered via `@EnableConfigurationProperties` in the autoconfiguration class are named by type; Spring Boot deduplicates them. All beans in the shared module use constructor injection, making double-registration failures visible at startup. Integration validated by running the EF backend's functional test suite after the move.

**[Risk] `DialCoreDeploymentInvokerConfiguration` creates a `RestClient` bean (`dialCoreTryOutRestClient`) currently also used by the Try-It-Out path (`TryItOutService`). Moving the configuration to the shared module means the EF backend's `TryItOutService` injects a bean declared in the shared module.**
→ Mitigation: This is acceptable — the `RestClient` bean is infrastructure, not business logic. The shared module's `DialCoreDeploymentInvokerConfiguration` declares both `RestClient` beans (the deployment invoker one and the try-out one, if applicable), or the try-out `RestClient` is moved back to the EF backend's configuration. The exact split is determined during implementation.

**[Risk] `DashjoinJsonataEvaluationService` brings the `com.dashjoin.jsonata` library as a transitive dependency into the shared module. Any consumer of the shared module implicitly gets JSONata.**
→ Mitigation: Acceptable — JSONata is needed for `ResponseColumnExtractor`, which is a core part of the execution engine. The library is small (< 1 MB) and has no transitive dependencies that conflict with Spring Boot.

**[Risk] Moving `TestCaseRunResult` to the shared module creates a compile dependency from the EF backend's analytics persistence layer onto the shared module. If future changes add DB-specific annotations to `TestCaseRunResult`, the shared module would need a JDBC dependency.**
→ Mitigation: `TestCaseRunResult` must remain a plain POJO with no persistence annotations. The ArchUnit no-JDBC rule in the shared module enforces this.

**[Risk] The `EvaluationRunProperties.runInputs` sub-tree (retention days for `test_case_run_inputs`) moves to the shared module even though it is only used by the EF backend. This slightly violates the principle of keeping EF-backend concerns in the EF backend.**
→ Mitigation: Accepted as a pragmatic trade-off to avoid splitting a single `@ConfigurationProperties` class. The `runInputs` field is read-only, causes no startup overhead, and has no side effects in the shared module.

## Migration Plan

1. **Create the `evaluation-runner-core` Gradle subproject** — `settings.gradle`, `build.gradle`, directory structure. Verify `./gradlew :evaluation-runner-core:build` succeeds on an empty module.
2. **Move domain models and config properties** — `TestCaseRunResult`, `EvaluationContext`, `TurnOutcome`, `EvaluationRunProperties`, `SseEventProcessingProperties`, `DialCoreProperties`, `McpClientProperties`, `DialFileStorageProperties`. Update all import sites in the EF backend.
3. **Move utilities** — `QuietJsonService`, `JsonbMapper`, `ValidationWarningsSerializer`, `EvalBaggage`, `TokenPropagationHelper`, `TemplateVariableResolver`, `DialCoreUrlBuilder`, `JsonataEvaluationService`, `DashjoinJsonataEvaluationService`. Update all import sites.
4. **Move SSE and streaming classes** — `SseEvent`, `SseParseResult`, `SseEventParser`, `StreamingResponseAccumulator`, `DeploymentInvocationSupport`. Update all import sites.
5. **Move clients** — `DialCoreDeploymentInvoker` + configuration; `DialFileClient`, `DialFileRefResolver`; all MCP client classes. Update all import sites.
6. **Move request/response pipeline** — Extract `RequestResolver` from `ResolvedRequestService`; move `RequestBodySerializer` (interface + 3 impls), `RequestBodySerializerRegistry`, `ResponseColumnExtractor`, `ResponseColumnTypeReconciler`. Update `EvaluationWorker` to inject `RequestResolver`. Update `ResolvedRequestService` to delegate to `RequestResolver`.
7. **Move core workers** — `EvaluationWorker`, `MultiTurnExecutor`, `DeploymentTurnInvoker`. Update all import sites in `InProcessEvaluationExecutor`.
8. **Write `EvaluationRunnerAutoConfiguration`** — declare all shared beans; create `AutoConfiguration.imports`. Verify the EF backend starts and all functional tests pass.
9. **Update ArchUnit tests** — update EF backend's `LayeredArchitectureTest` for changed package structure; add `RunnerLayeredArchitectureTest` to the shared module.
10. **Move unit tests** — `EvaluationWorkerTest`, `SseEventParserTest`, `DeploymentInvocationSupportTest`, `StreamingResponseAccumulatorTest`, `AdvancingClock` to shared module's `src/test/java`.
11. **Full build validation** — `./gradlew clean build` on the root; all tests pass; checkstyle clean; no ArchUnit violations.

**Rollback:** The refactoring is a pure move with no behavioral changes. If a blocking issue is found post-merge, rolling back is a matter of reverting the Gradle settings change and moving the class files back — no schema migrations or API contract reversals are required.

## Open Questions

- **`DialCoreDeploymentInvokerConfiguration` and the `dialCoreTryOutRestClient` bean:** This `RestClient` bean is created in the same configuration class as the deployment invoker's bean and is also used by the EF backend's `TryItOutService`. Should the try-out `RestClient` bean declaration remain in the shared module's configuration (acceptable since it is pure infrastructure), or should it be split into a separate EF-backend configuration class? Resolve during step 5 of the migration plan.
- **`@LogExecution` annotation:** All Spring component classes must have `@LogExecution` per AGENTS.md. This annotation is defined in `com.epam.aidial.evaluation.configuration.logging`. Should it move to the shared module (since shared module classes also need it), or should the shared module import it from the EF backend? Keeping it in the EF backend would create a shared-module → EF-backend dependency, which is forbidden. It should move to the shared module, and the EF backend imports it from there.
