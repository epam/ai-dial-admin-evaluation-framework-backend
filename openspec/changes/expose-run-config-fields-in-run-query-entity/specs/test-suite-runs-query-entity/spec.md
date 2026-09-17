## ADDED Requirements

### Requirement: Repetition count is derived from the run's stored run configuration
`number_of_runs` SHALL be the run's configured repetitions per test case, read in-database from the run's own `run_config` payload (key `numberOfRuns`) as a flat `integer` field — the value the run was created with. A run whose `run_config` lacks the key SHALL yield null and the query SHALL still succeed.

The field SHALL be usable wherever any other plain `integer` field is usable: `filter`, `sort`, `select`, `group_by`. `run_config` itself SHALL remain a non-exposed column; no other run-configuration member SHALL be exposed.
Status: **Planned**

#### Scenario: Value matches the run's configuration
- **WHEN** a run created with 3 repetitions per test case is selected via a `row` query with `number_of_runs`
- **THEN** the returned value is `3`

#### Scenario: Filterable, sortable and groupable as an integer
- **WHEN** an `aggregate` query filters `{"op":"gt","args":[{"field":"number_of_runs"},{"type":"integer","value":1}]}`, groups by `number_of_runs`, selects `count`, and sorts on `number_of_runs`
- **THEN** the response has one row per distinct repetition count greater than 1, ordered by that count, each with the number of matching runs

#### Scenario: Missing configuration key yields null
- **WHEN** a run's `run_config` does not contain `numberOfRuns`
- **THEN** `number_of_runs` is null for that run and the query succeeds

## MODIFIED Requirements

### Requirement: Entity field set
The `test_suite_runs` entity SHALL expose exactly the following flat fields, and no others:

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

The columns `suite_snapshot`, `run_config` and `error_details` SHALL NOT be exposed as fields, SHALL NOT be accepted in `filter`/`select`/`sort`/`group_by`, and SHALL NOT be part of the row projection when `select` is empty — this holds for `run_config` even though `number_of_runs` is derived from it. The entity SHALL carry no field that is derived outside the database (e.g. the REST listing's `grafanaExploreUrl`).
Status: **Implemented**

#### Scenario: Empty select projects the whole entity and nothing more
- **WHEN** a `row` query against `test_suite_runs` is posted with no `select`
- **THEN** every row carries exactly the fields in the table above and none of `suite_snapshot`, `run_config`, `error_details`

#### Scenario: Excluded column is rejected as unknown
- **WHEN** a query references `suite_snapshot` (or `run_config`, `error_details`) in `filter`, `select`, `sort` or `group_by`
- **THEN** the request is rejected with HTTP 400 as an unknown field

#### Scenario: Base schema matches the executable field set
- **WHEN** `GET /api/v1/queries/entities/schema/test_suite_runs` is called
- **THEN** the returned fields are exactly the rows of the table above, with the listed types and sources

## Implementation notes

- JSONB scalar extraction SPI: `src/main/java/com/epam/aidial/evaluation/data/db/repository/sql/json/JsonPathAccessor.java` / `PostgresJsonPathAccessor.java`
