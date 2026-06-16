## ADDED Requirements

### Requirement: TSMD soft validation on create and update
Status: Planned

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
Status: Planned

The `ValidationWarningCode` enum SHALL include a value `UNRESOLVED_REFERENCE` with the description "Reference to a column or resource that does not exist".

#### Scenario: Code appears in TSMD validation warnings
- **WHEN** a TSMD has a binding source referencing a non-existent column
- **THEN** the validation warning in `validationWarnings` SHALL have `code = "UNRESOLVED_REFERENCE"`

### Requirement: Hard validation — duplicate binding property names
Status: Planned

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
Status: Planned

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
Status: Planned

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

### Requirement: TSMD revalidation via manual suite revalidation endpoint
Status: Planned

When `POST /api/v1/test-suites/{id}/revalidation` is triggered, the system SHALL revalidate all TSMDs belonging to the suite in addition to revalidating test cases. TSMD revalidation SHALL use `MetricDefinitionValidationService` and SHALL run synchronously before the paginated test-case loop begins. The revalidation task response SHALL NOT include separate TSMD counters — TSMD revalidation is a side effect of the suite revalidation operation. The primary use case is restoring accurate validation state for TSMDs that were created before the DB migration (existing rows default to `is_valid = true` regardless of actual binding correctness).

#### Scenario: Manual revalidation corrects stale default validation state
- **WHEN** a suite has TSMDs that were persisted before the `is_valid` / `validation_warnings` columns existed (or otherwise have stale `is_valid = true` from the migration default) and client calls `POST /api/v1/test-suites/{id}/revalidation`
- **THEN** the system SHALL revalidate all TSMDs for the suite; a TSMD with a binding referencing a non-existent column SHALL be updated to `is_valid = false` with an `UNRESOLVED_REFERENCE` warning

### Requirement: Constant binding source — nullable value
Status: Planned

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

- `MetricDefinitionValidationService`: `com.epam.aidial.evaluation.service.domain.MetricDefinitionValidationService`
- `ValidationWarningCode.UNRESOLVED_REFERENCE`: added to `com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode`
- Duplicate-binding check: in `TestSuiteMetricDefinitionService.create()` / `.update()` — throw `ValidationException`
- DB: `test_suite_metric_definitions.is_enabled`, `is_valid`, `validation_warnings` — added via Flyway migration
- Auto-revalidation on suite update: `TestSuiteService.update()` calls `TestSuiteMetricDefinitionService.revalidateAllForSuite()` synchronously when `testCaseSchema` or `responseColumns` changes
- Manual revalidation: `RevalidationService.runRevalidationAsync()` calls `revalidateAllForSuite()` synchronously before the test-case loop
- New repository method: `TestSuiteMetricDefinitionRepository.updateValidation(UUID id, boolean valid, String warningsJson)`
- `validationWarnings` on `TestSuiteMetricDefinitionResponseDto` MUST always be serialized as `[]` (empty array) when there are no warnings — never as JSON `null`. Do NOT annotate this field with `@JsonInclude(NON_NULL)`. The mapper MUST initialize the field to an empty list rather than null.
- `path` convention for `UNRESOLVED_REFERENCE` warnings: use the list-level path (`$.configBindings` or `$.inputBindings`) — not element-level (e.g., `$.configBindings[0]`). The unresolved `columnName` value is identified in the `message` field (e.g., `"Column 'missing_col' not found in testCaseSchema"`).
- `BindingResolver`'s existing fail-fast behavior (throwing `IllegalArgumentException` for missing columns at evaluation time) remains as defense-in-depth. TSMDs with `UNRESOLVED_REFERENCE` warnings are filtered at load time by `findAllEnabledAndValidAggregatedByTestSuiteId`, so the resolver throw path should not trigger in practice; it guards against race conditions or stale validation state between a suite schema change and the next revalidation.
