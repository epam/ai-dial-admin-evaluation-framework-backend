## Why

GitHub issue #137 — *"[Eval > Datasets] Validation message unclear when Per turn field changed to Shared with conflicting values"*. A dataset schema field is flipped from `perTurn: true` to shared; the stored cases keep that field's values inside their `multiTurnData` turn maps, because `DatasetService.update` only drops *removed* fields (`DatasetService.java:272`) and never relocates values between buckets. The async revalidation that follows validates each turn against the **per-turn sub-schema**, which no longer contains the field, so the user sees `Unknown data field 'sha'` once per turn — a message that names neither the actual problem (the value sits in the wrong scope bucket) nor the fix.

The vagueness is structural, not cosmetic. `TestCaseValidationService.validateMultiTurn` hands `validateTestCase` only the sub-schema for the bucket being checked (`TestCaseValidationService.java:227,242`), so `validateTestCase` **cannot distinguish** "unknown to the whole schema" from "declared, but in the other bucket" — both fall into the same generic branch (`:162-167`). The `MultiTurnFieldsValidator` 400 that *does* state the problem precisely (`MultiTurnFieldsValidator.java:56-57,67-68`) never runs on this path: revalidation is an async recomputation job and cannot reject anything.

## What Changes

### 1. One owner for the placement rule, two modes

Introduce `TestCaseDataScopeResolver` (`service.domain`, injectable `@Component`) as the single definition of "which bucket does this key belong in":

- **throw mode** — `requireCorrectScope(data, turns, schema)`: what the API write path needs. `MultiTurnFieldsValidator.validateStructure` delegates its placement half to it and keeps its own empty-`multiTurnData` 400 and its single-turn early return. Both existing 400 messages and the HTTP behaviour of create / PUT / PATCH / batch are unchanged.
- **warn mode** — `inspect(data, turns, schema)`: what every recomputation path needs (dataset revalidation, CSV import fixup). Returns the misplacement warnings, the two buckets with the misplaced keys removed, and the set of misplaced field names.

This keeps the rule from being stated twice, per the project's "one definition per bounded context" convention.

### 2. Precise warning instead of the generic one

`TestCaseValidationService.validateMultiTurn` calls `inspect(...)` right after `splitSchema` and validates the *stripped* buckets against sub-schemas with the misplaced fields removed. A misplaced key therefore yields exactly one warning per occurrence, naming the misplacement, worded to match the existing 400 verbatim:

- shared field found in a turn → `Field 'sha' is shared (test-case-level) but values are specified on turn level. Re-create column for correct data attachment`
- per-turn field found in `data` → `Field 'sha' is per-turn but currently specified on a test case level. Re-create column for correct data attachment`

Both halves of that stripping are needed, and the second is the non-obvious one. Removing the key from the *map* suppresses the unknown-field warning (presence in the wrong bucket). Removing the field from the *sub-schema of the bucket it belongs to* suppresses the contradictory `Required field 'sha' is missing…` warning (absence from the right bucket) — which map-stripping alone does nothing about, since the required check iterates the sub-schema and reads the bucket map. Today a misplaced required field produces both warnings at once.

**Decision (explicit):** the message stays the misplacement wording as above. The issue's expected result mentions "conflicting per-turn values"; a variant that additionally reports whether the misplaced turn values differ is deliberately **out of scope** — the misplacement is the cause and is true whether or not the values differ.

### 3. New warning code `INVALID_SCOPE`

Added to `runner.dto.ValidationWarningCode`. A distinct code (rather than reusing `ADDITIONAL` with better text) is what lets the FE render a "wrong bucket" affordance and lets a client tell "your CSV has a stray column" from "this value is in the wrong bucket" — the substance of the issue. The enum is additive-safe: no exhaustive `switch` over it exists in `src/main`, `evaluation-runner-core`, or `eval-cli`. `DurableWarningMerger` is **not** extended to carry `INVALID_SCOPE` forward — unlike a CSV row conflict, a scope misplacement is fully re-derivable from stored state and must clear itself once the data or the schema is fixed.

### 4. Per-turn warning path corrected

A per-turn warning currently reports `path = "$.data.<field>"` (`TestCaseValidationService.java:165` and siblings) regardless of bucket, leaving `turnIndex` as the only disambiguator. Warnings stamped with a turn index get `$.multiTurnData[<i>].<field>` — which is also how a warning identifies its bucket, so no warning **message** changes.

The generic unknown-field warning survives verbatim for keys unknown to the **whole** schema, including its `Unknown data field '<x>'` text (an existing functional test asserts that substring).

### Non-goals

- Auto-relocating misplaced values during revalidation. It would silently rewrite user data on a schema edit and hide the authoring mistake; the warning is the honest signal.
- The `bulkPatch` selector-scoped `bulkOperations` path (`TestCaseService.java:245`), which writes `data` with no placement check and no revalidation at all. Real, separate, tracked elsewhere.
- Any change to CSV import's own conflict warnings (`INVALID_INPUT`) or to `DurableWarningMerger`'s carry-forward set.

## Capabilities

### New Capabilities

None. This change refines the diagnostics of existing validation behaviour.

### Modified Capabilities

- `test-cases`: the *Per-turn validation against the dataset schema* requirement gains the scope-misplacement warning — what a recomputation path (revalidation, CSV fixup) reports when a key sits in the wrong bucket, and the fact that it is a distinct `INVALID_SCOPE` warning rather than a generic unknown-field one. The *multiTurnData authoring field* requirement is clarified: the 400 covers fields **declared** in the dataset schema; an undeclared key is a content warning, not a rejection.
- `multi-turn-test-case`: the *Dataset revalidation preserves multi-turn shape* requirement gains the scope-flip scenario — a field re-scoped in the dataset schema leaves stored values in the old bucket, and revalidation SHALL report that misplacement precisely rather than as an unknown field.

## Impact

**Code**
- New: `service/domain/TestCaseDataScopeResolver.java`.
- Modified: `service/domain/MultiTurnFieldsValidator.java` (delegates placement), `service/domain/TestCaseValidationService.java` (inspect + append + stripped buckets + path fix), `evaluation-runner-core/.../runner/dto/ValidationWarningCode.java` (new constant).
- Unchanged signatures: `TestCaseValidationService.validateTestCase` and `validateMultiTurn` keep their parameter lists, so the five single-turn call sites and the Mockito stubs in `CsvImportServiceSchemaTest` are untouched.

**API** — additive only. `validationWarnings[].code` can now be `INVALID_SCOPE`, and `path` on a turn-scoped warning changes from `$.data.<field>` to `$.multiTurnData[<i>].<field>`. No endpoint, DTO shape, or status code changes. The FE must render the new code (and should be told, since #137 is an FE-visible tooltip).

**Database** — none. No Flyway migration; `validation_warnings` is JSONB and already carries free-form codes.

**Configuration** — none. No new property, so `docs/configuration.md` is untouched.

**Docs** — `docs/database-schema.md:342` enumerates the warning codes in the `validation_warnings` JSONB example; it is already stale (missing four existing codes and the `turnIndex` field) and is brought in sync along with `INVALID_SCOPE`. `AGENTS.md`'s multi-turn inline convention states "a misplaced field → **400**" unconditionally and must record the split (400 on write, `INVALID_SCOPE` on recomputation).

**Tests** — three unit tests construct the touched components directly and need the new collaborator: `MultiTurnFieldsValidatorTest.java:38`, `TestCaseValidationServiceMultiTurnTest.java:53`, `TestCaseValidationServiceTypeTest.java:44` (their assertions survive). `CsvImportModeFunctionalTests.java:730` asserts `contains("Unknown data field")` and stays green as long as the prefix is preserved.

**Risk** — low. The only behavioural change on the API write path is nil (same 400s); on recomputation paths a case that is invalid today stays invalid, with a better message and one fewer contradictory warning.
