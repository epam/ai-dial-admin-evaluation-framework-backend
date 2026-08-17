## MODIFIED Requirements

### Requirement: Create and manage TestCases inside a Dataset
The service SHALL manage TestCases as children of a Dataset with full CRUD operations. TestCases store a unified `data` map (Map<String, Object>). Per-case overrides of suite-level templates and bindings are no longer supported; test cases carry only their identity, the data map, and validity metadata. A test case carries no per-suite participation state: which of a dataset's test cases a given suite runs is decided solely by that suite's `testCaseFilter` (see `suite-test-case-filter`).
Status: **Planned**

#### Scenario: Create a test case
- **WHEN** client calls `POST /api/v1/datasets/{datasetId}/test-cases` with a valid body
- **THEN** system SHALL create a TestCase linked to the Dataset; require `testCaseName`; default `data` to `{}`; calculate `valid` from the dataset's `testCaseSchema`

#### Scenario: List test cases
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-cases`
- **THEN** system SHALL return a paginated list of TestCases under the dataset

#### Scenario: Sort and filter test cases
- **WHEN** client calls `GET .../test-cases?sort=...&filter=...`
- **THEN** system SHALL apply sorting and filtering per entity-filtering spec; supported filter fields: `testCaseName`, `valid`, `createdAt`; supported sort fields: `testCaseName`, `createdAt`, `updatedAt`, `valid` (the `enabled` field is removed — a suite narrows the test cases it runs via its `testCaseFilter`, see the `suite-test-case-filter` spec)

#### Scenario: Pagination with optional total count
- **WHEN** client calls `GET .../test-cases?page=0&size=50&includeTotalCount=true`
- **THEN** system SHALL return `totalElements` and `totalPages`; without param, omit them

#### Scenario: Range filter with multiple conditions
- **WHEN** client calls `GET .../test-cases?filter=createdAt:ge:1000&filter=createdAt:le:2000`
- **THEN** system SHALL return test cases in the time range

#### Scenario: Invalid filter field
- **WHEN** client calls `GET .../test-cases?filter=unknownField:eq:value`
- **THEN** system SHALL respond with HTTP 400 and include invalid field in error details

#### Scenario: Default sort order
- **WHEN** client calls `GET .../test-cases` without sort parameter
- **THEN** system SHALL return results sorted by `createdAt,desc`

#### Scenario: Get test case by id
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-cases/{testCaseId}` for an existing TestCase
- **THEN** system SHALL return the TestCase including `data`; no `requestTemplateOverride` or `inputBindingsOverride` fields are present in the response

#### Scenario: Get resolved request for test case (under suite context)
- **WHEN** client calls `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request`
- **THEN** system SHALL return the resolved request (URL, query params, headers, body) using the suite's template/bindings and the test case's `data`; the `testCaseId` MUST belong to a dataset referenced by `testSuiteId` (otherwise HTTP 404). The endpoint stays suite-scoped because the resolved request depends on suite-level execution config; no per-case overrides apply.

#### Scenario: Update test case (full replacement)
- **WHEN** client calls `PUT .../datasets/{datasetId}/test-cases/{testCaseId}` with a valid body
- **THEN** system SHALL replace the TestCase, recalculate `valid` against the dataset's schema, update `updatedAt`

#### Scenario: Delete single test case
- **WHEN** client calls `DELETE .../datasets/{datasetId}/test-cases/{testCaseId}`
- **THEN** system SHALL delete the TestCase and return HTTP 204

#### Scenario: Bulk delete test cases
- **WHEN** client calls `DELETE .../datasets/{datasetId}/test-cases` with optional filter
- **THEN** system SHALL delete matching TestCases (or all if no filter) and return count of deleted items

### Requirement: Mutable TestSuite fields
The service SHALL allow updating mutable suite fields (e.g., `deploymentRef`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `datasetId`, `testCaseFilter`). Suite PUTs SHALL trigger synchronous suite-level re-validation only; suite PUTs SHALL NOT spawn an async `RevalidationTask`. Async tasks are spawned only by dataset PUTs that mutate `testCaseSchema` — see the `datasets` and `test-suites` specs.
Status: **Planned**

#### Scenario: Update endpointRef triggers synchronous suite-level re-validation
- **WHEN** client updates `endpointRef` schema on an existing suite
- **THEN** system SHALL re-run synchronous suite-level validation (`SuiteValidationService`) against the referenced dataset's schema, update `isValid`/`validationWarnings`, return HTTP 200 with the updated suite; system SHALL NOT spawn an async `RevalidationTask` and SHALL NOT return HTTP 202
