# evaluation-runner-core Module

## Purpose

This spec defines the `evaluation-runner-core` Gradle subproject — its module boundary, package structure, dependency contract, autoconfiguration mechanism, and code quality constraints. It is the authoritative contract for what the shared module contains and what it guarantees to consumers (the EF backend and future CLI runners).

Status: **Implemented**

## Requirements

### Requirement: Gradle multi-module build structure
The repository SHALL be a multi-module Gradle build with two subprojects: `evaluation-runner-core` (the shared execution engine) and the existing EF backend application module (root or a named subproject). The `settings.gradle` SHALL declare both subprojects. The EF backend's `build.gradle` SHALL declare `implementation project(':evaluation-runner-core')` as a dependency.

#### Scenario: evaluation-runner-core builds independently
- **WHEN** `./gradlew :evaluation-runner-core:build` is run
- **THEN** the shared module SHALL compile, pass all its own tests, and pass Spotless and Checkstyle checks without requiring the EF backend module to be present on the classpath

#### Scenario: EF backend depends on shared module
- **WHEN** `./gradlew :build` (or the EF backend module's build task) is run
- **THEN** the EF backend SHALL compile and all tests SHALL pass with `evaluation-runner-core` on its classpath

#### Scenario: No circular dependency
- **WHEN** the Gradle dependency graph is resolved
- **THEN** `evaluation-runner-core` SHALL have no dependency (direct or transitive) on the EF backend module

### Requirement: Module boundary — execution engine scope
The `evaluation-runner-core` module SHALL contain exactly the Phase 1 test-case execution engine and its direct dependencies. It SHALL NOT contain any Phase 2 (metric evaluation), Phase 3 (score statistics), job orchestration, DB persistence, or SSE progress infrastructure.

**Classes owned by `evaluation-runner-core`:**

*Execution workers (package `runner.job`):*
`EvaluationWorker`, `MultiTurnExecutor`, `DeploymentTurnInvoker`, `DeploymentInvocationSupport`, `EvaluationContext`, `TurnOutcome`, `TestCaseRunner` (concurrent-dispatch logic: semaphore/virtual-thread executor, Bucket4j rate limiting, cancellation grace period, synthetic-error-row handling — extracted from the EF backend's `InProcessEvaluationExecutor`), `TestCaseRunResultFactory` (builds a synthetic error `TestCaseRunResult` from a `TestCaseRunInput`, run index, exception, and clock millis — pure function, no template resolution or DB access), `ResultBatchWriter` (interface: `addResults(List<TestCaseRunResult>)`, `flush()` — an instance is scoped to one run; the interface itself is DB-free and lives here, but its Postgres-backed implementation, `PostgresResultBatchWriter`/`PostgresResultBatchWriterFactory`, lives in the EF backend's `service.domain.job` package since it depends on `TestCaseRunResultRepository`, `TestSuiteRunSseService`, and the analytics `TransactionTemplate`)

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
`TestCaseRunResult`, `TestCaseRunInput` (the execution path's input counterpart to `TestCaseRunResult` — pure POJO, used by `EvaluationWorker`/`MultiTurnExecutor`/`TestCaseRunner` and by the EF backend's repository/mapper layer)

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

### Requirement: Root package `com.epam.aidial.evaluation.runner`
All classes in `evaluation-runner-core` SHALL use `com.epam.aidial.evaluation.runner` as the root package. Sub-packages SHALL mirror the functional grouping: `runner.job`, `runner.service`, `runner.client.dialcore`, `runner.client.mcp`, `runner.config`, `runner.config.properties`, `runner.model`, `runner.util`.

#### Scenario: No class in shared module uses the EF backend root package
- **WHEN** the shared module source is compiled
- **THEN** no class declaration SHALL use the package `com.epam.aidial.evaluation` (without the `.runner` suffix)

### Requirement: Dependency set — no JDBC, no jOOQ, no Flyway, no springdoc
The `evaluation-runner-core/build.gradle` SHALL declare the following dependencies and no others (direct compile-scope):
- Spring Boot BOM platform
- `spring-boot-starter` (core autoconfiguration, no web, no security)
- MCP SDK BOM + `mcp-core` + `mcp-json-jackson3`
- `bucket4j_jdk17-core` (rate limiting)
- Lombok (compile-only) + MapStruct + annotation processors
- OpenTelemetry BOM + `opentelemetry-spring-boot-starter`
- `commons-lang3`, `commons-text`, `commons-collections4`
- `dashjoin:jsonata`
- `hibernate-validator`
- `networknt:json-schema-validator`

It SHALL NOT declare: `spring-boot-starter-jdbc`, `spring-boot-starter-jooq`, `flyway-core`, `springdoc-openapi-starter-webmvc-ui`, `postgresql` driver, `spring-boot-starter-web`, `spring-boot-starter-security`.

#### Scenario: Shared module has no web or security starter
- **WHEN** the shared module's dependency tree is resolved
- **THEN** `spring-boot-starter-web` and `spring-boot-starter-security` SHALL NOT appear (directly or transitively from the module's own declarations)

### Requirement: Spring Boot autoconfiguration
The `evaluation-runner-core` module SHALL provide a Spring Boot autoconfiguration class `EvaluationRunnerAutoConfiguration` that registers all shared beans. This class SHALL be declared in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` so that any Spring Boot application with the module on its classpath automatically receives all execution engine beans.

#### Scenario: EF backend picks up shared beans without manual wiring
- **WHEN** the EF backend application starts with `evaluation-runner-core` on its classpath
- **THEN** `EvaluationWorker`, `MultiTurnExecutor`, `RequestResolver`, `ResponseColumnExtractor`, `DialCoreDeploymentInvoker`, `McpToolInvoker`, `SseEventParser`, and all other shared beans SHALL be available in the application context without any `@Import` or `@ComponentScan` annotation in the EF backend's application class

#### Scenario: Autoconfiguration is idempotent
- **WHEN** `EvaluationRunnerAutoConfiguration` is loaded
- **THEN** it SHALL use `@ConditionalOnMissingBean` or rely on Spring Boot's deduplication for `@ConfigurationProperties` beans to avoid duplicate bean registration if a consumer overrides any shared bean

### Requirement: Shared module's `JsonbMapper` bean uses an explicit bean name
The shared module's `com.epam.aidial.evaluation.runner.util.JsonbMapper` SHALL be registered as a Spring bean under the explicit name `runnerJsonbMapper` (e.g. `@Component("runnerJsonbMapper")`), not the default simple-class-name bean name.

#### Scenario: No ConflictingBeanDefinitionException at EF backend startup
- **WHEN** the EF backend application context starts with both the shared module's `com.epam.aidial.evaluation.runner.util.JsonbMapper` and the EF backend's own `com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper` on the classpath
- **THEN** both beans SHALL register successfully without a `ConflictingBeanDefinitionException`, because they share a simple class name (`JsonbMapper`) but the shared module's bean is registered under the distinct explicit name `runnerJsonbMapper`

#### Scenario: EF backend JsonbMapper delegates to the shared bean by explicit name
- **WHEN** the EF backend's `com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper` is constructed
- **THEN** it SHALL inject the shared module's `JsonbMapper` bean (by type, resolvable because only one bean of that shared-module type exists) and delegate `mapRequestTemplate`/`mapInputBindings` calls to it

### Requirement: Code quality — same Spotless, Checkstyle; lighter ArchUnit
The `evaluation-runner-core` module SHALL apply the same Spotless + palantir-java-format and Checkstyle configurations as the EF backend (shared config files). It SHALL have its own ArchUnit test class `RunnerModuleConstraintsTest` enforcing:
1. No JDBC/jOOQ/Flyway imports (see "No JDBC/jOOQ/Flyway types in shared module").
2. No reverse dependency on the EF backend's package (see "No reverse dependency on EF backend").
3. `runner.client.*` classes SHALL NOT depend on `runner.job` classes.
4. All `@RestController`, `@Controller`, `@Service`, `@Repository`, `@Component`, `@Configuration` classes SHALL have `@LogExecution` at the class level.

The dual-datasource rules, experimental-layer rules, and DB-model rules from the EF backend's `LayeredArchitectureTest` SHALL NOT be replicated in the shared module.

#### Scenario: Checkstyle passes on shared module
- **WHEN** `./gradlew :evaluation-runner-core:checkstyleMain` is run
- **THEN** it SHALL report no violations

#### Scenario: Spotless passes on shared module
- **WHEN** `./gradlew :evaluation-runner-core:spotlessCheck` is run
- **THEN** it SHALL report no formatting violations

#### Scenario: ArchUnit enforces client-layer isolation
- **WHEN** `RunnerModuleConstraintsTest` is run
- **THEN** no class in `com.epam.aidial.evaluation.runner.client` SHALL have a dependency on any class in `com.epam.aidial.evaluation.runner.job`

### Requirement: `RequestResolver` extracted from `ResolvedRequestService`
A new `@Component RequestResolver` SHALL be created in the shared module containing the `resolve(RequestTemplateDto template, List<InputBindingDto> bindings, Map<String,Object> data)` logic currently implemented in `ResolvedRequestService`. `EvaluationWorker` and `MultiTurnExecutor` SHALL inject `RequestResolver` directly. The EF backend's `ResolvedRequestService` SHALL retain the `@Transactional resolveRequest(UUID suiteId, UUID testCaseId)` overload and SHALL inject `RequestResolver` to reuse the shared resolution logic.

#### Scenario: EvaluationWorker uses RequestResolver, not ResolvedRequestService
- **WHEN** `EvaluationWorker` resolves a request during test case execution
- **THEN** it SHALL call `requestResolver.resolve(template, bindings, data)` — no DB access occurs during the call

#### Scenario: ResolvedRequestService delegates to RequestResolver
- **WHEN** `ResolvedRequestService.resolveRequest(UUID suiteId, UUID testCaseId)` is called (Try-It-Out path)
- **THEN** after loading the suite and test case from the DB, it SHALL delegate to `requestResolver.resolve(template, bindings, testCaseData)` for the actual template resolution

### Requirement: `TestCaseRunResult` as shared domain model
`TestCaseRunResult` SHALL reside in the shared module at `com.epam.aidial.evaluation.runner.model`. It SHALL remain a pure Lombok POJO with no jOOQ, JDBC, or Spring Data annotations. The EF backend's `TestCaseRunResultRecordMapper` SHALL import `TestCaseRunResult` from the shared module and map it to/from the jOOQ-generated `TestCaseRunResultsRecord`.

#### Scenario: TestCaseRunResult has no persistence annotations
- **WHEN** the shared module is compiled
- **THEN** `TestCaseRunResult` SHALL have no annotations from `org.jooq`, `jakarta.persistence`, or `org.springframework.data`

#### Scenario: EF backend RecordMapper maps from shared model
- **WHEN** `TestCaseRunResultRecordMapper.from(TestCaseRunResult)` is called in the EF backend
- **THEN** it SHALL produce a `TestCaseRunResultsRecord` (jOOQ-generated, in EF backend) from the shared `TestCaseRunResult` without requiring any fields to be added to the shared model

### Requirement: `LogExecution` annotation resides in shared module
The `@LogExecution` annotation (currently `com.epam.aidial.evaluation.configuration.logging.LogExecution`) SHALL be relocated to the shared module at `com.epam.aidial.evaluation.runner.config.logging.LogExecution`. The EF backend SHALL import it from the shared module. All existing usages in both modules SHALL reference the new package.

#### Scenario: EF backend imports LogExecution from shared module
- **WHEN** the EF backend compiles
- **THEN** all `@LogExecution` annotations in the EF backend SHALL resolve to `com.epam.aidial.evaluation.runner.config.logging.LogExecution`

#### Scenario: No duplicate LogExecution definitions
- **WHEN** both modules are on the classpath
- **THEN** there SHALL be exactly one `LogExecution` annotation class reachable at runtime
