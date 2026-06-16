## MODIFIED Requirements

### Requirement: SuiteType enum in data model
`TestSuite.suiteType` SHALL be typed as `SuiteType` enum (not `String`) in the data model class. `TestSuiteRowMapper` SHALL call `SuiteType.fromValue(rs.getString("suite_type"))` to map the DB value. Callers of `testSuite.getSuiteType()` SHALL compare directly against enum constants (e.g. `SuiteType.MCP_TOOL`) rather than using string comparison or `SuiteType.isMcpTool(String)`. The helper method `SuiteType.isMcpTool(String)` SHALL be removed.

**Status**: Planned

**Implementation notes**: DB column remains `VARCHAR(20) NOT NULL DEFAULT 'DEPLOYMENT'` — no migration needed. All existing data contains valid enum values.

#### Scenario: DEPLOYMENT suite type mapped from DB
- **WHEN** the DB row has `suite_type = 'DEPLOYMENT'`
- **THEN** `TestSuite.suiteType` is `SuiteType.DEPLOYMENT`

#### Scenario: MCP_TOOL suite type mapped from DB
- **WHEN** the DB row has `suite_type = 'MCP_TOOL'`
- **THEN** `TestSuite.suiteType` is `SuiteType.MCP_TOOL`

#### Scenario: Invalid suite type fails fast
- **WHEN** the DB row has an unrecognized `suite_type` value
- **THEN** `SuiteType.fromValue()` throws `IllegalArgumentException`

#### Scenario: MCP branching uses enum comparison
- **WHEN** `EvaluationWorker` (or any service) needs to branch on MCP vs deployment
- **THEN** it compares `testSuite.getSuiteType() == SuiteType.MCP_TOOL` directly (no string comparison)
