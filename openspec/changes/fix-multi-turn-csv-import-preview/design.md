## Context

See proposal.md — *Why* for the defect and the reproduction.

Constraints that shape the approach:

- `CsvImportService` is ~1350 lines and already holds four near-duplicate schema-derivation paths (`buildValidationSchema`, `persistSchema`, `buildFinalSchema`, `buildAutoDetectedSchema`) and two divergent row-processing loops (`preview`, `importCsv`). The bug is duplication: one path (`buildValidationSchema` → `applyScopeFromDataset`) knows about `perTurn` and the other three do not; one loop (`importCsv` → `processRun`) knows about turns and the other does not.
- Both loops stream: `importCsv` buffers only the current run so a large CSV never lands in memory, and preview must keep that property.
- `importCsv` runs inside `@Transactional("metaTransactionManager")`; `preview` performs no writes and no transaction. Neither boundary changes.
- Per AGENTS.md, conversion/validation logic belongs in injectable `@Component`s under `service.domain.*`, not private methods — which is also what makes the shared logic testable once instead of twice.
- `ValidationWarningsSerializer.deserializeTurns` returns `null` on unreadable JSON (graceful degradation, correct for read paths) and `serializeTurns(null)` returns `null`, which `PostgresTestCaseRepository.batchUpdate` writes as SQL `NULL` into `multi_turn_data`.

## Goals / Non-Goals

**Goals**
- One owner for CSV→schema field derivation, so `perTurn` preservation cannot be true on one path and false on three.
- One owner for run grouping and one for multi-turn assembly, consumed by both `preview` and `importCsv`, so preview cannot drift from import again.
- Keep preview's and import's streaming/bounded-memory profile.
- Keep the single-turn contract byte-identical: `CsvImportModeFunctionalTests` must pass unmodified.

**Non-Goals**
- Not a rewrite of `CsvImportService`. Type inference, cell parsing, conflict-strategy persistence, mode/schema-handling semantics and the batch/fixup loop structure stay as they are.
- No new package, no new architectural layer, no change to the `service.domain.csv` package's role.
- Not a general refactor of the four schema paths into one call site — they legitimately differ in *which* fields they include; only the per-field construction is unified.

## Decisions

### D1 — A single schema-field builder owns `perTurn` carry-forward

New `@Component` in `service.domain.csv` (working name `CsvSchemaFieldBuilder`) with the two constructions the service needs:
- build a field list from CSV column bindings, with types either unknown (validation-time) or inferred (persist/fixup/preview-time),
- append CSV columns absent from an existing schema (the MERGE delta).

Both take the dataset's current schema and copy `perTurn` by field name onto every field they emit; a column with no same-named current field gets `perTurn` absent.

`CsvImportService` constructs `FieldDefinitionDto` at **six** sites today, and all six route through the builder — a partial migration is what created this bug:

| site | today | after |
|---|---|---|
| `buildSchemaFromBindings` | full rebuild, no scope | deleted → builder (types unknown) |
| `buildSchemaFromInferred` | full rebuild, no scope | deleted → builder (types inferred) |
| `mergeSchemaWithBindings` | MERGE delta, no scope | deleted → builder (delta) |
| `persistSchema` MERGE branch | inline new-field construction | builder (delta) |
| `buildFinalSchema` MERGE branch | inline new-field construction | builder (delta) |
| `buildAutoDetectedSchema` MERGE branch | inline new-field construction | builder (delta) |

The three MERGE branches only ever construct fields that are *new* to the schema, so `perTurn`-absent is already correct there; they are routed through the builder anyway so there is exactly one place that knows how a CSV-derived field is built. `applyScopeFromDataset` is deleted along with the two rebuild helpers.

*Why:* the defect is that carry-forward was a property of one call path. Making it a property of the constructor makes the wrong version unrepresentable. *Alternative rejected:* calling the existing `applyScopeFromDataset` from the other three paths — same fix in four places, one deletion away from regressing, and it mutates shared objects (below).

The builder never mutates an input field definition and never returns an input instance — every field it emits is new. `applyScopeFromDataset` currently calls `setPerTurn` on the field defs it is handed, which is the mutation being removed. Note this invariant is about the builder's *own* output: the MERGE consumers legitimately start from `new ArrayList<>(currentSchema)` and therefore still carry the dataset schema's existing instances for untouched fields — that is the merge semantics, not aliasing by the builder. Covered by a unit test asserting the input list's instances are unmutated and absent from the builder's emitted fields.

**Only `perTurn` is carried.** `required`, `displayName` and `description` are dropped by the same code and are a real defect, deliberately excluded (proposal — *Non-goals*): carrying `required` forward changes validation strictness dataset-wide and would contradict the existing `test-cases` scenarios that assert `required: false` after auto-detection. It gets its own change.

### D2 — Run grouping via a stateless component plus a per-call accumulator

Streaming grouping is inherently stateful (the current run, and the set of multi-turn names already completed), but a Spring singleton must not hold per-request state. So: a stateless `@Component` (working name `CsvRunGrouper`) exposes a factory for a small per-call accumulator object; the accumulator takes rows one at a time and emits a completed `CsvRun` when the `testCaseName` changes, plus a final `flush()`.

```
var accumulator = runGrouper.newAccumulator();
for (CSVRecord record : parser) {
    ParsedCsvRow row = parseRow(...);
    CsvRun completed = accumulator.add(row);      // null until a run closes
    if (completed != null) { handle(completed); }
}
CsvRun last = accumulator.flush();
```

`CsvRun` is a pure carrier: the rows, the case name, the first CSV row number, whether the run is multi-turn, and whether this multi-turn name has already appeared in an earlier run (the non-contiguity signal `processRun` produces today via `completedMultiTurnNames`). `ParsedCsvRow` is `CsvImportService`'s private `ParsedRow` record promoted to `service.domain.csv` as a **public** record — `CsvImportService` lives in `service.domain`, so package-private would not be visible to it. The same applies to `ColumnBinding` (today `private record` on the service), which the schema-field builder needs.

"Multi-turn" is `any row whose turnIndex parses to a non-null Integer`, matching today's `run.stream().anyMatch(r -> r.turnIndex() != null)` — **not** "any non-blank cell". `parseTurnIndex` already returns `null` for an unparseable cell, so a `turnIndex` of `abc` must keep the run single-turn exactly as it does today.

*Why an accumulator rather than `groupAll(rows)`:* returning `List<CsvRun>` would buffer the whole CSV. Import is strictly O(run) today and must stay so; preview additionally retains one name per row for collision detection (`allCsvNames`/`seenNames`), which is O(rows) before and after this change — but buffering every row's parsed `data` map on top of that is a different order of cost and is what the accumulator avoids. *Why not a `Consumer<CsvRun>` callback:* import needs to interleave per-`CSVRecord` type inference (which needs the raw record, not the parsed row) with run handling; an explicit `add`-returns-completed-run keeps both loops readable and identical in shape.

**Non-contiguity tracking stays multi-turn-only.** Today `completedMultiTurnNames` is populated only in `processRun`'s multi-turn branch, so two non-adjacent groups of blank-`turnIndex` rows produce no non-contiguity warning — they are handled by the ordinary duplicate-name path. The accumulator must reproduce that exactly, or existing single-turn tests gain unspecified warnings.

### D3 — Run ≠ test case, and where duplicate detection keys

The grouper's output is a run; the *case count* rule lives with the consumers:
- multi-turn run → 1 test case, 1 name occurrence, warnings anchored to the run's first row number;
- single-turn run of K rows → K test cases, K name occurrences, warnings anchored per row.

Both `preview` and `importCsv` derive their counters from that rule: `totalTestCases` and preview's `seenNames` on one side, `processRun`'s existing per-row loop on the other (unchanged for single-turn). `addCollisionWarnings` already emits exactly one warning per colliding name at that name's first CSV row — which for a multi-turn case *is* the run's first row — so it is refactored only to consume `(caseName, firstRowNumber)` pairs instead of a parallel per-row name list, dropping its now-dead `seenInCsv` parameter. No behavior change is intended there.

*Why this is called out as a decision:* collapsing adjacent same-named **single-turn** rows into one case is the highest-risk way to get this wrong — it would silently drop the duplicate warning and under-count `totalTestCases` while N rows are still written. The existing `previewWithWithinCsvDupAnnotatesWarnings` uses `DupRow`/`duprow`, which differ in case, so run grouping (name `equals`) splits them into two runs and the test would keep passing. A new preview test with two adjacent *identical* names is required.

### D4 — A multi-turn assembler owns ordering, partitioning and conflict detection

New `@Component` (working name `MultiTurnRunAssembler`) takes a multi-turn `CsvRun` plus the validation schema and returns the assembled shape: ordered per-turn maps, the shared map, and the detected conflicts (shared-column mismatch, duplicate `turnIndex`, plus the JSON-parse flag the run's rows already carry). It absorbs today's private `orderTurns`, `assembleMultiTurn` and `hasDuplicateTurnIndex`, and delegates scope partitioning to the existing `TestCaseFieldScopeResolver` (unchanged).

Conflicts are returned as data, not warnings: `importCsv` merges them with `TestCaseValidationService.validateMultiTurn` output into the persisted `ValidationResult` (today's `validateRunAsMultiTurn`), while `preview` renders the same conflicts as preview warnings without persisting. Keeping `TestCaseValidationService` calls in the service preserves the current transaction/validation boundary and keeps the assembler a pure function.

### D5 — Preview reuses the import pipeline; `totalTestCases` is additive

`preview` gains the same accumulator loop as `importCsv` and, per completed run, computes the same assembly. It then:
- adds 1 (multi-turn) or K (single-turn) to `totalTestCases`;
- registers 1 or K name occurrences for duplicate detection;
- validates the run the way import does — `validateTestCase(row.data(), validationSchema, …)` per row for a single-turn run (unchanged), `validateMultiTurn(shared, turns, validationSchema, …)` for a multi-turn run, merged with the assembler's conflicts, exactly mirroring `validateRunAsMultiTurn` minus persistence;
- appends assembled cases to `sampleRows` until the sample limit, each carrying that result as its `valid`/`validationWarnings` — a multi-turn sample also carries `multiTurnData` (`toResponseDto` gains the turn array), a single-turn sample is unchanged;
- emits the merged warnings, anchored to the run's first row number.

The validation step is not optional bookkeeping: `TestCaseResponseDto.valid` is a primitive `boolean`, so an assembled multi-turn sample built without a validation result would silently report `valid=false`, a visible regression from today. And a preview that only reported assembly conflicts would stop reporting ordinary schema warnings (unknown field, type mismatch) for multi-turn CSVs, contradicting this requirement's own premise that preview mirrors import.

`totalRows` keeps counting CSV data rows. Adding `totalTestCases` rather than repurposing `totalRows` avoids silently changing a number every existing client already renders — including for single-turn CSVs, where the two coincide and no client would notice the semantics had shifted under them. OpenAPI examples for the preview response are updated with the new field (and a multi-turn sample in the `full` example).

### D6 — Fixup branches on the stored turn array, and never writes back an unreadable one

`fixupTestCases` gains a multi-turn branch: deserialize `multi_turn_data`, coerce changed columns inside each turn map as well as in shared `data`, re-validate via `TestCaseValidationService.validateMultiTurn` — passing the **full** schema, which that method splits by scope internally (`scopeResolver.splitSchema(testCaseSchema)`); passing a pre-split list would compile and silently reclassify every per-turn field as unknown — and include the re-serialized turn array in the `batchUpdate` row, which already writes `multi_turn_data`. The "did anything change" check widens to cover the turn maps; today it looks only at `data`, which is why the pass is inert for a turn-only case.

`fixupTestCases` is a **recomputation** pass, so D8's carry-forward rule applies: the re-validated result is unioned with the stored `SOURCE_CONFLICT` warnings and the case stays invalid while any remain. Without that, RC3 itself erases the import's conflict verdict before revalidation is ever reached — reachable today with `dup,0,1` / `dup,0,2`, where `inferCellType` makes the column `INTEGER`, coercion changes the stored values, and the re-validation fires.

The guard is the load-bearing part: `deserializeTurns` returns `null` for *both* "no turns" and "turns present but unreadable". On this write path those must not be conflated — treating unreadable as absent yields `MULTI_TURN_DATA = NULL`, which converts a multi-turn case to single-turn and destroys every turn, i.e. a worse version of the bug being fixed. So the branch keys on the raw column being non-blank, and a non-blank column that fails to parse causes the row to be **skipped entirely** (not added to `toUpdate`) with a WARN log carrying the exception as the last SLF4J argument. Distinguishing the two cases requires a read that *throws*, which `deserializeTurns` cannot do — so `ValidationWarningsSerializer` gains a sibling `deserializeTurnsStrict` that propagates the `JacksonException`. The existing lenient method and all its current callers are untouched: its graceful-degradation contract remains correct for read paths, and the strict variant is what write paths use. This is the AGENTS.md fail-safe-for-data-integrity rule applied to a read that feeds a write.

### D7 — Revalidation Phase 1 sees the turns; the `@Async` self-invocation is left alone

`importCsv` ends with `revalidationService.startDatasetRevalidation(datasetId)` whenever it persisted a schema. That method calls its own `@Async runDatasetRevalidationAsync` on `this`, so the Spring proxy is bypassed and Phase 1 executes **synchronously, inside the import transaction**. Phase 1 coerces `deserializeMap(tc.getData())` and validates it with `validateTestCase(postCoercionData, datasetSchema, null, List.of(), false, datasetId)`, writing through `updateValidationIfUnchanged` — whose `updated_at` guard passes, because the row it re-read is the one this same request just wrote.

Two independent defects, and only one of them is about CSV import:

**(a) The erased conflict verdict** is handled by D8's carry-forward rule, not here. Phase 1 keeps re-validating every row exactly as today; it simply stops discarding warnings it cannot regenerate.

**(b) Phase 1 is multi-turn-blind on every trigger**, dataset-schema-PUT included: it never coerces or validates turn contents, so a type-invalid per-turn value is silently marked valid. Fix: branch on the stored turn array — coerce each turn map with the same `SchemaChangeCoercer` used for shared `data`, validate via `validateMultiTurn` passing the **full** schema (it calls `scopeResolver.splitSchema` internally; handing it a pre-split list compiles and silently reclassifies every per-turn field as unknown), and persist the turn array under the same `updated_at` precondition. `TestCaseRepository` has no guarded update that writes `multi_turn_data` — `updateDataIfUnchanged` sets `DATA` and `UPDATED_AT_MS` only — so a sibling method writing both columns is added; the single-turn path keeps calling the existing one.

The D6 guard applies here with a wider blast radius: Phase 1 runs on every schema PUT, not only when a fixup found a changed column, so a row whose raw `multi_turn_data` is non-blank but fails `deserializeTurnsStrict` is skipped for **both** guarded writes and logged at WARN.

*Rejected alternative — a skip-set:* have `importCsv` pass the names it wrote and have Phase 1 skip them, on the grounds that they were just validated. It looks tidy and it is wrong. In OVERRIDE mode `buildValidationSchema` emits `type(null)` for every field, so the import performs **no type validation at all**; Phase 1 against the persisted typed schema is what catches a cell that could not be coerced (`fixupTestCases` only re-validates rows whose data actually changed, so it does not cover them either). Skipping those rows would leave them permanently marked valid. It would also silently change single-turn behavior and contradict two existing `test-cases` requirements that say an import spawns a task to *"coerce and revalidate existing test cases"*.

**Orphan per-turn keys must be pruned.** `DatasetService` removes fields dropped from a schema via `testCaseService.removeDataFields`, whose SQL is `UPDATE test_cases SET data = data - {0}::text[]` — `multi_turn_data` untouched. Today that is invisible because Phase 1 never looks at turns. Once it does, `validateMultiTurn` reports every stale turn key as an unknown field and the case goes invalid. So `removeDataFields` is widened to prune the same names from each element of `multi_turn_data`, guarded on the column being non-null. This is a consequence of D7(b), not independent scope. Note its reach: `DatasetService` passes only the field names actually **removed** from the schema, so the widening fixes the removed-field case and nothing else.

*What is deliberately not changed:* the `@Async` self-invocation. Making Phase 1 genuinely asynchronous would move it out of the import transaction and change when every revalidation runs and what it observes — well beyond this fix. RC4 makes **no `RevalidationService` method-signature change**, so `DatasetService` is untouched; it does add one constructor dependency (the D8 merger), which `RevalidationServiceTest`'s positional construction must absorb.

### D8 — One rule for every recomputation: import-derived conflicts are durable

Three passes can recompute a case's stored verdict — `fixupTestCases`, revalidation Phase 1 inside the same import request, and revalidation Phase 1 again on any later dataset schema PUT. Each derives validity from the stored row, and the import's conflict findings are not in the stored row. Patching them one at a time just moves the defect to the next pass, so the rule is stated once and applied at each:

- `validateRunAsMultiTurn` tags the two findings it folds into the persisted verdict — duplicate `turnIndex` and shared-column mismatch — with a new `ValidationWarningCode.SOURCE_CONFLICT`, meaning *"derived from the import source, not recoverable from stored data"*.
- The third multi-turn finding, non-contiguity, is **out of this rule** and stays as it is. It is emitted in `processRun` as a `CsvImportWarningDto` on the response, never reaches the case's `ValidationResult`, and never sets `valid=false`. That is coherent: a non-contiguous name is a *second* run of a name that already exists, and no strategy leaves a conflicted case behind — under `FAIL` the request 409s and rolls back, under `SKIP` the second run is not persisted, and under `OVERRIDE` it legitimately replaces the earlier case (`insertOrOverride`), which is the requested outcome rather than a defect. Making it durable would mean newly invalidating cases that are valid today, which no scenario asks for.
- A small injectable merger in `service.domain` takes (recomputed result, stored warnings JSON) and returns the recomputed warnings unioned with the stored `SOURCE_CONFLICT` entries, with `valid = recomputedValid && preserved.isEmpty()`. Both recomputation passes call it immediately before writing — which means `RevalidationService` gains it as a constructor dependency; there is no way to have an injectable merger *and* an unchanged constructor, and the merger is the part that must not be duplicated.
- **Recomputation only.** A direct API write (`PUT`/`PATCH` a test case) carries new user-authored content and legitimately clears these warnings, exactly as today — those paths do not call the merger.

*Why a new enum constant rather than reusing `ADDITIONAL`* (which `validateRunAsMultiTurn` uses today): `ADDITIONAL` is also emitted by ordinary schema validation for unknown fields, so preserving it wholesale would resurrect stale unknown-field warnings on every pass. The rule needs a code no recomputation can itself produce. The constant is additive on the wire (serialized by name).

*Consequence, stated rather than hidden:* a `SOURCE_CONFLICT` warning persists until the case is edited or re-imported. That is the intended lifetime — the case genuinely was authored from conflicting rows, and no amount of inspecting the stored data can disprove it.

## Risks / Trade-offs

- **Adjacent same-named single-turn rows collapsed into one case** → the explicit run ≠ test case rule (D3) plus a new preview functional test with two adjacent identical names asserting the duplicate warning survives and `totalTestCases == totalRows`. The existing `DupRow`/`duprow` test cannot catch this.
- **New non-contiguity warnings leaking onto single-turn runs** → accumulator tracks completed names for multi-turn runs only (D2), pinned by running `CsvImportModeFunctionalTests` unmodified.
- **Preview and import now share code, so a defect surfaces in both** → the shared components get direct unit tests (grouping, ordering, duplicate `turnIndex`, shared conflict, schema carry-forward, no-mutation) in addition to functional tests on both endpoints. Net risk is still lower than two divergent copies, which is what produced this bug.
- **Preview response changes for multi-turn CSVs** (`sampleRows` granularity, warning row anchoring) → unavoidable; it *is* the fix. Bounded by keeping `totalRows` untouched and single-turn responses additive-only.
- **`CsvImportServiceSchemaTest` constructs `CsvImportService` positionally and stubs `validateTestCase` strictly** → new constructor arguments must be threaded through, and any existing test whose CSV becomes multi-turn under the new grouping needs `validateMultiTurn` stubbed. Expect this test to need mechanical updates; treat unexpected failures there as a signal that grouping changed single-turn behavior.
- **Re-serializing turn maps drops explicit JSON nulls** — the shared `ObjectMapper` uses `NON_NULL` content inclusion, so a per-turn key with an explicit null value disappears when the fixup rewrites the array. Pre-existing on the import write path (`serializeTurns` in `toMultiTurnEntity`), inherited rather than introduced. Accepted, with a key-set assertion in the fixup test so it cannot silently widen.
- **D7 touches a service every revalidation trigger shares** (dataset schema PUT, not just CSV import) → branch strictly on the presence of a stored turn array so the single-turn path is byte-identical, and run the existing revalidation functional tests unmodified.
- **A dataset already corrupted by this bug gets noisier before it gets better.** Its schema has `perTurn` stripped, so `schemaSplit.perTurn()` is empty and D7(b)'s turn validation reports every turn key as an unknown field: from the first revalidation after deploy, its multi-turn cases are marked invalid with one warning per turn key per turn. Nothing here repairs a stored schema — RC1 carries forward whatever `perTurn` is currently stored — so clearing it means restoring the `perTurn` flags with a PATCH, after which the next revalidation reports the cases valid again. Accepted: the warnings are truthful about what the stored schema now says, and recovering such a dataset already requires re-importing the original CSV (see the proposal's *Data* note).
- **The round-trip guarantee is schema-scoped** — a case holding a key its dataset schema does not declare loses that key on export (`CsvExportService` derives columns from the schema), so no import fix can round-trip it. Stated as a condition on the requirement rather than silently assumed.
- **Already-corrupted datasets are not repaired by this change** → a stripped schema can be fixed with a PATCH, but a case already flattened to `data = {turn 0}`, `multiTurnData = [{}, …]` has lost turns 1..N-1 from the database. Recovery is re-importing the user's original CSV; called out in the rollout note, not implemented as a data migration.

## Migration Plan

Code-only: no Flyway migration, no configuration property, no feature flag. Deployable in one step; rollback is a redeploy of the previous artifact. `totalTestCases` is response-only and no stored shape changes, but rollback is *lossy* for one thing: `SOURCE_CONFLICT` is written into the `validation_warnings` JSONB, and the previous artifact's `ObjectMapper` does not enable `READ_UNKNOWN_ENUM_VALUES_AS_NULL`, so parsing such a row throws — `ValidationWarningsSerializer.deserializeWarnings` catches it and returns an empty list. Affected rows would therefore show *no* warnings (not wrong ones) until revalidated. Safe, but state it rather than claim a clean rollback.

Post-merge, `AGENTS.md`'s multi-turn inline-conventions bullet gains the `perTurn`-preservation invariant, since its absence is how this shipped.
