# Query Schema Discovery

## Purpose

The structured query DSL (see `structured-query-model`) is body-delivered: a client posts an envelope
naming an `entity` and flat field names in its `filter`/`select`/`sort`/`group_by` sections. To
compose a valid query the client must first discover which entities are queryable and which flat
field names each one exposes — including JSONB-backed fields whose flattening depends on a concrete
instance (e.g. an `eval_summaries` row's `data:`/`response:`/`metric:` columns are defined by the
originating test suite run's snapshot).

This capability defines the read-only discovery surface under the experimental `/api/v1/queries`
namespace: an entity catalog, an instance-independent base schema (JSONB fields listed as-is), an
instance-specific detailed schema for complex entities (per-instance JSONB flattening derived from a
test suite run snapshot), and the discovery error contract. Entities contribute their schema through
a provider SPI collected by a registry, so adding a queryable entity is a matter of registering a
provider rather than editing the web layer.

## Requirements

### Requirement: Queryable entity catalog
The system SHALL expose, at `GET /api/v1/queries/entities`, the list of entities that can be named as
the `entity` of a structured query. Each catalog item SHALL carry the entity wire `name`, a `complex`
flag, and a `schemaIdField` — the name of the query parameter whose value selects a concrete instance
for the detailed schema endpoint — which SHALL be present for complex entities and null for simple
entities. Entities SHALL be listed in stable alphabetical order by name.
Status: **Implemented**

#### Scenario: Catalog lists simple and complex entities
- **WHEN** `GET /api/v1/queries/entities` is called
- **THEN** the response lists each registered entity in alphabetical order, the simple entity
  `test_suites` with `complex=false` and a null `schemaIdField`, and the complex entity
  `eval_summaries` with `complex=true` and `schemaIdField="test_suite_run_id"`

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

### Requirement: Instance-specific detailed schema for complex entities
The system SHALL expose, at `GET /api/v1/queries/entities/schema/{name}/detailed`, the flat schema of
a complex entity for a concrete instance, replacing the flattenable JSONB fields of the base schema
with per-instance field families. For `eval_summaries`, the instance is a test suite run and the
schema SHALL be derived from that run's frozen snapshot: `data:<field>` families from the snapshot's
test-case schema, `response:<column>` families from the snapshot's response columns, and per metric of
the run's latest computation a numeric `metric:<name>:<field>` family plus an opaque object
`metricInfo:<name>`. The instance SHALL be selected by `test_suite_run_id` (the advertised
`schemaIdField`); when only `test_suite_id` is supplied the suite's latest run SHALL be used.
Status: **Implemented**

#### Scenario: Detailed schema derived from the run snapshot
- **WHEN** `GET /api/v1/queries/entities/schema/eval_summaries/detailed?test_suite_run_id=<runId>` is
  called for a run whose snapshot defines a test-case field `question`, a response column `answer`,
  and a metric `Accuracy`
- **THEN** the response includes `data:question`, `response:answer`, `metric:Accuracy:<field>`, and
  `metricInfo:Accuracy`, and does not include the unflattened `test_case_data`, `extracted_columns`,
  `metric_values`, or `metric_infos` fields

#### Scenario: Suite id resolves the latest run
- **WHEN** the detailed schema is requested with only `test_suite_id=<suiteId>`
- **THEN** the schema is derived from that suite's most recent run's snapshot

#### Scenario: Run without a snapshot is rejected
- **WHEN** the detailed schema is requested for a run that has no suite snapshot (a legacy run created
  before the snapshot model)
- **THEN** the request is rejected with a validation error and no schema is returned

### Requirement: `eval_summaries` response fields span the suite's request chain
The `eval_summaries` instance-specific detailed schema SHALL derive its `response::<column>` virtual fields from the **suite-wide union** of the run snapshot's response columns — the snapshot's own `responseColumns` followed by each `additionalRequests[i].responseColumns` in chain order — not from the snapshot's own `responseColumns` alone. Because response-column names are globally unique across a suite's chain, the union SHALL yield no duplicate field name, and each field SHALL keep its declared type and its `extracted_columns` physical source exactly as a suite-level column does. A run of a suite without `additionalRequests` SHALL produce the identical field list it produced before this change.

The new physical columns `request_index` and `total_requests` on `test_case_eval_summaries` SHALL become queryable base fields automatically, through the existing rule that the base schema is derived from the entity's generated jOOQ table; no per-entity field enumeration SHALL be added for them.
Status: **Implemented**

#### Scenario: Detailed schema lists an additional request's response column
- **WHEN** `GET /api/v1/queries/entities/schema/eval_summaries/{runId}` is called for a run whose snapshot declares `configId` on the suite and `answer` on one additional request
- **THEN** the response SHALL list both `response::configId` and `response::answer`, each sourced from `extracted_columns`

#### Scenario: Single-request run's field list is unchanged
- **WHEN** the detailed schema is requested for a run of a suite with no `additionalRequests`
- **THEN** the `response::*` field list SHALL be exactly the snapshot's `responseColumns`, as before this change

#### Scenario: Request columns appear in the base schema without a code-level field list
- **WHEN** `GET /api/v1/queries/entities/schema/eval_summaries` is called after the analytics migration and jOOQ regeneration
- **THEN** `request_index` and `total_requests` SHALL be listed as integer base fields, derived from the generated table

### Requirement: Schema discovery error contract
The system SHALL reject invalid discovery requests with specific HTTP statuses: an unknown entity name
SHALL return 404; a detailed-schema request against a simple (non-complex) entity SHALL return 400; a
detailed-schema request that supplies neither a valid `test_suite_run_id` nor `test_suite_id`, or a
malformed instance id, SHALL return 400; an instance id that resolves to no existing run SHALL return
404.
Status: **Implemented**

#### Scenario: Unknown entity
- **WHEN** any schema endpoint is called with an entity name that is not registered
- **THEN** the response status is 404

#### Scenario: Detailed schema on a simple entity
- **WHEN** `GET /api/v1/queries/entities/schema/test_suites/detailed` is called
- **THEN** the response status is 400

#### Scenario: Malformed or missing instance id
- **WHEN** the detailed schema for `eval_summaries` is requested with no instance parameter, or with a
  `test_suite_run_id` that is not a UUID
- **THEN** the response status is 400

#### Scenario: Instance id resolves to no run
- **WHEN** the detailed schema for `eval_summaries` is requested with a well-formed `test_suite_run_id`
  that matches no existing run
- **THEN** the response status is 404

### Requirement: `test_cases` is a complex queryable entity
The system SHALL register `test_cases` as a complex queryable entity so clients can discover its
field schema and query test cases through the Structured Query DSL. The entity's catalog item SHALL
have `complex=true` and `schemaIdField="dataset_id"`. Its instance-independent base schema SHALL be
derived from the generated `test_cases` jOOQ table (with the `data` JSONB field listed as-is). Its
instance-specific detailed schema SHALL require a `dataset_id` parameter and SHALL flatten the JSONB
`data` field into `data::<field>` entries derived from that dataset's test-case schema, mapping each
declared field type to a query field type and preserving array-typed fields as `array`. A detailed
schema request without `dataset_id`, or naming a dataset that does not exist, SHALL follow the schema
discovery error contract.
Status: **Implemented**

#### Scenario: Catalog lists the test_cases complex entity
- **WHEN** `GET /api/v1/queries/entities` is called
- **THEN** the response lists `test_cases` with `complex=true` and `schemaIdField="dataset_id"`, in
  alphabetical order among the other entities

#### Scenario: Detailed schema flattens data fields for a dataset
- **WHEN** `GET /api/v1/queries/entities/schema/test_cases?dataset_id=<id>` is called for a dataset
  whose test-case schema declares fields `category` (string) and `tags` (array)
- **THEN** the response replaces the JSONB `data` field with flattened entries `data::category`
  (typed string) and `data::tags` (typed array), alongside the entity's base columns

An execute query over `test_cases` SHALL include a `dataset_id` equality filter (a `dataset_id = <uuid>`
comparison); the system uses it both to scope the returned rows to that dataset AND to type each
`data::<field>` binding (enabling array-aware `co`/`nc`). A query omitting the `dataset_id` filter, or
providing a non-UUID value, SHALL be rejected with HTTP 400.

#### Scenario: Ad-hoc query over test_cases executes
- **WHEN** a client `POST /api/v1/queries/execute` with `entity=test_cases`, a `dataset_id` equality
  filter, and a filter over `data::<field>` values
- **THEN** the system SHALL return the matching test-case rows scoped to that dataset

#### Scenario: Array-field CONTAINS over test_cases produces JSONB containment
- **WHEN** a client `POST /api/v1/queries/execute` with `entity=test_cases`, a `dataset_id` equality
  filter, and a `co` comparison on an array-typed `data::<field>`
- **THEN** the system SHALL translate `co` to JSONB element containment (consistent with the run path)
  and return the matching rows

#### Scenario: Execute over test_cases without dataset_id is rejected
- **WHEN** a client `POST /api/v1/queries/execute` with `entity=test_cases` but no `dataset_id`
  equality filter (or a non-UUID `dataset_id`)
- **THEN** the system SHALL respond with HTTP 400

## Implementation notes

- Controller: `experimental.query.web.QuerySchemaController` (`/api/v1/queries`).
- Registry + SPI: `experimental.query.service.QueryEntityRegistry`, `QueryableEntitySchemaProvider`.
- Base schema derivation: `experimental.query.service.JooqTableSchemaResolver` (+ `QueryFieldBinding`).
- Providers: `TestSuitesSchemaProvider` (simple; appends 6 virtual `deployment_ref::*` and
  `mcp_deployment_ref::*` sub-field entries to the jOOQ-derived base schema), `EvalSummariesSchemaProvider` (complex, run-snapshot
  derived via `TestSuiteRunService` + `RunMetricSnapshotRepository`; families mirror the CSV export
  column planner), `TestCasesSchemaProvider` (complex, dataset-scoped; models
  `EvalSummariesSchemaProvider`; dataset schema loaded via `DatasetService`). DTOs: `QueryEntityDto`,
  `QueryEntitySchemaDto`, `QuerySchemaFieldDto`, `QueryFieldType`.
- Error mapping: `EntityNotFoundException` → 404, `ValidationException` → 400 via the global handler.
- Tests: `QuerySchemaDiscoveryFunctionalTests`, `EvalSummariesSchemaProviderTest`,
  `QueryEntityRegistryTest`, `JooqTableSchemaResolverTest`, `TestSuitesSchemaProviderTest`,
  `PostgresTestSuiteEntityResolverTest`.
- `test_cases` execute path: `TestCaseQueryRepository` + `PostgresTestCaseQueryRepository` bind
  `test_cases` to the `TEST_CASES` table on `@Qualifier("metaDsl")`, delegating to
  `StructuredQueryExecutor`. Type-aware flattened bindings produced by `TestCaseFieldBindingsBuilder`.
