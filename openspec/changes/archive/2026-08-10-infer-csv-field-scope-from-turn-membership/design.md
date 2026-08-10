## Context

See proposal.md — Why. The defect is one line: `CsvSchemaFieldBuilder.newField` resolves an undeclared column's scope as `scopeByName.get(name)` → `null` → shared, so a schema-less dataset partitions every multi-turn column into `shared` and `MultiTurnRunAssembler` reports `sharedConflict`.

Two facts about the existing pipeline shape the design:

1. **Scope has two consumers with different timing.** During streaming, `processTestCase`/`handlePreviewCase` partition and validate each case as it closes — but only **multi-turn** cases consult scope at all: the single-turn path stores `data` verbatim and validates via `validateTestCase`, which never reads `perTurn` (`MultiTurnFieldsValidator` is not on the CSV path and returns immediately for `multiTurnData == null`). After streaming, `persistSchema` (`CsvImportService:323`), `buildFinalSchema`/`fixupTestCases`, and preview's `buildAutoDetectedSchema` (`:197`) run — by which point every row has been seen.
2. **Type inference already rides the stream.** `inferredTypes` is accumulated per row inside the loop (`:290`) and consumed post-stream. Scope membership can ride the identical idiom.

Together these dissolve the ordering problem the deleted two-pass variance design (`infer-csv-field-scope-from-turn-variance`) was built around: no schema decision is needed pre-stream that cannot be safely over-approximated, and every persisted artifact is built post-stream anyway.

## Goals / Non-Goals

**Goals:**

- Importing an exported multi-turn CSV into a schema-less dataset reproduces its turn structure, in a **single parse** of the stream — no `InputStreamSource` plumbing, no second pass.
- One scope-resolution rule across all four schema-derivation sites (validation, persisted, fixup, preview `autoDetectedSchema`) and across preview and import.
- Declared-shared columns keep their conflict semantics untouched.

**Non-Goals:**

- Variance-based inference (keeping a multi-turn case's constant columns shared) — rejected as the prior design; membership over-approximates deliberately.
- A `turns` total-count CSV column (completeness checking) — separable follow-up; introduces a new reserved header name with backward-compatibility cost (`resolveColumnBindings` maps system columns by name, so existing user columns named `turns` would be silently re-mapped).
- ZIP export multi-turn blindness; `DatasetCloneService`; carry-forward of `required`/`displayName`/`description`; non-contiguity as a hard 400 (stays a warning); repairing datasets already corrupted by the current behavior.

## Decisions

### D1 — Three-tier scope resolution in `CsvSchemaFieldBuilder`

`newField` resolves `perTurn` as:

```
declared  (currentSchema CONTAINS the field name)   → that field's perTurn, verbatim
undeclared AND name ∈ multiTurnColumns              → TRUE
otherwise                                           → null (shared)
```

**The declared test MUST be `scopeByName.containsKey(name)`, not `scopeByName.get(name) != null`.** A declared field whose `perTurn` is absent is *declared shared* and must stay shared — that is precisely the case whose turn-row disagreement must still raise `sharedConflict`. Today's `get(...)` collapses declared-shared and undeclared into the same `null`.

`buildFromBindings` and `buildMergeDelta` gain one parameter: `Set<String> multiTurnColumns`. No new component — the builder stays the single owner of field construction, and the rule is three lines.

*Alternative rejected:* a `boolean defaultPerTurn` mode flag — a set expresses both the validation-time over-approximation (pass all data column names) and the final membership (pass the observed set) through one code path, with no branching.

### D2 — Single pass: over-approximate during streaming, exact after

```
pre-stream    buildValidationSchema(…, multiTurnColumns = ALL data-bound names)
streaming     per row:   updateInferredTypes (existing)
              per case:  case.multiTurn() → sawMultiTurnCase = true
              per case:  partition + validate against validationSchema   (unchanged calls)
post-stream   persistSchema / buildFinalSchema / buildAutoDetectedSchema(
                  …, multiTurnColumns = sawMultiTurnCase ? ALL data-bound names : ∅)
```

The validation schema marks **every undeclared column** per-turn. This over-approximation is safe because scope has exactly one consumer class during streaming — the multi-turn path (`MultiTurnRunAssembler.partition` + `validateMultiTurn`) — and for multi-turn cases per-turn is the desired answer. Single-turn cases are scope-agnostic (Context §1), so the over-approximation is unobservable for them.

**Self-consistency invariant:** when the file contains a multi-turn case, the post-stream set equals the streaming over-approximation exactly, so rows partitioned mid-stream (undeclared → turn maps, including blank cells materialized as `""` — see D3) always agree with the persisted schema, and the post-import `startDatasetRevalidation` pass re-validates them cleanly. When the file contains none, scope was never consulted mid-stream and the persisted schema is all-shared, byte-identical to today's. No post-hoc re-partition, re-validation trigger, or new fixup is needed; `fixupTestCases` stays type-driven.

*Alternatives rejected:* (a) two-pass variance inference — the deleted prior design; correct but requires `InputStreamSource` re-read plumbing, a grouping pre-pass, and ~2× parse cost, to preserve a distinction (constant-across-turns columns stay shared) the user has accepted losing; (b) per-column turn membership — see D3.

### D3 — The gate is file-level ("CSV contains a multi-turn case"), not per-column

When the accumulator closes a case with `multiTurn() == true` (some row's `turnIndex` parses to an integer — the grouper's existing discriminator, `CsvTestCaseGrouper:64`), set a `sawMultiTurnCase` flag; do this in both the import and preview loops, including the final `flush()` case (identical tracking ⇒ preview/import parity). Post-stream, the membership set passed to the D1 tiers is all data-bound field names when the flag is set, empty otherwise. No injectable component: it is a boolean next to the existing `updateInferredTypes` accumulation idiom.

**Per-column membership ("mark only the columns observed on multi-turn rows") was rejected on a verified code fact:** `CsvCellParser.parseCell` returns `""` for blank cells, never null (`CsvCellParser:24-27`), and `parseRow` puts the coerced value for every mapped data column unconditionally in the schema-less case (`CsvImportService:1239-1248`, `SchemaTypeCoercer.coerce(value, null)` = identity) — so **every data-bound column key is present in every row's data map, blank or not**, and column-level turn membership is unobservable from row keys. Defining membership over non-blank values instead would desynchronize the mid-stream partition (which routes the `""` entries of blank-on-every-turn columns into persisted turn maps) from a final schema calling those columns shared, and the unconditional post-import revalidation (`RevalidationService` → `validateMultiTurn` → "Unknown data field") would invalidate the case — a #120-shaped regression. Recovering per-column granularity would require changing `parseRow` blank materialization or stripping blanks in `MultiTurnRunAssembler`, both explicitly out of scope.

### D4 — Component interaction, contracts, boundaries

```
TestCaseController ──▶ CsvImportService.preview / importCsv        (signatures unchanged)
                            │  pre-stream: buildValidationSchema(bindings, currentSchema, allDataNames)
                            │  streaming:  CsvTestCaseGrouper ──▶ MultiTurnRunAssembler (unchanged)
                            │              sawMultiTurnCase |= closed case is multi-turn
                            │  post:       persistSchema / buildFinalSchema / buildAutoDetectedSchema
                            │                └─▶ CsvSchemaFieldBuilder (D1 tiers)
                            └──▶ TestCaseValidationService, TestCaseRepository, RevalidationService (unchanged)
ZipImportService ──────────▶ CsvImportService                        (call sites unchanged)
```

- **Data model / DB**: none. No migration, no jOOQ regen, no DTO change. `test_case_schema` JSONB gains `"perTurn": true` entries it could already hold.
- **API contract**: none. Behavior: fewer false conflicts, `autoDetectedSchema[].perTurn` accurate for schema-less previews.
- **Transactions**: unchanged — `importCsv` stays `@Transactional("metaTransactionManager")`; `preview` stays non-transactional. The accumulation is in-memory loop state.
- **Error handling**: unchanged — no new failure mode is introduced; existing `ValidationException` / `UncheckedIOException` paths are untouched.
- **Unchanged classes**: `MultiTurnRunAssembler`, `TestCaseFieldScopeResolver`, `MultiTurnFieldsValidator`, `CsvExportService`, `TestCaseController`, `ZipImportService`.

## Risks / Trade-offs

- **`containsKey` vs `get` is a silent-failure point** — getting D1 wrong reintroduces #120 for declared-shared fields. → Dedicated `CsvSchemaFieldBuilderTest` case (declared-shared field whose name is also in the membership set stays shared) + the *Declared-shared field is never re-scoped* functional scenario.
- **Validation-schema over-approximation could leak to an unnoticed scope consumer.** The safety argument rests on "only the multi-turn path reads `perTurn` during streaming". → Pinned by the *Empty dataset schema has nothing to preserve* functional scenario (asserts persisted schema **and** case validity/warnings unchanged for a single-turn-only file) and by grepping `validationSchema` consumers during implementation.
- **Fidelity loss: with any multi-turn case in the file, every undeclared column imports as per-turn** — including constant shared columns and, in mixed files, columns used only by single-turn cases. Data-lossless (merged effective view identical up to blank materialization; export repeats shared values per turn row, so round trip stays byte-identical; single-turn cases are scope-agnostic and the runnable-selector binds per-turn fields over `coalesce(multi_turn_data, jsonb_build_array(data))`), but a destination user sees "per-turn" badges on fields the source declared shared, and a later multi-turn author there must put those fields in turns. → Accepted in the proposal; variance inference remains the documented upgrade path if fidelity is ever required.
- **Mid-stream/final schema divergence** (the #120 failure shape). → Excluded structurally: the gate makes the post-stream set equal to the streaming over-approximation whenever any case is multi-turn (D2 invariant), stated in the spec (*preview reports the scope import would persist*, partition agrees with persisted scope) and covered by the round-trip functional test.

## Migration Plan

Deploy-only; no migration, no config, no rollback steps. Previously mangled datasets (turns collapsed by #120) are not repaired — re-import the source CSV after upgrading.
