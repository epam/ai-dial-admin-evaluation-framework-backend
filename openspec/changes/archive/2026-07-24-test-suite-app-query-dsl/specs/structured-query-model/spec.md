## MODIFIED Requirements

### Requirement: JSONB sub-field resolver prefix families
The system SHALL support resolving flattened JSONB sub-field names for the `test_suites` entity via
the `JsonbFieldResolver`. Two new prefix families SHALL be recognised:
- `deployment_ref::<key>` — resolves to `deployment_ref ->> '<key>'` (text extraction) over the
  `deployment_ref` JSONB column on the `test_suites` table.
- `mcp_deployment_ref::<key>` — resolves to `mcp_deployment_ref ->> '<key>'` (text extraction) over
  the `mcp_deployment_ref` JSONB column on the `test_suites` table.

Both families SHALL use the existing `textPath()` resolution path in `JsonbFieldResolver`, guarded
by the same `jsonbColumn()` check that ensures the resolver only activates when the entity's
bindings include the backing column. The resulting `QueryFieldType` for all sub-fields resolved via
these families SHALL be `STRING`.

A suffix that resolves to a JSON key absent from the stored object SHALL return SQL `NULL` (standard
Postgres `->>` semantics); this is not an error.

Status: **Implemented** (targeted at this change)

#### Scenario: deployment_ref::name resolves to text extraction
- **WHEN** a `test_suites` query names the field `deployment_ref::name` in a filter or select
- **THEN** the query builder emits `deployment_ref ->> 'name'` as the SQL expression for that field

#### Scenario: mcp_deployment_ref::id resolves to text extraction
- **WHEN** a `test_suites` query names the field `mcp_deployment_ref::id` in a filter or select
- **THEN** the query builder emits `mcp_deployment_ref ->> 'id'` as the SQL expression for that field

#### Scenario: deployment_ref sub-field on an MCP suite returns NULL
- **WHEN** a `test_suites` query selects `deployment_ref::name` for a suite whose `deployment_ref`
  column is NULL (e.g. an MCP-type suite)
- **THEN** the projected value for that row is SQL `NULL`, not an error

#### Scenario: Opaque OBJECT binding is unaffected
- **WHEN** a `test_suites` query names the plain field `deployment_ref` (no `::` suffix)
- **THEN** the query builder uses the existing `OBJECT`-typed `deployment_ref` column binding,
  not the sub-field resolver path

#### Scenario: Resolver does not activate for entities without the backing column
- **WHEN** a query targets an entity other than `test_suites` and names `deployment_ref::name`
- **THEN** `JsonbFieldResolver` returns null (backing column not in bindings) and the field is
  rejected as unknown with HTTP 400
