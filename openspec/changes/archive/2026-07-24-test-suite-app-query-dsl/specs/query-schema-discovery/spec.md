## MODIFIED Requirements

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
| `mcp_deployment_ref::id` | `string` | `mcp_deployment_ref` |
| `mcp_deployment_ref::name` | `string` | `mcp_deployment_ref` |
| `mcp_deployment_ref::type` | `string` | `mcp_deployment_ref` |

Status: **Implemented** (targeted at this change)

#### Scenario: Base schema lists JSONB fields unflattened
- **WHEN** `GET /api/v1/queries/entities/schema/eval_summaries` is called
- **THEN** the response lists the entity's plain columns with their inferred types and lists its
  JSONB-backed fields (e.g. `test_case_data`, `metric_values`, `metric_infos`, `extraction_warnings`)
  as-is with type `object` or `array`, none of them flattened

#### Scenario: test_suites base schema includes deployment_ref sub-fields
- **WHEN** `GET /api/v1/queries/entities/schema/test_suites` is called
- **THEN** the response includes `deployment_ref::id`, `deployment_ref::name`,
  `deployment_ref::version` each typed `string` with source `deployment_ref`, AND the plain
  `deployment_ref` entry typed `object`

#### Scenario: test_suites base schema includes mcp_deployment_ref sub-fields
- **WHEN** `GET /api/v1/queries/entities/schema/test_suites` is called
- **THEN** the response includes `mcp_deployment_ref::id`, `mcp_deployment_ref::name`,
  `mcp_deployment_ref::type` each typed `string` with source `mcp_deployment_ref`, AND the plain
  `mcp_deployment_ref` entry typed `object`
