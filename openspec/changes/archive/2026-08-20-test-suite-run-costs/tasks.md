## 1. dial-adas client (`client.dialadas`)

- [x] 1.1 Add `DialAdasProperties` (`@Getter @Setter @Validated @LogExecution @ConfigurationProperties(prefix = "dial.adas")`) with `baseUrl` (`@NotBlank`), `connectTimeoutMs`, `readTimeoutMs` (`@Min(0)`) — no field initializers.
- [x] 1.2 Add `application.yml` block `dial.adas.base-url` / `connect-timeout-ms` / `read-timeout-ms` with `DIAL_ADAS_URL` / `DIAL_ADAS_CONNECT_TIMEOUT_MS` / `DIAL_ADAS_READ_TIMEOUT_MS` env vars and sane local defaults, following the existing `dial.components.core.*` / `dial.mcp.*` blocks.
- [x] 1.3 Add `DialAdasClientConfiguration` (`@Configuration @LogExecution`) exposing `@Bean("dialAdasRestClient") RestClient`, built with `SimpleClientHttpRequestFactory` timeouts from `DialAdasProperties`, reusing `DialCoreClientConfiguration.authorizationTokenInterceptor()` and `DialCoreClientConfiguration.tracingInterceptor(openTelemetry)`.
- [x] 1.4 Add `DialAdasClientException` (`RuntimeException` with `int statusCode` + `message`), mirroring `McpInvocationException`'s shape.
- [x] 1.5 Add request/response DTOs: `AdasAggregateQueryDto` (`entity`, `mode`, `filter`/`select` as `tools.jackson.databind.node.ObjectNode`, `groupBy`) and `AdasAggregateResponseDto` (`rows: List<AdasAggregateRowDto>`), `AdasAggregateRowDto` (`count: Long`, `avgCost: Double`, mapped from the `avg_cost` alias).
- [x] 1.6 Add `DialAdasClient` (`@Service @LogExecution @RequiredArgsConstructor`) with `executeAggregate(AdasAggregateQueryDto query)` → `POST /v1/queries/execute`, mapping `RestClientResponseException`/`ResourceAccessException` (timeout-rooted → 504, else 502) to `DialAdasClientException`. No retry loop.
- [x] 1.7 Unit test `DialAdasClientTest`: verify request body shape sent to the stubbed `RestClient`/server, and exception mapping for timeout (504) vs. connection/response failure (502).

## 2. Run-cost query construction (`service.domain`)

- [x] 2.1 Add `RunCostQueryBuilder` (`@Component @LogExecution`) with `buildAggregateQuery(UUID runId, String phase)`, building the `and(co(json_extract_string(request_tags,"baggage"), "eval.run.id=<runId>"), co(json_extract_string(request_tags,"baggage"), "eval.phase=<phase>"))` filter and the `avg(total_price) as avg_cost` / `count()` select list, using `TracingConstants.PHASE_EXECUTION` / `PHASE_METRIC_EVALUATION` for phase literals.
- [x] 2.2 Unit test `RunCostQueryBuilderTest`: assert the exact JSON shape produced for both the execution and metric-evaluation phases.

## 3. Service and DTO

- [x] 3.1 Add `RunCostsResponseDto` (`service.domain.dto`) with `avgTestCaseCost` / `avgMetricEvalCost` (`Double`), `@Data @Builder @NoArgsConstructor @AllArgsConstructor`, `@Schema(example = ...)` on both fields.
- [x] 3.2 Add `TestSuiteRunService.getRunCosts(UUID runId)`: `@Transactional("metaTransactionManager", readOnly = true)`, `findById().orElseThrow(EntityNotFoundException)`, then two `dialAdasClient.executeAggregate(runCostQueryBuilder.buildAggregateQuery(...))` calls (execution, metric-evaluation), mapping `count == 0` (or empty `rows`) to `null` for that average.
- [x] 3.3 Unit test additions in `TestSuiteRunServiceTest` for `getRunCosts`: both phases have data; one phase has zero rows → null; unknown run id → `EntityNotFoundException`.

## 4. Controller and error handling

- [x] 4.1 Add `GET /api/v1/test-suite-runs/{id}/costs` to `TestSuiteRunController`, with `@Operation`/`@ApiResponse` (200/404/502/504) matching the existing endpoints' documentation style.
- [x] 4.2 Add `@ExceptionHandler(DialAdasClientException.class)` to `DefaultExceptionHandler`, mirroring `handleMcpInvocationException`: map to `ErrorCode.UPSTREAM_TIMEOUT` (504) or `ErrorCode.UPSTREAM_ERROR` (else).
- [x] 4.3 Functional test: add a `GET .../costs` case to the `TestSuiteRunController` functional test suite — 200 with a stubbed dial-adas response (e.g. `MockRestServiceServer` bound to the `dialAdasRestClient` bean, or WireMock), and 404 for an unknown run id.

## 5. Docs and spec index

- [x] 5.1 Update `docs/configuration.md` with a new `### 5.5 DIAL ADAS Client` section (6-column table: Property | Environment Variable | Default | Required | Applied when | Description) and ToC entry, matching the format of 5.1/5.4.
- [x] 5.2 Update `openspec/specs/README.md` per the Spec Index Maintenance Policy to list the new `test-suite-run-costs` spec folder.
- [x] 5.3 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` and fix any violations.

## 6. Verification

- [x] 6.1 Run `./gradlew test --tests "com.epam.aidial.evaluation.client.dialadas.*"`.
- [x] 6.2 Run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.TestSuiteRunServiceTest"`.
- [x] 6.3 Run the relevant functional test suite (e.g. `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestSuiteRunTests"`) to confirm the new `DialAdasClientConfiguration` bean and `TestSuiteRunService`/`RunCostQueryBuilder` wiring boot correctly end-to-end.
- [x] 6.4 Manually verify `GET /api/v1/test-suite-runs/{id}/costs` against a real run once dial-adas's run-id filtering behavior is confirmed fixed (tracked externally by the user).
