## ADDED Requirements

### Requirement: List test suites depending on a dataset
The system SHALL provide `GET /api/v1/datasets/{datasetId}/test-suites` that returns the test suites bound to the dataset — every suite whose `dataset_id` equals the path id. The response SHALL be a plain JSON array of `DatasetDependentSuiteDto` items, each carrying exactly `id` (UUID), `name` (String), and `description` (String, nullable). The endpoint SHALL NOT be paginated, filterable, or sortable. Visibility SHALL NOT affect this endpoint — it lists dependents for both PUBLIC and PRIVATE datasets.
Status: **Planned**

#### Scenario: Dataset with bound suites returns their summaries
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-suites` for an existing dataset that has one or more bound test suites
- **THEN** system SHALL respond with HTTP 200 and a JSON array containing one `DatasetDependentSuiteDto` per bound suite, each with `id`, `name`, and `description` matching the suite, and SHALL NOT include any other suite fields

#### Scenario: Dataset with no bound suites returns empty array
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-suites` for an existing dataset that has no bound test suites
- **THEN** system SHALL respond with HTTP 200 and an empty JSON array `[]`

#### Scenario: Unknown dataset returns 404
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-suites` for a dataset id that does not exist
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

#### Scenario: Lists dependents of a PRIVATE dataset
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-suites` for an existing PRIVATE dataset bound to a suite
- **THEN** system SHALL respond with HTTP 200 and include the bound suite's summary; visibility SHALL NOT block the listing

### Requirement: DatasetDependentSuiteDto wire shape
The system SHALL expose `DatasetDependentSuiteDto` as the response element type for `GET /api/v1/datasets/{datasetId}/test-suites`. The DTO SHALL contain exactly three fields — `id` (UUID), `name` (String), `description` (String, nullable) — and SHALL NOT expose the full `TestSuiteResponseDto` field set.
Status: **Planned**

#### Scenario: DatasetDependentSuiteDto fields
- **WHEN** client receives a `DatasetDependentSuiteDto`
- **THEN** the payload SHALL include `id`, `name`, and `description`, and SHALL NOT include suite fields such as `suiteType`, `datasetId`, `version`, `responseColumns`, `inputBindings`, `validationWarnings`, `createdBy`, `createdAt`, or `updatedAt`

#### Scenario: Null suite description serializes as null
- **WHEN** a bound suite has no `description` and client receives its `DatasetDependentSuiteDto`
- **THEN** the `description` field SHALL be present with value `null`

### Requirement: OpenAPI documentation for the dataset dependent-suites endpoint
`GET /api/v1/datasets/{datasetId}/test-suites` SHALL carry OpenAPI annotations including an operation summary, the `DatasetDependentSuiteDto` array response schema, a response example, and documented 200 and 404 responses, under the existing "Datasets" tag.
Status: **Planned**

#### Scenario: Swagger UI shows the dependent-suites endpoint
- **WHEN** user opens Swagger UI
- **THEN** the `GET /api/v1/datasets/{datasetId}/test-suites` operation SHALL appear under the "Datasets" tag with a summary describing the listing of dependent suites, an array-of-`DatasetDependentSuiteDto` response schema with an example, and documented 200 and 404 responses

## Implementation notes

- New endpoint method on `web.controller.DatasetController` (`@GetMapping("/{id}/test-suites")`), alongside the existing `/{id}/test-cases` and `/{id}/revalidation-tasks` sub-resources.
- New read method on `service.domain.DatasetService` that performs the dataset existence check (reusing the existing not-found path that yields HTTP 404 `NOT_FOUND`) and delegates to `TestSuiteService` (cross-domain read via the owning service per the best-practices spec; `DatasetService` already injects `TestSuiteService`).
- New method on `service.domain.TestSuiteService` returning `List<DatasetDependentSuiteDto>`, backed by a new selective-column projection query (id, name, description only) on `data.db.repository.TestSuiteRepository` / `PostgresTestSuiteRepository` filtered by `TEST_SUITES.DATASET_ID` — see `docs/patterns/selective-column-projection.md`.
- New pure-carrier projection `data.db.model.TestSuiteSummary` (id, name, description) keeps DTOs out of the data layer; new DTO `service.domain.dto.DatasetDependentSuiteDto`.
- No DB schema change, no jOOQ regeneration, no new config property, no `FilterWhitelists`/`SortWhitelists`/`OpenApiQueryParamCustomizer` entry (non-paginated).
