# Test Suite Runs Query Entity

## Purpose

Exposes test suite runs as a `test_suite_runs` entity of the structured query DSL so a client can list, filter, sort and aggregate runs together with the deployment they executed against and the names of the metrics their latest computation produced, in one request and without a bespoke REST listing.

Status: **Implemented**

## Requirements

### Requirement: `test_suite_runs` is a simple queryable entity on the meta datasource
The system SHALL accept `test_suite_runs` as the `entity` of a structured query at `POST /api/v1/queries/execute`, in both `row` and `aggregate` modes, and SHALL list it in the entity catalog as a simple entity (`complex=false`, null `schemaIdField`). All of its **queryable** fields — those accepted in `filter`, `select`, `sort` and `group_by` and published in the entity's schema — SHALL be resolved from the meta database in a single statement; no analytics datasource SHALL be involved in resolving them. A `row` result page MAY additionally carry extension-derived keys merged after that statement (see `query-result-page-extension`); such keys are not queryable fields.
Status: **Implemented**

#### Scenario: Row-mode query lists runs
- **WHEN** a client posts `{"entity":"test_suite_runs","mode":"row","filter":{"op":"eq","args":[{"field":"test_suite_id"},{"type":"uuid","value":"<suite>"}]},"sort":[{"field":"created_at_ms","dir":"desc"}]}`
- **THEN** the response rows are that suite's runs, newest first, each carrying every field of the entity schema

#### Scenario: Aggregate-mode query groups runs by deployment
- **WHEN** a client posts an `aggregate` query with `group_by: ["deployment_ref::id"]` and `select: [{"expr":{"field":"deployment_ref::id"}}, {"expr":{"fn":"count","args":[]},"as":"runs"}]`
- **THEN** the response has one row per distinct deployment id (including one null-keyed row for runs without a snapshot deployment ref) with the run count

### Requirement: Entity field set
The `test_suite_runs` entity SHALL expose exactly the following flat **queryable** fields, and no others:

| Field | Type | Source | Notes |
|---|---|---|---|
| `id` | `uuid` | `id` | |
| `test_suite_id` | `uuid` | `test_suite_id` | |
| `test_run_name` | `string` | `test_run_name` | user-provided or auto-generated run name |
| `status` | `string` | `status` | |
| `number_of_test_cases` | `integer` | `number_of_test_cases` | |
| `started_at_ms` | `long` | `started_at_ms` | nullable |
| `completed_at_ms` | `long` | `completed_at_ms` | nullable |
| `error_message` | `string` | `error_message` | nullable |
| `created_at_ms` | `long` | `created_at_ms` | |
| `updated_at_ms` | `long` | `updated_at_ms` | |
| `number_of_runs` | `integer` | `run_config` | `run_config ->> 'numberOfRuns'`; see "Repetition count is derived from the run's stored run configuration" |
| `suite_type` | `string` | `suite_snapshot` | `suite_snapshot ->> 'suiteType'` |
| `deployment_ref::id` | `string` | `suite_snapshot` | `suite_snapshot -> 'deploymentRef' ->> 'id'` |
| `deployment_ref::name` | `string` | `suite_snapshot` | |
| `deployment_ref::version` | `string` | `suite_snapshot` | |
| `deployment_ref::type` | `string` | `suite_snapshot` | |
| `mcp_deployment_ref::id` | `string` | `suite_snapshot` | `suite_snapshot -> 'mcpDeploymentRef' ->> 'id'` |
| `mcp_deployment_ref::name` | `string` | `suite_snapshot` | |
| `mcp_deployment_ref::type` | `string` | `suite_snapshot` | |
| `mcp_deployment_ref::transport` | `string` | `suite_snapshot` | |
| `metric_names` | `array` | `run_metric_snapshots` | see "Latest-computation metric names" |

The columns `suite_snapshot`, `run_config` and `error_details` SHALL NOT be exposed as fields, SHALL NOT be accepted in `filter`/`select`/`sort`/`group_by`, and SHALL NOT be part of the row projection when `select` is empty — this holds for `run_config` even though `number_of_runs` is derived from it. The entity SHALL carry no **queryable** field that is derived outside the database (e.g. the REST listing's `grafanaExploreUrl`). Extension-derived result keys are not queryable fields and are governed by `query-result-page-extension`.
Status: **Implemented**

#### Scenario: Empty select projects the whole entity and nothing more
- **WHEN** a `row` query against `test_suite_runs` is posted with no `select`
- **THEN** every row carries exactly the fields in the table above and none of `suite_snapshot`, `run_config`, `error_details` — plus any extension-derived keys that apply to it, which are not entity fields

#### Scenario: Excluded column is rejected as unknown
- **WHEN** a query references `suite_snapshot` (or `run_config`, `error_details`) in `filter`, `select`, `sort` or `group_by`
- **THEN** the request is rejected with HTTP 400 as an unknown field

#### Scenario: Base schema matches the executable field set
- **WHEN** `GET /api/v1/queries/entities/schema/test_suite_runs` is called
- **THEN** the returned fields are exactly the rows of the table above, with the listed types and sources

### Requirement: Repetition count is derived from the run's stored run configuration
`number_of_runs` SHALL be the run's configured repetitions per test case, read in-database from the run's own `run_config` payload (key `numberOfRuns`) as a flat `integer` field — the value the run was created with. A run whose `run_config` lacks the key SHALL yield null and the query SHALL still succeed.

The field SHALL be usable wherever any other plain `integer` field is usable: `filter`, `sort`, `select`, `group_by`. `run_config` itself SHALL remain a non-exposed column; no other run-configuration member SHALL be exposed.
Status: **Implemented**

#### Scenario: Value matches the run's configuration
- **WHEN** a run created with 3 repetitions per test case is selected via a `row` query with `number_of_runs`
- **THEN** the returned value is `3`

#### Scenario: Filterable, sortable and groupable as an integer
- **WHEN** an `aggregate` query filters `{"op":"gt","args":[{"field":"number_of_runs"},{"type":"integer","value":1}]}`, groups by `number_of_runs`, selects `count`, and sorts on `number_of_runs`
- **THEN** the response has one row per distinct repetition count greater than 1, ordered by that count, each with the number of matching runs

#### Scenario: Missing configuration key yields null
- **WHEN** a run's `run_config` does not contain `numberOfRuns`
- **THEN** `number_of_runs` is null for that run and the query succeeds

### Requirement: Deployment reference fields come from the run's own snapshot only
`suite_type`, `deployment_ref::*` and `mcp_deployment_ref::*` SHALL be read from the run's `suite_snapshot` — the configuration the run actually executed against — and SHALL NOT fall back to the current `test_suites` row. A run whose `suite_snapshot` is null (legacy run, or a run that has not reached the snapshot phase) SHALL yield null for all of these fields. A `DEPLOYMENT` run SHALL yield nulls for `mcp_deployment_ref::*` and an `MCP` run SHALL yield nulls for `deployment_ref::*`.
Status: **Implemented**

#### Scenario: Snapshot ref survives a later suite edit
- **WHEN** a run completed against deployment `A` and the suite's `deploymentRef` is subsequently changed to `B`
- **THEN** a `test_suite_runs` query returns `deployment_ref::id = "A"` for that run

#### Scenario: Run without a snapshot yields null refs
- **WHEN** a run row has `suite_snapshot IS NULL`
- **THEN** the query returns null for `suite_type`, every `deployment_ref::*` and every `mcp_deployment_ref::*` field, and the request does not fail

#### Scenario: Refs are filterable and sortable
- **WHEN** a query filters `{"op":"eq","args":[{"field":"deployment_ref::id"},{"type":"string","value":"deploy-001"}]}` and sorts by `deployment_ref::name`
- **THEN** only runs whose snapshot deployment id is `deploy-001` are returned, ordered by the snapshot deployment name

### Requirement: Latest-computation metric names
`metric_names` SHALL be a JSON array of the distinct `tsmd_name` values of the run's **latest computation** in `run_metric_snapshots`, sorted ascending under the meta database's default text collation. "Latest" SHALL be the computation with the greatest `computed_at_ms` for that run, ties broken by the **smallest** `computation_id`, matching the system-wide tiebreak so that this lookup and the eval-summary-based one never disagree on a tied run. A run with no snapshot rows SHALL yield an empty array `[]`, never null. Names SHALL be the full metric names as captured on the run (the TSMD name), not metric output-field names.
Status: **Implemented**

#### Scenario: Latest computation wins
- **WHEN** a run has snapshots for computation `c1` (`computed_at_ms=1000`, metrics `Accuracy`, `Relevance`) and `c2` (`computed_at_ms=2000`, metric `Accuracy` only)
- **THEN** `metric_names` is `["Accuracy"]`

#### Scenario: Same-millisecond computations resolve deterministically
- **WHEN** two computations of one run share `computed_at_ms`
- **THEN** `metric_names` is taken from the computation with the smaller `computation_id`, and repeated executions of the query return the same array

#### Scenario: Tied run resolves one computation across both paths
- **WHEN** a run has two computations sharing `computed_at_ms`, and a `row` query returns both `metric_names` and `overall_score_value` for it
- **THEN** both values come from the same computation

#### Scenario: Names are sorted
- **WHEN** the latest computation captured metrics named `Relevance`, `Accuracy`, `Toxicity`
- **THEN** `metric_names` is `["Accuracy","Relevance","Toxicity"]`

#### Scenario: Metric-less run yields empty array
- **WHEN** a run has no `run_metric_snapshots` rows (e.g. a PENDING run, or a suite with no metrics)
- **THEN** `metric_names` is `[]`

### Requirement: `metric_names` supports whole-element containment filters
`metric_names` SHALL be bound as an `array`-typed field, so `co` and `nc` SHALL match a **whole element** of the array (never a substring), with the same semantics as array-typed `test_cases` fields: a bare field operand compares case-sensitively; a `lower(...)`/`upper(...)`-wrapped operand compares case-insensitively; `nc` SHALL be total (a run with `[]` satisfies `nc`).
Status: **Implemented**

#### Scenario: Runs that produced a metric
- **WHEN** a query filters `{"op":"co","args":[{"field":"metric_names"},{"type":"string","value":"Accuracy"}]}`
- **THEN** only runs whose latest computation includes a metric named exactly `Accuracy` are returned; a run whose only metric is `AccuracyV2` is not

#### Scenario: Case-insensitive containment
- **WHEN** a query filters `{"op":"co","args":[{"fn":"lower","args":[{"field":"metric_names"}]},{"type":"string","value":"accuracy"}]}`
- **THEN** runs with a metric named `Accuracy` are returned

#### Scenario: Runs that did not produce a metric
- **WHEN** a query filters `{"op":"nc","args":[{"field":"metric_names"},{"type":"string","value":"Accuracy"}]}`
- **THEN** runs lacking `Accuracy`, including metric-less runs with `[]`, are returned

### Requirement: Enrichment cost is proportional to the runs visited
Computing `deployment_ref::*`, `mcp_deployment_ref::*` and `metric_names` SHALL be evaluated per run row visited by the outer query (run-scoped), never by pre-aggregating the whole `run_metric_snapshots` table for every request. A query whose filter and sort are satisfiable by the run table's own indexes SHALL not read snapshot rows of runs outside its result page. The latest-computation lookup SHALL be served by an index on `run_metric_snapshots` leading with `(test_suite_run_id, computed_at_ms DESC)`.
Status: **Implemented**

#### Scenario: Paged query touches only its page
- **WHEN** a `row` query sorted by `created_at_ms DESC` with `page: {"offset":0,"limit":25}` is executed against a database with many runs and snapshots
- **THEN** the execution plan reads snapshot rows only for the runs emitted in that page (a correlated SubPlan evaluated only for the rows the page emits, against the composite index), not for every run

### Requirement: Existing run listing is unchanged
Onboarding `test_suite_runs` as a query entity SHALL NOT alter the request or response contract of `GET /api/v1/test-suite-runs`, `GET /api/v1/test-suite-runs/{id}`, or any other test-suite-run REST endpoint.
Status: **Implemented**

#### Scenario: REST listing still works
- **WHEN** `GET /api/v1/test-suite-runs?filter=status:eq:COMPLETED` is called after the change
- **THEN** the response shape and semantics are identical to before the change

### Requirement: Run-level overall score on a row result page
A `row`-mode `test_suite_runs` result page SHALL carry, per row, the derived key `overall_score_value`: the run-level `overall` metric score of that run's **latest computation**, read from the analytics `metric_score_results` entity (`metric_score_name` = `overall`, `metric_name` = `overall`). "Latest computation" SHALL be the canonical latest resolved from the run's eval summaries — the same computation the run's detail view resolves for the `"latest"` sentinel — and SHALL NOT be inferred from the newest `overall` row, which would answer "latest computation that has an overall row" and could show a score the detail view does not.

The key SHALL be omitted for a run that has no such score. It SHALL NOT be part of the entity's queryable field set: it SHALL be rejected in `filter`, `select`, `sort` and `group_by`, and SHALL NOT appear in the published schema. Lookup cost SHALL be proportional to the page, never per-row: the whole page SHALL be resolved in a bounded number of statements independent of page size.
Status: **Implemented**

#### Scenario: Scored run carries its latest computation's overall value
- **WHEN** a `row` query returns a run whose latest computation produced a run-level `overall` score
- **THEN** that row carries `overall_score_value` with that computation's value

#### Scenario: Newer computation without an overall row does not surface a stale score
- **WHEN** a run's latest computation produced no `overall` score but an earlier computation did
- **THEN** the row carries no `overall_score_value`, rather than the earlier computation's value

#### Scenario: Unscored run omits the key
- **WHEN** a `row` query returns a run with no eval summaries, or whose latest computation produced no `overall` score
- **THEN** that row carries no `overall_score_value` key

#### Scenario: Key is not queryable
- **WHEN** a query references `overall_score_value` in `filter`, `select`, `sort` or `group_by`
- **THEN** the request is rejected with HTTP 400 as an unknown field

#### Scenario: Aggregate mode is untouched
- **WHEN** an `aggregate`-mode query runs against `test_suite_runs`
- **THEN** no `overall_score_value` key is added to any group row

#### Scenario: Projection without `id` is untouched
- **WHEN** a `row` query's `select` omits `id`, or renames it to another key
- **THEN** no row carries `overall_score_value` and the query succeeds

#### Scenario: Projection aliasing another expression as `id` is untouched
- **WHEN** a `row` query's `select` keys another expression as `id` (for example `{"expr": {"field": "test_run_name"}, "as": "id"}`)
- **THEN** no row carries `overall_score_value`, no lookup is attempted, and the query succeeds

#### Scenario: Projection already carrying the key is untouched
- **WHEN** a `row` query's `select` aliases another expression as `overall_score_value`
- **THEN** every row keeps the client-selected value and no lookup is attempted

#### Scenario: Analytics unavailable degrades to an unextended page
- **WHEN** the analytics lookup fails or the `metric_score_results` entity is not registered (non-Postgres analytics vendor)
- **THEN** the response is HTTP 200 with the rows the query produced and no `overall_score_value` key

#### Scenario: Page cost does not scale with page size
- **WHEN** a `row` query returns a full page of runs
- **THEN** the score for the whole page is resolved in a bounded number of statements, not one per row

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

## Implementation notes

- Entity resolver (derived table `tsr`, `run_config` scalar extraction, correlated `metric_names` scalar subquery): `src/main/java/com/epam/aidial/evaluation/query/service/repository/PostgresTestSuiteRunEntityResolver.java`
- Base schema provider: `src/main/java/com/epam/aidial/evaluation/query/service/TestSuiteRunsSchemaProvider.java`
- Shared field vocabulary (entity name, excluded columns, ref descriptors, `number_of_runs`, `metric_names`): `src/main/java/com/epam/aidial/evaluation/query/service/TestSuiteRunQueryFields.java`
- JSONB scalar extraction SPI (`jsonbAtAsInteger`): `src/main/java/com/epam/aidial/evaluation/data/db/repository/sql/json/JsonPathAccessor.java` / `PostgresJsonPathAccessor.java`
- Composite index serving the latest-computation lookup: `src/main/resources/db/migration/meta/POSTGRES/V1.34__ReplaceRunMetricSnapshotsRunIndex.sql`
- Functional coverage: `src/test/java/com/epam/aidial/evaluation/functional/tests/TestSuiteRunStructuredQueryFunctionalTests.java` (nested `TestSuiteRunStructuredQueryTests` in `PostgresFunctionalTests`), plus `QuerySchemaDiscoveryFunctionalTests` and `StructuredQueryExecuteFunctionalTests`
- Pattern doc with EXPLAIN evidence: `docs/patterns/test-suite-runs-query-entity.md`
- Total-cost enrichment (opt-in, `query-dsl.enrichment.test-suite-run.cost.*`): `src/main/java/com/epam/aidial/evaluation/query/service/repository/TotalCostTestSuiteRunsPageExtender.java` (timed `Future.get` on `testSuiteRunCostEnrichmentExecutor`, dedicated `testSuiteRunCostEnrichmentDialAdasClient`), shared lookup `service/domain/BatchRunTotalCostLookup.java`, wiring in `configuration/TestSuiteRunCostEnrichmentAsyncConfiguration.java` / `client/dialadas/DialAdasClientConfiguration.java`, properties `configuration/properties/query/QueryDslTestSuiteRunCostEnrichmentProperties.java`; tests `TotalCostTestSuiteRunsPageExtenderTest`, `TestSuiteRunCostEnrichmentFunctionalTests` (nested `TestSuiteRunCostEnrichmentTests`)
