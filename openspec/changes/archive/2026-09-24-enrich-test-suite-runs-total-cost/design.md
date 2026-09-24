## Context

See [proposal.md](proposal.md) for motivation and the delta spec for observable behavior. The extension seam is already present: `JooqStructuredQueryExecutorExtender` invokes registered `QueryResultPageExtender`s after SQL and paging, containing unhandled extender failures. `OverallScoreTestSuiteRunsPageExtender` establishes row eligibility and additive map-copying behavior.

`CostService.fetchTotalCosts(Collection<UUID>)` currently owns both the one-call `AdasCostQueryBuilder.buildPageTotalCostQuery` invocation and `AdasBatchRunCostRowDto` parsing for `POST /api/v1/costs/test-suite-runs`. It uses the shared `DialAdasClient`, whose normal connection/read settings and endpoint failure behavior must remain unchanged.

`DialAdasClientConfiguration` already installs `DialCoreClientConfiguration.callerCredentialInterceptor()` and `.tracingInterceptor(OpenTelemetry)`: they respectively read `AuthorizationTokenHolder.getCredential()` and `Context.current()` when the outbound request executes. `AuthorizationTokenHolder` is a plain thread-local; `TokenPropagationHelper.withCredentialCallable` captures/restores the full redacting `CallerCredential` and clears it in `finally`. `RunExecutorFactory` exposes `isVirtualThreads()` and uses OTel context wrapping for its thread-per-task executor. Neither mechanism by itself propagates both requirements through this new submission boundary.

## Goals / Non-Goals

**Goals:**
- Attach page-bounded total run cost without altering queryability, schema discovery, or the existing batch endpoint.
- Make the request-thread deadline authoritative while preventing an abandoned dial-adas request from retaining a task beyond the deadline.
- Preserve caller credential kind/header and OpenTelemetry context through the asynchronous client call.
- Share one query-and-response-parsing implementation between the existing endpoint and the enrichment path.

**Non-Goals:**
- Change the generic page-extension coordinator or the existing overall-score extender.
- Share a timeout configuration or client instance with the existing endpoint; add retries, caching, pagination beyond the query page, or partial cost values.
- Start a transaction: the meta query has already completed, and the external lookup is a plain read.
- Change runner-core, CLI, MCP, database schema, or generated jOOQ code.

## Decisions

### D1. Conditional result-page extender, with existing applicability and merge mechanics

Add `TotalCostTestSuiteRunsPageExtender` in `query.service.repository`, enabled only by `query-dsl.enrichment.test-suite-run.cost.enabled=true`. It applies only where `query.entity()` is `test_suite_runs` and mode is `ROW`. It derives distinct parseable UUID `id`s only from rows that do not already contain `TestSuiteRunQueryFields.TOTAL_COST_FIELD`; absent/malformed ids and pre-keyed rows remain untouched.

After a lookup, it builds a new `QueryResultPage` only when a row gains a non-null value. It retains original row objects otherwise and uses `LinkedHashMap` copies for extended rows, appending `total_cost`. Thus existing keys, existing key order, row order, and `totalCount` remain intact; a missing ADAS group produces no key.

`TestSuiteRunQueryFields` gains the documented extension-only `TOTAL_COST_FIELD`; it stays absent from bindings and `TestSuiteRunsSchemaProvider`. `StructuredQueryResultDto#rows` and the execute operation description document that `total_cost`, like `overall_score_value`, is result-only.

*Alternative rejected:* put cost in `PostgresTestSuiteRunEntityResolver` or expose it in schema discovery. It comes from dial-adas after the meta query, so either option would advertise a field that cannot legally participate in DSL translation and would violate the open-key-set contract.

### D2. Extract the existing batch query execution and parsing into one reusable component

Add injectable `service.domain.BatchRunTotalCostLookup` with the exact public operation `Map<UUID, Double> fetchTotalCosts(Collection<UUID> runIds, DialAdasClient dialAdasClient)`. It invokes `AdasCostQueryBuilder.buildPageTotalCostQuery(runIds)` once and owns all response handling now in `CostService.fetchTotalCosts`: null response/rows yield an empty map; `AdasBatchRunCostRowDto.runId` is parsed as UUID; invalid values, including the builder's `other` bucket, are ignored; a null total does not become a row key.

`CostService.getTotalRunCosts` calls `fetchTotalCosts(runIds, dialAdasClient)` with the normal shared client, preserving endpoint validation, request ordering, nullable DTO values, and normal timeout/error propagation exactly. The extender calls the same operation with the named short-timeout enrichment client. The component does not catch `DialAdasClientException`; endpoint behavior remains a 502/504, whereas the extender preserves the page it received without a total-cost contribution.

*Alternative rejected:* duplicate the five-line builder/client/parser flow in the extender. It would create two implementations of `other`-bucket filtering and missing-row semantics, which are easy to drift despite producing the same result today. A second public endpoint-specific `CostService` API is also unnecessary: the reusable operation is an internal domain lookup, not a REST contract.

### D3. Dedicated conditional client, without changing shared ADAS behavior

Keep the existing scanned `DialAdasClient` as the normal client: annotate that existing service `@Primary` while retaining its `@Qualifier("dialAdasRestClient")` field, so every existing unqualified injection continues to receive its normal-timeout RestClient. Under the enabled condition, add a second named bean `testSuiteRunCostEnrichmentDialAdasClient`, constructed explicitly with a second `RestClient` bean named `testSuiteRunCostEnrichmentDialAdasRestClient`; that client reuses `DialAdasProperties.baseUrl` and the same credential/tracing interceptors but sets both connect and read timeouts to `Duration.ofSeconds(timeoutSec)`. Inject this second client only into `TotalCostTestSuiteRunsPageExtender` using `@Qualifier("testSuiteRunCostEnrichmentDialAdasClient")`.

The normal scanned service remains `@Primary`; the conditional bean is qualified and is never eligible for existing unqualified consumers. The dedicated client is a cleanup backstop, not the caller deadline.

*Alternative rejected:* alter `dial.adas.connect-timeout-ms` / `read-timeout-ms` globally. That would silently change the documented existing endpoint and every other ADAS caller instead of bounding only optional enrichment.

### D4. A named, lifecycle-managed executor follows the run thread-mode switch

Add a conditional `SimpleAsyncTaskExecutor` bean named for cost enrichment (prefix `test-suite-run-cost-enrichment-`). Configure `setVirtualThreads(runExecutorFactory.isVirtualThreads())`, daemon platform threads, and `setCancelRemainingTasksOnClose(true)`, matching the lifecycle guarantees of the run executor but with an enrichment-specific name. Use it through `AsyncTaskExecutor.submit(Callable)` so the request thread can wait on a `Future`; close it on application shutdown, cancelling any remaining lookup tasks.

Before submission, capture `CallerCredential credential = AuthorizationTokenHolder.getCredential()` and `Context context = Context.current()`. Submit `context.wrap(TokenPropagationHelper.withCredentialCallable(credential, lookup))`: `TokenPropagationHelper` installs/clears the full credential on the worker thread, and `Context.wrap` makes the captured OTel context current for the call. The existing RestClient interceptors then inject exactly those current values into the ADAS request. This is explicit rather than relying on accidental inheritance from virtual threads or a generic task decorator.

*Alternative rejected:* use a common application executor. It lacks a feature-specific name/lifecycle, could be saturated by unrelated work, and makes shutdown cancellation/timeout tests ambiguous. Use of the executor alone is also insufficient because credentials use a non-inheritable thread-local.

### D5. `Future.get` is the end-to-end deadline and failures degrade once

The extender waits with `future.get(timeoutSec, TimeUnit.SECONDS)`. On `TimeoutException`, it calls `future.cancel(true)`, logs once with the timeout exception as the final SLF4J argument, and returns the page it received. On `InterruptedException`, it cancels, restores `Thread.currentThread().interrupt()`, logs once with the caught exception last, and returns that received page. On `ExecutionException` or rejected submission it logs once with the caught exception last and returns that received page. In every branch the page retains earlier extender contributions and gains no partial `total_cost`.

The extender handles these expected asynchronous outcomes itself so `JooqStructuredQueryExecutorExtender` receives a normal page and does not emit a duplicate failure log. Unexpected runtime errors remain subject to the existing coordinator's per-extender failure isolation. No partial merge happens before `Future.get` returns successfully.

The dedicated RestClient's matching connect/read timeout and task cancellation are best-effort cleanup backstops; they do not extend or redefine the request-thread deadline, which remains authoritative even if underlying I/O ignores interruption or the individual timeout windows do not bound the full request.

### D6. Properties and wiring

Add a validated `@ConfigurationProperties` type under the existing configuration-properties hierarchy with prefix `query-dsl.enrichment.test-suite-run.cost`:

- `enabled`: non-null Boolean; YAML default `${QUERY_DSL_ENRICHMENT_TEST_SUITE_RUN_COST_ENABLED:false}`.
- `timeoutSec`: non-null integer with `@Min(1)`; YAML default `${QUERY_DSL_ENRICHMENT_TEST_SUITE_RUN_COST_TIMEOUT_SEC:2}`.

Create the extender, dedicated client, and executor only under the enabled property condition. Bind/validation tests cover valid defaults and invalid timeout values; conditional-context tests prove no cost-enrichment beans by default and their presence when enabled. Add two six-column `docs/configuration.md` rows, specifying default, environment variable, optional status, application condition, and that timeout applies to the lookup plus client cleanup backstop.

### D7. Extender ordering, component flow and transactions

`JooqStructuredQueryExecutorExtender` applies its registered extender list in bean order; this change does not establish an ordering relationship with `OverallScoreTestSuiteRunsPageExtender`. The two keys are independent and each extender's failure fallback preserves whatever page it received, so the guarantees hold in either order and later extenders remain applicable.

```
StructuredQueryService
  -> JooqStructuredQueryExecutorExtender
  -> JooqStructuredQueryExecutor (meta query completes)
  -> registered QueryResultPageExtenders (bean order)
       -> TotalCostTestSuiteRunsPageExtender (when enabled)
            capture CallerCredential + OTel Context
            -> enrichment executor / Future.get deadline
               -> BatchRunTotalCostLookup(dedicated DialAdasClient, distinct page ids)
                  -> one AdasCostQueryBuilder.buildPageTotalCostQuery(ids)
                  -> one dial-adas aggregate call
            -> additive total_cost map merge
  -> StructuredQueryController
```

All work after the base page is read-only and external. No transaction is opened or propagated across the meta database and dial-adas boundary.

## Risks / Trade-offs

- **[Risk] A slow external call adds latency to a query page.** → Feature is disabled by default; one page-wide request has a two-second default authoritative deadline and failure preserves the page already enriched by earlier extenders.
- **[Risk] Cancellation may not instantly abort a blocking HTTP implementation.** → `Future.cancel(true)`, executor close cancellation, and matched dedicated-client connect/read timeouts bound cleanup without extending the response deadline.
- **[Risk] Credential leaks between reused platform threads.** → `TokenPropagationHelper` clears `AuthorizationTokenHolder` in `finally`; tests run sequential calls with/without credentials to prove cleanup.
- **[Risk] Two `DialAdasClient` beans could redirect existing consumers to the short timeout.** → Preserve/declare the established client primary and require the enrichment qualifier at the new call site; context tests exercise both wiring paths.
- **[Risk] Page map mutation or overwrite could break client aliases.** → Reuse the existing overall-score extender's copy-on-add and `containsKey` pattern; unit tests assert identity/value/order/count behavior.

## Migration Plan

No migration or data backfill. Deploy with `enabled=false`; enabling the configuration property makes the additive key available after restart. Rollback is setting it false (or reverting the conditional beans); already produced base query results and the existing batch endpoint are unaffected.
