## MODIFIED Requirements

### Requirement: Module boundary — execution engine scope
The `evaluation-runner-core` module SHALL contain exactly the Phase 1 test-case execution engine and its direct dependencies. It SHALL NOT contain any Phase 2 (metric evaluation), Phase 3 (score statistics), job orchestration, DB persistence, or SSE progress infrastructure.

**Classes owned by `evaluation-runner-core`:**

*Execution workers (package `runner.job`):*
`EvaluationWorker`, `TurnLoopExecutor`, `PerTurnBindingDetector`, `DeploymentTurnInvoker`, `DeploymentInvocationSupport`, `ExecutionErrorCodes`, `EvaluationContext`, `TurnOutcome`, `RunExecutorFactory` (single source of the run thread mode — virtual when `spring.threads.virtual.enabled` is `true`, platform otherwise — and builder of per-run, OTel-context-wrapped, thread-per-task worker executors used by both the EF backend's `ActiveRunRegistry` and eval-cli's `EvaluationContextFactory`), `TestCaseRunner` (concurrent-dispatch logic on a caller-owned thread-per-task executor supplied through `EvaluationContext.executor`: semaphore-bounded dispatch, Bucket4j rate limiting, synthetic-error-row handling for worker exceptions, no row for tasks interrupted by the owner's `shutdownNow()`; the runner never creates or shuts down the executor — extracted from the EF backend's `InProcessEvaluationExecutor`), `TestCaseRunResultFactory` (builds a synthetic error `TestCaseRunResult` from a `TestCaseRunInput`, run index, exception, and clock millis — pure function, no template resolution or DB access), `ResultBatchWriter` (interface: `addResults(List<TestCaseRunResult>)`, `flush()` — an instance is scoped to one run; the interface itself is DB-free and lives here, but its Postgres-backed implementation, `PostgresResultBatchWriter`/`PostgresResultBatchWriterFactory`, lives in the EF backend's `service.domain.job` package since it depends on `TestCaseRunResultRepository`, `TestSuiteRunSseService`, and the analytics `TransactionTemplate`)

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

*Utilities (packages `runner.util`, `runner.config.logging`):*
`QuietJsonService`, `JsonbMapper` (trimmed subset — keeps only `mapRequestTemplate(String)` and `mapInputBindings(String)`, the two read-direction methods Phase 1 execution needs; registered as a Spring bean under the explicit name `runnerJsonbMapper`, see the bean-naming requirement below. The EF backend keeps its own full `com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper` for every other method — write-direction mapping, `mapFieldDefinitions`, `mapResponseColumns`, `mapMetricBindings`, `mapOverallScore`, etc. — and delegates to the shared module's `JsonbMapper` for the two shared methods so there is one source of truth for `RequestTemplateDto`/`InputBindingDto` parsing), `ValidationWarningsSerializer`, `EvalBaggage`, `TokenPropagationHelper`, `LogExecution` (annotation)

*Config properties (package `runner.config.properties`):*
`EvaluationRunProperties`, `SseEventProcessingProperties`, `DialCoreProperties`, `McpClientProperties`, `DialFileStorageProperties`

*Domain model (package `runner.model`):*
`TestCaseRunResult`, `TestCaseRunInput` (the execution path's input counterpart to `TestCaseRunResult` — pure POJO, used by `EvaluationWorker`/`TurnLoopExecutor`/`TestCaseRunner` and by the EF backend's repository/mapper layer)

*Autoconfiguration (package `runner.config`):*
`EvaluationRunnerAutoConfiguration`

#### Scenario: No JDBC/jOOQ/Flyway types in shared module
- **WHEN** ArchUnit's `RunnerModuleConstraintsTest` is run in the shared module
- **THEN** it SHALL find no class in `com.epam.aidial.evaluation.runner` that imports any type from `org.jooq`, `org.springframework.jdbc`, `org.flywaydb`, or `javax.sql`

#### Scenario: No reverse dependency on EF backend
- **WHEN** ArchUnit's `RunnerModuleConstraintsTest` is run in the shared module
- **THEN** it SHALL find no class in `com.epam.aidial.evaluation.runner` that imports any type from `com.epam.aidial.evaluation` (the EF backend's root package)

#### Scenario: Classes staying in EF backend are not duplicated
- **WHEN** the shared module is built
- **THEN** the following classes SHALL NOT exist in `evaluation-runner-core`: `InProcessEvaluationExecutor`, `EvaluationExecutor`, `TestSuiteEvaluationJob`, `PostgresResultBatchWriter`, `PostgresResultBatchWriterFactory`, `MetricEvaluationWorker`, `InProcessMetricEvaluationExecutor`, `MetricEvaluationExecutor`, `ResolvedRequestService`, `DialCoreClient`, `TestSuiteRunSseService`, `SuiteSnapshotBuilder` — note: `ResultBatchWriter` (the DB-free interface) DOES exist in `evaluation-runner-core`; only its Postgres-backed implementations (`PostgresResultBatchWriter`/`PostgresResultBatchWriterFactory`) stay in the EF backend


### Requirement: Spring Boot autoconfiguration
The `evaluation-runner-core` module SHALL provide a Spring Boot autoconfiguration class `EvaluationRunnerAutoConfiguration` that registers all shared beans. This class SHALL be declared in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` so that any Spring Boot application with the module on its classpath automatically receives all execution engine beans.

#### Scenario: EF backend picks up shared beans without manual wiring
- **WHEN** the EF backend application starts with `evaluation-runner-core` on its classpath
- **THEN** `EvaluationWorker`, `TurnLoopExecutor`, `RequestResolver`, `ResponseColumnExtractor`, `DialCoreDeploymentInvoker`, `McpToolInvoker`, `SseEventParser`, and all other shared beans SHALL be available in the application context without any `@Import` or `@ComponentScan` annotation in the EF backend's application class

#### Scenario: Autoconfiguration is idempotent
- **WHEN** `EvaluationRunnerAutoConfiguration` is loaded
- **THEN** it SHALL use `@ConditionalOnMissingBean` or rely on Spring Boot's deduplication for `@ConfigurationProperties` beans to avoid duplicate bean registration if a consumer overrides any shared bean

#### Scenario: Run executor factory follows the Spring thread mode
- **WHEN** a consumer application starts with `evaluation-runner-core` on its classpath
- **THEN** a `RunExecutorFactory` bean SHALL be available whose thread mode equals the resolved value of `spring.threads.virtual.enabled` (virtual when `true`, platform when `false` or unset), and a consumer-defined `RunExecutorFactory` bean SHALL take precedence over the autoconfigured one
