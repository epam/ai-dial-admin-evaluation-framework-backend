## Why

TSMDs currently have no validation state and no way to be selectively disabled. A TSMD with a broken column reference (referencing a deleted test case column) or a required metric property left unbound will silently fail at evaluation runtime, making it hard to diagnose why a run produces incomplete metric results. This change introduces soft validation (non-blocking) and an enabled flag to mirror the pattern already in place for test cases.

## What Changes

- Add `is_enabled` flag to `TestSuiteMetricDefinition` (default `true`); clients can disable individual TSMDs without deleting them
- Add `is_valid` / `validation_warnings` soft-validation state to `TestSuiteMetricDefinition` — set synchronously on create/update and refreshed during suite revalidation
- Introduce `MetricDefinitionValidationService` that checks: (a) `TestCase` binding column refs exist in `testCaseSchema`, (b) `Response` binding column refs exist in `responseColumns`, (c) required metric schema properties have a binding, (d) required metric schema properties are not bound to a `null` constant
- Make `ConstantBindingSourceDto.value` nullable (remove `@NotNull`) — null constant value is now a valid stored state; soft validation catches it when the property is required
- Add hard validation (HTTP 400) for duplicate `property` names within `configBindings` or `inputBindings`
- Add `UNRESOLVED_REFERENCE` to `ValidationWarningCode` for broken column references
- Evaluation run metric phase loads only enabled AND valid TSMDs; disabled or invalid TSMDs are skipped
- Suite revalidation (`RevalidationService`) extended to also revalidate all TSMDs for the suite

## Capabilities

### New Capabilities
- `tsmd-validation`: Soft validation of TSMD bindings against suite schema and metric declaration schemas; `is_valid`, `validation_warnings`, `is_enabled` lifecycle management for TSMDs; `MetricDefinitionValidationService`; `UNRESOLVED_REFERENCE` warning code

### Modified Capabilities
- `test-suite-metric-definitions`: New fields (`enabled`, `valid`, `validationWarnings`) on request/response DTOs; changed `ConstantBindingSourceDto.value` nullability; new duplicate-binding hard validation
- `metric-evaluation`: TSMD loading for metric evaluation phase now filters by `is_enabled = true AND is_valid = true`

## Impact

**API (breaking for Constant binding)**: `ConstantBindingSourceDto.value` becomes nullable — previously `null` was rejected with HTTP 400, now it is accepted and produces a soft validation warning if the target property is required. Clients that relied on the 400 for null-value detection need to handle the new warning response.

**DB**: One Flyway migration adding three columns to `test_suite_metric_definitions`: `is_enabled BOOLEAN NOT NULL DEFAULT TRUE`, `is_valid BOOLEAN NOT NULL DEFAULT TRUE`, `validation_warnings JSONB NOT NULL DEFAULT '[]'`.

**Code**:
- New: `MetricDefinitionValidationService` (`service.domain`)
- Modified: `TestSuiteMetricDefinition`, `AggregatedMetricDefinition` models; both RowMappers; `TestSuiteMetricDefinitionResponseDto`, `TestSuiteMetricDefinitionRequestDto`; `TestSuiteMetricDefinitionMapper`; `TestSuiteMetricDefinitionService` (create/update flow)
- Modified: `MetricDeclarationVersionRepository` + Postgres impl — add `findByIdAndMetricDeclarationId()`
- Modified: `TestSuiteMetricDefinitionRepository` + Postgres impl — add `findAllEnabledAndValidAggregatedByTestSuiteId()`, update INSERT/UPDATE/SELECT SQLs
- Modified: `ConstantBindingSourceDto` — remove `@NotNull` on `value`
- Modified: `ValidationWarningCode` — add `UNRESOLVED_REFERENCE`
- Modified: `RevalidationService` — extend to revalidate TSMDs
- Modified: `TestSuiteEvaluationJob` — use `findAllEnabledAndValidAggregatedByTestSuiteId()`
- Docs: `docs/database-schema.md` updated for new columns
