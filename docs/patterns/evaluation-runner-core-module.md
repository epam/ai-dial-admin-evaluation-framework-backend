# `evaluation-runner-core` module (Phase 1 execution engine extraction)

The Phase 1 execution path (`EvaluationWorker`, the unified `TurnLoopExecutor` turn loop — superseding the earlier chat-completions-only `MultiTurnExecutor`, which was removed — `RequestResolver`, DIAL Core/MCP clients, SSE parsing, response column extraction) lives in a separate DB-free Gradle subproject under root package `com.epam.aidial.evaluation.runner`, so a future standalone CI runner can share exact execution-logic parity with the EF backend.

## Request resolution

`RequestResolver` (`runner.service`, JSONata-powered: URL/query/header/body template resolution, with the JSON body evaluated via `RequestBodyEvaluator`) is the shared request-resolution entry point — the EF backend's `ResolvedRequestService` now only keeps the DB-backed Try-It-Out lookup (`resolveRequest(UUID, UUID)`) and delegates to an injected `RequestResolver`.

`TurnLoopExecutor` (the run path) injects `RequestResolver` directly and calls `resolveForRun` (frame-bound, throws on evaluation failure), while `ResolvedRequestService.resolveRequest` calls `resolve` (empty frame, downgrades an evaluation failure to a validation warning).

## Wiring

The module contributes all its beans to the EF backend via Spring Boot autoconfiguration (`EvaluationRunnerAutoConfiguration` + `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`) — no manual `@Import`/`@ComponentScan` needed.

`JsonbMapper` is split: the shared module's `runner.util.RunnerJsonbMapper` (bean name `runnerJsonbMapper` — avoids a `ConflictingBeanDefinitionException` from the simple-name collision with the EF backend's own `service.domain.mapper.JsonbMapper`) keeps only the read-direction methods Phase 1 needs (`mapRequestTemplate`, `mapInputBindings`); everything else (including `mapOverallScore`) stays in the EF backend's `JsonbMapper`, which delegates the two shared methods to an injected `RunnerJsonbMapper` field.

## Deliberate duplication

A handful of cross-boundary DTOs (`FieldDefinitionDto`, `SuiteType`, `ValidationConstants`, `ValidationException`) are deliberately duplicated — a full copy in `runner.dto`/`runner.model`/`runner.exception` for the module's own use, and the EF backend's own richer/validated copy (or, for `ValidationException`, a subclass of the module's) for everything else — rather than the module depending back on the EF backend.

## Enforcement

The module's own `RunnerModuleConstraintsTest` (ArchUnit) enforces: no JDBC/jOOQ/Flyway dependency, no dependency back on the EF backend outside `runner.*`, `client` does not depend on `job`, every Spring component carries `@LogExecution`.

See `evaluation-runner-core-module` in [specs/README.md](../../openspec/specs/README.md) and the package tables in [key-packages.md](../key-packages.md).
