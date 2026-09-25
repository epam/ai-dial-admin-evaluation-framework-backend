## Why

Clients need one request to list a test suite's metric definitions together with their metric declaration and version schemas. The required joined repository query already exists but is not exposed through REST.

## What Changes

- Add `GET /api/v1/test-suites/{testSuiteId}/metric-definitions/aggregated`.
- Return an unpaginated JSON array using the existing aggregated response contract.
- Include all definitions in the suite, including disabled or invalid ones; return an empty array when none are found.
- Reuse the existing aggregate repository query and mapper.
- Add OpenAPI examples and functional coverage.
- Do not add filtering, sorting, pagination, DTO expansion, schema/configuration changes, or new packages.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `aggregated-metric-definition`: Add suite-wide listing of aggregated metric definitions.

## Impact

Affects the test-suite metric-definition controller, service, OpenAPI examples, and tests. No database, dependency, security, rollout, or configuration impact.