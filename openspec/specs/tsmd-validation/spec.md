# TSMD Validation

## Purpose
This spec describes the soft validation and enabled/disabled state management for Test Suite Metric Definitions (TSMDs). Soft validation computes a `valid` flag and `validationWarnings` array on every TSMD create/update and on suite schema changes, without blocking the save operation. TSMDs that fail validation or are explicitly disabled are excluded from metric evaluation.

Status: **Implemented**

## Requirements

### Requirement: TSMD soft validation on create and update

The system SHALL compute a validation result for a TSMD synchronously when the TSMD is created or updated. The result SHALL be stored as `is_valid` (boolean) and `validation_warnings` (JSONB array) on the entity before the INSERT/UPDATE. Validation SHALL NOT block the save — a TSMD with `is_valid = false` is stored successfully and surfaced to the caller via the response DTO.

`MetricDefinitionValidationService` (injectable `@Service` in `service.domain`) SHALL accept the deserialized binding lists, metric config/input schemas (from `MetricDeclarationVersion`), and suite schema context (`testCaseSchema`, `responseColumns` from `TestSuite`), and return a `ValidationResult`.

#### Scenario: Valid TSMD — all bindings resolved
- **WHEN** a TSMD is created with `configBindings` and `inputBindings` whose `columnName` references all exist in `testCaseSchema` / `responseColumns` and all required metric properties have non-null bindings
- **THEN** system SHALL set `is_valid = true`, `validation_warnings = []`, and return `valid = true` in the response DTO

#### Scenario: Valid TSMD — no bindings
- **WHEN** a TSMD is created with empty `configBindings` and `inputBindings` and the metric has no required config or input properties
- **THEN** system SHALL set `is_valid = true`, `validation_warnings = []`

#### Scenario: Invalid — TestCase column reference unresolved
- **WHEN** a TSMD is created with a `TestCase` binding source whose `columnName` does not exist in the test suite's `testCaseSchema`
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = UNRESOLVED_REFERENCE`, `path = "$.configBindings"` (when the binding is in `configBindings`) or `"$.inputBindings"` (when in `inputBindings`), and a `message` field that identifies the missing `columnName` value

#### Scenario: Invalid — Response column reference unresolved
- **WHEN** a TSMD is created with a `Response` binding source whose `columnName` does not exist in the test suite's `responseColumns`
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = UNRESOLVED_REFERENCE`, `path = "$.configBindings"` (when the binding is in `configBindings`) or `"$.inputBindings"` (when in `inputBindings`), and a `message` field that identifies the missing `columnName` value

#### Scenario: Invalid — required metric property has no binding
- **WHEN** a TSMD is created and the metric's config or input schema lists a property in its `"required"` array that has no binding in `configBindings` or `inputBindings` respectively
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = REQUIRED` and a message identifying the property name

#### Scenario: Invalid — required metric property bound to null constant
- **WHEN** a TSMD is created with a `Constant` binding source where `value` is `null` AND the target metric property is listed in the schema's `"required"` array
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = REQUIRED` and a message identifying the property name

#### Scenario: Warning — binding targets unknown property
- **WHEN** a TSMD is created with a binding whose `property` does not appear as a key in the metric's config or input schema `"properties"` object
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = ADDITIONAL` and a message identifying the property name

#### Scenario: Revalidation on update
- **WHEN** a TSMD is updated (PUT)
- **THEN** the system SHALL recompute the validation result using the updated bindings and the current suite schema and metric schema, and persist the new `is_valid` / `validation_warnings` values

#### Scenario: Metric schema without "properties" key — graceful degradation
- **WHEN** the metric declaration version's config or input schema is missing, null, or does not contain a `"properties"` key
- **THEN** the validation service SHALL skip only the property-name checks (`ADDITIONAL`) and required-property checks (`REQUIRED`) for that schema, log a WARN, and the validation result for those skipped checks SHALL be `is_valid = true`, `validation_warnings = []`; `UNRESOLVED_REFERENCE` checks for `TestCase` binding `columnName` references (resolved against `testCaseSchema`) and `Response` binding `columnName` references (resolved against `responseColumns`) SHALL still be performed, since they depend on the suite schema context — not on the metric schema's `"properties"` key

### Requirement: UNRESOLVED_REFERENCE validation warning code

The `ValidationWarningCode` enum SHALL include a value `UNRESOLVED_REFERENCE` with the description "Reference to a column or resource that does not exist".

#### Scenario: Code appears in TSMD validation warnings
- **WHEN** a TSMD has a binding source referencing a non-existent column
- **THEN** the validation warning in `validationWarnings` SHALL have `code = "UNRESOLVED_REFERENCE"`

### Requirement: Hard validation — duplicate binding property names

The system SHALL reject create and update requests where `configBindings` or `inputBindings` contain more than one entry with the same `property` value. The check is applied independently per list — the same `property` name MAY appear once in `configBindings` and once in `inputBindings`. On violation the system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`.

#### Scenario: Duplicate property in configBindings rejected
- **WHEN** client sends `configBindings` with two entries both having `property = "threshold"`
- **THEN** system SHALL respond with HTTP 400 and `VALIDATION_ERROR`

#### Scenario: Duplicate property in inputBindings rejected
- **WHEN** client sends `inputBindings` with two entries both having `property = "reference"`
- **THEN** system SHALL respond with HTTP 400 and `VALIDATION_ERROR`

#### Scenario: Same property in configBindings and inputBindings is allowed
- **WHEN** client sends one entry with `property = "model"` in `configBindings` and one entry with `property = "model"` in `inputBindings`
- **THEN** system SHALL accept the request (no duplicate violation)

### Requirement: TSMD enabled flag

Each TSMD SHALL have an `enabled` boolean field (default `true`). Clients MAY set `enabled = false` on create or update to exclude the TSMD from metric evaluation without deleting it. The `enabled` field SHALL be included in the TSMD response DTO.

#### Scenario: Default enabled on create
- **WHEN** client creates a TSMD without specifying `enabled`
- **THEN** system SHALL set `enabled = true` and return it in the response

#### Scenario: Create with enabled = false
- **WHEN** client creates a TSMD with `enabled = false`
- **THEN** system SHALL persist `enabled = false` and return it in the response

#### Scenario: Toggle enabled via update
- **WHEN** client updates a TSMD with `enabled = false`
- **THEN** system SHALL set `enabled = false` and return the updated value

### Requirement: TSMD auto-revalidation on suite schema update

The system SHALL revalidate all TSMDs belonging to a suite synchronously whenever `PUT /api/v1/test-suites/{id}` changes `testCaseSchema` or `responseColumns`. Revalidation SHALL complete within the same request transaction and the updated TSMD validation state SHALL be visible immediately after the suite update response. No separate endpoint call is required.

#### Scenario: responseColumns change auto-revalidates TSMDs
- **WHEN** a suite's `responseColumns` is updated (e.g., a column is removed) via `PUT /api/v1/test-suites/{id}`
- **THEN** the system SHALL synchronously revalidate all TSMDs for the suite; a TSMD with a `Response` binding referencing the removed column SHALL have `is_valid = false` and a `UNRESOLVED_REFERENCE` warning immediately after the suite update response

#### Scenario: testCaseSchema change auto-revalidates TSMDs
- **WHEN** a suite's `testCaseSchema` is updated (e.g., a column is removed) via `PUT /api/v1/test-suites/{id}`
- **THEN** the system SHALL synchronously revalidate all TSMDs for the suite; a TSMD with a `TestCase` binding referencing the removed column SHALL have `is_valid = false` and a `UNRESOLVED_REFERENCE` warning immediately after the suite update response

#### Scenario: Unrelated suite update does not trigger TSMD revalidation
- **WHEN** a suite is updated without changing `testCaseSchema` or `responseColumns` (e.g., only `name` changes)
- **THEN** TSMD validation state SHALL remain unchanged

### Requirement: Response reference resolution spans the suite's request chain
TSMD soft validation SHALL resolve `Response`-sourced binding references against the **suite-wide union** of response columns — the suite's own `responseColumns` plus every `additionalRequests[i].responseColumns` — so a binding to a column declared on any request in the chain resolves cleanly and does NOT produce an `UNRESOLVED_REFERENCE` warning. A reference that matches no column anywhere in the chain SHALL continue to produce `UNRESOLVED_REFERENCE`. Because response-column names are globally unique across the chain, resolution SHALL remain by bare name with no request qualifier and no ambiguity.

Automatic revalidation of a suite's TSMDs SHALL be triggered when the **union** of response columns changes on a suite update — including when a column is added to, removed from, or renamed within any `additionalRequests[i].responseColumns`, or when an entry is added to or removed from `additionalRequests` — and SHALL NOT be triggered by a change to an additional request that leaves the union unchanged (for example an edit to its `urlTemplate`, `headers` or `inputBindings`). The revalidation itself SHALL be performed with the post-update union.
Status: **Implemented**

#### Scenario: Binding to an additional request's column resolves
- **WHEN** a TSMD binds a metric parameter to the response column `answer`, declared on `additionalRequests[0]`
- **THEN** validation SHALL NOT emit `UNRESOLVED_REFERENCE` for that binding

#### Scenario: Binding to a nonexistent column still warns
- **WHEN** a TSMD binds a metric parameter to a response column declared on no request in the chain
- **THEN** validation SHALL emit `UNRESOLVED_REFERENCE` for that binding and the TSMD SHALL be marked invalid

#### Scenario: Adding a column to an additional request triggers revalidation
- **WHEN** `PUT /api/v1/test-suites/{id}` adds a response column to `additionalRequests[0]`
- **THEN** the suite's TSMDs SHALL be revalidated synchronously against the new union

#### Scenario: Removing an additional request triggers revalidation
- **WHEN** an update removes an `additionalRequests` entry that declared response columns
- **THEN** the suite's TSMDs SHALL be revalidated, and any TSMD binding to one of the removed columns SHALL become invalid with `UNRESOLVED_REFERENCE`

#### Scenario: Non-column chain edits do not trigger revalidation
- **WHEN** an update changes only an additional request's `urlTemplate`, leaving every request's `responseColumns` untouched
- **THEN** TSMD revalidation SHALL NOT be triggered

### Requirement: TSMD revalidation via manual suite revalidation endpoint

When `POST /api/v1/test-suites/{id}/revalidation` is triggered, the system SHALL revalidate all TSMDs belonging to the suite in addition to revalidating test cases. TSMD revalidation SHALL use `MetricDefinitionValidationService` and SHALL run synchronously before the paginated test-case loop begins. The revalidation task response SHALL NOT include separate TSMD counters — TSMD revalidation is a side effect of the suite revalidation operation. The primary use case is restoring accurate validation state for TSMDs that were created before the DB migration (existing rows default to `is_valid = true` regardless of actual binding correctness).

#### Scenario: Manual revalidation corrects stale default validation state
- **WHEN** a suite has TSMDs that were persisted before the `is_valid` / `validation_warnings` columns existed (or otherwise have stale `is_valid = true` from the migration default) and client calls `POST /api/v1/test-suites/{id}/revalidation`
- **THEN** the system SHALL revalidate all TSMDs for the suite; a TSMD with a binding referencing a non-existent column SHALL be updated to `is_valid = false` with an `UNRESOLVED_REFERENCE` warning

### Requirement: Output schema validation in TSMD soft validation
The `MetricDefinitionValidationService` SHALL validate the metric declaration version's `output_schema` during TSMD creation, update, and revalidation. Validation SHALL use `OutputSchemaFieldExtractor.extractFieldNames(outputSchema)` — if the returned list is empty, the output schema is invalid. This covers null/blank input, missing `"properties"` key, non-object `"properties"`, empty `"properties"`, and malformed JSON. If validation fails, the TSMD SHALL be marked `is_valid = false` with a warning code `INVALID_OUTPUT_SCHEMA`.
Status: **Implemented**

#### Scenario: Valid output schema — properties with at least one field
- **WHEN** a TSMD is created or updated and the metric declaration version's output schema contains `{"properties": {"score": {...}}}` (one or more keys)
- **THEN** the output schema validation SHALL pass — no `INVALID_OUTPUT_SCHEMA` warning SHALL be produced. Other validation checks (UNRESOLVED_REFERENCE, REQUIRED, ADDITIONAL) still apply independently.

#### Scenario: Invalid — output schema is null or empty string
- **WHEN** a TSMD is created and the metric declaration version's `output_schema` is null, blank, or `"{}"`
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = INVALID_OUTPUT_SCHEMA` and a message indicating the output schema is missing or empty

#### Scenario: Invalid — output schema has no properties key
- **WHEN** a TSMD is created and the metric declaration version's output schema is valid JSON but does not contain a `"properties"` key
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = INVALID_OUTPUT_SCHEMA` and a message indicating the output schema has no properties

#### Scenario: Invalid — properties is empty object
- **WHEN** a TSMD is created and the metric declaration version's output schema has `{"properties": {}}`
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = INVALID_OUTPUT_SCHEMA` and a message indicating the output schema has no output fields

#### Scenario: Invalid — properties is not an object
- **WHEN** a TSMD is created and the metric declaration version's output schema has `"properties"` as a non-object value (e.g., string, array)
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = INVALID_OUTPUT_SCHEMA`

#### Scenario: Malformed JSON in output schema
- **WHEN** a TSMD is created and the metric declaration version's `output_schema` contains invalid JSON
- **THEN** system SHALL set `is_valid = false`, add a warning with `code = INVALID_OUTPUT_SCHEMA` and a message indicating the schema is malformed

#### Scenario: Revalidation catches newly invalid output schemas
- **WHEN** `POST /api/v1/test-suites/{id}/revalidation` is triggered and a TSMD references a metric declaration version whose output schema was updated to an invalid state (e.g., properties removed during re-sync)
- **THEN** the TSMD SHALL be updated to `is_valid = false` with an `INVALID_OUTPUT_SCHEMA` warning

#### Scenario: Output schema validation independent of binding checks
- **WHEN** a TSMD has valid bindings but an invalid output schema
- **THEN** the TSMD SHALL be marked `is_valid = false` due to the output schema check, regardless of binding validity

### Requirement: INVALID_OUTPUT_SCHEMA validation warning code
The `ValidationWarningCode` enum SHALL include a value `INVALID_OUTPUT_SCHEMA` with the description "Metric output schema is missing, empty, or malformed".
Status: **Implemented**

#### Scenario: Code appears in TSMD validation warnings
- **WHEN** a TSMD references a metric with an invalid output schema
- **THEN** the validation warning in `validationWarnings` SHALL have `code = "INVALID_OUTPUT_SCHEMA"`

### Requirement: Condition syntax is validated at write time
When a Test Suite Metric Definition is created or updated, a non-blank `condition` SHALL be validated as a syntactically valid JSONata expression. Malformed expressions are rejected with HTTP 400. A blank/null condition is a no-op.
Status: **Implemented**

#### Scenario: Malformed condition rejected on create
- **WHEN** a metric definition is created with a syntactically invalid JSONata `condition`
- **THEN** the request is rejected with HTTP 400

#### Scenario: Malformed condition rejected on update
- **WHEN** an existing metric definition is updated with a malformed `condition`
- **THEN** the request is rejected with HTTP 400

### Requirement: Constant binding source — nullable value

`ConstantBindingSourceDto.value` SHALL be nullable (accept JSON `null`). Clients may set `value = null` to represent an explicit JSON null constant. The system SHALL accept the binding and store it. If the binding targets a required metric property, soft validation SHALL produce a `REQUIRED` warning; if the property is optional, no warning is produced.

#### Scenario: Constant with null value accepted
- **WHEN** client creates a TSMD with `{"property": "model", "source": {"$type": "Constant", "value": null}}`
- **THEN** system SHALL accept the request (HTTP 201), persist the null value, and return the binding in the response

#### Scenario: Constant null on required property produces validation warning
- **WHEN** a TSMD is created with `value = null` for a property listed in the metric schema's `"required"` array
- **THEN** `is_valid = false` and `validationWarnings` contains an entry with `code = REQUIRED`

#### Scenario: Constant null on optional property is valid
- **WHEN** a TSMD is created with `value = null` for a property NOT listed in `"required"`
- **THEN** `is_valid = true` and `validationWarnings = []`

## Implementation Notes

- `MetricDefinitionValidationService`: `com.epam.aidial.evaluation.service.domain.MetricDefinitionValidationService` — accepts `outputSchemaJson` (7th parameter) and uses `OutputSchemaFieldExtractor` to validate it
- `OutputSchemaFieldExtractor`: `com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor` — injectable `@Component` used by both validation and the metric evaluation executor
- `ValidationWarningCode.UNRESOLVED_REFERENCE`: added to `com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode`
- `ValidationWarningCode.INVALID_OUTPUT_SCHEMA`: added to `com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode` with description "Metric output schema is missing, empty, or malformed"
- Duplicate-binding check: in `TestSuiteMetricDefinitionService.create()` / `.update()` — throw `ValidationException`
- DB: `test_suite_metric_definitions.is_enabled`, `is_valid`, `validation_warnings` — added via Flyway migration
- Auto-revalidation on suite update: `TestSuiteService.update()` calls `TestSuiteMetricDefinitionService.revalidateAllForSuite()` synchronously when `testCaseSchema` or `responseColumns` changes
- Manual revalidation: `RevalidationService.runRevalidationAsync()` calls `revalidateAllForSuite()` synchronously before the test-case loop
- New repository method: `TestSuiteMetricDefinitionRepository.updateValidation(UUID id, boolean valid, String warningsJson)`
- `validationWarnings` on `TestSuiteMetricDefinitionResponseDto` MUST always be serialized as `[]` (empty array) when there are no warnings — never as JSON `null`. Do NOT annotate this field with `@JsonInclude(NON_NULL)`. The mapper MUST initialize the field to an empty list rather than null.
- `path` convention for `UNRESOLVED_REFERENCE` warnings: use the list-level path (`$.configBindings` or `$.inputBindings`) — not element-level (e.g., `$.configBindings[0]`). The unresolved `columnName` value is identified in the `message` field (e.g., `"Column 'missing_col' not found in testCaseSchema"`).
- `path` convention for `INVALID_OUTPUT_SCHEMA` warnings: use `$.outputSchema`.
- `BindingResolver`'s existing fail-fast behavior (throwing `IllegalArgumentException` for missing columns at evaluation time) remains as defense-in-depth. TSMDs with `UNRESOLVED_REFERENCE` warnings are filtered at load time by `findAllEnabledAndValidAggregatedByTestSuiteId`, so the resolver throw path should not trigger in practice; it guards against race conditions or stale validation state between a suite schema change and the next revalidation.
