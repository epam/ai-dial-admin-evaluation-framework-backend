## Purpose

Exposes test suite runs as a `test_suite_runs` entity of the structured query DSL so a client can list, filter, sort and aggregate runs together with the deployment they executed against and the names of the metrics their latest computation produced, in one request and without a bespoke REST listing.

## ADDED Requirements

### Requirement: `test_suite_runs` is a simple queryable entity on the meta datasource
The system SHALL accept `test_suite_runs` as the `entity` of a structured query at `POST /api/v1/queries/execute`, in both `row` and `aggregate` modes, and SHALL list it in the entity catalog as a simple entity (`complex=false`, null `schemaIdField`). All of its fields SHALL be resolved from the meta database in a single statement; no analytics datasource SHALL be involved.
Status: **Planned**

#### Scenario: Row-mode query lists runs
- **WHEN** a client posts `{"entity":"test_suite_runs","mode":"row","filter":{"op":"eq","args":[{"field":"test_suite_id"},{"type":"uuid","value":"<suite>"}]},"sort":[{"field":"created_at_ms","dir":"desc"}]}`
- **THEN** the response rows are that suite's runs, newest first, each carrying every field of the entity schema

#### Scenario: Aggregate-mode query groups runs by deployment
- **WHEN** a client posts an `aggregate` query with `group_by: ["deployment_ref::id"]` and `select: [{"expr":{"field":"deployment_ref::id"}}, {"expr":{"fn":"count","args":[]},"as":"runs"}]`
- **THEN** the response has one row per distinct deployment id (including one null-keyed row for runs without a snapshot deployment ref) with the run count

### Requirement: Entity field set
The `test_suite_runs` entity SHALL expose exactly the following flat fields, and no others:

| Field | Type | Source | Notes |
|---|---|---|---|
| `id` | `uuid` | `id` | |
| `test_suite_id` | `uuid` | `test_suite_id` | |
| `test_run_name` | `string` | `test_run_name` | |
| `status` | `string` | `status` | |
| `number_of_test_cases` | `integer` | `number_of_test_cases` | |
| `started_at_ms` | `long` | `started_at_ms` | nullable |
| `completed_at_ms` | `long` | `completed_at_ms` | nullable |
| `error_message` | `string` | `error_message` | nullable |
| `created_at_ms` | `long` | `created_at_ms` | |
| `updated_at_ms` | `long` | `updated_at_ms` | |
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

The columns `suite_snapshot`, `run_config` and `error_details` SHALL NOT be exposed as fields, SHALL NOT be accepted in `filter`/`select`/`sort`/`group_by`, and SHALL NOT be part of the row projection when `select` is empty. The entity SHALL carry no field that is derived outside the database (e.g. the REST listing's `grafanaExploreUrl`).
Status: **Planned**

#### Scenario: Empty select projects the whole entity and nothing more
- **WHEN** a `row` query against `test_suite_runs` is posted with no `select`
- **THEN** every row carries exactly the fields in the table above and none of `suite_snapshot`, `run_config`, `error_details`

#### Scenario: Excluded column is rejected as unknown
- **WHEN** a query references `suite_snapshot` (or `run_config`, `error_details`) in `filter`, `select`, `sort` or `group_by`
- **THEN** the request is rejected with HTTP 400 as an unknown field

#### Scenario: Base schema matches the executable field set
- **WHEN** `GET /api/v1/queries/entities/schema/test_suite_runs` is called
- **THEN** the returned fields are exactly the rows of the table above, with the listed types and sources

### Requirement: Deployment reference fields come from the run's own snapshot only
`suite_type`, `deployment_ref::*` and `mcp_deployment_ref::*` SHALL be read from the run's `suite_snapshot` — the configuration the run actually executed against — and SHALL NOT fall back to the current `test_suites` row. A run whose `suite_snapshot` is null (legacy run, or a run that has not reached the snapshot phase) SHALL yield null for all of these fields. A `DEPLOYMENT` run SHALL yield nulls for `mcp_deployment_ref::*` and an `MCP` run SHALL yield nulls for `deployment_ref::*`.
Status: **Planned**

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
`metric_names` SHALL be a JSON array of the distinct `tsmd_name` values of the run's **latest computation** in `run_metric_snapshots`, sorted alphabetically (ascending, case-sensitive). "Latest" SHALL be the computation with the greatest `computed_at_ms` for that run, ties broken by the greatest `computation_id`. A run with no snapshot rows SHALL yield an empty array `[]`, never null. Names SHALL be the full metric names as captured on the run (the TSMD name), not metric output-field names.
Status: **Planned**

#### Scenario: Latest computation wins
- **WHEN** a run has snapshots for computation `c1` (`computed_at_ms=1000`, metrics `Accuracy`, `Relevance`) and `c2` (`computed_at_ms=2000`, metric `Accuracy` only)
- **THEN** `metric_names` is `["Accuracy"]`

#### Scenario: Same-millisecond computations resolve deterministically
- **WHEN** two computations of one run share `computed_at_ms`
- **THEN** `metric_names` is taken from the computation with the greater `computation_id`, and repeated executions of the query return the same array

#### Scenario: Names are sorted
- **WHEN** the latest computation captured metrics named `Relevance`, `Accuracy`, `Toxicity`
- **THEN** `metric_names` is `["Accuracy","Relevance","Toxicity"]`

#### Scenario: Metric-less run yields empty array
- **WHEN** a run has no `run_metric_snapshots` rows (e.g. a PENDING run, or a suite with no metrics)
- **THEN** `metric_names` is `[]`

### Requirement: `metric_names` supports whole-element containment filters
`metric_names` SHALL be bound as an `array`-typed field, so `co` and `nc` SHALL match a **whole element** of the array (never a substring), with the same semantics as array-typed `test_cases` fields: a bare field operand compares case-sensitively; a `lower(...)`/`upper(...)`-wrapped operand compares case-insensitively; `nc` SHALL be total (a run with `[]` satisfies `nc`).
Status: **Planned**

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
Computing `deployment_ref::*`, `mcp_deployment_ref::*` and `metric_names` SHALL be evaluated per run row visited by the outer query (run-scoped), never by pre-aggregating the whole `run_metric_snapshots` table for every request. A query whose filter and sort are satisfiable by the run table's own indexes SHALL not read snapshot rows of runs outside its result page. The latest-computation lookup SHALL be served by an index on `run_metric_snapshots (test_suite_run_id, computed_at_ms DESC, computation_id DESC)`.
Status: **Planned**

#### Scenario: Paged query touches only its page
- **WHEN** a `row` query sorted by `created_at_ms DESC` with `page: {"offset":0,"limit":25}` is executed against a database with many runs and snapshots
- **THEN** the execution plan reads snapshot rows only for the runs emitted in that page (nested-loop lateral evaluation against the composite index), not for every run

### Requirement: Existing run listing is unchanged
Onboarding `test_suite_runs` as a query entity SHALL NOT alter the request or response contract of `GET /api/v1/test-suite-runs`, `GET /api/v1/test-suite-runs/{id}`, or any other test-suite-run REST endpoint.
Status: **Planned**

#### Scenario: REST listing still works
- **WHEN** `GET /api/v1/test-suite-runs?filter=status:eq:COMPLETED` is called after the change
- **THEN** the response shape and semantics are identical to before the change
