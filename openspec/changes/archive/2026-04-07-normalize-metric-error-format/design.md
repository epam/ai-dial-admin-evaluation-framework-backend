## Context

When the metric evaluation engine encounters a transport failure (HTTP 500, timeout, retries exhausted), `MetricOutputMapper.buildMetricValues()` produces `{"tsmdName": {"error": null}}` — a synthetic `"error"` key. Successful evaluations use real output field names (e.g., `{"tsmdName": {"score": 0.95}}`). This asymmetry forces clients to branch on key names rather than simply checking for `null` values.

The output schema is available at evaluation time via `AggregatedMetricDefinition.versionOutputSchema`, and is also snapshotted in `run_metric_snapshots.output_schema` — providing both the runtime source and the migration JOIN path.

Current code path:
```
MetricOutputMapper.buildMetricValues(Map<String, Object> tsmdResults)
  → for each tsmd: if Exception → tsmdNode.putNull("error")
```

## Goals / Non-Goals

**Goals:**
- Normalize `metricValues` shape so all TSMD entries always use real output field names as keys
- Validate output schema during TSMD soft validation to catch invalid schemas early
- Migrate existing error-shaped data in `test_case_eval_summaries`

**Non-Goals:**
- Changing the `metricInfos` structure for non-transport-failure entries (field-level errors from metric responses retain their existing per-field format)
- Modifying the batch-write API validation contract (null leaf values already accepted)
- Adding output schema validation during metric provider sync (sync stores as-is; validation happens at TSMD binding time)

## Decisions

### Decision 1: Replace `Map<String, Object>` with sealed `TsmdEvaluationResult` type

**Choice**: Introduce a sealed interface `TsmdEvaluationResult` in `service.domain.job` with two record variants: `Success(EvaluationResponseDto response, List<String> outputFieldNames)` and `Failure(Exception error, List<String> outputFieldNames)`. Replace the untyped `Map<String, Object> tsmdResults` in `InProcessMetricEvaluationExecutor` with `Map<String, TsmdEvaluationResult>`. Update `MetricOutputMapper.buildMetricValues()` and `buildMetricInfos()` to accept `Map<String, TsmdEvaluationResult>`. Update `checkForErrors()` similarly.

**Alternatives considered**:
- *Parallel Map (`Map<String, List<String>> tsmdOutputFields`)* : Minimal change, but papers over the existing code smell of `Map<String, Object>` with `instanceof` chains in three places. Each new field would require yet another parallel map.
- *Passing full output schema*: Unnecessarily couples the mapper to JSON Schema parsing.

**Rationale**: The current `Map<String, Object>` is a stringly-typed union (`EvaluationResponseDto | Exception`) that forces `instanceof` checks in `MetricOutputMapper.buildMetricValues()`, `buildMetricInfos()`, and `checkForErrors()`. A sealed interface with Java 21 pattern matching eliminates all three `instanceof` chains, co-locates output field names with the result they belong to, and makes the type system enforce exhaustiveness. The change scope is contained to two files (`InProcessMetricEvaluationExecutor`, `MetricOutputMapper`) with no API surface changes.

### Decision 2: Extract output field names in a dedicated injectable component

**Choice**: Create `OutputSchemaFieldExtractor` as an injectable `@Component` in `service.domain` that extracts `List<String>` field names from an output schema JSON string. Returns empty list for null/malformed schemas.

**Rationale**: Follows the project convention of specialized injectable components for conversion logic (per AGENTS.md). Used by both `InProcessMetricEvaluationExecutor` (runtime) and `MetricDefinitionValidationService` (validation).

### Decision 3: New validation warning code INVALID_OUTPUT_SCHEMA

**Choice**: Add `INVALID_OUTPUT_SCHEMA` to `ValidationWarningCode` enum. Validation in `MetricDefinitionValidationService` checks that `output_schema` is non-null, has a `"properties"` key that is a JSON object, and has at least one property. TSMDs with invalid output schemas get `is_valid = false`.

**Rationale**: Consistent with existing validation pattern (UNRESOLVED_REFERENCE, REQUIRED, ADDITIONAL). Catches bad schemas early rather than at evaluation time.

### Decision 4: Transport failure metricInfos normalization

**Choice**: When a transport failure occurs, `metricInfos` changes from `{"tsmdName": {"error": "message"}}` to `{"tsmdName": {"field1": {"error": "message"}, "field2": {"error": "message"}, ...}}` — one error entry per output field.

**Rationale**: Matches the existing field-level error structure in `metricInfos`. Clients can use a uniform lookup: `metricInfos[tsmdName][fieldName].error` works for both field-level errors and transport failures.

### Decision 5: Flyway migration uses SQL-only JSONB manipulation

**Choice**: `V1.8__NormalizeErrorShapedMetricValues.sql` uses a CTE that JOINs `test_case_eval_summaries` with `run_metric_snapshots` on `computation_id`, identifies TSMD entries with the `"error"` key pattern, and rebuilds the JSONB using `jsonb_object_agg` with field names from `output_schema->'properties'`.

**Rationale**: Pure SQL avoids needing application-level migration code. The JOIN path (`computation_id` + `tsmd_name`) is indexed on both sides. The migration also rebuilds corresponding `metric_infos` entries.

### Decision 6: Fallback for missing output schema at runtime

**Choice**: If a TSMD has an empty/invalid output schema at evaluation time (shouldn't happen after validation, but defense-in-depth), produce an empty object `{}` in `metricValues` for that TSMD and record the error only in `metricInfos` (`{"tsmdName": {"error": "message"}}`). Log a WARN.

**Rationale**: Validation (Decision 3) prevents this path in practice. On the rare edge case (race condition between schema update and evaluation), an empty `{}` is honest — we don't know the fields, so we don't pretend. The error is still discoverable via `metricInfos`. This avoids introducing the synthetic `"error"` key that this entire change is eliminating.

### Decision 7: Migration handles orphaned/malformed records gracefully

**Choice**: The migration only updates records where a matching `run_metric_snapshots` row exists AND `output_schema->'properties'` has at least one key. Records without a matching snapshot or with empty schemas are left unchanged.

**Rationale**: Avoids data corruption for edge cases (orphaned computations, metrics synced before schema support).

## Risks / Trade-offs

- **[Breaking API change]** → Clients checking for `"error"` key in `metricValues` will break. Mitigation: document in release notes; the new format is simpler (always check `value === null`).
- **[Migration on large tables]** → `test_case_eval_summaries` could have millions of rows, but the migration only touches rows with `"error"` keys (transport failures are a small fraction). The WHERE clause limits scope. Mitigation: the migration uses indexed JOINs.
- **[Schema availability gap]** → Old metric declarations synced before `output_schema` was stored may have empty `{}` schemas. Mitigation: migration skips these (Decision 7); validation catches them going forward (Decision 3).

## Migration Plan

1. Deploy code changes (MetricOutputMapper, validation, OutputSchemaFieldExtractor)
2. Flyway V1.8 auto-applies on startup, migrating existing error-shaped records
3. No rollback needed — the old format is a subset of valid formats (null leaf values were always accepted)
