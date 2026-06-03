# Test Cases (Delta)

Delta for change **rename-metric-definition-to-metric-declaration**: rename metric-definitions stub to metric-declarations; update controller and table references.

## MODIFIED Requirements

### Requirement: MetricDeclarations read-only stub
The service SHALL provide read-only list of seeded metric declarations (Accuracy, Latency, Relevance). GET /api/v1/metric-declarations (paginated, filterable, sortable); GET .../metric-declarations/{id}.

#### Scenario: List metric declarations
- **WHEN** client calls `GET /api/v1/metric-declarations` with optional pagination, filter, sort
- **THEN** system SHALL return a paginated list of MetricDeclarations (or empty page)

#### Scenario: Get metric declaration by ID
- **WHEN** client calls `GET /api/v1/metric-declarations/{id}` with a valid declaration ID
- **THEN** system SHALL return the MetricDeclaration with id, name, description, createdAt

#### Scenario: Filter and sort metric declarations
- **WHEN** client calls `GET /api/v1/metric-declarations?filter=...&sort=...`
- **THEN** system SHALL apply filtering and sorting per entity-filtering spec

## Implementation Notes (delta)

- Controllers: TestCaseController, TestSuiteController (revalidation endpoints), **MetricDeclarationController** (replacing MetricDefinitionController).
- DB: test_cases, revalidation_tasks, **metric_declarations** (table renamed from metric_definitions in meta DB).
