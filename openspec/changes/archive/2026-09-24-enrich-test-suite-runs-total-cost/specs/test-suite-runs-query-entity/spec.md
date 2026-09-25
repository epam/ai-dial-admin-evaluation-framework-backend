## ADDED Requirements

### Requirement: Run-level total cost on a row result page
When `query-dsl.enrichment.test-suite-run.cost.enabled` is enabled, a `row`-mode `test_suite_runs` result page SHALL carry the extension-derived key `total_cost` for each eligible run with matching dial-adas usage data. The value SHALL be the total `total_price` for that run across all matching usage rows. The lookup SHALL apply no `eval.phase` filter; therefore it includes the currently recorded `execution` and `metric-evaluation` usage rows without treating those values as an exclusive whitelist.

The key SHALL be result-only: it SHALL NOT be accepted in `filter`, `select`, `sort` or `group_by`, and SHALL NOT appear in the published entity schema. It SHALL be omitted, rather than set to null, when no usage group exists or the group's total is null. Existing row keys SHALL win over the derived key; row and key order and the page total count SHALL remain unchanged.

The property `query-dsl.enrichment.test-suite-run.cost.enabled` SHALL use environment variable `QUERY_DSL_ENRICHMENT_TEST_SUITE_RUN_COST_ENABLED` and default to `false`. The property `query-dsl.enrichment.test-suite-run.cost.timeout-sec` SHALL use environment variable `QUERY_DSL_ENRICHMENT_TEST_SUITE_RUN_COST_TIMEOUT_SEC`, default to `2`, and reject values below `1`. The request thread's timed `Future.get` SHALL be the authoritative end-to-end enrichment wait deadline. The dedicated dial-adas client used only for this enrichment SHALL set both its connection and read timeout to the same configured value as a cleanup backstop; it SHALL NOT change the normal client or the existing batch endpoint's timeout behavior.

The dedicated enrichment executor SHALL follow `spring.threads.virtual.enabled`: virtual threads when enabled and daemon platform threads when disabled. It SHALL cancel remaining work when it closes.

Status: **Implemented**

#### Scenario: Enabled row query returns total cost
- **WHEN** cost enrichment is enabled and a row-mode `test_suite_runs` query returns a run with matching dial-adas usage rows
- **THEN** that row carries `total_cost` equal to the sum of `total_price` across all matching usage rows

#### Scenario: Disabled enrichment adds no derived key
- **WHEN** `query-dsl.enrichment.test-suite-run.cost.enabled` is false or absent
- **THEN** a row-mode `test_suite_runs` query performs no dial-adas cost lookup and adds no `total_cost` extension-derived key, while preserving any `total_cost` key already projected by the query

#### Scenario: Missing usage omits the key
- **WHEN** an eligible returned run has no matching dial-adas usage group, or its group's total is null
- **THEN** its row carries no `total_cost` key rather than `total_cost: null`

#### Scenario: Existing key is not overwritten
- **WHEN** a returned row already contains `total_cost`, for example through a client-supplied select alias
- **THEN** the row retains its existing value and the enrichment does not overwrite it

#### Scenario: Non-target or non-eligible query does no lookup
- **WHEN** a query targets another entity, a row-mode `test_suite_runs` query returns an empty page or omits `id`, every returned id is absent or non-UUID, every row already contains `total_cost`, or the query runs in aggregate mode
- **THEN** the extender performs zero dial-adas requests and returns the page it received unchanged

#### Scenario: Lookup remains page-bounded
- **WHEN** an enabled row-mode query returns N eligible test suite runs in one page
- **THEN** the system performs exactly one dial-adas aggregate request for that page's distinct run ids, with no per-row cost requests

#### Scenario: Cost query has no phase filter
- **WHEN** the system looks up total cost for an enabled result page
- **THEN** its dial-adas aggregate request applies no `eval.phase` filter and groups matching usage rows by run id

#### Scenario: Timeout configuration bounds enrichment
- **WHEN** enrichment is enabled without an explicit timeout override
- **THEN** the request thread waits at most 2 seconds for the enrichment future and the dedicated dial-adas client's connection and read timeouts are each 2 seconds

#### Scenario: Executor follows application thread mode
- **WHEN** `spring.threads.virtual.enabled` is true or false while enrichment is enabled
- **THEN** enrichment work runs respectively on virtual threads or daemon platform threads, and container shutdown cancels remaining enrichment work

#### Scenario: Enrichment failure preserves preceding contributions
- **WHEN** the page cost lookup times out, dial-adas fails, its asynchronous execution fails, or the executor rejects its submission
- **THEN** the response is HTTP 200 with the page exactly as received by this extender, including contributions from any extender that ran before it (e.g. `overall_score_value` when that extender ran first; extender order is not guaranteed), and without a partial `total_cost` contribution

#### Scenario: Request interruption preserves caller interruption
- **WHEN** the request thread is interrupted while waiting for the page cost lookup
- **THEN** the pending lookup is cancelled, the interrupt status is restored, and the response retains the page as received by this extender without a partial `total_cost` contribution

#### Scenario: Later extensions remain applicable after total-cost failure
- **WHEN** total-cost enrichment fails and another registered page extender follows it
- **THEN** the later extender remains eligible to contribute to the returned page

#### Scenario: Async lookup preserves caller credential and trace context
- **WHEN** an enabled page cost lookup runs asynchronously for an authenticated caller
- **THEN** its dial-adas request carries the caller's original credential kind and header value and remains in the caller's OpenTelemetry context
