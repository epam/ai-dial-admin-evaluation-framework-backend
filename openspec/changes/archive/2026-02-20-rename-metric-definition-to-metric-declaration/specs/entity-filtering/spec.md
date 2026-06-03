# Entity Filtering (Delta)

Delta for change **rename-metric-definition-to-metric-declaration**: update list-endpoint naming from MetricDefinitions to MetricDeclarations.

## MODIFIED Requirements

### Requirement: List endpoints covered by this spec
This spec applies to list endpoints that support pagination, structured filtering, and sort/filter parameter limits. The endpoints in scope SHALL be: TestSuites, TestCases, and **MetricDeclarations** (replacing the previous reference to MetricDefinitions).

#### Scenario: MetricDeclarations list endpoint
- **WHEN** client calls a MetricDeclarations list endpoint (e.g. `GET /api/v1/metric-declarations`) with pagination, filter, or sort parameters
- **THEN** system SHALL apply the same pagination, filtering, and parameter-limit rules as for TestSuites and TestCases
