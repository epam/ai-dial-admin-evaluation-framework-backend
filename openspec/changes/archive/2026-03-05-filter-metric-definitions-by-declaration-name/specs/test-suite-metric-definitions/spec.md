## MODIFIED Requirements

### Requirement: List Test Suite Metric Definitions
The system SHALL allow listing TSMDs for a test suite via `GET /api/v1/test-suites/{suiteId}/metric-definitions` with pagination, filtering, and sorting.

#### Scenario: Paginated list
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?page=0&size=50`
- **THEN** system SHALL return a paginated response with TSMDs belonging to that suite

#### Scenario: Filter by name
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?filter=name:contains:accuracy`
- **THEN** system SHALL return only TSMDs whose name matches the filter

#### Scenario: Filter by metric declaration name (exact match)
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?filter=metricDeclarationName:eq:Accuracy`
- **THEN** system SHALL return only TSMDs whose underlying metric declaration name equals "Accuracy"

#### Scenario: Filter by metric declaration name (substring)
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?filter=metricDeclarationName:contains:accur`
- **THEN** system SHALL return only TSMDs whose underlying metric declaration name contains the substring (case-insensitive)

#### Scenario: Filter by metric declaration name (not equal)
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?filter=metricDeclarationName:ne:Latency`
- **THEN** system SHALL return only TSMDs whose underlying metric declaration name is not "Latency"

#### Scenario: Sort by name
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?sort=name,asc`
- **THEN** system SHALL return TSMDs sorted by name ascending

#### Scenario: Sort by createdAt
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?sort=createdAt,desc`
- **THEN** system SHALL return TSMDs sorted by creation timestamp descending

#### Scenario: Default sort order
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions` without sort parameter
- **THEN** system SHALL return results sorted by `createdAt,desc`

#### Scenario: Invalid filter field
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions?filter=unknownField:eq:value`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Empty list
- **WHEN** client calls `GET /api/v1/test-suites/{suiteId}/metric-definitions` and the suite has no TSMDs
- **THEN** system SHALL respond with HTTP 200 and an empty page result

### Requirement: TSMD response DTO shape
The TSMD response SHALL include: `id` (UUID), `testSuiteId` (UUID), `metricDeclarationId` (UUID), `metricDeclarationVersionId` (UUID), `name` (String), `metricDeclarationName` (String — the human-readable name of the referenced metric declaration), `configBindings` (list of binding objects), `inputBindings` (list of binding objects), `createdAt` (epoch ms), `updatedAt` (epoch ms). The `configBindings` and `inputBindings` fields SHALL be serialized as JSON arrays (not as raw JSON strings).

#### Scenario: Response includes all fields
- **WHEN** client retrieves a TSMD via GET
- **THEN** the response body SHALL contain all specified fields with correct types, including `metricDeclarationName`

#### Scenario: metricDeclarationName populated on create
- **WHEN** client creates a TSMD via POST
- **THEN** the response body SHALL include `metricDeclarationName` with the name of the referenced metric declaration

#### Scenario: metricDeclarationName populated on update
- **WHEN** client updates a TSMD via PUT (potentially changing the metric declaration reference)
- **THEN** the response body SHALL include `metricDeclarationName` reflecting the current metric declaration
