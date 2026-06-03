## MODIFIED Requirements

### Requirement: Create a TestSuite
The service SHALL allow creating a new TestSuite. The request body SHALL accept `testCaseSchema` (list of field definitions), `requestTemplate` (nullable `RequestTemplateDto` with embedded `${{variable}}` syntax), and `inputBindings` (list of explicit binding definitions), replacing the previous `testCasesDefinition` structure.

#### Scenario: Valid payload
- **WHEN** client calls `POST /api/v1/test-suites` with a valid body including `testCaseSchema`, `requestTemplate`, and `inputBindings`
- **THEN** system SHALL create a new TestSuite, perform suite-level soft validation, and return the created entity including `isValid` and `validationWarnings`

#### Scenario: CreatedBy attribution
- **WHEN** `config.rest.security.mode=oidc` and an authenticated client creates a TestSuite
- **THEN** system SHALL store `createdBy` from JWT subject

#### Scenario: Missing author is rejected in OIDC mode
- **WHEN** `config.rest.security.mode=oidc` and a request is not authenticated (no user detected)
- **THEN** system SHALL reject the request with HTTP 401

#### Scenario: Anonymous author allowed only in no-security mode
- **WHEN** `config.rest.security.mode=none` and an unauthenticated client creates a TestSuite
- **THEN** system SHALL store `createdBy` as `anonymous`

#### Scenario: Embedded deployment and endpoint references
- **WHEN** client calls `POST /api/v1/test-suites` with a valid body including `deploymentRef` and `endpointRef`
- **THEN** system SHALL persist those objects as part of the TestSuite and return them in the response

### Requirement: Update a TestSuite
The service SHALL allow updating an existing TestSuite by id. When `testCaseSchema`, `requestTemplate`, or `inputBindings` change, the system SHALL trigger re-validation of existing TestCases.

#### Scenario: Existing id
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a valid body
- **THEN** system SHALL update the existing TestSuite, recalculate suite-level `isValid` and `validationWarnings`, and return the updated entity

#### Scenario: Missing id
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` for a non-existent TestSuite
- **THEN** system SHALL respond with HTTP 404

#### Scenario: Update embedded refs
- **WHEN** client updates `deploymentRef` and/or `endpointRef` of an existing TestSuite
- **THEN** system SHALL persist the updated embedded objects, recalculate suite-level `isValid` and `validationWarnings` (e.g. schema conformance, relativeUrlPattern changes), trigger re-validation of existing TestCases, and return the updated suite

#### Scenario: Update testCaseSchema triggers re-validation
- **WHEN** client updates `testCaseSchema` of an existing TestSuite
- **THEN** system SHALL trigger re-validation of existing TestCases against the new schema

#### Scenario: Update requestTemplate triggers re-validation
- **WHEN** client updates `requestTemplate` of an existing TestSuite
- **THEN** system SHALL trigger re-validation (template variables re-extracted, bindings re-checked)

#### Scenario: Update inputBindings triggers re-validation
- **WHEN** client updates `inputBindings` of an existing TestSuite
- **THEN** system SHALL trigger re-validation of TestCases (required fields re-checked)

### Requirement: EndpointContractDto schemas are optional
The `endpointRef.requestBodySchema` and `endpointRef.responseBodySchema` fields SHALL be optional (nullable). It SHALL be possible to define a TestSuite with only a `requestTemplate` and no endpoint schemas. Schemas improve usability via validation but are not required.

#### Scenario: Create TestSuite without endpoint schemas
- **WHEN** client creates a TestSuite with `endpointRef` containing `method` and `relativeUrlPattern` but no `requestBodySchema` or `responseBodySchema`
- **THEN** system SHALL accept the request

#### Scenario: Schema-based validation is skipped when schema absent
- **WHEN** `endpointRef.requestBodySchema` is null
- **THEN** system SHALL skip template-vs-schema validation (no schema warnings generated)

### Requirement: Suite-level soft validation (`isValid` + `validationWarnings`)
The TestSuite response SHALL include `isValid` (boolean) and `validationWarnings` (structured list, same format as TestCase validation warnings). Suite-level validation covers template + bindings configuration correctness and is **independent of test case data**. Suite `isValid` is recalculated on every create or update. TestCase `isValid` covers data-specific checks only — the two layers are independent.

#### Scenario: Create returns suite validation result
- **WHEN** client creates a TestSuite with `requestTemplate` and `inputBindings`
- **THEN** the response SHALL include `isValid` and `validationWarnings` reflecting suite-level checks (urlTemplate, binding coverage, binding references, schema conformance)

#### Scenario: Update recalculates suite validation
- **WHEN** client updates `requestTemplate`, `inputBindings`, `testCaseSchema`, or `endpointRef`
- **THEN** system SHALL recalculate `isValid` and `validationWarnings` for the suite

#### Scenario: Suite valid — no warnings
- **WHEN** all suite-level checks pass (urlTemplate valid, all required variables bound, all bindings reference valid fields and template variables, template conforms to endpoint schema)
- **THEN** `isValid` SHALL be `true` and `validationWarnings` SHALL be empty

#### Scenario: Suite invalid — warnings produced
- **WHEN** any suite-level check fails (e.g. urlTemplate null, required variable unbound, binding references unknown field)
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL contain structured warning objects (same format as TestCase: `fieldName`, `path`, `message`, optional `code`)

#### Scenario: Suite with no request template produces warning
- **WHEN** TestSuite has `requestTemplate: null`
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL include a warning (e.g. "urlTemplate is required for request assembly") — same as when `requestTemplate` is non-null but `urlTemplate` is null

#### Scenario: Suite validation accessible without test cases
- **WHEN** a TestSuite has just been created and has no test cases yet
- **THEN** the response SHALL still include `isValid` and `validationWarnings` from suite-level checks

## REMOVED Requirements

### Requirement: Manage embedded TestCasesDefinition via TestSuite API
**Reason**: Replaced by three separate top-level concepts: `testCaseSchema`, `requestTemplate`, and `inputBindings`. The `testCasesDefinition` wrapper with computed `parameterFields` and stored `factFields` is no longer needed.
**Migration**: Existing `factFields` entries are migrated to `testCaseSchema` entries. Computed `parameterFields` are removed (bindings replace this functionality).

### Requirement: testCasesDefinition.factFields optional; init when missing
**Reason**: `testCasesDefinition` is removed. `testCaseSchema` replaces `factFields` and is optional (defaults to empty list).
**Migration**: Existing `factFields` migrated to `testCaseSchema` entries.

### Requirement: Fact field entries have required name and type (when factFields present)
**Reason**: Validation moves to `testCaseSchema` field definitions (same rules: `name` and `type` required, `description` optional).
**Migration**: Same validation applied to `testCaseSchema` entries.

### Requirement: Fact fields list size cap
**Reason**: Cap now applies to `testCaseSchema` (same configured maximum).
**Migration**: Same cap applied to `testCaseSchema` size.

## ADDED Requirements

### Requirement: testCaseSchema structure and validation
The service SHALL accept `testCaseSchema` as a list of `FieldDefinitionDto` entries, each with `name` (String, required, non-blank), `type` (SchemaFieldType enum, required), `required` (boolean), and `description` (String, optional). The list SHALL be optional (defaults to empty). The list size SHALL not exceed the configured maximum (default 128).

#### Scenario: Valid testCaseSchema
- **WHEN** client sends `testCaseSchema` with valid entries (each with non-blank name and valid type)
- **THEN** system SHALL accept and persist the schema

#### Scenario: testCaseSchema omitted
- **WHEN** client creates/updates TestSuite without `testCaseSchema` or with `testCaseSchema: null`
- **THEN** system SHALL initialize `testCaseSchema` to an empty list

#### Scenario: Field without name
- **WHEN** client sends a `testCaseSchema` entry with null or blank `name`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Field without type
- **WHEN** client sends a `testCaseSchema` entry with null `type`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Schema list size cap
- **WHEN** client sends `testCaseSchema` with more entries than the configured maximum
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Duplicate field names in schema
- **WHEN** client sends `testCaseSchema` with two entries having the same `name` (case-insensitive)
- **THEN** system SHALL respond with HTTP 400

### Requirement: No stored roles in field definitions
The service SHALL NOT store role annotations (INPUT, FACT) on field definitions. Field roles are emergent — derived by clients from `inputBindings` (fields referenced by bindings = inputs, fields without bindings = fact candidates).

#### Scenario: Field role derivation
- **WHEN** client requests a TestSuite with `testCaseSchema` and `inputBindings`
- **THEN** the response SHALL NOT include role information in field definitions; clients derive roles from bindings
