## MODIFIED Requirements

### Requirement: List endpoints covered by this spec
This spec applies to list endpoints that support pagination, structured filtering, and sort/filter parameter limits. The endpoints in scope SHALL be: TestSuites, TestCases, MetricDeclarations, and TestSuiteMetricDefinitions.

#### Scenario: MetricDeclarations list endpoint
- **WHEN** client calls a MetricDeclarations list endpoint (e.g. `GET /api/v1/metric-declarations`) with pagination, filter, or sort parameters
- **THEN** system SHALL apply the same pagination, filtering, and parameter-limit rules as for TestSuites and TestCases

#### Scenario: TestSuiteMetricDefinitions list endpoint
- **WHEN** client calls a TestSuiteMetricDefinitions list endpoint (e.g. `GET /api/v1/test-suites/{suiteId}/metric-definitions`) with pagination, filter, or sort parameters
- **THEN** system SHALL apply the same pagination, filtering, and parameter-limit rules as for other list endpoints
