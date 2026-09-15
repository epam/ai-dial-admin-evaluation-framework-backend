## MODIFIED Requirements

### Requirement: Queryable entity catalog
The system SHALL expose, at `GET /api/v1/queries/entities`, the list of entities that can be named as
the `entity` of a structured query. Each catalog item SHALL carry the entity wire `name`, a `complex`
flag, and a `schemaIdField` — the name of the query parameter whose value selects a concrete instance
for the detailed schema endpoint — which SHALL be present for complex entities and null for simple
entities. Entities SHALL be listed in stable alphabetical order by name.
Status: **Implemented**

#### Scenario: Catalog lists simple and complex entities
- **WHEN** `GET /api/v1/queries/entities` is called
- **THEN** the response lists each registered entity in alphabetical order, the simple entities
  `test_suites` and `test_suite_runs` with `complex=false` and a null `schemaIdField`, and the complex
  entity `eval_summaries` with `complex=true` and `schemaIdField="test_suite_run_id"`

### Requirement: Instance-independent base schema
The system SHALL expose, at `GET /api/v1/queries/entities/schema/{name}`, the entity's flat base
schema: a list of fields each carrying a name, a flat field type, and the physical source it maps to.
The base schema SHALL be instance-independent and SHALL list JSONB-backed fields as-is, typed
`object` or `array`, without flattening them. The schema SHALL be derived from the entity's generated
jOOQ table so that it follows the physical database schema, with `VARCHAR(36)` columns typed `uuid`.

For the `test_suites` entity, the base schema SHALL additionally include the following virtual
sub-field entries sourced from the `deployment_ref` and `mcp_deployment_ref` JSONB columns. These
entries SHALL appear alongside (not instead of) the opaque `object`-typed column entries:

| Field name | Type | Source |
|---|---|---|
| `deployment_ref::id` | `string` | `deployment_ref` |
| `deployment_ref::name` | `string` | `deployment_ref` |
| `deployment_ref::version` | `string` | `deployment_ref` |
| `deployment_ref::type` | `string` | `deployment_ref` |
| `mcp_deployment_ref::id` | `string` | `mcp_deployment_ref` |
| `mcp_deployment_ref::name` | `string` | `mcp_deployment_ref` |
| `mcp_deployment_ref::type` | `string` | `mcp_deployment_ref` |
| `mcp_deployment_ref::transport` | `string` | `mcp_deployment_ref` |

For the `test_suite_runs` entity, the base schema SHALL be the plain `test_suite_runs` columns
**minus** `suite_snapshot`, `run_config` and `error_details` (these three SHALL NOT appear at all —
not even as opaque `object` entries), plus the following virtual entries. The ref sub-fields are
sourced from the run's `suite_snapshot`, not from `test_suites`:

| Field name | Type | Source |
|---|---|---|
| `suite_type` | `string` | `suite_snapshot` |
| `deployment_ref::id` | `string` | `suite_snapshot` |
| `deployment_ref::name` | `string` | `suite_snapshot` |
| `deployment_ref::version` | `string` | `suite_snapshot` |
| `deployment_ref::type` | `string` | `suite_snapshot` |
| `mcp_deployment_ref::id` | `string` | `suite_snapshot` |
| `mcp_deployment_ref::name` | `string` | `suite_snapshot` |
| `mcp_deployment_ref::type` | `string` | `suite_snapshot` |
| `mcp_deployment_ref::transport` | `string` | `suite_snapshot` |
| `metric_names` | `array` | `run_metric_snapshots` |

Status: **Implemented**

#### Scenario: Base schema lists JSONB fields unflattened
- **WHEN** `GET /api/v1/queries/entities/schema/eval_summaries` is called
- **THEN** the response lists the entity's plain columns with their inferred types and lists its
  JSONB-backed fields (e.g. `test_case_data`, `metric_values`, `metric_infos`, `extraction_warnings`)
  as-is with type `object` or `array`, none of them flattened

#### Scenario: test_suites base schema includes deployment_ref sub-fields
- **WHEN** `GET /api/v1/queries/entities/schema/test_suites` is called
- **THEN** the response includes `deployment_ref::id`, `deployment_ref::name`,
  `deployment_ref::version`, `deployment_ref::type` each typed `string` with source `deployment_ref`,
  AND the plain `deployment_ref` entry typed `object`

#### Scenario: test_suites base schema includes mcp_deployment_ref sub-fields
- **WHEN** `GET /api/v1/queries/entities/schema/test_suites` is called
- **THEN** the response includes `mcp_deployment_ref::id`, `mcp_deployment_ref::name`,
  `mcp_deployment_ref::type`, `mcp_deployment_ref::transport` each typed `string` with source
  `mcp_deployment_ref`, AND the plain `mcp_deployment_ref` entry typed `object`

#### Scenario: test_suite_runs base schema excludes heavy JSONB columns and adds snapshot-derived fields
- **WHEN** `GET /api/v1/queries/entities/schema/test_suite_runs` is called
- **THEN** the response contains no `suite_snapshot`, `run_config` or `error_details` entry, contains
  `suite_type` and the eight `deployment_ref::*`/`mcp_deployment_ref::*` entries each typed `string`
  with source `suite_snapshot`, and contains `metric_names` typed `array` with source
  `run_metric_snapshots`

#### Scenario: test_suite_runs base schema matches what the executor accepts
- **WHEN** every field name returned by `GET /api/v1/queries/entities/schema/test_suite_runs` is used
  in the `select` of a `row` query against `test_suite_runs`
- **THEN** the query executes successfully and each returned row has exactly those keys
