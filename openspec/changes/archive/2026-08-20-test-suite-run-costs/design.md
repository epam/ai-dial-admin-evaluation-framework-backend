## Context

Run cost data lives only in dial-adas, an external analytics service that stores DIAL Core usage-log records (`dial_usage_log`) and exposes them through a JSON query DSL over `POST {dial-adas-base-url}/v1/queries/execute`. Each usage-log row carries `request_tags.baggage`, a comma-joined string of the OTel baggage already attached to outgoing calls by `EvalBaggage`/`TracingConstants` (`evaluation-runner-core/.../runner/util/EvalBaggage.java`), e.g.:

```
eval.phase=execution,eval.run.id=<runId>,eval.suite.id=<suiteId>,run.index=0,testcase.id=<testCaseId>
```

`eval.run.id` is the run's own `TestSuiteRun.id` — there is no separate correlation-id column (confirmed: this same tag/value is already used by `GrafanaLinkBuilder` for Grafana Explore deep links). `eval.phase` is either `execution` (test-case/deployment calls) or `metric-evaluation` (judge-model calls), per `TracingConstants.PHASE_EXECUTION` / `PHASE_METRIC_EVALUATION`.

The user confirmed dial-adas supports a server-side `"mode": "aggregate"` query that returns an `avg(...)` in one round trip:

```json
{
  "entity": "dial_usage_log",
  "mode": "aggregate",
  "filter": { "op": "and", "args": [ /* conditions */ ] },
  "group_by": [],
  "select": [
    { "expr": { "type": "fn", "name": "count", "args": [] } },
    { "expr": { "type": "fn", "name": "avg", "args": [ { "type": "field", "name": "total_price" } ] }, "as": "avg_cost" }
  ]
}
```
returning `{ "rows": [ { "count": N, "avg_cost": <double> } ] }`.

Filtering by run id uses a `co` (contains) op over `json_extract_string(request_tags, "baggage")` against the literal `"eval.run.id=<runId>"` substring (the same approach the user demonstrated working for row-mode queries). The user has flagged that dial-adas currently has an open issue with this run-id filter and will validate end-to-end once it's fixed there; this design targets the intended/contracted behavior and adds no speculative workaround.

The existing `client.dialcore` package (`DialCoreClient`, `DialCoreClientConfiguration`, `DialCoreProperties`) and `client.metricprovider` package are the direct precedents for adding a new outbound HTTP client in this codebase, so the new `client.dialadas` package mirrors their shape rather than introducing a new pattern.

## Goals / Non-Goals

**Goals:**
- Expose `GET /api/v1/test-suite-runs/{id}/costs` returning `avgTestCaseCost` and `avgMetricEvalCost` for a run.
- Add a `client.dialadas` package that talks to dial-adas's query DSL, following the established `RestClient`-based client conventions (typed bean, `@ConfigurationProperties`, auth propagation, tracing interceptor, custom exception mapped by `DefaultExceptionHandler`).
- Keep the dial-adas query-JSON construction isolated in one small, independently testable component, not inlined in the service.
- Return `null` (not `0`) for a phase with zero matching usage-log rows.

**Non-Goals:**
- No per-test-case or per-request cost breakdown (only the two run-level averages, per product decision).
- No DB persistence of cost data — always a live read-through to dial-adas; no caching layer in this change.
- No pagination/row-mode fetching — the aggregate query returns exactly one row per call, so no `page`/cursor handling is needed.
- No workaround for the dial-adas run-id filtering issue — this change assumes it is fixed; if it isn't, the endpoint will surface as an `UPSTREAM_ERROR`/wrong-average bug to be triaged separately, not silently masked here.
- No changes to any existing capability's requirements (additive only).

## Decisions

### 1. New package `com.epam.aidial.evaluation.client.dialadas`, mirroring `client.dialcore`

- `DialAdasProperties` — `@Getter @Setter @Validated @LogExecution @ConfigurationProperties(prefix = "dial.adas")`: `baseUrl` (`@NotBlank`), `connectTimeoutMs`, `readTimeoutMs` (`@Min(0)`). Defaults only in `application.yml` (`dial.adas.base-url`, etc.), per the project's `@ConfigurationProperties` convention — no Java field initializers. Lives in `com.epam.aidial.evaluation.configuration.properties.dialadas`, not `client.dialadas`: `Application.java` only registers `@ConfigurationProperties` beans under `@ConfigurationPropertiesScan(basePackages = "com.epam.aidial.evaluation.configuration.properties")` (same reason `MetricProviderProperties`/`GrafanaProperties` live under `configuration.properties.*` rather than beside their client classes) — placing it in `client.dialadas` produced an unsatisfied-dependency error at context startup.
- `DialAdasClientConfiguration` — `@Configuration @LogExecution`, builds `@Bean("dialAdasRestClient") RestClient` using `SimpleClientHttpRequestFactory` (connect/read timeout from `DialAdasProperties`) plus `.requestInterceptor(DialCoreClientConfiguration.authorizationTokenInterceptor())` and `.requestInterceptor(DialCoreClientConfiguration.tracingInterceptor(openTelemetry))`, **reusing** those two static helpers directly rather than duplicating them, since the auth-propagation and W3C-trace-injection behavior needs to be identical to `DialCoreClient`'s.
- `DialAdasClient` — `@Service @LogExecution @RequiredArgsConstructor`, one method `executeAggregate(AdasAggregateQueryDto query)` → `POST /v1/queries/execute` via `.retrieve().body(AdasAggregateResponseDto.class)`. No retry loop (unlike `DialCoreClient`): this is a single user-facing read, not a background/critical-path call, so a failed attempt should surface immediately as a 502/504 rather than add latency via retries. `RestClientResponseException`/`ResourceAccessException` map to `DialAdasClientException` (status + message), mirroring `DialCoreClient`'s exception-mapping tail (`SocketTimeoutException`-rooted → 504, else 502).
- `DialAdasClientException` — plain `RuntimeException` with `int statusCode` + `message`, mirroring `McpInvocationException`'s shape (simplest to map in `DefaultExceptionHandler`, which already has a template for this exact pattern).
- DTOs: `AdasAggregateQueryDto` (`entity`, `mode`, `filter`, `groupBy`, `select` — `filter`/`select` are heterogeneous op/fn/field/value trees, modeled as `tools.jackson.databind.node.ObjectNode` rather than a full typed AST, since this is one fixed, code-controlled query shape used from a single call site — a full AST would be premature abstraction) and `AdasAggregateResponseDto` (`rows: List<AdasAggregateRowDto>` with a typed `AdasAggregateRowDto { count: Long, avgCost: Double }` since we control the `select` aliases we emit).

**Alternative considered**: extend `DialCoreClient` itself with a `dialadas`-flavored method. Rejected — dial-adas is a distinct external service with its own base URL/timeouts, and `client.dialcore` is scoped to actual DIAL Core APIs (models/applications/deployments/toolsets); mixing concerns there would blur the client boundary the codebase already keeps clean.

### 2. Query construction isolated in `RunCostQueryBuilder` (`service.domain` package)

A small `@Component @LogExecution` class, `RunCostQueryBuilder.buildAggregateQuery(UUID runId, String phase)`, returns the `AdasAggregateQueryDto` for one phase. This keeps the JSON-shape logic (the `and(co(...), co(...))` filter tree and the `avg(total_price) as avg_cost` / `count()` select list) out of `TestSuiteRunService`, independently unit-testable, and reusable if a second run-cost-shaped query is ever needed. Phase literals come from `TracingConstants.PHASE_EXECUTION` / `PHASE_METRIC_EVALUATION` (already defined in `evaluation-runner-core`) rather than new string constants, keeping the two "phase" vocabularies (baggage tag values vs. query filter values) provably in sync.

**Alternative considered**: inline query-building in `TestSuiteRunService`. Rejected per AGENTS.md's "use specialized, injectable components for conversion/validation logic instead of private/inner methods" — also makes the exact filter/select JSON independently testable without mocking the HTTP client.

### 3. Service method `TestSuiteRunService.getRunCosts(UUID runId)`

- The transaction is scoped to only `testSuiteRunRepository.findById(runId).orElseThrow(() -> new EntityNotFoundException(...))`, isolated in a private `ensureRunExists(UUID runId)` helper that wraps just that check in a `TransactionTemplate(metaTransactionManager)` (`readOnly = true`), matching the `TransactionTemplate`-scoped-lookup pattern already used by `TestSuiteService.delete()`/`detachFromDataset()`. A method-level `@Transactional` was deliberately avoided here — it would otherwise hold the meta-DB connection open across the two `dialAdasClient.executeAggregate(...)` HTTP calls below, and this endpoint is expected to be polled (see Risks). No other run field is needed (no time-range guard is used — see Risks).
- Calls `dialAdasClient.executeAggregate(runCostQueryBuilder.buildAggregateQuery(runId, PHASE_EXECUTION))` then again with `PHASE_METRIC_EVALUATION` — two sequential HTTP calls (not parallelized in this change; both are fast aggregate queries against a single indexed-ish filter, and adding concurrency here would be premature given no evidence of latency issues).
- Maps each response's single row (`rows.isEmpty()` or `count == 0` → `null` for that average) to `RunCostsResponseDto`.

### 4. New DTO `RunCostsResponseDto` (`service.domain.dto` package, alongside `TestSuiteRunRequestDto`/`TestSuiteRunUpdateDto`)

`@Data @Builder @NoArgsConstructor @AllArgsConstructor` with `@Schema(example = ...)` on both `Double` fields (`avgTestCaseCost`, `avgMetricEvalCost`), following the existing response DTO convention (e.g. `ExecutionInfoResponseDto`).

### 5. Controller: new method on existing `TestSuiteRunController`

`GET /api/v1/test-suite-runs/{id}/costs`, same `@PathVariable UUID id` / `@Operation`/`@ApiResponse` shape as `getRun`/`cancelRun`, documenting 200/404/502/504.

### 6. Exception handling: extend `DefaultExceptionHandler`

Add `@ExceptionHandler(DialAdasClientException.class)` mirroring `handleMcpInvocationException` verbatim in structure: resolve `HttpStatus` from the exception's status code (default `BAD_GATEWAY` if unresolved), map to `ErrorCode.UPSTREAM_TIMEOUT` (504) or `ErrorCode.UPSTREAM_ERROR` (else) — both codes already exist and already describe "upstream service error/timeout" generically, so no new `ErrorCode` enum value is introduced.

### 7. Configuration

`application.yml`, new block alongside the existing `dial:` tree:
```yaml
dial:
  adas:
    base-url: ${DIAL_ADAS_URL:http://localhost:8087}
    connect-timeout-ms: ${DIAL_ADAS_CONNECT_TIMEOUT_MS:5000}
    read-timeout-ms: ${DIAL_ADAS_READ_TIMEOUT_MS:30000}
```
`docs/configuration.md` gets a new `### 5.5 DIAL ADAS Client` section (6-column table, same format as 5.1/5.4) plus a ToC entry.

## Risks / Trade-offs

- **[Risk] dial-adas's run-id filter is currently unreliable** (per the user) → **Mitigation**: none added speculatively in code; this is tracked as an external dependency the user will validate once fixed. If it surfaces again post-fix, the fix belongs in the filter construction inside `RunCostQueryBuilder`, which is isolated and unit-testable in isolation from the rest of the change.
- **[Risk] No time-range guard on the query** — if `eval.run.id` substring matching ever has a collision or false-positive edge case (e.g., truncated storage of the baggage string), results could be wrong without an independent signal (e.g., request_time bounds) to cross check → **Mitigation**: deliberately deferred (see Non-Goals) since the user asked us not to guess a workaround; can be added later as an additional `and` condition using the run's `startedAt`/`completedAt` (mirroring `GrafanaLinkBuilder`'s `TIME_BUFFER_MS` pattern) if the substring-only filter proves insufficient.
- **[Risk] New external dependency** (dial-adas reachability) on an endpoint users will poll → **Mitigation**: no retry loop (fails fast), clear 502/504 error codes, and this endpoint's failure is isolated — it cannot affect other test-suite-run endpoints since it makes no writes and touches no other run state.
- **[Trade-off] Two sequential HTTP calls per request** (one per phase) instead of one combined query → simpler `RunCostQueryBuilder`/`DialAdasClient` contract (one query = one phase = one row), at the cost of ~2x latency versus a single combined aggregate-with-`group_by`-on-phase query. Accepted for now; revisit only if latency becomes a measured problem, since a `group_by` variant would first need confirmation that dial-adas's DSL supports grouping by a `json_extract_string` expression (unconfirmed, unlike the plain `and`/`co` filter shape the user already validated).

## Migration Plan

- No DB schema changes, no data migration. Purely additive: new client package, new service method, new controller endpoint, new config properties, new exception mapping.
- Deploy: requires `DIAL_ADAS_URL` (and optionally timeout overrides) to be set in each environment; defaults to `http://localhost:8087` for local dev, matching the pattern of `DIAL_CORE_URL`/`METRIC_PROVIDERS_DIAL_BASE_URL`.
- Rollback: revert the change — no persisted state to unwind.

## Open Questions

- Exact final base path/port dial-adas will be reachable at in each deployed environment (dev/staging/prod) — to be filled into environment-specific config, not blocking this change (default + env var override is enough for now).
- ~~Whether dial-adas's run-id filtering issue is fixed before or after this change ships~~ — resolved: verified working end-to-end against a real deployment.
