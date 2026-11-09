# OpenAPI Query Parameter Documentation

## Purpose

Keep registered REST list/filter descriptions and Swagger examples aligned with typed endpoint whitelists and pagination configuration; document controller-owned CSV and response-expansion parameters separately.

Status: **Implemented**

## Requirements

### Requirement: Filter parameter descriptions SHALL be auto-generated from FilterWhitelists
Status: **Implemented**
The system SHALL auto-generate OpenAPI parameter descriptions for `filter` query parameters by reading `FilterWhitelists` at startup. The generated description SHALL include a field-operator matrix showing each allowed field, its type, supported operators, and an example value.

#### Scenario: Filter description includes field-operator matrix
- **WHEN** the OpenAPI spec is generated for a list endpoint that supports filtering
- **THEN** the `filter` parameter description SHALL contain a table with columns: Field, Type, Operators, Example
- **AND THEN** each row SHALL correspond to a field from the endpoint's `FilterSpec`

#### Scenario: Filter description includes format and semantics
- **WHEN** the OpenAPI spec is generated for a list endpoint that supports filtering
- **THEN** the `filter` parameter description SHALL document the format as `field:operator:value`
- **AND THEN** the description SHALL state that multiple filters are combined with AND logic
- **AND THEN** the description SHALL state the maximum number of filter parameters (32)

#### Scenario: Field type labels use hints
- **WHEN** a filter field has type LONG and its name matches a timestamp pattern (createdAt, updatedAt, startedAt, completedAt)
- **THEN** the type label SHALL be `timestamp (epoch ms)`
- **WHEN** a filter field has type LONG and does not match a timestamp pattern
- **THEN** the type label SHALL be `integer`
- **WHEN** a filter field has type BOOLEAN
- **THEN** the type label SHALL be `boolean (true/false)`
- **WHEN** a filter field has type UUID
- **THEN** the type label SHALL be `uuid`
- **WHEN** a filter field has type JSONB_STRING
- **THEN** the type label SHALL be `jsonb string`

#### Scenario: New filter field automatically appears in docs
- **WHEN** a developer adds a new field entry to a `FilterWhitelists` constant
- **THEN** the generated OpenAPI spec SHALL include that field in the corresponding endpoint's filter description without any additional documentation changes

### Requirement: Sort parameter descriptions SHALL be auto-generated from SortWhitelists
Status: **Implemented**
The system SHALL auto-generate OpenAPI parameter descriptions for `sort` query parameters by reading `SortWhitelists` at startup. The generated description SHALL list all sortable fields and the default sort order.

#### Scenario: Sort description includes sortable fields and default
- **WHEN** the OpenAPI spec is generated for a list endpoint that supports sorting
- **THEN** the `sort` parameter description SHALL list all sortable field names from the endpoint's `SortSpec`
- **AND THEN** the description SHALL state the default sort (e.g., `createdAt,desc`)
- **AND THEN** the description SHALL document the format as `field[,asc|desc]`
- **AND THEN** the description SHALL state the maximum number of sort parameters (32)

#### Scenario: New sort field automatically appears in docs
- **WHEN** a developer adds a new field entry to a `SortWhitelists` constant
- **THEN** the generated OpenAPI spec SHALL include that field in the corresponding endpoint's sort description without any additional documentation changes

### Requirement: Pagination parameter descriptions SHALL reflect configuration
Status: **Implemented**
The system SHALL auto-generate OpenAPI parameter descriptions for `page` and `size` query parameters using values from `PaginationProperties`.

#### Scenario: Page parameter description
- **WHEN** the OpenAPI spec is generated for a list endpoint with offset-based pagination
- **THEN** the `page` parameter description SHALL state that pages are 0-indexed and the default is 0

#### Scenario: Size parameter description
- **WHEN** the OpenAPI spec is generated for a list endpoint with offset-based pagination
- **THEN** the `size` parameter description SHALL state the default value from configuration and the maximum allowed value

### Requirement: Cursor pagination parameter SHALL be documented
Status: **Implemented**
The system SHALL provide a description for the `cursor` query parameter on analytics endpoints that explains cursor-based pagination.

#### Scenario: Cursor parameter description
- **WHEN** the OpenAPI spec is generated for the analytics list endpoint
- **THEN** the `cursor` parameter description SHALL state that the value is an opaque cursor obtained from `nextCursor` in a previous response
- **AND THEN** the description SHALL state that omitting the cursor returns the first page

### Requirement: Special query parameters SHALL have enriched descriptions
Status: **Implemented**
Controller annotations SHALL describe the following endpoint-specific parameters. `OpenApiQueryParamCustomizer` only replaces descriptions/examples for `filter`, `sort`, `page`, `size`, and `cursor`; it SHALL leave these special parameters unchanged.

#### Scenario: includeTotalCount parameter
- **WHEN** the OpenAPI spec includes the `includeTotalCount` parameter
- **THEN** its description SHALL state the default value (false) and explain that enabling it adds `totalElements` and `totalPages` to the response

#### Scenario: includeWarnings parameter
- **WHEN** the OpenAPI spec includes the `includeWarnings` parameter
- **THEN** its description SHALL state the default value (false) and explain that enabling it includes `validationWarnings` in the response

#### Scenario: delimiter parameter
- **WHEN** the OpenAPI spec includes the `delimiter` parameter on CSV endpoints
- **THEN** its description SHALL state the default value (`,`) and that it must be a single ASCII character

#### Scenario: importMode parameter
- **WHEN** the OpenAPI spec includes the `importMode` parameter
- **THEN** its description SHALL list all enum values (OVERRIDE, APPEND, MERGE) with a brief explanation of each

#### Scenario: conflictStrategy parameter
- **WHEN** the OpenAPI spec includes the `conflictStrategy` parameter
- **THEN** its description SHALL list all enum values (FAIL, SKIP, OVERRIDE) with a brief explanation of each

### Requirement: Path-to-whitelist registry SHALL cover all list endpoints
Status: **Implemented**
The customizer SHALL maintain an explicit path-to-whitelist registry. It SHALL customize only registered paths and existing operation parameters; adding a new endpoint requires a registry entry rather than automatic discovery of arbitrary list endpoints.

#### Scenario: All offset-paginated list endpoints are registered
- **WHEN** the customizer processes the OpenAPI spec
- **THEN** the following paths SHALL be mapped to their respective whitelists: `/api/v1/datasets`, `/api/v1/test-suites`, `/api/v1/datasets/{datasetId}/test-cases`, `/api/v1/test-suites/{testSuiteId}/metric-definitions`, `/api/v1/metric-declarations`, `/api/v1/test-suite-runs`
- **AND** the datasets filter description SHALL explain that this list includes PUBLIC datasets only and that `visibility` is not filterable

#### Scenario: Analytics cursor-paginated endpoint is registered
- **WHEN** the customizer processes the OpenAPI spec
- **THEN** `/api/v1/analytics/test-case-results` and `/api/v1/analytics/eval-summaries` SHALL be mapped to their filter whitelists with cursor pagination mode and no sort

#### Scenario: CSV export endpoint filter is registered
- **WHEN** the customizer processes the OpenAPI spec
- **THEN** `/api/v1/datasets/{datasetId}/test-cases/export.csv` SHALL be mapped to the test cases filter whitelist with no sort and no pagination
- **AND** `/api/v1/analytics/eval-summaries/export/preview` SHALL use the eval summaries filter whitelist without pagination

#### Scenario: Snapshot listing and its supported alias
- **WHEN** the customizer processes `/api/v1/run-metric-snapshots` or the currently registered deprecated `/api/v1/analytics/run-metric-snapshots` alias
- **THEN** it SHALL apply the run-metric-snapshot filter whitelist without pagination or sort, including the note that `runId` is required

### Requirement: Parameter examples SHALL be provided for filter and sort
Status: **Implemented**
The system SHALL set example values on filter and sort parameters so Swagger UI pre-fills them in "Try it out" mode.

#### Scenario: Filter parameter example
- **WHEN** the OpenAPI spec is generated for a list endpoint with filtering
- **THEN** the `filter` parameter SHALL have an example using the alphabetically first STRING field that supports `co` (e.g., `name:co:test`), or a generated example for the alphabetically first allowed field if none supports `co`

#### Scenario: Sort parameter example
- **WHEN** the OpenAPI spec is generated for a list endpoint with sorting
- **THEN** the `sort` parameter SHALL have an example value using the default sort (e.g., `createdAt,desc`)

## Implementation notes

- [OpenApiQueryParamCustomizer](../../../src/main/java/com/epam/aidial/evaluation/configuration/OpenApiQueryParamCustomizer.java) — exact registry, five managed parameters, dataset/snapshot notes.
- [QueryParamDescriptionGenerator](../../../src/main/java/com/epam/aidial/evaluation/configuration/QueryParamDescriptionGenerator.java) — matrix, type labels, enum-backed operator catalog, examples, configured size descriptions.
- [TestCaseController](../../../src/main/java/com/epam/aidial/evaluation/web/controller/TestCaseController.java) — special parameter annotations; no `includeEnabled` export parameter exists.
