## ADDED Requirements

### Requirement: Filter parameter descriptions SHALL be auto-generated from FilterWhitelists
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
The system SHALL auto-generate OpenAPI parameter descriptions for `page` and `size` query parameters using values from `PaginationProperties`.

#### Scenario: Page parameter description
- **WHEN** the OpenAPI spec is generated for a list endpoint with offset-based pagination
- **THEN** the `page` parameter description SHALL state that pages are 0-indexed and the default is 0

#### Scenario: Size parameter description
- **WHEN** the OpenAPI spec is generated for a list endpoint with offset-based pagination
- **THEN** the `size` parameter description SHALL state the default value from configuration and the maximum allowed value

### Requirement: Cursor pagination parameter SHALL be documented
The system SHALL provide a description for the `cursor` query parameter on analytics endpoints that explains cursor-based pagination.

#### Scenario: Cursor parameter description
- **WHEN** the OpenAPI spec is generated for the analytics list endpoint
- **THEN** the `cursor` parameter description SHALL state that the value is an opaque cursor obtained from `nextCursor` in a previous response
- **AND THEN** the description SHALL state that omitting the cursor returns the first page

### Requirement: Special query parameters SHALL have enriched descriptions
The system SHALL provide detailed descriptions for all special query parameters including defaults, allowed values, and behavioral notes.

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

#### Scenario: includeEnabled parameter
- **WHEN** the OpenAPI spec includes the `includeEnabled` parameter on the CSV export endpoint
- **THEN** its description SHALL state the default value (false) and explain that enabling it adds the `enabled` column to the CSV output

### Requirement: Path-to-whitelist registry SHALL cover all list endpoints
The system SHALL maintain a registry mapping each list endpoint path to its corresponding `FilterSpec` and `SortSpec` constants.

#### Scenario: All offset-paginated list endpoints are registered
- **WHEN** the customizer processes the OpenAPI spec
- **THEN** the following paths SHALL be mapped to their respective whitelists: `/api/v1/test-suites`, `/api/v1/test-suites/{testSuiteId}/test-cases`, `/api/v1/metric-declarations`, `/api/v1/test-suite-runs`

#### Scenario: Analytics cursor-paginated endpoint is registered
- **WHEN** the customizer processes the OpenAPI spec
- **THEN** the path `/api/v1/analytics/test-case-results` SHALL be mapped to its filter whitelist with cursor pagination mode and no sort

#### Scenario: CSV export endpoint filter is registered
- **WHEN** the customizer processes the OpenAPI spec
- **THEN** the path `/api/v1/test-suites/{testSuiteId}/test-cases/export.csv` SHALL be mapped to the test cases filter whitelist with no sort and no pagination

### Requirement: Parameter examples SHALL be provided for filter and sort
The system SHALL set example values on filter and sort parameters so Swagger UI pre-fills them in "Try it out" mode.

#### Scenario: Filter parameter example
- **WHEN** the OpenAPI spec is generated for a list endpoint with filtering
- **THEN** the `filter` parameter SHALL have an example value using one of the endpoint's allowed fields (e.g., `name:contains:test`)

#### Scenario: Sort parameter example
- **WHEN** the OpenAPI spec is generated for a list endpoint with sorting
- **THEN** the `sort` parameter SHALL have an example value using the default sort (e.g., `createdAt,desc`)
