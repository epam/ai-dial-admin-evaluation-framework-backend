## MODIFIED Requirements

### Requirement: Module boundary — execution engine scope
The `evaluation-runner-core` module SHALL contain exactly the Phase 1 test-case execution engine and its direct dependencies. It SHALL NOT contain any Phase 2 (metric evaluation), Phase 3 (score statistics), job orchestration, DB persistence, or SSE progress infrastructure. As a narrow exception, it SHALL contain a DB-free **service-provider interface** for inline metric evaluation — `InlineMetricEvaluator` plus its two carrier types `InlineMetricRequest` and `InlineMetricResult` (package `runner.job`) — because `TurnLoopExecutor` (owned by this module) must be able to invoke a per-run evaluator without depending back on the EF backend. The interface and its carriers hold no Phase 2 logic themselves — they define only the contract; every actual implementation (TSMD evaluation, provider dispatch, EvalSummary construction) stays in the EF backend, unchanged from the existing rule that Phase 2 classes are backend-only.

**The SPI contract is total: `InlineMetricEvaluator.evaluate(InlineMetricRequest)` MUST NOT throw.** This includes checked exceptions — the interface method signature declares none — and, in particular, an interruption observed during evaluation (e.g. while waiting on a provider call or a semaphore) MUST be folded into a returned `InlineMetricResult` with `failed = true` rather than propagated as an `InterruptedException`, with the calling thread's interrupt flag re-set before returning so the surrounding cancellation machinery still observes it. `TurnLoopExecutor` invokes `evaluate()` from inside its own `try { ... } catch (RuntimeException)` block, which synthesizes a `REQUEST_RESOLUTION_ERROR` row for the current turn whenever an exception escapes that block; if `evaluate()` were allowed to throw, an evaluator defect would silently replace a genuine SUCCESS row with a synthetic error row, destroying real response data for a failure unrelated to the deployment call. Every implementation of this SPI MUST therefore wrap its own internal failures (provider errors, timeouts, condition evaluation, interruption) into a normal `InlineMetricResult` return value instead of letting anything propagate.

**Classes owned by `evaluation-runner-core`:**

*Execution workers (package `runner.job`):*
`EvaluationWorker`, `TurnLoopExecutor`, `PerTurnBindingDetector`, `DeploymentTurnInvoker`, `DeploymentInvocationSupport`, `ExecutionErrorCodes`, `EvaluationContext`, `TurnOutcome`, `TestCaseRunner` (concurrent-dispatch logic: semaphore/virtual-thread executor, Bucket4j rate limiting, cancellation grace period, synthetic-error-row handling — extracted from the EF backend's `InProcessEvaluationExecutor`), `TestCaseRunResultFactory` (builds a synthetic error `TestCaseRunResult` from a `TestCaseRunInput`, run index, exception, and clock millis — pure function, no template resolution or DB access), `ResultBatchWriter` (interface: `addResults(List<TestCaseRunResult>)`, `flush()` — an instance is scoped to one run; the interface itself is DB-free and lives here, but its Postgres-backed implementation, `PostgresResultBatchWriter`/`PostgresResultBatchWriterFactory`, lives in the EF backend's `service.domain.job` package since it depends on `TestCaseRunResultRepository`, `TestSuiteRunSseService`, and the analytics `TransactionTemplate`), `InlineMetricEvaluator` (interface: `evaluate(InlineMetricRequest)` returning `InlineMetricResult` — the DB-free SPI a per-run inline metric-evaluation implementation satisfies; a `null` value on `EvaluationContext.inlineMetricEvaluator` means "run non-inline, behave exactly as before this SPI existed"), `InlineMetricRequest` (carrier: the row's `TestCaseRunResult` plus the accumulated `Map<String,Object>` metrics frame so far — runner-local / JDK types only), `InlineMetricResult` (carrier: the frame entry to fold into the accumulator, plus a `failed` flag the caller uses to decide whether to abort the chain)

*SSE and streaming (package `runner.job`):*
`SseEventParser`, `SseEvent`, `SseParseResult`, `StreamingResponseAccumulator`

*Request pipeline (package `runner.service`):*
`RequestResolver` (extracted from EF backend's `ResolvedRequestService`), `RequestBodySerializer` (interface), `JsonRequestBodySerializer`, `MultipartFormDataRequestBodySerializer`, `UrlEncodedFormRequestBodySerializer`, `RequestBodySerializerRegistry`, `TemplateVariableResolver`, `DialCoreUrlBuilder`

*Response pipeline (package `runner.service`):*
`ResponseColumnExtractor`, `ResponseColumnTypeReconciler`

*JSONata evaluation (package `runner.service`):*
`JsonataEvaluationService` (interface), `DashjoinJsonataEvaluationService`

*DIAL Core invocation client (package `runner.client.dialcore`):*
`DialCoreDeploymentInvoker`, `DialCoreDeploymentInvokerConfiguration`

*DIAL file client (package `runner.client.dialcore`):*
`DialFileClient`, `DialFileRefResolver`

*MCP client (package `runner.client.mcp`):*
`McpToolInvoker`, `McpClientConfiguration`, `McpInvocationException`, `McpTransport`

*MCP service (package `runner.service`):*
`McpRequestResolver`, `McpResponseSerializer`

*Utilities (packages `runner.util`, `runner.config.logging`, `runner.constants`):*
`QuietJsonService`, `JsonbMapper` (trimmed subset — keeps only `mapRequestTemplate(String)` and `mapInputBindings(String)`, the two read-direction methods Phase 1 execution needs; registered as a Spring bean under the explicit name `runnerJsonbMapper`, see the bean-naming requirement below. The EF backend keeps its own full `com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper` for every other method — write-direction mapping, `mapFieldDefinitions`, `mapResponseColumns`, `mapMetricBindings`, `mapOverallScore`, etc. — and delegates to the shared module's `JsonbMapper` for the two shared methods so there is one source of truth for `RequestTemplateDto`/`InputBindingDto` parsing), `ValidationWarningsSerializer`, `EvalBaggage`, `TokenPropagationHelper`, `LogExecution` (annotation), `JsonataReservedNames` (constants — including the reserved frame binding names `_request`, `_response`, and, as of this change, `_metrics`)

*Config properties (package `runner.config.properties`):*
`EvaluationRunProperties`, `SseEventProcessingProperties`, `DialCoreProperties`, `McpClientProperties`, `DialFileStorageProperties`

*Domain model (package `runner.model`):*
`TestCaseRunResult`, `TestCaseRunInput` (the execution path's input counterpart to `TestCaseRunResult` — pure POJO, used by `EvaluationWorker`/`TurnLoopExecutor`/`TestCaseRunner` and by the EF backend's repository/mapper layer)

*Autoconfiguration (package `runner.config`):*
`EvaluationRunnerAutoConfiguration`

Status: **Planned**

#### Scenario: No JDBC/jOOQ/Flyway types in shared module
- **WHEN** ArchUnit's `RunnerModuleConstraintsTest` is run in the shared module
- **THEN** it SHALL find no class in `com.epam.aidial.evaluation.runner` that imports any type from `org.jooq`, `org.springframework.jdbc`, `org.flywaydb`, or `javax.sql`

#### Scenario: No reverse dependency on EF backend
- **WHEN** ArchUnit's `RunnerModuleConstraintsTest` is run in the shared module
- **THEN** it SHALL find no class in `com.epam.aidial.evaluation.runner` that imports any type from `com.epam.aidial.evaluation` (the EF backend's root package)

#### Scenario: Classes staying in EF backend are not duplicated
- **WHEN** the shared module is built
- **THEN** the following classes SHALL NOT exist in `evaluation-runner-core`: `InProcessEvaluationExecutor`, `EvaluationExecutor`, `TestSuiteEvaluationJob`, `PostgresResultBatchWriter`, `PostgresResultBatchWriterFactory`, `MetricEvaluationWorker`, `InProcessMetricEvaluationExecutor`, `MetricEvaluationExecutor`, `ResolvedRequestService`, `DialCoreClient`, `TestSuiteRunSseService`, `SuiteSnapshotBuilder`, `InlineModeDetector`, `MetricRowEvaluator`, `InlineMetricEvaluatorImpl`, `InlineMetricEvaluatorFactory`, `BindingResolver` — note: `ResultBatchWriter` and `InlineMetricEvaluator` (the DB-free interfaces) DO exist in `evaluation-runner-core`; only the Postgres-backed / DB-dependent implementations and orchestration classes stay in the EF backend

#### Scenario: InlineMetricEvaluator SPI has no DB or backend dependency
- **WHEN** `RunnerModuleConstraintsTest` is run
- **THEN** `InlineMetricEvaluator`, `InlineMetricRequest`, and `InlineMetricResult` SHALL import only runner-module-local or JDK types — no `org.jooq`, `org.springframework.jdbc`, `org.flywaydb`, `javax.sql`, or `com.epam.aidial.evaluation` (outside `.runner`) type

#### Scenario: evaluate() must not throw, including on interruption
- **WHEN** an `InlineMetricEvaluator` implementation's internal work fails for any reason, including an `InterruptedException` observed mid-evaluation
- **THEN** `evaluate()` SHALL return a normal `InlineMetricResult` with `failed = true` instead of throwing, re-setting the calling thread's interrupt flag first if the failure was an interruption — no exception, checked or unchecked, SHALL escape the call
