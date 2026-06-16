## MODIFIED Requirements

### Requirement: List TestSuites (paginated)
The service SHALL provide a paginated endpoint to list TestSuites.
Status: **Implemented**

#### Scenario: Default pagination
- **WHEN** client calls `GET /api/v1/test-suites` without params
- **THEN** response SHALL be a page of TestSuites with default `page=0` and `size=20`

#### Scenario: Pagination bounds
- **WHEN** client calls `GET /api/v1/test-suites?page=<p>&size=<s>`
- **THEN** `page` SHALL be >= 0 and `size` SHALL be between 1 and 100

#### Scenario: Sorting
- **WHEN** client calls `GET /api/v1/test-suites?sort=<field>[,<asc|desc>]` (repeatable)
- **THEN** system SHALL apply sorting using a whitelist of allowed fields

#### Scenario: Structured filtering (`filter`)
- **WHEN** client calls `GET /api/v1/test-suites?filter=<field>:<op>:<value>` (repeatable)
- **THEN** system SHALL apply AND-combined filters using a TestSuite-specific whitelist of fields and operators

#### Scenario: Filter by id (exact match)
- **WHEN** client calls `GET /api/v1/test-suites?filter=id:eq:<uuid>`
- **THEN** system SHALL return only the TestSuite with that exact id (or an empty page if not found)

#### Scenario: Filter by id (set membership)
- **WHEN** client calls `GET /api/v1/test-suites?filter=id:in:<uuid1>,<uuid2>`
- **THEN** system SHALL return only TestSuites whose id is in the provided comma-separated list

#### Scenario: Filter by description (substring, case-insensitive)
- **WHEN** client calls `GET /api/v1/test-suites?filter=description:co:evaluation`
- **THEN** system SHALL return only TestSuites whose `description` contains `"evaluation"` (case-insensitive)

#### Scenario: Filter by updatedAt range
- **WHEN** client calls `GET /api/v1/test-suites?filter=updatedAt:gte:1700000000000&filter=updatedAt:lt:1800000000000`
- **THEN** system SHALL return only TestSuites last updated within that epoch-millisecond range (AND semantics)
