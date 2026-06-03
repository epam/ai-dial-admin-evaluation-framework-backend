## MODIFIED Requirements

### Requirement: Valid JSON Schema for endpoint schemas
The service SHALL reject create/update requests when `endpointRef.requestBodySchema`, `endpointRef.responseBodySchema`, or any `endpointRef.parameters[i].schema` contains invalid JSON Schema (e.g. invalid `type` value). Validation SHALL be against JSON Schema Draft-07 rules (meta-schema or equivalent).

The `requestBodySchema` field is now a polymorphic `RequestBodySchemaDto` with `contentType` discriminator. For `application/json` and `application/x-www-form-urlencoded` variants, the `schema` field SHALL be validated as JSON Schema. For `multipart/form-data` variant, each `FormPartSchemaDto` with a `schema` field SHALL have that field validated as JSON Schema.

#### Scenario: Invalid type in JSON requestBodySchema
- **WHEN** client calls POST or PUT with `endpointRef.requestBodySchema` of type `application/json` containing a property with invalid `type` (e.g. `"type": "abc"`)
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

#### Scenario: Multipart requestBodySchema with invalid part schema
- **WHEN** client calls POST or PUT with `endpointRef.requestBodySchema` of type `multipart/form-data` and a `FormPartSchemaDto` has a `schema` field with invalid JSON Schema
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Unknown contentType in requestBodySchema rejected
- **WHEN** client calls POST or PUT with `endpointRef.requestBodySchema` having an unrecognized `contentType`
- **THEN** system SHALL respond with HTTP 400

### Requirement: EndpointContractDto schemas are optional
The `endpointRef.requestBodySchema` and `endpointRef.responseBodySchema` fields SHALL be optional (nullable). It SHALL be possible to define a TestSuite with only a `requestTemplate` and no endpoint schemas. Schemas improve usability via validation but are not required.

#### Scenario: Create TestSuite without endpoint schemas
- **WHEN** client creates a TestSuite with `endpointRef` containing `method` and `relativeUrlPattern` but no `requestBodySchema` or `responseBodySchema`
- **THEN** system SHALL accept the request

#### Scenario: Schema-based validation is skipped when schema absent
- **WHEN** `endpointRef.requestBodySchema` is null
- **THEN** system SHALL skip template-vs-schema validation (no schema warnings generated)

#### Scenario: Content type consistency validation
- **WHEN** `endpointRef.requestBodySchema` has `contentType: "multipart/form-data"` and `requestTemplate.body` has `contentType: "application/json"`
- **THEN** system SHALL add a suite-level soft validation warning about content type mismatch between schema and template
