## Context

Multi-turn test cases (single `test_cases` row with a `multi_turn_data JSONB` turn array) currently force `data = '{}'` whenever `multi_turn_data` is present — enforced by both `MultiTurnFieldsValidator` (400) and the DB CHECK `chk_test_cases_multi_turn_exclusive`. Consequently **every** field of a multi-turn case must be repeated in every turn map. There is no place for test-case-level data that stays constant across turns (e.g. a `tags` field for run selection, or a shared context injected each turn).

The multi-turn feature lives only on the unmerged `feat/17-...` branch (migrations `V1.26–28`, DTO fields, `MultiTurnExecutor`, the ALL-turns filter). There is no production multi-turn data. Single-turn cases, however, are in production and must remain byte-identical. Dataset schema is `datasets.test_case_schema` JSONB → `List<FieldDefinitionDto>`.

## Goals / Non-Goals

**Goals:**
- Allow `data` (shared) and `multiTurnData` (per-turn) to coexist on one test case.
- Let the dataset schema declare, per field, whether it is shared or per-turn.
- Make shared fields usable everywhere a turn's data is used: template resolution, conditional-metric JSONata, metric input, and run-selection filtering.
- Keep single-turn behavior and all existing schemas byte-identical.

**Non-Goals:**
- No first-class `tags` type or metadata namespace — shared fields are ordinary schema fields with `perTurn=false`.
- No change to the turn-loop execution model, fail-fast, per-turn analytics rows, snapshot shape, or MCP guard.
- No migration of existing data (scope lives in JSONB schema; no column change).
- No new endpoints, paths, or status codes (placement error reuses `VALIDATION_ERROR`).

## Decisions

### D1 — Per-field scope declared in the schema via `perTurn` boolean (default false = shared)
Add `perTurn` (boolean) to `FieldDefinitionDto`, persisted in `test_case_schema` JSONB. Absent ⇒ shared, so existing schemas and single-turn cases are unchanged.
- *Why:* Schema is the single source of truth for field shape; scope is a shape concern. A boolean is the minimal addition; default-shared is the safe interpretation and gives the better failure mode (forget the flag → a per-turn value lands in a "shared" bucket → caught by placement validation).
- *Alternatives:* (a) dedicated `tags`/metadata field — too narrow, doesn't cover arbitrary shared fields; (b) enum `scope` — more verbose, no current need for a third scope; (c) default per-turn — flips existing schemas, worse failure mode.

### D2 — Buckets + merged effective view
Shared fields live in `data`; per-turn fields live in each `multiTurnData[i]`; a field is exactly one scope. A turn's **effective view = merge(data, multiTurnData[i])** with per-turn keys winning on overlap (overlap shouldn't occur given disjoint scopes + placement validation). This merged map is what `MultiTurnExecutor.runTurn` resolves the template against and what feeds the conditional-metric `data` namespace and metric input.
- *Why:* One consistent "what the turn sees" everywhere; matches "which data varies vs stays constant." Single-turn's effective view is just its `data`, so nothing changes for single-turn.
- *Alternatives:* filter-only shared fields (smaller blast radius but shared data unusable in prompts/conditions — rejected, too limiting); separate `shared` namespace in templates/conditions (more concepts and API surface — rejected).

### D3 — Placement is a hard 400; content issues stay warnings
A per-turn field found in `data`, or a shared field found in a turn map, is a structural error → HTTP 400 `VALIDATION_ERROR` at create/PUT/PATCH/batch (in `MultiTurnFieldsValidator`, which also drops the old mutual-exclusivity rejection but keeps empty-array rejection). Missing-required and type-mismatch remain invalidating **warnings**, checked per scope: required-shared against `data`, required-per-turn in every turn (`TestCaseValidationService.validateMultiTurn`).
- *Why:* Placement is unambiguous author error and cheap to check up front; content validity follows the existing warning convention (schema-shape problems don't 400 today).
- *Alternatives:* placement as warning (loses the safety net for the exact thing the feature controls); tolerate silently (a per-turn field in `data` silently becomes constant — the failure this feature exists to prevent).

### D4 — PATCH updates the two buckets independently
Remove the opposite-field clearing in `TestCaseService.applyMergePatch`: patching `data` updates the shared bucket only; patching `multiTurnData` updates the per-turn bucket only; `multiTurnData: null` reverts to single-turn. Placement + per-scope validation run after the merge.
- *Why:* With coexistence, implicit clearing would destroy the other bucket's data. (The bulk selector-based patch path already doesn't clear; this aligns the per-item path.)

### D5 — Scope-aware filter bindings
`TestCaseFieldBindingsBuilder` consults each field's `perTurn` flag: shared fields bind to the outer row's `data` (row-level, constant); per-turn fields bind to the per-turn element `elem`. `QueryDslRunnableTestCaseSelector.compile()` keeps the `NOT EXISTS(jsonb_array_elements(coalesce(multi_turn_data, jsonb_build_array(data))) … WHERE (<filter>) IS NOT TRUE)` lateral; shared references remain valid because the lateral is correlated to the outer row. `TestCasesSchemaProvider.detailedSchema` annotates each `data::<field>` with its scope for discovery.
- *Why:* A shared field is constant across turns, so quantifying it over turns is meaningless — bind it once at row level. Mixed filters just AND the two.

### D6 — CSV repeats shared columns on every turn row, validates identical on import
Export writes shared columns on every turn row of a case. Import resolves each column's scope from the dataset schema, requires a case's shared columns to be identical across its rows (mismatch → conflict warning, invalidate), and assembles per-turn columns into `multiTurnData`. Single-turn rows unchanged.
- *Why:* Self-contained rows survive spreadsheet sort/filter; unambiguous per row. Consistency check reuses the importer's existing conflict-warning channel.
- *Alternative:* shared on first row only (compact but order-sensitive and ambiguous "blank = inherit").

### D7 — Remove the CHECK by editing migration V1.27 in place
Edit `V1.27__AddMultiTurnDataToTestCases.sql` to keep the `multi_turn_data` column add but drop the `chk_test_cases_multi_turn_exclusive` CHECK. No new column (scope is in JSONB), so no jOOQ regeneration.
- *Why:* Branch is unmerged and Testcontainers/CI rebuild fresh, so there's no persistent DB whose Flyway checksum matters; a clean final history beats an add-then-drop-a-constraint pair.
- *Risk:* a developer with a persistent local DB that already ran V1.27 hits a checksum mismatch → resolved by a clean/Flyway repair (documented in tasks).

## Risks / Trade-offs

- **[Same field name, different physical location across case types]** In a single-turn case all fields live in `data`; in a multi-turn case a per-turn field lives in `multiTurnData[i]`. → Mitigation: scope is schema-uniform and validation/binding/CSV all derive location from the schema flag, so no code branches on "guessing" location.
- **[Editing an archived change's migration]** V1.27 is attributed to the archived multi-turn change. → Mitigation: this is a deliberate, documented pre-merge amendment; the delta spec + this design record why.
- **[Merged-view precedence on accidental overlap]** If a key appears in both buckets (should be blocked by placement 400), per-turn wins. → Mitigation: placement validation makes overlap unreachable via the API; the precedence rule is a defensive tiebreak only.
- **[Filter correlation correctness]** Shared-field predicates must reference the outer `data` from inside the `NOT EXISTS` lateral. → Mitigation: bindings for shared fields target `TEST_CASES.DATA` (outer), not `elem`; covered by a functional test mixing shared + per-turn predicates.

## Migration Plan

1. Edit `V1.27` to drop the CHECK (keep column). Developers with a persisted local Postgres run `./gradlew flywayRepair`-equivalent or recreate the DB; CI/Testcontainers unaffected.
2. Add `perTurn` to `FieldDefinitionDto` + dataset schema validation; update OpenAPI `@Schema`.
3. Rework validation (`MultiTurnFieldsValidator`, `TestCaseValidationService`), PATCH (`applyMergePatch`), executor merge (`MultiTurnExecutor`), condition dict (`ConditionExpressionEvaluator`), filter bindings (`TestCaseFieldBindingsBuilder` / `TestCasesSchemaProvider`), and CSV (`CsvExportService` / `CsvImportService`).
4. Update `docs/database-schema.md`, `AGENTS.md`, `openspec/specs/README.md`, and the multi-turn OpenAPI example.
5. **Rollback:** revert code; re-adding the CHECK is only safe if no coexisting-data rows exist. Since the feature is unmerged, rollback is a branch revert.

## Open Questions

None — all model, validation, execution, filter, CSV, and migration decisions are locked (see Decisions).
