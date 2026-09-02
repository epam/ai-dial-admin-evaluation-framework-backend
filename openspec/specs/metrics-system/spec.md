# Metrics System

## Purpose
This spec defines metric declarations, versioning, and storage of metric results calculated over run outcomes.

Status: **Planned/Vision** (based on design docs; not fully implemented in current codebase)

## Key Terms
- **MetricDeclaration**: metric definition (schemas + impl reference).
- **MetricDeclarationVersion**: immutable version of MetricDeclaration for reproducibility.
- **TestSuiteMetricDefinition (TSMD)**: suite-scoped materialized selection/config of metrics and bindings.
- **MetricResult**: output of applying a TSMD to a TestCaseRunResult.

## Requirements

### Requirement: Maintain a catalog of MetricDeclarations
The system SHALL maintain a catalog of MetricDeclarations available for use in TestSuites. Each MetricDeclaration SHALL be identified by (provider_id, name); provider_id SHALL be required and SHALL come from configuration when synced from a metric provider. The catalog SHALL contain only metrics synced from configured metric provider services; previously seeded (stub) records SHALL be removed by migration and SHALL NOT be returned by the API.
Status: **Planned**

#### Scenario: Declarations are discoverable
- **WHEN** clients query metric catalog
- **THEN** system SHALL expose available MetricDeclarations and their schemas

#### Scenario: Catalog contains only provider-synced metrics
- **WHEN** clients query metric catalog after migration and sync
- **THEN** system SHALL return only MetricDeclarations that were synced from configured metric providers (no legacy seeded stubs)

### Requirement: Support MetricDeclaration versioning
The system SHALL version MetricDeclarations to support reproducibility and recalculation. MetricDeclarationVersion SHALL be persisted with id, metric_declaration_id, schema_version, config_schema, input_schema, output_schema, description, display_name, and created_at. A new version SHALL be created when config_schema, input_schema, output_schema, description, or display_name change. implementation_version and implementation_ref are out of scope. schema_version SHALL be a per-declaration sequence: the database SHALL enforce that at most one MetricDeclarationVersion row exists for a given (metric_declaration_id, schema_version) pair, and the writer that assigns the next schema_version SHALL serialize concurrent assignment for the same declaration so the enforced uniqueness cannot fail a sync.
Status: **Planned**

#### Scenario: Reproducibility
- **WHEN** a MetricResult is stored
- **THEN** it SHALL reference the MetricDeclarationVersion used to compute it

#### Scenario: Compatible recalculation
- **WHEN** metric logic changes but schemas remain compatible
- **THEN** system SHALL allow recalculating metrics over existing run data

#### Scenario: Duplicate schema_version rejected
- **WHEN** a second MetricDeclarationVersion row is written for a (metric_declaration_id, schema_version) pair that already exists
- **THEN** the database SHALL reject the write (unique index violation) rather than storing two rows for that version

#### Scenario: Concurrent version assignment
- **WHEN** two application instances sync the same provider at the same time and both need a new version for the same metric declaration
- **THEN** the next schema_version SHALL be assigned under a lock on the parent declaration row, so the two writes receive distinct schema_versions instead of one failing on the unique index

#### Scenario: Latest version metadata on declaration
- **WHEN** a MetricDeclaration has one or more MetricDeclarationVersions
- **THEN** MetricDeclaration.description SHALL reflect the description of the latest version (by schema_version) and MetricDeclaration.display_name SHALL reflect the display_name of the latest version

### Requirement: Store named metric outputs
The system SHALL store metric outputs as named results via the eval summary model. Each metric computation produces one EvalSummary row per test case, containing all metric scores in a structured `metric_values` JSONB column. Individual named outputs are keyed by TSMD name and output name (e.g., `{"Accuracy": {"score": 0.85, "f1": 0.78}}`). Values SHALL be numeric (typically normalized to [0..1]). Optional detailed info SHALL be stored in a separate `metric_infos` JSONB column.
Status: **Implemented**

#### Scenario: Fixed named outputs stored in metric_values
- **WHEN** a metric produces results for a test case
- **THEN** outputs SHALL be stored in the `metric_values` JSONB column of the `test_case_eval_summaries` table, keyed by TSMD name with nested output names and numeric values

#### Scenario: Detailed info stored separately
- **WHEN** a metric produces optional info/metadata alongside output values
- **THEN** info SHALL be stored in the `metric_infos` JSONB column, keyed by TSMD name with nested output names and arbitrary JSON detail objects

#### Scenario: Reproducibility via computation_id and metric_declaration_version_id
- **WHEN** a MetricResult is stored
- **THEN** the computation SHALL be tracked via `computation_id` in the eval summary row, and the MetricDeclarationVersion used SHALL be recorded in `run_metric_snapshots` for that computation

### Requirement: Eval summary as MetricResult storage model
The system SHALL use the `test_case_eval_summaries` table as the storage model for MetricResults, replacing the originally planned separate `metric_results` table from the entity-relationship model. This wide-table design stores all metric outputs for a test case in a single row alongside the test case context, optimized for grid rendering and OLAP-ready denormalization. See `metrics-storage` spec for full schema and API details.
Status: **Implemented**

#### Scenario: MetricResult storage location
- **WHEN** the metric computation pipeline produces results
- **THEN** results SHALL be stored in `test_case_eval_summaries` (analytics DB), not in a separate normalized `metric_results` table

### Requirement: List metric declarations (stub)
The service SHALL provide a paginated endpoint to list metric declarations available for discovery. Each listed declaration SHALL include id, provider_id, name, display_name, description, and created_at. The endpoint MAY support an optional filter by provider_id. The endpoint MAY include the latest MetricDeclarationVersion's config_schema, input_schema, and output_schema for discovery (exact shape to be defined in implementation). When these schema fields are included, they SHALL be serialized as JSON objects (not as JSON strings). Previously seeded (stub) records SHALL have been removed by migration; the list SHALL contain only provider-synced metrics. The display_name field SHALL be nullable in the response (providers may omit it).
Status: **Partial** (provider_id and versioning added; filter/schemas optional)

#### Scenario: Empty catalog
- **WHEN** client calls `GET /api/v1/metric-declarations` and no metric declarations exist (e.g. no sync run yet or no providers configured)
- **THEN** system SHALL respond with HTTP 200 and an empty page result

#### Scenario: Pagination and sorting
- **WHEN** client calls `GET /api/v1/metric-declarations?page=<p>&size=<s>&sort=<field>[,<asc|desc>]` (repeatable)
- **THEN** system SHALL apply pagination and safe sorting using a whitelist of allowed fields

#### Scenario: Optional filter by provider_id
- **WHEN** client calls `GET /api/v1/metric-declarations?filter=providerId:eq:<id>` (using the existing generic filter mechanism)
- **THEN** system SHALL return only MetricDeclarations for that provider_id when the filter is present

#### Scenario: displayName included in response
- **WHEN** client calls `GET /api/v1/metric-declarations` and a declaration has a display_name
- **THEN** system SHALL include the displayName field in each response item

#### Scenario: displayName null when provider did not supply it
- **WHEN** client calls `GET /api/v1/metric-declarations` and a declaration has no display_name (provider omitted it or sync has not run yet)
- **THEN** system SHALL return null (or omit) the displayName field for that item

### Requirement: MetricDeclaration has provider identity
Each MetricDeclaration SHALL have a non-null provider_id. The system SHALL enforce UNIQUE(provider_id, name) so the same metric name from different providers is distinct. provider_id SHALL be set from configuration when declarations are synced from a metric provider.

#### Scenario: Same metric name from two providers
- **WHEN** two configured providers both expose a metric named "exact_match"
- **THEN** system SHALL store two MetricDeclaration rows (one per provider_id) and SHALL list both via the API

#### Scenario: Uniqueness constraint
- **WHEN** sync attempts to insert or update a MetricDeclaration with (provider_id, name) that already exists
- **THEN** system SHALL upsert (update existing) rather than fail with a duplicate key error

### Requirement: Get latest metric declaration version by declaration id
The service SHALL provide an endpoint GET /api/v1/metric-declarations/{id}/latest that returns the latest MetricDeclarationVersion for the metric declaration with the given id. Latest SHALL be determined by the greatest schema_version for that metric_declaration_id. The response SHALL include the version's id, metric_declaration_id, schema_version, config_schema, input_schema, output_schema, description, display_name, and created_at (or equivalent epoch-ms timestamp). The config_schema, input_schema, and output_schema fields in the response SHALL be serialized as JSON objects (not as JSON strings), consistent with how other JSONB-backed schema fields (e.g. test case data, test suite schemas) are returned by the API. The display_name field SHALL be nullable in the response.

#### Scenario: Latest version returned with object-typed schemas and displayName
- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and the metric declaration exists and has at least one version
- **THEN** system SHALL respond with HTTP 200 and the latest MetricDeclarationVersion where configSchema, inputSchema, and outputSchema are JSON objects and displayName reflects the value stored in that version (may be null)

#### Scenario: Empty schemas returned as empty objects
- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and the latest version has empty schemas (stored as `{}` in DB; columns are NOT NULL)
- **THEN** system SHALL return empty JSON objects for those fields (not empty strings or the string `"{}"`)

#### Scenario: Metric declaration not found
- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and no metric declaration exists with that id
- **THEN** system SHALL respond with HTTP 404

#### Scenario: No versions for declaration
- **WHEN** client calls GET /api/v1/metric-declarations/{id}/latest and the metric declaration exists but has no MetricDeclarationVersion rows
- **THEN** system SHALL respond with HTTP 404

### Requirement: List every metric declaration with its latest version
The service SHALL provide an endpoint GET /api/v1/metric-declarations/versions/latest that returns every metric declaration together with its latest MetricDeclarationVersion as a JSON array. Latest SHALL be determined per metric_declaration_id by the greatest schema_version. Each array item SHALL be the metric declaration - id (the declaration's id), provider_id, name, display_name, description, created_at - with the latest version nested under `latestVersion`, whose shape SHALL match the single-version response of GET /api/v1/metric-declarations/{id}/latest (id, metric_declaration_id, schema_version, config_schema, input_schema, output_schema, description, display_name, created_at). config_schema, input_schema, and output_schema SHALL be serialized as JSON objects (not JSON strings). display_name SHALL be nullable at both levels; because responses are serialized with NON_NULL inclusion, a null display_name is omitted from the payload rather than emitted as null. Metric declarations that have no MetricDeclarationVersion rows SHALL be omitted from the array. The array SHALL be ordered by metric_declaration_id.

#### Scenario: One latest version per declaration
- **WHEN** client calls GET /api/v1/metric-declarations/versions/latest and several metric declarations each have one or more versions
- **THEN** system SHALL respond with HTTP 200 and exactly one item per metric declaration, each item's id being the declaration's id and its latestVersion.schemaVersion being that declaration's greatest schema_version

#### Scenario: Declaration identity fields present without a second request
- **WHEN** client calls GET /api/v1/metric-declarations/versions/latest
- **THEN** each item SHALL carry the declaration's provider_id and name (and display_name, description, created_at), so no follow-up GET /api/v1/metric-declarations/{id} is needed

#### Scenario: Shared column names resolved per table
- **WHEN** client calls GET /api/v1/metric-declarations/versions/latest and a declaration and its latest version hold different values for the columns that exist in both tables (id, display_name, description, created_at_ms)
- **THEN** the item's top-level values SHALL come from the metric_declarations row and the latestVersion values SHALL come from the metric_declaration_versions row

#### Scenario: Declarations without versions are omitted
- **WHEN** client calls GET /api/v1/metric-declarations/versions/latest and some metric declarations have no MetricDeclarationVersion rows
- **THEN** system SHALL omit those declarations from the array rather than returning items with a null latestVersion or responding 404

#### Scenario: Empty catalog
- **WHEN** client calls GET /api/v1/metric-declarations/versions/latest and no MetricDeclarationVersion rows exist
- **THEN** system SHALL respond with HTTP 200 and an empty array

#### Scenario: Schemas returned as JSON objects
- **WHEN** client calls GET /api/v1/metric-declarations/versions/latest
- **THEN** each item's latestVersion.configSchema, latestVersion.inputSchema, and latestVersion.outputSchema SHALL be JSON objects (empty objects when the stored schema is `{}`), consistent with GET /api/v1/metric-declarations/{id}/latest

## Implementation Notes
- Vision references: `docs/design/entity-relationship-model.md` (metric entities, versioning, recalculation policy), `docs/design/infrastructure-architecture.md` (metrics job + services).
- Latest-per-declaration query: `MetricDeclarationVersionRepository.findLatestPerMetricDeclaration()` uses Postgres `SELECT DISTINCT ON (metric_declaration_id) ... ORDER BY metric_declaration_id, schema_version DESC`, served by `uq_metric_declaration_versions_declaration_version` as a single index scan (no `MAX(schema_version)` self-join). The `ORDER BY` is the selection criterion, not cosmetic: `DISTINCT ON` keeps the first row per key. No tiebreaker column is needed because that index is unique. The response is therefore ordered by `metric_declaration_id`; a different response order would require wrapping this query in a subquery, since `DISTINCT ON` forces `ORDER BY` to lead with the distinct key.
- The same query INNER JOINs `metric_declarations` and returns the composite `MetricDeclarationWithLatestVersion`, so the declaration's fields come from the one round trip rather than a second query per item. That join - not a filter - is what omits version-less declarations, and being many-to-one on the distinct key it leaves `DISTINCT ON` yielding exactly one row per declaration. The joined record is split back into the two typed jOOQ records with `record.into(TABLE)`, which reuses both existing `*RecordMapper` components unchanged; see the `into(TABLE)` section of [typed-sql-dsl pattern doc](../../../docs/patterns/jooq-typed-sql-dsl.md) for why no column may be aliased there.
- Version uniqueness is a UNIQUE **index** (`V1.30`), not a UNIQUE constraint, and it replaced the non-unique V1.9 index on the same columns: a constraint cannot declare `schema_version DESC`, and only a DESC second column can serve the `DISTINCT ON` ordering above, so a constraint would have left two indexes over the same pair. Nothing FK-references the pair, so an index is sufficient. `PostgresMetricDeclarationVersionRepository.save` locks the parent `metric_declarations` row (`SELECT ... FOR UPDATE`) before computing `MAX(schema_version) + 1`, because `MetricProviderSyncJob` runs on every instance without leader election and `syncOne` is one transaction per provider - an unserialized lost update would now abort that whole provider's sync with a 23505 instead of silently writing a duplicate row.
- Metric provider client: config_schema, input_schema, and output_schema are kept as `String` in the DB model (`MetricDeclarationVersion`) and normalized to string for storage and comparison via a custom Jackson deserializer. In response DTOs (`MetricDeclarationVersionResponseDto`), these fields are typed as `Map<String, Object>` so the REST API returns them as JSON objects. Conversion between `String` (model) and `Map<String, Object>` (DTO) is handled by `JsonbMapper.mapJsonSchema`, consistent with other JSONB-backed schema fields (e.g. test case data, test suite schemas).

## Open Questions / TODO
- Decide append-vs-replace policy for recalculated MetricResults (design doc notes this as open).
- Define sync mechanism between backend and metrics services (scheduled/manual) and required configuration knobs.

