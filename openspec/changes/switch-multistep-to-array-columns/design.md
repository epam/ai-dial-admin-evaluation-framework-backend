## Context

The multi-step POC (archived `2026-06-29-multi-step-conversation-poc`) stores per-turn
bindings in a suite-level `multistepInputBindings` (`List<List<InputBindingDto>>`) plus a
`test_suites.multistep_input_bindings` JSONB column. Turn count is thus fixed in suite
config. Real usage needs turn count to vary **per test case**: each test case supplies its
own conversation length. Suite-level per-turn bindings cannot express that.

The execution machinery already supports what we need: `ResolvedRequestService.resolve(
template, bindings, data)` substitutes `${{var}}` placeholders from a flat data map via
`TemplateVariableResolver` (constantValue wins → dataField lookup → default). So per-turn
resolution is just "project the data map for turn `i`, then call the existing resolver."
`BindingValidator` does not type-check a bound column against its placeholder, so an
`ARRAY`-typed column bound to a scalar `${{var}}` already validates with the normal path.

## Goals / Non-Goals

**Goals:**
- Drive turn count per test case from array-valued bound columns.
- Remove all suite-level per-turn binding config (`multistepInputBindings`) and its column.
- Keep the `multiStep` flag as the only multi-turn suite config.
- Reuse existing resolution/validation/extraction; keep result shape and metric normalization.
- Isolate per-test-case data problems (fail that case, continue the run).

**Non-Goals:**
- Renaming `multiStep` → `multiTurn` (kept as-is to minimize churn).
- Changing per-turn template *structure* (template still describes one turn).
- Enforcing dataset field type == `ARRAY` (validation stays permissive, as today).
- Streaming multi-turn; "whole array as a single-turn value" semantics.
- Back-compat with the removed field (POC is unreleased).

## Decisions

### D1: Turn count derived per test case, in the executor
`N` = common length of array-valued columns referenced by the single `inputBindings`
`dataField`s, computed inside `MultiStepConversationExecutor.execute(...)` (runs once per
test case). Scalars and `constantValue` bindings broadcast. Alternative — a suite-level
`turns` count — rejected: it re-introduces the exact rigidity we are removing.

### D2: Per-turn data projection reuses `ResolvedRequestService.resolve`
For turn `i`, build `perTurnData` = shallow copy of the test-case data with each iterating
(array-valued) field replaced by its `i`-th element; call the existing
`resolve(template, inputBindings, perTurnData)`. No new templating code. Alternative — a
bespoke per-turn resolver — rejected as duplication.

### D3: Validation reduces to the normal binding path + messages-body check
`SuiteValidationService` for `multiStep == true` runs
`bindingValidator.validate(variables, inputBindings, testCaseSchema, suiteId)` plus the
existing "JSON body with top-level `messages` array" check. The non-empty / step-cap /
per-step-loop logic is deleted with the field. Array-length/cap/no-array checks are
per-test-case runtime concerns, not suite validity (config cannot see data).

### D4: Failure isolation via result status, not exceptions
Data problems (length mismatch, `N` > cap, no array column) produce a single `ERROR`
`TestCaseRunResult` for that case and return normally, so the worker/run proceeds with
other cases. This mirrors the existing fail-fast persistence path (partial history, no
throw across the case boundary).

### D5: Schema drop via Flyway + jOOQ regen
`V1.24__DropMultistepInputBindingsFromTestSuites.sql` drops the column; run
`./gradlew generateJooq` and commit the regenerated `TestSuites`/`TestSuitesRecord`. The
`multi_step` boolean column stays.

## Risks / Trade-offs

- [An array-valued column a user intended to pass whole to one turn will now be iterated]
  → Documented contract: in `multiStep` mode arrays iterate. Whole-array-per-turn is out of
  scope; use `constantValue` or a scalar column for constants.
- [Dropping the column is irreversible on the data] → Acceptable: POC is unreleased and the
  feature semantics change; rollback = revert the migration + code together (no prod data).
- [Permissive validation lets a non-array column pass config validation, failing only at run]
  → Intended: turn count is per-test-case data, surfaced as a clear per-case `ERROR`.
- [Two metric call sites already normalize `extractedColumns`] → Unchanged; this change does
  not touch `ExtractedColumnsNormalizer` or its call sites.

## Migration Plan

1. Add Flyway `V1.24` dropping `multistep_input_bindings`; `generateJooq`; commit generated diff.
2. Remove the field from model/mapper/repository/DTOs/mappers/JsonbMapper/SuiteSnapshotDto/
   SuiteSnapshotBuilder/EvaluationContext/TestSuiteEvaluationJob.
3. Reduce `SuiteValidationService.validateMultiStep`.
4. Rewrite `MultiStepConversationExecutor` turn derivation + per-turn projection.
5. Update tests, `docs/database-schema.md`, `AGENTS.md`, and specs.

Rollback: revert the change set (code + migration) together; no production data depends on
the dropped column (unreleased POC).

## Open Questions

None — the four shaping decisions (replace, arrays-iterate/scalars-broadcast, per-test-case
failure isolation, keep `multiStep` naming) are settled.
