## ADDED Requirements

### Requirement: Repetition count is derived from the run's stored run configuration
`number_of_runs` SHALL be the run's configured number of repetitions per test case, read in-database out of the run's own `run_config` payload (JSON key `numberOfRuns`) and surfaced as a flat `integer` field. It SHALL be the value the run was created with — the configuration the run actually executed under — and SHALL NOT be recomputed from analytics rows or re-read from any other source. A run whose `run_config` does not carry the key SHALL yield null for this field, and the query SHALL still succeed.

The field SHALL be usable wherever any other plain `integer` field of the entity is usable: `filter` (including range and null-polarity operators), `sort`, `select` and `group_by`. Exposing it SHALL NOT expose `run_config` itself: `run_config` SHALL remain a non-exposed column, rejected as an unknown field and absent from the row projection. No other member of the run configuration (execution settings, retry policy) SHALL be exposed as a field.
Status: **Planned**

#### Scenario: Value matches the run's configuration
- **WHEN** a run was created with a run configuration specifying 3 repetitions per test case, and a `row` query against `test_suite_runs` selects `number_of_runs` for that run
- **THEN** the returned value is `3`

#### Scenario: Filterable as an integer
- **WHEN** a query filters `{"op":"gt","args":[{"field":"number_of_runs"},{"type":"integer","value":1}]}`
- **THEN** only runs configured with more than one repetition per test case are returned

#### Scenario: Sortable and groupable
- **WHEN** an `aggregate` query is posted with `group_by: ["number_of_runs"]`, `select: [{"expr":{"field":"number_of_runs"}}, {"expr":{"fn":"count","args":[]},"as":"runs"}]` and a sort on `number_of_runs`
- **THEN** the response has one row per distinct repetition count, ordered by that count, each with the number of runs configured with it

#### Scenario: Missing configuration key yields null
- **WHEN** a run's `run_config` payload does not contain a `numberOfRuns` key
- **THEN** `number_of_runs` is null for that run and the query succeeds

#### Scenario: The backing column stays unexposed
- **WHEN** a query references `run_config` in `filter`, `select`, `sort` or `group_by`
- **THEN** the request is rejected with HTTP 400 as an unknown field, even though `number_of_runs` is derived from that column

#### Scenario: Other run-configuration members are not fields
- **WHEN** a query references a run-configuration execution or retry setting (e.g. `run_config::execution`, `execution`, `retry`) as a field
- **THEN** the request is rejected with HTTP 400 as an unknown field

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

### Requirement: Enrichment cost is proportional to the runs visited
Computing `deployment_ref::*`, `mcp_deployment_ref::*`, `number_of_runs` and `metric_names` SHALL be evaluated per run row visited by the outer query (run-scoped), never by pre-aggregating the whole `run_metric_snapshots` table for every request. A query whose filter and sort are satisfiable by the run table's own indexes SHALL not read snapshot rows of runs outside its result page. The latest-computation lookup SHALL be served by an index on `run_metric_snapshots (test_suite_run_id, computed_at_ms DESC, computation_id DESC)`. Deriving `number_of_runs` SHALL require no join, no subquery and no additional table access beyond the run row itself.
Status: **Implemented**

#### Scenario: Paged query touches only its page
- **WHEN** a `row` query sorted by `created_at_ms DESC` with `page: {"offset":0,"limit":25}` is executed against a database with many runs and snapshots
- **THEN** the execution plan reads snapshot rows only for the runs emitted in that page (a correlated SubPlan evaluated only for the rows the page emits, against the composite index), not for every run

#### Scenario: Repetition count adds no table access
- **WHEN** a `row` query selects `number_of_runs`
- **THEN** its execution plan accesses no table other than `test_suite_runs` on account of that field

## Implementation notes

- Entity resolver (derived table `tsr`, `run_config` scalar extraction, correlated `metric_names` scalar subquery): `src/main/java/com/epam/aidial/evaluation/query/service/repository/PostgresTestSuiteRunEntityResolver.java`
- Base schema provider: `src/main/java/com/epam/aidial/evaluation/query/service/TestSuiteRunsSchemaProvider.java`
- Shared field vocabulary (entity name, excluded columns, ref descriptors, `number_of_runs`, `metric_names`): `src/main/java/com/epam/aidial/evaluation/query/service/TestSuiteRunQueryFields.java`
- JSONB scalar extraction SPI: `src/main/java/com/epam/aidial/evaluation/data/db/repository/sql/json/JsonPathAccessor.java` / `PostgresJsonPathAccessor.java`
- Pattern doc: `docs/patterns/test-suite-runs-query-entity.md`
