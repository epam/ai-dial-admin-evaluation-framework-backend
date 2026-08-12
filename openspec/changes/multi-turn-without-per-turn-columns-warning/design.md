## Context

See proposal.md — Why. Relevant current state:

- `TestCaseValidationService.validateMultiTurn` already splits the dataset schema via `TestCaseFieldScopeResolver.splitSchema`, so the per-turn sub-schema needed to detect "no per-turn columns declared" is available at the point of the check with no extra work and no new collaborator.
- The method already owns one case-level, non-field warning of exactly the shape needed: the over-max-turns warning (`fieldName=null`, `path=$.multiTurnData`, `code=ADDITIONAL`, no turn index).
- Validity is `valid = warnings.isEmpty()`, evaluated **before** the truncation to `validation.max-warnings-per-case`, so adding a warning is sufficient to flip `is_valid` and truncation cannot resurrect validity.
- Validation is dataset-scoped: it receives the dataset schema, the case's data, and (only for override re-checks) the effective template/bindings. It has no access to which suites bind which fields, and must not grow one.

## Goals / Non-Goals

**Goals:**
- One detection point, inherited by all four validation entry points (REST write, dataset revalidation, CSV row validation, post-import fixup) without touching any of them.
- Zero new classes, beans, enum constants, DTO fields, or config properties.
- The warning is recomputed from stored state on every pass, never persisted-and-carried-forward, so it self-clears when the schema or the case changes.

**Non-Goals:**
- Changing run-time turn-count behavior (`PerTurnBindingDetector` / `TurnLoopExecutor` stay untouched).
- Rejecting such a write with 400. The user-visible contract stays "persist and warn", consistent with the over-cap rule.
- Introducing a non-invalidating ("advisory") warning tier. None exists today and this change does not add one.
- Backfilling `is_valid` for already-stored cases.

## Decisions

**1. Detect in `validateMultiTurn`, not at the write boundary (`MultiTurnFieldsValidator`).**
`MultiTurnFieldsValidator` is the 400 surface and only runs on writes; revalidation and the CSV fixup pass never reach it. Placing the check in `validateMultiTurn` — the one function every path that computes a case's validity calls — is what makes the four entry points inherit it for free. *Alternative rejected:* a new `service.domain` component. The rule is a single boolean over an already-computed schema split; a dedicated injectable class would add indirection without adding a testable seam, since `validateMultiTurn` is already directly unit-tested.

**2. Reuse `ValidationWarningCode.ADDITIONAL` rather than adding a code.**
The defect is "the case carries data that has nowhere to go" — the same category as the over-cap warning, which already uses `ADDITIONAL` at the same path. A new enum constant would be a cross-module change (`evaluation-runner-core`'s `runner.dto`) and would require FE handling to be useful; the distinguishing signal clients need — path `$.multiTurnData` with no `fieldName` — is already there. *Alternative rejected:* `INVALID_SCOPE`. Nothing is misplaced here: per-turn scope simply does not exist in this dataset, so the field-level "move this value" reading that `INVALID_SCOPE` carries would mislead.

**3. Trigger on "no *named* per-turn field", evaluated over the per-turn split.**
The condition is `!turns.isEmpty()` and the per-turn sub-schema contains no field with a non-blank name. Matching `TestCaseFieldScopeResolver.perTurnFieldNames`' own name filter keeps a malformed schema entry (`perTurn=true` with a null/blank name — a field no data key can ever match) from silently suppressing the warning. The `!turns.isEmpty()` guard makes the check inert for the theoretically-empty turn array that the write path already rejects with a 400 but that revalidation could read from legacy data.

**4. Place the check immediately after the max-turns check, but *prepend* the warning to the list.**
Position in the method is about readability (the two case-level checks sit together); position in the list is about survival. The list is seeded with `placement.warnings()` — up to one `INVALID_SCOPE` per misplaced occurrence per turn, so up to `max-turns` (10) of them — followed by the shared-bucket warnings, and is then truncated to `validation.max-warnings-per-case` (default **5**). An appended warning is therefore the first thing truncation deletes: five misplaced turns in an all-shared schema fill the cap exactly, and `subList(5, 6).clear()` removes the one warning that explains why. `warnings.add(0, …)` costs nothing on an `ArrayList` of this size and makes the coexistence rule in the delta spec unconditional. Validity is unaffected either way — `valid = warnings.isEmpty()` is computed before truncation. Emitting both case-level warnings when both apply (over-cap **and** no per-turn columns) is intentional: they are independent defects.

**5. Let the warning coexist with `INVALID_SCOPE` warnings instead of suppressing either.**
When the turns hold values for shared-declared fields in an all-shared schema, `TestCaseDataScopeResolver.inspect` emits one `INVALID_SCOPE` per occurrence and this check adds one case-level warning. The per-field warnings say *which* values are stranded; the case-level one says *why* no turn can hold them. Suppressing the case-level warning when misplacements exist would hide the root cause exactly in the case where the author most needs it. Note this combination is unreachable through a direct write — `MultiTurnFieldsValidator` rejects a declared shared field inside a turn with a 400 before validation runs — so it arises only on the recomputation paths (dataset revalidation after a scope flip, CSV-import fixup). That is why its coverage is a unit test plus a revalidation-path functional test rather than a REST test.

**6. Message wording follows the existing over-cap warning's register** — states the observed turn count and the schema fact, then the consequence: `Test case has <N> turns but the dataset schema declares no per-turn columns; turn data cannot be attached`. No trailing remediation clause, unlike the `INVALID_SCOPE` messages, because the fix is a schema-level decision (declare a per-turn column, or drop the turns) rather than a single column to re-create.

## Risks / Trade-offs

- **Previously-runnable cases stop running.** A suite over a dataset of such cases now fails run creation with `409 INVALID_OPERATION` instead of executing each case once from its shared `data`. → This is the intended correction (the authored turns were being silently discarded), and it is reachable only for cases whose turns could never have been used. Called out in proposal.md — Impact so it is not discovered at run time.
- **Over-firing on a legitimate all-shared multi-turn dataset.** → Covered by a negative unit test (per-turn column declared + all turn maps empty → still valid) and by keeping the trigger purely schema-derived, so it cannot vary with suite bindings.
- **Stored `is_valid` drifts until the next validation pass.** Existing affected cases keep reporting valid until an edit, schema change, or import re-validates them. → Accepted: no backfill migration. Consistent with how every other validation rule change has landed in this codebase (validity is recomputed, never retro-written).
- **Spec reversal.** The `test-cases` scenario "Empty per-turn maps are valid" asserted the opposite for the all-shared case, and its trailing claim ("the turn count alone determines the number of turns run") already contradicted the implemented turn-count rule. → The delta rewrites that scenario into a per-turn-columns-declared version plus the new invalidating scenario, and corrects the run-count sentence; `multi-turn-test-case` needs no delta because it delegates the validation surface to `test-cases`.
