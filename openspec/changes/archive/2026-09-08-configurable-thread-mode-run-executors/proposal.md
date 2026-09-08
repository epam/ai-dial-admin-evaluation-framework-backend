## Why

Every test suite run currently occupies a platform thread from a fixed `ThreadPoolTaskExecutor` (core 5 / max 10 / queue 50) for its entire lifetime while doing almost nothing but waiting on worker futures and sequential jOOQ calls. Because a JDK pool only grows past its core size when its queue is full, the effective run concurrency is **5**, not the advertised `test-suite-run.limits.max-concurrent-runs-global` (20) — runs 6..55 sit queued as PENDING. At the same time the run's worker executors (`RunHandle`, eval-cli `EvaluationContextFactory`) hardcode `Executors.newVirtualThreadPerTaskExecutor()`, which makes CPU hotspots invisible to sampling profilers (they cannot sample virtual threads) with no way to opt out for troubleshooting.

## What Changes

- Introduce a single **thread-mode switch** for all run-related executors, reusing Spring Boot's `spring.threads.virtual.enabled` (`VIRTUAL_THREADS_ENABLED`). Default `true`; set `false` to run the job executor and every run's worker executor on platform threads for sampling/profiling.
- New `RunExecutorFactory` in `evaluation-runner-core` (`com.epam.aidial.evaluation.runner.job`), contributed as a bean by `EvaluationRunnerAutoConfiguration`: the one place that knows the thread mode and builds per-run worker executors (`Context.taskWrapping(Executors.newThreadPerTaskExecutor(threadFactory))`, virtual or daemon platform factory).
- `RunHandle` receives its executor from the factory (via `ActiveRunRegistry`) instead of building it; eval-cli's `EvaluationContextFactory` does the same.
- **BREAKING (configuration)**: `testSuiteRunExecutor` becomes a thread-per-run `SimpleAsyncTaskExecutor` (virtual or platform per the switch, no pool, no queue, no concurrency limit — admission is already gated by the run limits). The `test-suite-run.executor.core-pool-size|max-pool-size|queue-capacity` properties, their `TestSuiteRunProperties.Executor` class, `application.yml` block and `docs/configuration.md` rows are removed. Deployments that still set them start normally; the values have no effect.
- `EXECUTOR_REJECTED` handling is retained: rejection now only happens when the executor has been closed (application context shutting down).
- On application context close the job executor interrupts in-flight job threads (`cancelRemainingTasksOnClose`), preserving today's `shutdownNow()`-on-close behaviour; runs left non-terminal are reconciled at next startup as before.
- eval-cli `application.yml`: `spring.threads.virtual.enabled` becomes env-overridable (`${VIRTUAL_THREADS_ENABLED:true}`), documented in `eval-cli/README.md`.
- Docs: `docs/configuration.md` gains a `spring.threads.virtual.enabled` row (Spring section) and loses the three executor rows; AGENTS.md Debugging Tips gains the profiling switch; `docs/patterns/run-cancellation.md`, `docs/key-packages.md`, `openspec/config.yaml` Key Patterns updated.

## Capabilities

### New Capabilities
- (none)

### Modified Capabilities
- `test-suite-runs`: "Dedicated async executor" (pool) is replaced by a thread-per-run job executor whose thread mode follows `spring.threads.virtual.enabled`; "Configuration properties" drops the executor pool properties and gains the thread-mode property; "Trigger a test suite run" rejection scenario re-scoped to executor shutdown.
- `eval-execution-engine`: "In-process evaluation execution" — parallel execution runs on the run owner's thread-per-task worker executor (virtual by default, platform on opt-out), not a hardcoded virtual-thread executor.
- `observability-and-logging`: "OTel context propagation through async evaluation execution" — wording no longer assumes a thread pool or virtual threads; propagation guarantees unchanged.
- `evaluation-runner-core-module`: module inventory and autoconfiguration gain `RunExecutorFactory`.

## Impact

- **Code**: `evaluation-runner-core` (`runner/job/RunExecutorFactory`, `runner/config/EvaluationRunnerAutoConfiguration`); root (`configuration/AsyncConfiguration`, `configuration/properties/testsuite/TestSuiteRunProperties`, `service/domain/job/RunHandle`, `ActiveRunRegistry`, comment in `TestSuiteEvaluationJob`, `functional/FunctionalTests` drain helper); eval-cli (`cli/service/EvaluationContextFactory`, `application.yml`, `README.md`, `build.gradle` dependency cleanup).
- **Configuration**: removes `test-suite-run.executor.*`; relies on `spring.threads.virtual.enabled` (already `true` in the root `application.yml`). `docs/configuration.md` must be updated (rows removed + one row added).
- **Behaviour**: run concurrency is now governed solely by `test-suite-run.limits.*`; more runs can execute concurrently than before (up to the configured 20 instead of 5). No API or DB schema change; no Flyway migration.
- **Tests**: new `RunExecutorFactoryTest` (runner-core) and `AsyncConfigurationTest` (root) prove both thread modes; `RunHandleTest`, `ActiveRunRegistryTest`, `TestSuiteEvaluationJobTest`, eval-cli `EvaluationContextFactoryTest` adapted; functional test base drains via `ActiveRunRegistry` instead of pool introspection.
