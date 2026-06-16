## MODIFIED Requirements

### Requirement: List test suite runs with filtering
The service SHALL provide `GET /api/v1/test-suite-runs` to list runs with filtering, sorting, and pagination. Filterable fields SHALL include: `testSuiteId` (UUID, `eq`/`in`), `id` (UUID, `eq`/`in`), `status` (STRING, `eq`/`ne`/`in`), `testRunName` (STRING, `eq`/`ne`/`co`/`in`), `createdAt` (LONG epoch ms, `gt`/`gte`/`lt`/`lte`), `startedAt` (LONG epoch ms, `gt`/`gte`/`lt`/`lte`), `completedAt` (LONG epoch ms, `gt`/`gte`/`lt`/`lte`).

#### Scenario: Filter by testSuiteId
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=testSuiteId:eq:<uuid>`
- **THEN** system SHALL return only runs belonging to that test suite

#### Scenario: Filter by id (equality)
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=id:eq:<uuid>`
- **THEN** system SHALL return only the run with that exact id

#### Scenario: Filter by id (set membership)
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=id:in:<uuid1>,<uuid2>`
- **THEN** system SHALL return only runs whose id appears in the provided set

#### Scenario: Filter by status
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=status:eq:RUNNING`
- **THEN** system SHALL return only runs with status RUNNING

#### Scenario: Filter by testRunName
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=testRunName:eq:My Regression Test`
- **THEN** system SHALL return only runs with that exact test run name

#### Scenario: Filter by createdAt range
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=createdAt:gte:1735689600000&filter=createdAt:lt:1738368000000`
- **THEN** system SHALL return only runs created within that time window

#### Scenario: Filter by startedAt range
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=startedAt:gte:<epochMs>`
- **THEN** system SHALL return only runs where `startedAt` is greater than or equal to the given epoch ms value; runs with null `startedAt` SHALL be excluded

#### Scenario: Filter by startedAt upper bound
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=startedAt:lt:<epochMs>`
- **THEN** system SHALL return only runs where `startedAt` is strictly less than the given epoch ms value; runs with null `startedAt` SHALL be excluded

#### Scenario: Filter by completedAt range
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=completedAt:gte:<epochMs>`
- **THEN** system SHALL return only runs where `completedAt` is greater than or equal to the given epoch ms value; runs with null `completedAt` (PENDING or RUNNING runs) SHALL be excluded

#### Scenario: Filter by completedAt upper bound
- **WHEN** client calls `GET /api/v1/test-suite-runs?filter=completedAt:lt:<epochMs>`
- **THEN** system SHALL return only runs where `completedAt` is strictly less than the given epoch ms value; runs with null `completedAt` SHALL be excluded

#### Scenario: Multiple filters combined with AND
- **WHEN** client provides multiple `filter` parameters
- **THEN** system SHALL apply all filters using AND combination
