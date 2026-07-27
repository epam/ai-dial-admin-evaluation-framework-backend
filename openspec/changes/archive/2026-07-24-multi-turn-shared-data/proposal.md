## Why

Today a multi-turn test case must carry **all** of its data per-turn: the DB CHECK `chk_test_cases_multi_turn_exclusive` forces `data = '{}'` whenever `multi_turn_data` is present. There is no way to attach test-case-level data that stays constant across turns — e.g. a `tags` field used to filter cases into a run, or a shared context/system-prompt injected on every turn. Authors are forced to repeat such values in every turn map, which is redundant, error-prone, and makes turn-level intent ("what actually varies between turns") unclear.

This change lets `data` and `multiTurnData` coexist on one test case, with the dataset schema declaring, per field, whether it is **shared** (test-case-level) or **per-turn**.

## What Changes

- **BREAKING (pre-merge, unshipped):** `data` and `multiTurnData` may now be populated **together**. The mutual-exclusivity rule is removed — both the app-level 400 and the DB CHECK `chk_test_cases_multi_turn_exclusive`. The multi-turn discriminator stays `multiTurnData != null`.
- **Per-field scope in the schema:** `FieldDefinitionDto` gains `perTurn` (boolean, default `false` = shared), persisted in the existing `datasets.test_case_schema` JSONB. No DB column change. Absent ⇒ shared, so existing schemas and all single-turn cases are byte-identical.
- **Buckets:** shared fields live in `data`; per-turn fields live in each `multiTurnData[i]`. A field is exactly one scope.
- **Merged effective view:** each turn's effective data = `merge(shared data, multiTurnData[i])`. This merged map feeds template resolution, the conditional-metric JSONata `data`, and metric input — so a shared field is usable in prompts, conditions, and metrics on every turn.
- **Placement validation:** a field placed in the wrong bucket (per-turn field in `data`, or shared field in a turn map) → **hard 400** at create/PATCH/batch. Missing-required and type-mismatch remain warnings (invalidate case), checked per scope: required-shared against `data`, required-per-turn in every turn.
- **PATCH independence:** patching `data` and patching `multiTurnData` no longer clear each other; they update the two buckets independently.
- **Scope-aware filtering:** the suite `testCaseFilter` binds shared fields to the outer row's `data` (row-level, constant) and per-turn fields to the turn element under the existing ALL-turns-match wrapper; a filter may mix both.
- **CSV:** shared columns are repeated on every turn row of a case; import validates they are identical across a case's rows (mismatch → conflict warning). Per-turn columns vary per row. Single-turn rows unchanged.
- **Empty per-turn maps allowed:** a multi-turn case whose turn maps are all `{}` (all schema fields shared) is valid provided no required per-turn field is unmet — the turn count alone drives N iterations over shared context.

Everything is additive at the API/DTO level except the removal of the mutual-exclusivity rule; there are no new endpoints, paths, or status codes beyond the placement 400 (reusing `VALIDATION_ERROR`).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `datasets`: `FieldDefinitionDto` / `testCaseSchema` gains an optional `perTurn` boolean (default `false`), with validation.
- `test-cases`: `data` and `multiTurnData` may coexist; DB CHECK and mutual-exclusivity 400 removed; per-scope validation with hard-400 placement checks; PATCH updates the two buckets independently.
- `multi-turn-conversation`: turn execution resolves against the merged (shared + per-turn) view; the "mutually exclusive" scenario is replaced by "coexist"; CSV multiplication repeats/validates shared columns.
- `suite-test-case-filter`: filter binding is scope-aware — shared fields bind to the outer row's `data`, per-turn fields to the turn element.
- `conditional-metric-execution`: the JSONata dictionary's `data` is the merged (shared + per-turn) view.

## Impact

**Code (primary touch points):**
- `service.domain.dto.FieldDefinitionDto` (+`perTurn`); dataset schema validation in `DatasetService` / schema validator.
- `service.domain.MultiTurnFieldsValidator` (drop mutual-exclusivity; add scope-placement 400), `TestCaseValidationService.validateMultiTurn` (per-scope required/type checks), `TestCaseService.applyMergePatch` (remove opposite-field clearing).
- `service.domain.job.MultiTurnExecutor` (merge shared `data` with each turn before resolving), `service.domain.ConditionExpressionEvaluator` (merged `data`).
- `experimental.query.service.TestCaseFieldBindingsBuilder` (scope-aware bindings), `TestCasesSchemaProvider` (annotate scope), `QueryDslRunnableTestCaseSelector` (consume scoped bindings).
- `service.domain.CsvExportService` / `CsvImportService` (repeat + validate shared columns).

**Database:** edit meta migration `V1.27__AddMultiTurnDataToTestCases.sql` to keep the `multi_turn_data JSONB` column add but **drop** the `chk_test_cases_multi_turn_exclusive` CHECK. No new column (scope lives in `test_case_schema` JSONB). Testcontainers/CI rebuild fresh, so the Flyway checksum change is safe here; jOOQ regeneration not required (no column/type change). `docs/database-schema.md` updated to drop the CHECK.

**API / OpenAPI:** `@Schema` for `perTurn` on `FieldDefinitionDto`; multi-turn example updated to show `data` + `multiTurnData` coexisting with a shared field.

**Config:** none.

**Docs:** `docs/database-schema.md`, `AGENTS.md` (multi-turn inline convention), and `openspec/specs/README.md` summaries for the modified capabilities.
