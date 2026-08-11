## Why

Importing an exported multi-turn CSV into a dataset with an empty `testCaseSchema` (GitHub #120 follow-up) still fails: every undeclared column defaults to shared scope, so `MultiTurnRunAssembler` sees the case's turn rows "disagree" on shared columns, flags `sharedConflict`, invalidates the case, and discards turns 2..N. Preview reports the same false conflicts. A multi-turn dataset therefore cannot be moved between environments via CSV unless the destination schema is authored by hand first.

## What Changes

- A CSV data column with no same-named field in the dataset's current schema (undeclared) no longer unconditionally defaults to shared. Its scope is inferred from the CSV's turn structure by a **file-level gate**: when the CSV contains at least one multi-turn case (a contiguous same-`testCaseName` run where some row's `turnIndex` parses to an integer), every undeclared column becomes `perTurn: true`; when the CSV contains no multi-turn case, every undeclared column stays shared (`perTurn` absent) — today's behavior. (Per-column granularity is not achievable in the existing pipeline: `parseRow` materializes blank cells as `""`, so every data-bound column key is present on every row and column-level turn membership cannot be observed; see design D3.)
- Declared fields keep their scope verbatim — including declared-shared (`perTurn` absent in the current schema), whose turn-row disagreements still raise the shared-conflict warning. The declared/undeclared distinction is `containsKey`, not `get != null`.
- Inference is **single-pass**, riding the existing streaming pipeline: during streaming, the validation schema treats undeclared columns as per-turn (harmless for single-turn cases, whose validation path never consults scope); the persisted schema and preview's `autoDetectedSchema` — both already computed after streaming, like type inference — apply the observed membership set.
- Preview and import share the inference, so `autoDetectedSchema` reports exactly the scope import would persist.
- No new endpoint, DTO field, configuration property, reserved CSV column, or Flyway migration. `MultiTurnRunAssembler`, `TestCaseFieldScopeResolver`, and export are unchanged.

Not in scope (deliberately): a `turns` total-count CSV column (new reserved header name — backward-compatibility cost, separable completeness-check follow-up); turn-variance inference (keeping constant columns shared); ZIP export multi-turn blindness; `DatasetCloneService` multi-turn handling; non-contiguity as a hard 400 (stays a warning).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `multi-turn-test-case`: the *CSV schema rebuild preserves per-field scope* requirement changes — scope of an undeclared column IS now derived from the CSV's turn structure (file-level gate) instead of always defaulting to shared; the *New CSV column defaults to shared* and *Empty schema has nothing to preserve* scenarios are conditioned on the gate; the shared-column conflict rule is scoped to declared-shared columns.

## Impact

- **Code**: `service.domain.csv.CsvSchemaFieldBuilder` (scope tiers: declared → observed-in-multi-turn → shared), `service.domain.CsvImportService` (accumulate the multi-turn column set during the existing streaming loop, thread it into `buildValidationSchema` / `persistSchema` / `buildFinalSchema` / `buildAutoDetectedSchema`). No other production classes.
- **API**: no contract change. Behavior change: previews/imports of multi-turn CSVs against schema-less datasets stop reporting shared-conflict warnings and start persisting `perTurn: true` fields; `autoDetectedSchema` reflects it.
- **DB**: none. `test_case_schema` JSONB gains `"perTurn": true` entries it could already hold.
- **Docs/specs**: `multi-turn-test-case` delta spec; AGENTS.md inline convention bullet ("a column with no same-named current field is new and gets `perTurn` absent") must be updated; `openspec/specs/README.md` multi-turn summary mentions flat CSV multiplication — check for material inaccuracy.
- **Risk**: in a schema-less dataset, a multi-turn CSV makes **every** undeclared column per-turn — including a multi-turn case's genuinely-shared columns (constant across turns) and, in a mixed file, columns used only by single-turn cases — data-lossless (values duplicated per turn; merged effective view identical; export round trip byte-stable since export already writes shared values on every turn row), but the shared designation is not recoverable from a CSV without variance analysis. Accepted trade-off vs. the rejected two-pass variance design.

## Test Plan

- Unit: `CsvSchemaFieldBuilderTest` (scope tiers, declared-shared beats membership — the `containsKey` regression guard), `CsvImportServiceSchemaTest` (schema derivation with membership set).
- Functional (`MultiTurnCsvFunctionalTests`): multi-turn CSV into an empty dataset persists per-turn fields, preserves all turns, no conflict; export → import into schema-less dataset → export round trip; declared-shared conflict still fires; mixed single/multi-turn file marks every undeclared column per-turn; single-turn CSV into an empty dataset unchanged; preview `autoDetectedSchema` matches persisted scope.
