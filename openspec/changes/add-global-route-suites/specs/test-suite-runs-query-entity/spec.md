## MODIFIED Requirements

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
| `routes_ref::name` | `string` | `suite_snapshot` | `suite_snapshot -> 'routesRef' ->> 'name'` |
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

### Requirement: Deployment reference fields come from the run's own snapshot only
`suite_type`, `deployment_ref::*`, `routes_ref::name` and `mcp_deployment_ref::*` SHALL be read from the run's `suite_snapshot` — the configuration the run actually executed against — and SHALL NOT fall back to the current `test_suites` row. A run whose `suite_snapshot` is null (legacy run, or a run that has not reached the snapshot phase) SHALL yield null for all of these fields. A `DEPLOYMENT` run SHALL yield nulls for `mcp_deployment_ref::*` and `routes_ref::name`, an `MCP` run SHALL yield nulls for `deployment_ref::*` and `routes_ref::name`, and a `ROUTE` run SHALL yield nulls for `deployment_ref::*` and `mcp_deployment_ref::*`.
Status: **Implemented**

#### Scenario: Snapshot ref survives a later suite edit
- **WHEN** a run completed against deployment `A` and the suite's `deploymentRef` is subsequently changed to `B`
- **THEN** a `test_suite_runs` query returns `deployment_ref::id = "A"` for that run

#### Scenario: Run without a snapshot yields null refs
- **WHEN** a run row has `suite_snapshot IS NULL`
- **THEN** the query returns null for `suite_type`, every `deployment_ref::*`, `routes_ref::name` and every `mcp_deployment_ref::*` field, and the request does not fail

#### Scenario: ROUTE run exposes and filters by routes_ref::name from its own snapshot
- **WHEN** a run's `suite_snapshot` is `{"snapshotVersion":"2","suiteType":"ROUTE","routesRef":{"name":"routes/platform/route-X"}}` and a query filters `{"op":"eq","args":[{"field":"routes_ref::name"},{"type":"string","value":"routes/platform/route-X"}]}` selecting `id`, `suite_type` and `routes_ref::name`
- **THEN** only that run is returned, with `suite_type = "ROUTE"` and `routes_ref::name = "routes/platform/route-X"`; runs whose snapshot names a different route are not returned

#### Scenario: Non-ROUTE run yields null routes_ref::name
- **WHEN** a query selects `routes_ref::name` for a run whose snapshot has no `routesRef` (a DEPLOYMENT or MCP run)
- **THEN** the value is null and the request does not fail

#### Scenario: Refs are filterable and sortable
- **WHEN** a query filters `{"op":"eq","args":[{"field":"deployment_ref::id"},{"type":"string","value":"deploy-001"}]}` and sorts by `deployment_ref::name`
- **THEN** only runs whose snapshot deployment id is `deploy-001` are returned, ordered by the snapshot deployment name

## Implementation notes

- `TestSuiteRunQueryFields` (registers a `routes_ref` descriptor over snapshot key `routesRef` with sub-field `name`, between the deployment and MCP descriptors), `PostgresTestSuiteRunEntityResolver`, `TestSuiteRunsSchemaProvider`. The entity now exposes 22 queryable fields (previously 21).
- Tests: `TestSuiteRunStructuredQueryFunctionalTests` ("a ROUTE run exposes and filters by routes_ref::name from its own snapshot"; the field-set tests now assert 22 fields), `TestSuiteRunsSchemaProviderTest`, `PostgresTestSuiteRunEntityResolverTest`.
