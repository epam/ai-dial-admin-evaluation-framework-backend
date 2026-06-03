## 1. OutputSchemaFieldExtractor Component

- [x] 1.1 Create `OutputSchemaFieldExtractor` in `service.domain` — injectable `@Component` with `@LogExecution`, `@Slf4j`. Method `extractFieldNames(String outputSchema)` returns `List<String>` of field names from `output_schema.properties` keys. Handles null/blank input, missing `properties` key, non-object `properties`, malformed JSON — all return empty list with WARN log. (done: component exists, handles all edge cases from spec)
- [x] 1.2 Unit test `OutputSchemaFieldExtractorTest` — covers: valid single-field schema, valid multi-field schema, null input, blank input, empty `{}` schema, schema without `properties`, `properties` as non-object, malformed JSON. (done: all scenarios pass)

## 2. TSMD Output Schema Validation

- [x] 2.1 Add `INVALID_OUTPUT_SCHEMA` to `ValidationWarningCode` enum with description "Metric output schema is missing, empty, or malformed". (done: enum value exists)
- [x] 2.2 Inject `OutputSchemaFieldExtractor` into `MetricDefinitionValidationService` as a constructor dependency. Extend `validate()` to check output schema validity by calling the injected `OutputSchemaFieldExtractor.extractFieldNames()`. If result is empty, add `INVALID_OUTPUT_SCHEMA` warning and set `is_valid = false`. Pass `versionOutputSchema` as a new parameter to `validate()`. All three call sites in `TestSuiteMetricDefinitionService` (create, update, revalidateAll) must pass the metric declaration version's output schema to the updated `validate()` method. (done: validation runs on create/update/revalidation, produces correct warning)
- [x] 2.3 Unit test additions to `MetricDefinitionValidationServiceTest` — covers: valid output schema passes, null output schema fails with INVALID_OUTPUT_SCHEMA, empty properties fails, existing binding checks still apply independently. (done: all scenarios pass)
- [x] 2.4 Functional test for TSMD with invalid output schema — create a TSMD referencing a metric declaration version with empty output schema, verify response has `valid = false` and `validationWarnings` contains `INVALID_OUTPUT_SCHEMA`. (done: test passes)

## 3. TsmdEvaluationResult Sealed Type & MetricOutputMapper Refactor

- [x] 3.1 Create sealed interface `TsmdEvaluationResult` in `service.domain.job` with two record variants: `Success(EvaluationResponseDto response, List<String> outputFieldNames)` and `Failure(Exception error, List<String> outputFieldNames)`. (done: sealed interface and records exist)
- [x] 3.2 Update `InProcessMetricEvaluationExecutor.evaluateAndBuild()` — replace `Map<String, Object> tsmdResults` with `Map<String, TsmdEvaluationResult>`. Extract output field names per TSMD using `OutputSchemaFieldExtractor` before the async loop. Wrap async results as `Success`/`Failure` with field names. Update timeout fallback to produce `Failure` with pre-extracted field names. (done: executor uses typed results throughout)
- [x] 3.3 Update `InProcessMetricEvaluationExecutor.checkForErrors()` to accept `Map<String, TsmdEvaluationResult>` — replace `instanceof Exception`/`instanceof EvaluationResponseDto` with pattern matching on sealed type. (done: method uses typed results)
- [x] 3.4 Update `MetricOutputMapper.buildMetricValues()` to accept `Map<String, TsmdEvaluationResult>`. Use pattern matching: `Success` → `mapResponseValues()`, `Failure` with non-empty field names → putNull per field, `Failure` with empty field names → empty `{}` (no synthetic keys). (done: method uses sealed type with pattern matching)
- [x] 3.5 Update `MetricOutputMapper.buildMetricInfos()` to accept `Map<String, TsmdEvaluationResult>`. Use pattern matching: `Success` → `buildResponseInfos()`, `Failure` with non-empty field names → `{"fieldName": {"error": "message"}}` per field, `Failure` with empty field names → `{"error": "message"}` (error only in metricInfos). (done: method uses sealed type with pattern matching)
- [x] 3.6 Update `MetricOutputMapperTest` — adjust all tests to use `TsmdEvaluationResult.Success`/`Failure` instead of raw `EvaluationResponseDto`/`Exception`. Add transport-failure test with real field names, fallback test with empty field names, mixed success/failure test. (done: all scenarios pass)

## 4. Flyway Data Migration

- [x] 4.1 Create `V1.8__NormalizeErrorShapedMetricValues.sql` in `db/migration/analytics/POSTGRES/`. SQL: JOIN `test_case_eval_summaries` with `run_metric_snapshots` on `computation_id`, identify TSMD entries in `metric_values` with `{"error": null}` pattern and corresponding entries in `metric_infos` with `{"error": "<string>"}` pattern, rebuild JSONB using field names from `output_schema->'properties'`. Skip rows with no matching snapshot or empty output schema. Handle `metric_infos IS NULL` gracefully (skip `metric_infos` update when null). Both `metric_values` and `metric_infos` columns SHALL be updated in the same UPDATE statement to ensure coordinated, atomic transformation per row. **Implementation hint**: Use `CASE WHEN metric_infos IS NOT NULL THEN <rebuilt_infos> ELSE metric_infos END` for the `metric_infos` column assignment to preserve NULL when no metric_infos existed. (done: migration applies cleanly, transforms error entries)
- [x] 4.2 Update `docs/database-schema.md` to note that `metric_values` TSMD entries always use real output field names (no synthetic `"error"` key). (done: doc updated)

## 5. Existing Test Updates

- [x] 5.1 Update `EvalSummaryFunctionalTests` — verify that transport-failure eval summaries use real output field names in `metricValues`. Update any assertions that check for the `"error"` key pattern. (done: tests pass with new format)
- [x] 5.2 Update `InProcessMetricEvaluationExecutorTest` (if exists) or relevant integration test — verify the executor passes field names to the mapper correctly. (done: test passes)
- [x] 5.3 Run full build `./gradlew clean build` — checkstyle + all tests pass. (done: green build)

## 6. Spec Sync

- [x] 6.1 Update `openspec/specs/metric-evaluation/spec.md`, `openspec/specs/tsmd-validation/spec.md`, and `openspec/specs/metrics-storage/spec.md` with changes from delta specs. (done: main specs reflect new requirements)
