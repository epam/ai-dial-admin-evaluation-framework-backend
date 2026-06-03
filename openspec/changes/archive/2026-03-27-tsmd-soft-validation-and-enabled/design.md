## Context

`TestSuiteMetricDefinition` (TSMD) is the join between a test suite and a metric declaration version, with parameter bindings (`configBindings`, `inputBindings`) that reference suite columns (`testCaseSchema`, `responseColumns`) and constant values. Currently TSMDs have no validation state and no enabled flag. A TSMD with a broken column reference or a missing required-property binding silently fails during the metric evaluation phase at runtime. The pattern for soft validation already exists for `TestSuite` and `TestCase`; this change extends it to TSMDs with the same DB columns and lifecycle.

## Goals / Non-Goals

**Goals:**
- Add `is_enabled`, `is_valid`, `validation_warnings` to the TSMD DB table and domain model
- Run soft validation on create/update synchronously; refresh during suite revalidation
- Allow `null` as `ConstantBindingSourceDto.value` (stored state); surface as `REQUIRED` warning when the target metric property is required
- Hard-reject requests with duplicate `property` names within `configBindings` or `inputBindings`
- Metric evaluation phase uses only enabled AND valid TSMDs
- Auto-revalidate TSMDs synchronously on suite update when `testCaseSchema` or `responseColumns` changes
- Revalidate TSMDs synchronously at the start of the manual suite revalidation endpoint (before the test-case loop)

**Non-Goals:**
- Deep JSON Schema validation (type checking, format, pattern) of bound values against the metric schema — only required-property and null-constant checks
- UI filtering or sorting by `is_valid` or `is_enabled` (filter whitelist not extended in this change)
- A separate revalidation endpoint for TSMDs only (shares the existing suite revalidation endpoint)

## Decisions

### D1: Validate before save — load `TestSuite` + `MetricDeclarationVersion` upfront

**Decision**: Replace the existing existence-only checks (`existsById`, `existsByIdAndMetricDeclarationId`) with full-object fetches (`findById`, `findByIdAndMetricDeclarationId`). Run `MetricDefinitionValidationService.validate()` before `repository.save()`. Set `valid` and `validationWarnings` on the entity before the INSERT — no separate `updateValidation()` UPDATE round-trip needed.

**Alternative considered**: Save first, then fetch aggregated form (3-table JOIN) and validate, then UPDATE. Rejected because it adds a second write per request and the aggregated fetch is heavier than loading suite + version separately (both already needed for the existing checks).

**Repository addition**: `MetricDeclarationVersionRepository.findByIdAndMetricDeclarationId(UUID id, UUID metricDeclarationId): Optional<MetricDeclarationVersion>` — minimal addition since `MetricDeclarationVersion` already carries `configSchema` and `inputSchema`.

### D2: Service-level duplicate binding check → `ValidationException`

**Decision**: Check for duplicate `property` values in `configBindings` and `inputBindings` in `TestSuiteMetricDefinitionService.create()` / `.update()` before entity mapping. Throw `ValidationException` (already mapped to HTTP 400 `VALIDATION_ERROR` in `DefaultExceptionHandler`). The check is per-list independently — a property MAY appear in both `configBindings` and `inputBindings` if it exists in both schemas.

**Alternative considered**: Custom Bean Validation constraint `@UniqueBindingProperties`. Rejected — requires 3 new files (annotation, validator, test) for a check used in a single place; `ValidationException` is consistent with how other semantic 400s are thrown in the service layer.

### D3: `MetricDefinitionValidationService` — validation logic

**Component**: `@Service` `MetricDefinitionValidationService` in `service.domain`. Injectable, independently testable.

**Input**: `List<MetricParameterBindingDto> configBindings`, `inputBindings`, `String configSchemaJson`, `inputSchemaJson` (raw JSONB from `MetricDeclarationVersion`), `String testCaseSchemaJson`, `responseColumnsJson` (raw JSONB from `TestSuite`).

**Validation matrix** (applied identically to configBindings×configSchema and inputBindings×inputSchema):

| Check | Code |
|---|---|
| `property` not in schema `"properties"` keys | `ADDITIONAL` |
| `ConstantBindingSource.value == null` AND property in schema `"required"` | `REQUIRED` |
| `TestCaseBindingSource.columnName` ∉ `testCaseSchema[].name` | `UNRESOLVED_REFERENCE` |
| `ResponseBindingSource.columnName` ∉ `responseColumns[].name` | `UNRESOLVED_REFERENCE` |
| property in schema `"required"` has no binding at all | `REQUIRED` |

**JSON Schema parsing**: Parse `configSchemaJson` / `inputSchemaJson` with `ObjectMapper` into `JsonNode`. Extract property names from `schema["properties"]` (if present) and required list from `schema["required"]` (if present). Log and treat schema as empty on parse failure (graceful degradation — broken schema at storage level should not block TSMD saves).

**New `ValidationWarningCode`**: `UNRESOLVED_REFERENCE` — reference to a column that does not exist in the suite schema.

### D4: `findAllEnabledAndValidAggregatedByTestSuiteId` in repository

**Decision**: Add a dedicated method to `TestSuiteMetricDefinitionRepository` that extends the existing `SELECT_ALL_AGGREGATED_BY_SUITE_SQL` with `AND md.is_enabled = true AND md.is_valid = true`. `TestSuiteEvaluationJob.buildMetricEvaluationContext()` calls this method instead of `findAllAggregatedByTestSuiteId()`.

**Alternative**: Post-filter in Java after loading all TSMDs. Rejected — filtering in SQL is cheaper and consistent with the test case `findEnabledValidByTestSuiteId` precedent.

### D5: TSMD revalidation — two triggers, both synchronous

TSMD revalidation is always synchronous (TSMDs are typically < 20 per suite). It is NOT coupled to the async test-case revalidation mechanism.

**Shared implementation**: `TestSuiteMetricDefinitionService.revalidateAllForSuite(UUID suiteId, String testCaseSchemaJson, String responseColumnsJson)` — loads all TSMDs via `findAllAggregatedByTestSuiteId(suiteId)`, calls `MetricDefinitionValidationService.validate()` for each (metric schemas come from `AggregatedMetricDefinition.versionConfigSchema` / `versionInputSchema`), and persists results via `testSuiteMetricDefinitionRepository.updateValidation()`. Annotated `@Transactional("metaTransactionManager")`.

**Transaction propagation note**: Default `REQUIRED` propagation on `revalidateAllForSuite()` is intentional. When called from `TestSuiteService.update()` (already inside a meta transaction), the revalidation joins the outer transaction — all per-TSMD `updateValidation()` calls are committed atomically with the suite update. When called from `RevalidationService.runRevalidationAsync()` (an `@Async` method running in a new thread with no active transaction), `REQUIRED` opens a new transaction that covers all per-TSMD updates atomically. Do NOT introduce `REQUIRES_NEW` per-TSMD — that would commit each update independently and prevent rollback on error.

**Trigger 1 — suite update**: `TestSuiteService.update()` adds a private `isTsmdSchemaChanged()` helper that checks whether `testCaseSchema` OR `responseColumns` changed (both affect TSMD binding resolution). When true, `testSuiteMetricDefinitionService.revalidateAllForSuite()` is called synchronously within the same transaction, before the response is returned. This is intentionally separate from `isSchemaChanged()` (which drives the async test-case loop and does not include `responseColumns`). `responseColumns` is NOT added to `isSchemaChanged()`.

**Trigger 2 — manual revalidation endpoint**: `RevalidationService.runRevalidationAsync()` calls `testSuiteMetricDefinitionService.revalidateAllForSuite()` synchronously before the paginated test-case loop begins. The primary use case is restoring accurate validation state after deploy: existing TSMD rows default to `is_valid = true` from the migration regardless of actual binding correctness. Metric declaration versions are immutable (append-only); switching a TSMD to a new version is a TSMD update and triggers inline revalidation via trigger 1 path.

**Split-commit behavior when called from `runRevalidationAsync`**: Because `runRevalidationAsync` is an `@Async` method running in a new thread with no active transaction, `revalidateAllForSuite()` opens and commits its own transaction atomically covering all per-TSMD `updateValidation()` calls. This transaction commits and closes **before** `runRevalidationAsync` continues to the test-case loop. If the test-case loop subsequently fails (e.g., a test-case revalidation error marks the task FAILED), the TSMDs are already in their updated, durable state. This split-commit behavior is **intentional** — TSMD validation state should be durable regardless of the test-case loop outcome. Do NOT wrap `runRevalidationAsync` in a single outer transaction in an attempt to unify the commit; doing so would defeat the async pattern and couple two independent operations whose failure modes are orthogonal.

**Alternative considered (original design)**: Place TSMD revalidation only inside `runRevalidationAsync()`. Rejected because `responseColumns` changes do not trigger `startRevalidation()` — test cases do not reference response columns, so the existing `isSchemaChanged()` gate was never extended to cover them. This would silently leave `Response` bindings stale after a suite update.

### D6: DB migration — existing rows default to `is_valid = true`

**Decision**: `is_valid BOOLEAN NOT NULL DEFAULT TRUE`. Existing TSMDs that may have broken bindings will be marked valid until the next suite revalidation is triggered. This is acceptable — the pre-existing behavior was also silent-failure, so no regression.

**Alternative**: Default `is_valid = false` and require explicit revalidation. Rejected — would immediately break evaluation runs for existing suites without any user action.

## Risks / Trade-offs

- **No stale validation state in steady state**: TSMD validation is refreshed on every TSMD create/update (inline) and on every suite update that touches `testCaseSchema` or `responseColumns` (auto-trigger). Switching a TSMD to a new metric declaration version is a TSMD update and also triggers inline revalidation. The only known source of stale state is the migration default (`is_valid = true` for all pre-existing rows), which users resolve by calling the manual revalidation endpoint once after deploy.
- **JSON Schema parse robustness**: Metric config/input schemas are stored as JSONB. If a schema is malformed (e.g., stored without `"properties"` key), the validation service skips property-level checks and logs a warning. This avoids blocking saves when the schema itself is broken.
- **`null` constant value serialization**: Removing `@NotNull` from `ConstantBindingSourceDto.value` means `null` is now accepted by Jackson deserialization. The global `NON_NULL` `ObjectMapper` setting means serialization of `Map<String, Object>` with null values drops the key. Callers embedding null-valued bindings in DIAL request payloads must use `ObjectNode.putNull()` — this is already documented in AGENTS.md and is not a new constraint.

## Migration Plan

1. Flyway migration `V?.??__AddTsmdValidationColumns.sql` (next available version):
   ```sql
   ALTER TABLE test_suite_metric_definitions
     ADD COLUMN is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
     ADD COLUMN is_valid BOOLEAN NOT NULL DEFAULT TRUE,
     ADD COLUMN validation_warnings JSONB NOT NULL DEFAULT '[]';
   ```
2. No data backfill needed — defaults are safe (see D6).
3. Deploy application — new columns picked up by updated RowMapper.
4. Existing evaluation runs unaffected — all existing TSMDs are `is_enabled = true, is_valid = true` by default.
5. After deploy, users may trigger `POST /api/v1/test-suites/{id}/revalidation` to compute accurate validation state for existing TSMDs.

## Open Questions

None — all decisions resolved during design exploration.
