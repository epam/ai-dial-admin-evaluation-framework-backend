## Why

Running evaluations in CI pipelines requires executing test cases against a deployment without standing up the full EF backend (PostgreSQL, Flyway, job orchestration, SSE infrastructure). The Phase 1 execution engine — request resolution, deployment invocation, SSE accumulation, response extraction, MCP support — is already a coherent, DB-free subsystem inside the backend. Extracting it into a reusable Gradle module eliminates the need to maintain two parallel implementations and guarantees that a standalone CI runner has exact parity with the EF backend's evaluation logic.

## What Changes

- The single-module Gradle build is converted to a **multi-module build**. A new subproject `evaluation-runner-core` is added.
- The **Phase 1 execution engine** (EvaluationWorker, MultiTurnExecutor, DeploymentTurnInvoker, all supporting infrastructure) is moved from the EF backend into `evaluation-runner-core` under a new root package `com.epam.aidial.evaluation.runner`.
- All **clients required during execution** move to the shared module: `DialCoreDeploymentInvoker`, `McpToolInvoker`, `DialFileClient`, `DialFileRefResolver`.
- All **infrastructure utilities** used exclusively or primarily by the execution engine move to the shared module: `SseEventParser`, `StreamingResponseAccumulator`, `QuietJsonService`, `ValidationWarningsSerializer`, `EvalBaggage`, `TokenPropagationHelper`, `TemplateVariableResolver`, `DialCoreUrlBuilder`, `JsonataEvaluationService` / `DashjoinJsonataEvaluationService`.
- `JsonbMapper` **splits** rather than moving wholesale (see Decision 10 in `design.md`): only its two execution-path methods (`mapRequestTemplate`, `mapInputBindings` read direction) move to the shared module; every other method (suite/dataset/metric CRUD mapping, `mapOverallScore`, etc.) stays in a restored EF-backend `JsonbMapper` that delegates to the shared one for the two common methods. `FieldDefinitionDto`, `MetricParameterBindingDto`, and the `overallscore` DTOs move back out of `runner.dto` accordingly — they were only reachable via the non-execution `JsonbMapper` methods.
- The `resolve(template, bindings, data)` execution-path method is **extracted** from `ResolvedRequestService` into a new `RequestResolver` class in the shared module. The EF backend's `ResolvedRequestService` retains only the Try-It-Out DB-backed overload and delegates to `RequestResolver` for the execution path.
- `TestCaseRunResult` (currently `data.db.analytics.model`) moves to the shared module as a pure domain model. The EF backend's analytics `RecordMapper` maps from the shared model to the jOOQ record.
- The `evaluation-runner-core` module provides a **Spring Boot autoconfiguration** entry point so both the EF backend and a future CLI runner pick up all beans automatically.
- All **Spotless + palantir-java-format and Checkstyle rules** apply to the shared module identically; a lighter ArchUnit test (no dual-datasource or layering rules specific to the full backend) is added.
- No DB dependencies (JDBC, jOOQ, Flyway) are introduced in `evaluation-runner-core`.
- No Maven publication is configured; the module is an in-repo Gradle project dependency only.
- `ResultBatchWriter`'s flush destination becomes pluggable: a new `TestCaseResultsOutputWriter` interface (shared module) replaces the direct dependency on the EF backend's Postgres-writing class (renamed `ResultBatchWriterTransactional` → `AnalyticsDbResultsOutputWriter`, now implementing the interface). See Decision 11 in `design.md`. **Superseded by the `ResultBatchWriter` interface below (Decision 13)** — the narrower `TestCaseResultsOutputWriter` seam and `AnalyticsDbResultsOutputWriter` are removed.
- The concurrent-dispatch logic ("given a list of test cases, run them all against a deployment and report results") is extracted from `InProcessEvaluationExecutor` into a new DB-free `TestCaseRunner` (shared module), along with `TestCaseRunResultFactory`. `InProcessEvaluationExecutor` is thinned to DB paging + result buffering, delegating dispatch per page. See Decision 12 in `design.md`.
- `TestCaseRunner.run(...)`'s raw `Consumer<List<TestCaseRunResult>>` parameter is replaced by a proper DB-free `ResultBatchWriter` interface (shared module: `addResults(...)`/`flush()`, scoped one instance per run), superseding Decision 11's narrower `TestCaseResultsOutputWriter`. The EF backend's old concrete `ResultBatchWriter` class + `AnalyticsDbResultsOutputWriter` are replaced by `PostgresResultBatchWriterFactory` + `PostgresResultBatchWriter` (the latter a plain, non-bean, per-run instance using a manually-built `TransactionTemplate` for the transactional write instead of a separate `@Transactional` bean). See Decision 13 in `design.md`.

## Capabilities

### New Capabilities

- `evaluation-runner-core-module`: The new `evaluation-runner-core` Gradle subproject — its build setup, package structure, autoconfiguration, and the complete set of classes it owns. Documents what moves, what stays, and the module boundary contract.

### Modified Capabilities

- `eval-execution-engine`: The execution engine spec is unchanged in terms of requirements and scenarios. The implementation home changes: the core execution classes now live in `evaluation-runner-core` rather than the EF backend. The EF backend wires them via the shared module dependency. No API or behavioral changes.

## Impact

**Code moved (EF backend → `evaluation-runner-core`)**
- `service.domain.job`: `EvaluationWorker`, `MultiTurnExecutor`, `DeploymentTurnInvoker`, `DeploymentInvocationSupport`, `SseEventParser`, `SseEvent`, `SseParseResult`, `StreamingResponseAccumulator`, `EvaluationContext`, `TurnOutcome`, `TestCaseRunResultFactory`, and the new `TestCaseRunner` (see Decision 12 in `design.md`)
- `service.domain`: `RequestResolver` (new, extracted from `ResolvedRequestService`), `RequestBodySerializer` + 3 impls, `RequestBodySerializerRegistry`, `ResponseColumnExtractor`, `ResponseColumnTypeReconciler`, `TemplateVariableResolver`, `DialCoreUrlBuilder`, `McpRequestResolver`, `McpResponseSerializer`, `QuietJsonService`, `ValidationWarningsSerializer`, `JsonataEvaluationService`, `DashjoinJsonataEvaluationService`
- `service.domain.mapper`: only `JsonbMapper.mapRequestTemplate`/`mapInputBindings` (read direction) — extracted into the shared module's `JsonbMapper`, not a full-class move (see Decision 10 in `design.md`)
- `client.dialcore`: `DialCoreDeploymentInvoker` + its configuration class and `DialCoreProperties`, `DialFileClient`, `DialFileRefResolver`, `DialFileStorageProperties`
- `client.mcp`: `McpToolInvoker`, `McpClientConfiguration`, `McpClientProperties`, `McpRequestResolver`, `McpResponseSerializer`, `McpInvocationException`, `McpTransport`
- `configuration.properties.testsuite`: `EvaluationRunProperties` (execution + retry sub-trees)
- `configuration.properties`: `SseEventProcessingProperties`
- `configuration.security`: `TokenPropagationHelper`
- `utils`: `EvalBaggage`
- `data.db.analytics.model`: `TestCaseRunResult` (becomes a shared domain model; jOOQ record stays in EF backend)

**Code staying in EF backend (updated to import from shared module)**
- `InProcessEvaluationExecutor` (thinned to DB paging + result buffering; delegates dispatch to the shared module's `TestCaseRunner`, passing it a `PostgresResultBatchWriter` instance), `TestSuiteEvaluationJob`, `EvaluationExecutor` interface
- `PostgresResultBatchWriterFactory` + `PostgresResultBatchWriter` (replace the old concrete `ResultBatchWriter` class and `AnalyticsDbResultsOutputWriter`; `PostgresResultBatchWriter` implements the shared module's `ResultBatchWriter` interface and is a plain per-run instance, not a Spring bean)
- `MetricEvaluationWorker`, `MetricEvaluationContext`, `InProcessMetricEvaluationExecutor`, `MetricEvaluationExecutor` interface
- `ResolvedRequestService` (Try-It-Out overload only; delegates to shared `RequestResolver`)
- All DB repositories and jOOQ generated sources
- `TestSuiteRunSseService`, `SuiteSnapshotBuilder`, `SuiteSnapshotDto`
- `DialCoreClient` (catalog queries — `GET /openai/models`, `/v1/deployments`, etc.)
- All controllers, web security, and exception handlers
- `service.domain.mapper.JsonbMapper` (restored) — every method except `mapRequestTemplate`/`mapInputBindings` read direction: suite/dataset/metric CRUD mapping, `mapOverallScore`, `mapTestCaseFilter`, etc. Delegates to the shared module's `JsonbMapper` for the two common methods.
- `service.domain.dto.FieldDefinitionDto`, `service.domain.dto.MetricParameterBindingDto`, `service.domain.dto.overallscore.*` (`OverallScoreDefinition`, `Mean`, `WeightedMean`, `WeightedMetric`, `CustomFunction`) — moved back out of `runner.dto`; only reachable via the EF-backend-only `JsonbMapper` methods

**Dependencies**
- `evaluation-runner-core/build.gradle`: Spring Boot BOM platform, `spring-boot-starter`, MCP SDK BOM + `mcp-core` + `mcp-json-jackson3`, Bucket4j, Lombok + MapStruct, OpenTelemetry BOM + `opentelemetry-spring-boot-starter`, `commons-lang3`, `commons-text`, `commons-collections4`, `dashjoin:jsonata`, `hibernate-validator`, `networknt:json-schema-validator`. No JDBC, no jOOQ, no Flyway, no springdoc.
- EF backend `build.gradle`: adds `implementation project(':evaluation-runner-core')`; removes direct declarations of dependencies now owned by the shared module (where duplication would conflict).

**Build**
- `settings.gradle` gains `include 'evaluation-runner-core'`
- No schema changes; no Flyway migrations needed
- No API contract changes; no OpenAPI spec changes
- `ArchUnit` `LayeredArchitectureTest` in EF backend updated for any package-rule changes caused by moved classes; the shared module gets its own lighter ArchUnit test

**Tests moved to `evaluation-runner-core`**
- `EvaluationWorkerTest`, `SseEventParserTest`, `DeploymentInvocationSupportTest`, `StreamingResponseAccumulatorTest`, `AdvancingClock` (test helper)
