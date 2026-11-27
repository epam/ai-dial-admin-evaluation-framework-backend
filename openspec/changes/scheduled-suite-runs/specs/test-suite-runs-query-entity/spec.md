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
| `schedule_id` | `string` | `schedule_id` | id of the schedule that started the run; null for manually started runs |
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
| `overall_score_threshold` | `decimal` | `suite_snapshot` | `(suite_snapshot ->> 'overallScoreThreshold')::numeric`; nullable, snapshot-only |

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

#### Scenario: Runs can be filtered by the schedule that started them
- **WHEN** a `row` query against `test_suite_runs` filters `schedule_id` with `eq` (or `in`) on a schedule id
- **THEN** only runs started by that schedule are returned; manually started runs, whose `schedule_id` is null, are excluded

#### Scenario: Manually started runs have a null schedule_id
- **WHEN** a `row` query returns a run that was started manually
- **THEN** its `schedule_id` is null
