# Test Suites (delta: meta-model validations)

## ADDED Requirements

### Requirement: Valid JSON Schema for endpoint schemas
The service SHALL reject create/update requests when `endpointRef.requestBodySchema`, `endpointRef.responseBodySchema`, or any `endpointRef.parameters[i].schema` contains invalid JSON Schema (e.g. invalid `type` value). Validation SHALL be against JSON Schema Draft-07 rules (meta-schema or equivalent).

#### Scenario: Invalid type in requestBodySchema
- **WHEN** client calls POST or PUT with `endpointRef.requestBodySchema` containing a property with invalid `type` (e.g. `"type": "abc"`)
- **THEN** system SHALL respond with HTTP 400 and a clear validation error message

#### Scenario: Invalid type in responseBodySchema
- **WHEN** client calls POST or PUT with `endpointRef.responseBodySchema` containing invalid `type` (e.g. root or property `"type": "abc"`)
- **THEN** system SHALL respond with HTTP 400 and a clear validation error message

#### Scenario: Invalid type in parameter schema
- **WHEN** client calls POST or PUT with `endpointRef.parameters[i].schema` containing invalid `type`
- **THEN** system SHALL respond with HTTP 400 and a clear validation error message

#### Scenario: Schema with $ref rejected (v1)
- **WHEN** client calls POST or PUT with any schema (requestBodySchema, responseBodySchema, parameters[i].schema) containing `$ref` keyword
- **THEN** system SHALL respond with HTTP 400 and indicate `$ref` is not supported in v1

### Requirement: testCasesDefinition.factFields optional; init when missing
The service SHALL treat `testCasesDefinition.factFields` as optional. When it is missing or null, the system SHALL initialize it to an empty list and SHALL NOT reject the request.

#### Scenario: factFields omitted
- **WHEN** client sends create/update without `testCasesDefinition.factFields` or with `factFields` null
- **THEN** system SHALL initialize `factFields` to an empty list and SHALL accept the request

#### Scenario: factFields present empty
- **WHEN** client sends `testCasesDefinition.factFields` as an empty array
- **THEN** system SHALL accept the request and persist empty factFields

### Requirement: Fact field entries have required name and type (when factFields present)
When `testCasesDefinition.factFields` is present, the service SHALL reject create/update requests when any entry has missing or blank `name` or missing `type`. The `description` field in each fact field entry is optional; when not defined (null/absent), the system SHALL handle without error.

#### Scenario: Fact field without name
- **WHEN** client sends `testCasesDefinition.factFields` with an entry that has null or blank `name`
- **THEN** system SHALL respond with HTTP 400 and indicate the invalid fact field

#### Scenario: Fact field without type
- **WHEN** client sends `testCasesDefinition.factFields` with an entry that has null `type`
- **THEN** system SHALL respond with HTTP 400 and indicate the invalid fact field

#### Scenario: Fact field without description (optional)
- **WHEN** client sends a fact field entry with no `description` or `description` null
- **THEN** system SHALL accept the request and SHALL NOT report a validation error

### Requirement: Parameter definitions have required in
The service SHALL reject create/update requests when any entry in `endpointRef.parameters` has missing or null `in` (query, path, or header).

#### Scenario: Parameter without in
- **WHEN** client sends `endpointRef.parameters` with an entry that has null or missing `in`
- **THEN** system SHALL respond with HTTP 400 and indicate the invalid parameter

#### Scenario: Parameter with invalid in value
- **WHEN** client sends `endpointRef.parameters` with an entry that has an unsupported `in` value (e.g. `"cookie"`)
- **THEN** system SHALL respond with HTTP 400 (Bean Validation rejects invalid enum)

### Requirement: Fact fields list size cap
The service SHALL reject create/update requests when `testCasesDefinition.factFields` exceeds the configured maximum length (default 128).

#### Scenario: Fact fields list over limit
- **WHEN** client sends `testCasesDefinition.factFields` with more than the allowed number of entries (e.g. 128)
- **THEN** system SHALL respond with HTTP 400
