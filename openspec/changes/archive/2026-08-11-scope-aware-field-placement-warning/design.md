## Context

See `proposal.md` — *Why*. The design-relevant constraints:

- The placement rule exists today in exactly one place, in **throwing** form: `MultiTurnFieldsValidator.validateStructure` (`MultiTurnFieldsValidator.java:41-72`), reachable only from `TestCaseService.runValidation` (`:455`) — i.e. only from API writes.
- `TestCaseValidationService.validateMultiTurn` (`:213-266`) splits the schema and calls `validateTestCase` once per bucket with **only that bucket's sub-schema** (`:227`, `:242`). `validateTestCase` therefore structurally cannot tell "unknown to the whole schema" from "declared in the other bucket", and emits the generic warning at `:162-167`.
- Two callers reach `validateMultiTurn` without any placement check and cannot throw, because they are recomputation passes over stored data: `RevalidationService.processMultiTurnCase` (`:339-346`) and `CsvImportService.fixupMultiTurnCase` (`:886-887`). The #137 scenario is the first of these.
- `validateTestCase` is also called from five single-turn call sites; its signature is load-bearing and `CsvImportServiceSchemaTest` stubs `validateMultiTurn` with an exact 7-arg matcher.

## Goals / Non-Goals

**Goals**
- One definition of the placement rule, consumed in both throwing and warning form.
- A misplaced key produces exactly one warning, at the right path, with a distinguishable code.
- No signature change to `validateTestCase` or `validateMultiTurn`.

**Non-Goals (design-level)**
- No new bucket-aware variant of `validateTestCase`. Scope is a multi-turn concept; leaking it into the single-turn path is the thing this design avoids.
- No relocation of stored values, and no new write from the validation path. Validation stays read-only w.r.t. `data` / `multi_turn_data`.
- No change to `DurableWarningMerger`'s carry-forward set.

## Decisions

### D1 — A single injectable resolver with two modes (chosen)

New `@Component` in `service.domain`:

```java
public record ScopePlacement(
        Map<String, Object> shared,
        List<Map<String, Object>> turns,
        Set<String> misplacedFields,
        List<ValidationWarningDto> warnings) {}

public ScopePlacement inspect(Map<String, Object> data,
                              List<Map<String, Object>> turns,
                              List<FieldDefinitionDto> schema);

public void requireCorrectScope(Map<String, Object> data,
                                List<Map<String, Object>> turns,
                                List<FieldDefinitionDto> schema);
```

`requireCorrectScope` delegates to `inspect` and throws `ValidationException` carrying the first warning's message — so the 400 text and the warning text are literally the same string, produced once. It preserves `MultiTurnFieldsValidator`'s `multiTurnData == null` early return: a single-turn case has no turn bucket and is never scope-checked. `inspect` returns the buckets with misplaced keys removed **and** the set of misplaced field names, which is what lets the caller silence the collateral warnings (D2).

`inspect` checks `data` before the turn maps, so a case violating both directions throws the `data`-side message — matching today's ordering in `MultiTurnFieldsValidator:53-71`. It returns **new** maps and never mutates its inputs (see D2).

Scope membership keeps coming from `TestCaseFieldScopeResolver` (`perTurnFieldNames` / `sharedFieldNames`); the new component composes it rather than duplicating the split.

**Alternatives considered.**
*(A) Inline the check in `validateMultiTurn`.* Cheapest — no new class, no signature churn — but leaves the rule stated twice (throwing copy in `MultiTurnFieldsValidator`, warning copy here), which is exactly the drift the "one definition per bounded context" convention exists to prevent. Rejected.
*(C) Widen `validateTestCase` to also take the full schema.* Would let it answer "known but wrong bucket" itself, but it touches a method used by five single-turn call sites, leaks multi-turn scope semantics into single-turn validation, and still leaves the throwing copy elsewhere. Rejected.

### D2 — Strip the misplaced field from **both** the map and the sub-schema

`validateMultiTurn` calls `inspect(...)` immediately after `splitSchema`, then validates `placement.shared()` / `placement.turns()` against sub-schemas filtered by `placement.misplacedFields()`.

**Both halves are required, and the second is the non-obvious one.** A misplaced key produces two independent findings:

1. *Presence in the wrong bucket* → the unknown-field branch (`TestCaseValidationService.java:162-167`), because the key is not in that bucket's sub-schema. Removing the key from the map suppresses this.
2. *Absence from the right bucket* → the required-field branch (`:147-158`), which iterates the **sub-schema** and reads the **bucket map**. A shared field misplaced into a turn is missing from `data`; a per-turn field misplaced into `data` is missing from every turn. Removing the key from a map does **nothing** to this — it is suppressed only by removing the field from the sub-schema of the bucket it belongs to.

So the rule is symmetric in both directions: strip the field's name from the bucket map it was wrongly found in, **and** from the sub-schema of the bucket it should have been in. A field can be misplaced in only one direction, so a single `misplacedFields` set drives both filters. Without step 2 a misplaced *required* field yields the `INVALID_SCOPE` warning plus a contradictory `Required field 'x' is missing…` — exactly the multi-warning noise this change exists to remove.

**Why this cannot lose data.** Both recomputation callers *write before they validate* and pass their own already-serialized maps: `RevalidationService` serializes `postCoercionShared`/`postCoercionTurns` at `:322-328` and only then validates the same objects at `:339-346`; `CsvImportService.fixupMultiTurnCase` serializes at `:880-881` before validating at `:886-887`. The written JSON is therefore fixed before `inspect` is ever called. The invariant this design relies on is consequently **`inspect` must not mutate its inputs** — it returns new maps — rather than "the validated map is never the written map", which is false: they are the same objects.

### D3 — New `ValidationWarningCode.INVALID_SCOPE`, not reused `ADDITIONAL`

The FE needs to render a distinct affordance for "wrong bucket" versus "stray key" — that distinction is the substance of #137. Additive-safe: no exhaustive `switch` over the enum exists in `src/main`, `evaluation-runner-core`, or `eval-cli`; consumers treat it as an opaque string.

Deliberately **not** added to `DurableWarningMerger`'s preserved set (`DurableWarningMerger.java:51`, currently `INVALID_INPUT` only). `INVALID_INPUT` is preserved because it describes the *CSV rows a case was assembled from* — unrecoverable from stored state. A scope misplacement is the opposite: fully re-derivable from stored data plus the current schema, so it must clear itself the moment either is fixed.

### D4 — One string per direction, shared by the warning and the 400

`Field 'sha' is shared (test-case-level) but values are specified on turn level. Re-create column for correct data attachment` / `Field 'sha' is per-turn but currently specified on a test case level. Re-create column for correct data attachment`.

Each string is defined in exactly one place in the resolver, and `requireCorrectScope` throws with the same string the warning carries — so the write-time 400 and the recomputation-time warning can never drift apart. The text itself is deliberately actionable ("Re-create column…") rather than a restatement of the rule; it replaces the terser pre-change 400 wording, which described the constraint but not the remedy. Because both surfaces read from the one definition, changing the wording is a one-line edit with exact-match test assertions pinning it.

The alternative — a variant that also reports whether the misplaced turn values *differ*, matching #137's "conflicting values" phrasing — was explicitly declined by the requester. The misplacement is the cause and holds whether or not the values differ; a conflict-aware variant would add a second message shape for no change in the required user action.

### D5 — Bucket identification is the `path`, applied at the turn-index stamping point

`validateMultiTurn` already stamps `turnIndex` on each turn's warnings (`:249-252`). That is the one place that knows which bucket a warning came from, so it also rewrites `path` from `$.data.<field>` to `$.multiTurnData[<i>].<field>`. Extracted as a named private method rather than inlined in the loop.

The bucket is carried by `path` + `turnIndex` alone. An earlier draft also appended the bucket to the unknown-field *message*; that is dropped — it duplicates what `path` already says and would have needed a fragile discriminator (`code == ADDITIONAL`) to find the right warning among a turn's warnings. Message text is left exactly as it is today, so the `Unknown data field '<x>'` string that `CsvImportModeFunctionalTests.java:730` and possibly clients key on is untouched.

### D6 — Write-path behaviour is unchanged by construction

`MultiTurnFieldsValidator` keeps its empty-`multiTurnData` 400, its `multiTurnData == null` early return (`:43-45`, locked in by `MultiTurnFieldsValidatorTest:72-77` — a single-turn case is never scope-checked), and delegates only the placement half. Every write path (`create`, `update`, `patch`, `batchUpdate`, `batchPatch`, `bulkPatch.itemOperations`) reaches it through the unchanged `TestCaseService.runValidation:455`, so their 400s are bit-for-bit what they are today. The new warning is reachable on those paths only for keys that are *undeclared* — which today already pass the 400 and land in the generic branch.

Ordering matters when delegating: the null check must run **before** `requireCorrectScope`, not be folded into the resolver's null-safety.

## Risks / Trade-offs

- **A client switch-cases on `ValidationWarningCode` and breaks on the new value.** → Additive enum; verified no exhaustive switch in any module. The FE must be told, since #137 is an FE-rendered tooltip. Called out in the proposal's Impact.
- **A test asserts on the exact generic message for a misplaced field.** → Only one message-text assertion exists (`CsvImportModeFunctionalTests.java:730`) and it is a `noneMatch(contains("Unknown data field"))` on a path where no misplacement occurs. Prefix preserved regardless.
- **Stripping hides a genuine second problem** — a misplaced key that is *also* the wrong type, or a misplaced required field that would genuinely be missing even after relocation. → Accepted, and it is why the sub-schema filter is scoped to the misplaced names only. A value in the wrong bucket is not type-checkable until it is moved; the misplacement is the actionable finding, and every other check on that field runs on the next pass once it is fixed.
- **Two components now depend on `TestCaseFieldScopeResolver` through a third.** → Composition, not duplication; the split logic still has exactly one implementation.
- **Cases invalidated before this change keep their stale generic warning** until something revalidates them. → See Migration Plan; the #137 flow revalidates as part of the schema edit that causes the misplacement, so the reported scenario shows the new message immediately.

## Migration Plan

No Flyway migration, no config property, no backfill job.

Stored `validation_warnings` are recomputed, never read as input (except `DurableWarningMerger`'s `INVALID_INPUT` subset, untouched here). A case's warnings refresh on the next dataset revalidation, CSV import fixup, or direct edit. Datasets whose schema is not touched again keep whatever they show today — acceptable, because a misplacement can only arise from a schema edit or an undeclared key, and the former re-triggers revalidation by definition (`DatasetService.java:276-278`).

Rollback is a plain code revert: the next validation pass re-emits the old generic warnings, and no persisted shape depends on the new code value. One caveat worth knowing rather than guarding against — a case whose stored warnings already contain `INVALID_SCOPE` deserializes against the reverted enum as unreadable, and `ValidationWarningsSerializer.deserializeWarnings` degrades an unreadable list to **empty** (graceful-degradation by design), so that case shows *no* warnings until it is recomputed. It stays `is_valid=false` throughout, and the next revalidation or edit restores the full list.

## Open Questions

None blocking. One coordination item outside this repo: the FE should map `INVALID_SCOPE` to a dedicated tooltip treatment; until it does, it renders the message text like any other warning, which already resolves #137's complaint.
