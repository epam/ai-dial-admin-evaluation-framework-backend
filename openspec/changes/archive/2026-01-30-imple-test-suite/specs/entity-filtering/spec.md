## ADDED Requirements

### Requirement: Pagination parameters are supported on list endpoints
All list endpoints SHALL support pagination parameters.

#### Scenario: Default pagination
- **WHEN** client calls a list endpoint without pagination parameters
- **THEN** system SHALL return `page=0` with `size=20`

#### Scenario: Bounds validation
- **WHEN** client calls a list endpoint with `page < 0` or `size` outside `[1..100]`
- **THEN** system SHALL respond with HTTP 400

### Requirement: Structured filtering via repeatable `filter` parameter
List endpoints SHALL support structured filtering via a repeatable query parameter: `filter=<field>:<op>:<value>`.

Supported operators (v1) SHALL include: `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `like`, `in`, `isnull`.

#### Scenario: AND semantics
- **WHEN** client provides multiple `filter` parameters
- **THEN** system SHALL apply them with AND semantics

#### Scenario: Whitelisted filter fields/operators
- **WHEN** client provides `filter` conditions
- **THEN** system SHALL accept only whitelisted fields/operators for that endpoint and bind values as query parameters

#### Scenario: Invalid filter
- **WHEN** client provides an invalid filter syntax, unsupported operator, or non-whitelisted field
- **THEN** system SHALL respond with HTTP 400

## Open Questions / Follow-up Task

### Requirement: Filtering DSL refinement (future)
The filtering DSL SHALL be reviewed and refined in a separate task after initial UI/usage feedback.

#### Scenario: Future extension areas
- **WHEN** filtering requirements expand
- **THEN** system MAY introduce OR groups, nested jsonb-path filters, and/or `q` search (case-sensitive or case-insensitive), or adopt a standard query language (e.g., RSQL/OData) while preserving backward compatibility where possible

