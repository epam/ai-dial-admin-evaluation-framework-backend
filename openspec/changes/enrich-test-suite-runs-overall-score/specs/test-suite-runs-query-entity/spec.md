## ADDED Requirements

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
- **WHEN** a `row` query's `select` omits `id`
- **THEN** no row carries `overall_score_value` and the query succeeds

#### Scenario: Analytics unavailable degrades to an unextended page
- **WHEN** the analytics lookup fails or the `metric_score_results` entity is not registered (non-Postgres analytics vendor)
- **THEN** the response is HTTP 200 with the rows the query produced and no `overall_score_value` key

#### Scenario: Page cost does not scale with page size
- **WHEN** a `row` query returns a full page of runs
- **THEN** the score for the whole page is resolved in a bounded number of statements, not one per row

## MODIFIED Requirements

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

### Requirement: Enrichment cost is proportional to the runs visited
Computing `deployment_ref::*`, `mcp_deployment_ref::*` and `metric_names` SHALL be evaluated per run row visited by the outer query (run-scoped), never by pre-aggregating the whole `run_metric_snapshots` table for every request. A query whose filter and sort are satisfiable by the run table's own indexes SHALL not read snapshot rows of runs outside its result page. The latest-computation lookup SHALL be served by an index on `run_metric_snapshots` leading with `(test_suite_run_id, computed_at_ms DESC)`.
Status: **Implemented**

#### Scenario: Paged query touches only its page
- **WHEN** a `row` query sorted by `created_at_ms DESC` with `page: {"offset":0,"limit":25}` is executed against a database with many runs and snapshots
- **THEN** the execution plan reads snapshot rows only for the runs emitted in that page (a correlated SubPlan evaluated only for the rows the page emits, against the composite index), not for every run
