## 1. Module Scaffolding

- [x] 1.1 Add `include 'eval-cli'` to `settings.gradle`
- [x] 1.2 Create `eval-cli/build.gradle` modeled on `evaluation-runner-core/build.gradle` (Java 25 toolchain, checkstyle, spotless/palantir formatting, `-Werror`, the `commons-logging`/`spring-boot-starter-logging` exclusions), applying the `org.springframework.boot` plugin for real (not `apply false`), with `implementation project(':evaluation-runner-core')`, `info.picocli:picocli` + `info.picocli:picocli-spring-boot-starter`, `org.apache.commons:commons-csv`, and test deps (`spring-boot-starter-test`, `archunit-junit5`); configure `bootJar { mainClass.set('com.epam.aidial.evaluation.cli.EvalCliApplication') }`
- [x] 1.3 Create package skeleton under `eval-cli/src/main/java/com/epam/aidial/evaluation/cli/` (`config.properties`, `client.source`, `client.target`, `command`, `service`, `csv`, `model`, `exception`)
- [x] 1.4 Write `CliModuleConstraintsTest` (ArchUnit), modeled on `RunnerModuleConstraintsTest`: no JDBC/jOOQ/Flyway dependency; every Spring component annotated with `@LogExecution`; nothing under `com.epam.aidial.evaluation.cli..` depends on `com.epam.aidial.evaluation..` outside `com.epam.aidial.evaluation.runner..`/`com.epam.aidial.evaluation.cli..` (rules use `allowEmptyShould(true)` until later tasks add real classes)
- [x] 1.5 Verify the module builds and its test suite runs: `./gradlew :eval-cli:build`

## 2. Configuration

- [x] 2.1 Implement `SourceProperties` (`cli.source.*`: baseUrl, token, connect/read timeouts) with `@Validated`/`@NotBlank` — no field-initializer defaults
- [x] 2.2 Implement `EvalCliProperties` (`cli.*`: suitePrefix, suites[] as UUIDs, workDir, testRunName, nested `run.*` execution-tuning block: concurrencyLevel, rateLimitRps, requestTimeoutMs, maxRetries, retryDelayMs, retryBackoffMultiplier, maxRetryDelayMs, resultBatchSize, maxResponseSizeBytes, cancellationGracePeriodMs)
- [x] 2.3 Implement CLI-target token property (`cli.target.token`) alongside the existing `dial.components.core.*` binding for the target DIAL Core host
- [x] 2.4 Write `eval-cli/src/main/resources/application.yml` with all defaults for the above (per AGENTS.md: defaults live in YAML, not Java field initializers)
- [x] 2.5 Implement `ClockConfiguration` (`@Bean Clock clock() = Clock.systemUTC()`) — this module needs its own, distinct from the EF backend's
- [x] 2.6 Unit test: properties bind correctly from `application.yml` and validation fails for missing required fields

## 3. Source EF HTTP Client

- [x] 3.1 Implement local DTOs under `client/source/dto` (`TestSuiteResponseDto`, `TestSuiteCloneRequestDto`, `TestCaseResponseDto`, `TestSuiteUpdateResultDto`, `PageResponseDto<T>`, `TestSuiteRunResponseDto`), reusing `evaluation-runner-core`'s `runner.dto`/`runner.model` types for nested fields (deploymentRef, requestTemplate, inputBindings, responseColumns, suiteType); add a class-level comment on each noting it is manually kept in sync with the EF backend's `service.domain.dto` contract
- [x] 3.2 Implement `SourceClientConfiguration` (`@Bean("sourceRestClient") RestClient`) with a static-bearer-token request interceptor reading `SourceProperties.token`
- [x] 3.3 Implement `TestSuiteApiClient`: get-by-id, find-by-exact-name (used only by the clone-existence check), clone
- [x] 3.4 Implement `TestCaseApiClient`: paginated fetch that materializes the full test-case list for a dataset
- [x] 3.5 Implement `TestSuiteRunImportApiClient`: multipart `POST /api/v1/test-suites/{id}/runs/import` (file, optional testRunName, delimiter)
- [x] 3.6 Unit tests for all three clients using `MockRestServiceServer` against `SourceClientConfiguration`'s `RestClient`, covering success and non-2xx responses

## 4. Target DIAL Core Wiring

- [x] 4.1 Implement `CliTokenProvider` interface (`String currentToken()`) and `StaticCliTokenProvider` (reads `cli.target.token`); mark both interceptor wiring points with `// TODO(auth): replace with OIDC client-credentials once available`
- [x] 4.2 Implement `TargetDialCoreClientConfiguration` supplying the `@Bean("dialCoreTryOutRestClient") RestClient` that `DialCoreDeploymentInvoker` (in `evaluation-runner-core`) requires, mirroring the EF backend's `DialCoreDeploymentInvokerConfiguration` construction (`JdkClientHttpRequestFactory`, `DialCoreProperties`-driven baseUrl/timeouts, static-bearer + tracing interceptors)
- [x] 4.3 Confirm via a Spring context startup test that `EvaluationRunnerAutoConfiguration`'s beans (including `DialCoreDeploymentInvoker`) resolve correctly with this module's `dialCoreTryOutRestClient` bean present

## 5. CSV Result Writer

- [x] 5.1 Implement `CsvResultBatchWriter` (implements `evaluation-runner-core`'s `ResultBatchWriter`), writing the exact reserved import columns (`testCaseId, testCaseName, runIndex, requestBody, responseBody, responseStatusCode, executionStatus, startedAt, completedAt, traceId, retryCount, logDetails`) via Apache Commons CSV `CSVPrinter`, with `addResults` synchronized for thread-safe concurrent writes
- [x] 5.2 Unit test asserting exact header/column order and per-field value formatting (timestamp fields, enum name, JSON-serialized bodies) against `TestCaseRunResult` fixtures
- [x] 5.3 Concurrency test: invoke `addResults` from multiple threads simultaneously and assert the resulting CSV has no interleaved/corrupted rows

## 6. Orchestration Services

- [x] 6.1 Implement `TestCaseRunInputMapper` (MapStruct): `TestCaseResponseDto` → `TestCaseRunInput`
- [x] 6.2 Implement `EvaluationContextFactory`: builds `EvaluationContext` from a fetched `TestSuiteResponseDto` + CLI config, applying the target deployment-ref override in place of the suite's recorded (source-side) deployment reference; sources `token` from `CliTokenProvider` and timestamps from the injected `Clock`
- [x] 6.3 Implement `CloneService`: for each configured source suite ID, resolve its name, check whether `<prefix>_<name>` already exists on the source (`TestSuiteApiClient.findByExactName`); reuse it if found, otherwise call `TestSuiteApiClient.clone`; return `sourceId -> destinationCloneId`
- [x] 6.4 Implement `FetchService`: GET suite config + all test cases from the source; persist as a JSON bundle under `cli.workDir` so `fetch`/`run` can be invoked standalone
- [x] 6.5 Implement `RunOrchestrationService`: build `EvaluationContext` via `EvaluationContextFactory`, map fetched test cases via `TestCaseRunInputMapper`, obtain a `TestCaseRunner` via `TestCaseRunnerFactory.create(context, responseColumns, csvWriter)`, call `submit(inputs)` then `awaitCompletion()`, flush the CSV writer
- [x] 6.6 Implement `ImportService`: wraps `TestSuiteRunImportApiClient`, importing a results CSV into the resolved destination clone ID
- [x] 6.7 Unit tests for `CloneService` (both branches: create vs reuse), `FetchService` (pagination + bundle persistence), `EvaluationContextFactory` (deployment-ref override applied), `RunOrchestrationService` (delegates correctly to `TestCaseRunner`, does not reimplement concurrency/retry logic)

## 7. CLI Commands

- [x] 7.1 Implement `RootCommand` (picocli root `@Command`, `subcommands = {Clone, Fetch, Run, Import, Pipeline}`)
- [x] 7.2 Implement `CloneCommand`, `FetchCommand`, `RunCommand`, `ImportCommand` — each a thin picocli wrapper delegating to its corresponding service
- [x] 7.3 Implement `PipelineCommand`: for each configured suite, run clone → fetch → run → import in sequence, tracking `cloneId` explicitly as the import target regardless of whether it was newly created or reused
- [x] 7.4 Implement `EvalCliApplication` (`@SpringBootApplication`), bootstrapping via `picocli-spring-boot-starter`'s documented `CommandLine`/`IFactory` idiom, exiting with the command's return code
- [x] 7.5 Manual smoke test: `java -jar eval-cli.jar --help` lists all five commands with usage text

## 8. Documentation

- [x] 8.1 Add `eval-cli/README.md` with a configuration table (property, env var, default, description) for all `cli.*` and target DIAL Core properties
- [x] 8.2 Update `openspec/specs/README.md` per the Spec Index Maintenance Policy (done: new `eval-cli` spec folder listed)
- [x] 8.3 Update `AGENTS.md` per AGENTS.md Maintenance guidelines (done: new `eval-cli` subproject documented — build layout table, module description alongside `evaluation-runner-core`, and its package reference table)

## 9. Verification

- [x] 9.1 Run `./gradlew :eval-cli:test` and confirm `CliModuleConstraintsTest` passes
- [x] 9.2 Run `./gradlew :eval-cli:checkstyleMain :eval-cli:checkstyleTest` and `./gradlew spotlessApply`
- [ ] 9.3 End-to-end manual check: run `pipeline` against a local EF instance (`config.rest.security.mode=none`) acting as source and a stub/local deployment acting as target; confirm the imported run shows `SUCCESS` rows and that metric computation is triggered automatically after import (poll `GET /api/v1/test-suite-runs/{id}`)
- [ ] 9.4 Re-run `pipeline` a second time against the same configured suite and confirm the existing `<prefix>_<name>` clone is reused (no duplicate suite created) while a new import/run still occurs
