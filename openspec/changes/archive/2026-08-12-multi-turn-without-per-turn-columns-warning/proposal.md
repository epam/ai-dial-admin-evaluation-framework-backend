## Why

A test case can be persisted with a non-null `multiTurnData` array while the dataset schema declares **no** `perTurn: true` field. Nothing can ever be stored in those turns, and the run path collapses such a case to `N = 1` (`PerTurnBindingDetector` answers `false` when no per-turn field exists), so the persisted turns are dead weight. Today the case is reported **valid**: `validateMultiTurn` splits the schema, gets an empty per-turn sub-schema, validates every turn map against nothing, and finds no warning. The UI faithfully renders the persisted turn count — a "2 turns" badge over two blank turn rows next to a green `Valid` status — with no signal that the turns are unusable.

## What Changes

- Add one invalidating validation warning: a case carrying a non-empty `multiTurnData` whose dataset schema declares no `perTurn: true` field is marked `is_valid=false` with a case-level warning at `$.multiTurnData` (code `ADDITIONAL`, no `fieldName`, no `turnIndex`) — the same warning shape as the existing over-max-turns warning.
- The check lives in `TestCaseValidationService.validateMultiTurn`, immediately after the max-turns check, so every caller inherits it with no further wiring: the REST write path (`TestCaseService`), dataset revalidation (`RevalidationService`), and both CSV-import passes (row validation and post-persist fixup).
- **BREAKING (behavioral, spec-level)**: reverses the existing `test-cases` scenario *"Empty per-turn maps are valid"*, which asserts that a multi-turn case with an all-shared schema and empty turn maps is valid. A case with per-turn fields declared and all turn maps empty stays valid — only the no-per-turn-columns-at-all case flips to invalid.
- Corrects a factually stale sentence in the same `test-cases` requirement — "the turn count alone determines the number of turns run" — which contradicts the implemented turn-count rule (`multi-turn-test-case`: `N = multiTurnData.length` only when a binding references a `perTurn: true` field, else `N = 1`).

This ships as production behavior in this change. The modified requirement keeps its `Status: **Planned**` marker, matching the rest of the multi-turn block in `test-cases` — that repo-wide staleness is not this change's to fix.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `test-cases`: the per-turn validation requirement gains the "multi-turn case with no per-turn columns is invalid" rule, loses the blanket "empty per-turn maps are valid" claim, and drops its stale run-count sentence. `multi-turn-test-case` explicitly delegates the authoring/validation surface to `test-cases`, so no delta is needed there.

## Impact

- **Code**: `TestCaseValidationService.validateMultiTurn` (one new check, ~6 lines) and its javadoc. No new class, no new bean, no enum change (`ADDITIONAL` is reused), no DTO change.
- **API**: no contract change. Affected cases return `isValid: false` plus one more entry in `validationWarnings` on `GET /api/v1/datasets/{id}/test-cases` and on the write/import responses.
- **Runnable selection**: such cases are now excluded by `RunnableTestCaseSelector` (`is_valid` filter). A suite whose dataset contains only these cases fails run creation with `409 INVALID_OPERATION` "Suite has no valid and enabled test cases" — previously the run would have started and executed each case once. This is the intended correction: the author's turns were being silently discarded.
- **CSV import**: importing a `turnIndex`-carrying CSV into a dataset whose columns are all **declared** shared now persists every assembled multi-turn case as invalid (their turn maps come out `{}`), and the import response's `invalidCount` rises accordingly. Declared scope is never re-scoped by inference, so re-importing cannot fix it — the author must declare a per-turn column on the dataset schema. This is the most likely everyday encounter with the new warning; support and FE should expect it.
- **Existing data**: no migration. Affected cases flip to invalid on their next validation pass — a dataset schema change, an edit, CSV row validation, or the post-import fixup pass for the cases that pass actually rewrites (it early-returns when nothing was coerced). Stored `is_valid` is not rewritten retroactively by this change.
- **No** DB schema, config property, or `docs/configuration.md` change.
- **Tests**: unit coverage in `TestCaseValidationServiceMultiTurnTest` (fires when no per-turn column is declared; does **not** fire when per-turn columns are declared and turn maps are empty), plus functional coverage of the REST write path.
- **Risk**: over-firing on datasets that legitimately carry turns without per-turn columns. Mitigated by the negative unit test above and by the trigger depending solely on the dataset schema's declared scopes — never on suite bindings, which validation cannot see.
