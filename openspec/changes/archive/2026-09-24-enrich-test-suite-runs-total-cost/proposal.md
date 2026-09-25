## Why

`test_suite_runs` structured-query rows already gain the run-level overall score after the meta-datasource query. Users also need the total cost across a run's execution and metric-evaluation phases, but fetching `/api/v1/costs/test-suite-runs` once per displayed row would create an N+1 dial-adas pattern. The existing batch cost query provides the required single page-bounded lookup; this change makes it available as an optional, resilient result-only enrichment.

## What Changes

- Add an opt-in `total_cost` extension-derived key to `QueryMode.ROW` results for the `test_suite_runs` query entity. It is the grouped total across all matching usage rows for each returned run; the ADAS query applies no `eval.phase` filter (so it includes the currently recorded execution and metric-evaluation rows without treating them as an exclusive whitelist).
- Reuse `AdasCostQueryBuilder.buildPageTotalCostQuery(Collection<UUID>)` through refactored shared batch lookup/parsing logic, issuing exactly one dial-adas aggregate request for eligible page ids; do not change `POST /api/v1/costs/test-suite-runs` or its nullable response contract.
- Preserve the page-extension contract: rows with absent/malformed ids or an existing `total_cost` key remain untouched; absent dial-adas groups omit the key; copied maps preserve row/key order and total count; the derived key remains result-only and absent from schema discovery.
- Add conditional configuration: `query-dsl.enrichment.test-suite-run.cost.enabled` (`QUERY_DSL_ENRICHMENT_TEST_SUITE_RUN_COST_ENABLED`, default `false`) and `query-dsl.enrichment.test-suite-run.cost.timeout-sec` (`QUERY_DSL_ENRICHMENT_TEST_SUITE_RUN_COST_TIMEOUT_SEC`, default `2`). Document both in `docs/configuration.md`.
- Run the dial-adas lookup through a dedicated, lifecycle-managed executor that follows `RunExecutorFactory`'s virtual/platform-thread switch. The request thread waits only up to the configured `Future.get` deadline; the dedicated enrichment client uses matching connect/read timeouts as a cleanup backstop. Timeout, dial-adas failure, execution failure, or request interruption preserves the page as received by this extender (including prior extender contributions) without partial `total_cost`; interruption cancels the task and restores the interrupt flag.
- Propagate the current redacting `CallerCredential` and OpenTelemetry `Context` across the executor boundary using the project’s existing APIs, so the one batch request retains credential kind/header and trace context.

Non-goals: average execution or metric-evaluation cost; per-row dial-adas calls; changing query inputs, sorting/filtering/grouping/selecting by `total_cost`, schema discovery, CLI, MCP, the existing batch endpoint, or shared dial-adas timeout behavior; database migrations.

## Capabilities

### New Capabilities

<!-- None. -->

### Modified Capabilities

- `test-suite-runs-query-entity`: add the optional result-only `total_cost` key to row-mode run-query results, including batch lookup, omission, non-overwrite, deadline/degradation, and credential/trace propagation semantics.

## Impact

- **Code (planned):** a conditional `QueryResultPageExtender` implementation beside `OverallScoreTestSuiteRunsPageExtender`; an enrichment configuration-properties class/configuration, dedicated dial-adas client and lifecycle-managed executor; focused factoring in `CostService`/the existing batch-cost path so the page extender and endpoint share request construction and response parsing. `TestSuiteRunQueryFields` gains an extension-only `total_cost` constant and the Structured Query OpenAPI result description names the additional key.
- **API:** additive response-only key on eligible `POST /api/v1/queries/execute` `test_suite_runs` rows. No request DTO, endpoint, or schema-discovery change. Existing `POST /api/v1/costs/test-suite-runs` remains unchanged and may represent a missing usage group as `null`.
- **Configuration:** defaults live in `src/main/resources/application.yml`; add six-column rows to `docs/configuration.md`. The feature is disabled by default for safe rollout.
- **Security/observability:** the async task forwards the full `CallerCredential` rather than a bare token and propagates OpenTelemetry context; credential logging remains prohibited by the redacting type. Failures log once at their owning boundary with the caught exception as the final SLF4J argument.
- **Performance/reliability:** at most one external aggregate call per eligible page; no `eval.phase` filter. The request-thread `Future.get` timeout is the end-to-end deadline, with cancellation on timeout/interruption; failures intentionally retain the page as received by the cost extender, including prior extender contributions, without partial `total_cost`.
- **Tests:** extender unit tests; shared batch lookup tests retaining existing endpoint behavior; configuration binding/conditional-bean tests; executor lifecycle/thread-mode and short-timeout-client tests; credential/OpenTelemetry propagation tests; and an enabled end-to-end structured-query functional test. No Flyway migration or `generateJooq` work.
- **Documentation:** update the existing `test-suite-runs-query-entity` delta spec, `docs/configuration.md`, OpenAPI wording, `docs/patterns/query-result-page-extension.md`, `docs/patterns/test-suite-runs-query-entity.md`, the `QueryResultPageExtender` javadoc (self-handled-failure exception for bounded async lookups), and the implementation notes of sibling baseline specs `batch-run-costs` and `query-result-page-extension`. No cost pattern doc exists; none is added. This follows the existing page-extension pattern; no new top-level package or architectural layer is planned.
