## Context

See `proposal.md` — Why. This document covers only the technical shape of the fix.

Current state relevant to the approach:

- `evaluation-runner-core` already implements both features end to end. `RequestChainExecutor` builds the ordered request list, `TurnLoopExecutor` runs each request's turn loop, and `PerTurnBindingDetector.referencesPerTurnField(bindings, schema)` decides turn count from the **dataset schema**. Nothing in the engine needs to change.
- `EvaluationContext.snapshotTestCaseSchema` is the single input that activates per-turn detection. The backend feeds it from `SuiteSnapshotDto.testCaseSchema` (`TestSuiteEvaluationJob.buildContext`); `eval-cli`'s `EvaluationContextFactory.create` never sets it, and `SuiteFetchBundle` has nowhere to carry it — `TestSuiteResponseDto` has no schema field, and the schema lives on the dataset (`DatasetResponseDto.testCaseSchema`, whose `FieldDefinitionDto.perTurn` is the scope flag).
- `TestCaseRunResult` (runner model) already carries `requestIndex`/`totalRequests`/`turnIndex`/`totalTurns`, and `test_case_run_results` already has the four matching columns. The loss happens purely at the CSV/import boundary: `CsvResultBatchWriter.HEADERS` has 14 columns and `EvalResultsCsvParser.RESERVED_COLUMNS` has 15, neither including the four.
- The identity loss is silent for CLI-produced files, not loud. `CsvResultBatchWriter` omits `testCaseId`, and `EvalResultsCsvParser` fills the gap with `.testCaseId(testCaseId != null ? testCaseId : UUID.randomUUID())` — **a fresh UUID per row**. `EvalResultsImportService.testCaseIdentity` prefers `testCaseId` over `testCaseName`, so the duplicate key never collides for such a file: a multi-request or multi-turn CLI run imports cleanly and produces rows that each look like an unrelated single-request single-turn case. The duplicate-key rejection is real only for CSVs that carry `testCaseId` — hand-produced ones.
- A live run persists all `(request, turn)` rows of one repetition under a **single** `test_case_id`. The eval-summary natural key `(run, test_case_id, run_index, request_index, turn_index, computation_id, created_at_ms)` relies on that, so per-row random ids leave imported results ungroupable even once the four identity columns are carried.
- `SchemaValidationService.buildFieldSchema` flattens all schema fields into one `properties` map and all `required` fields into one `required` list, with no regard for `perTurn`; `EvalResultsCsvParser` validates every row's `testCaseData` against it. `TestCaseFieldScopeResolver` (`service.domain`) already exists as the project's single scope-splitting component (`splitSchema` returns a `SchemaSplit(shared, perTurn)`).
- Imported runs already capture a real suite snapshot: `executeRunAsync` calls `executeSnapshotPhase(runId, !skipDeploymentPhase)`, which for an import passes `captureTestCaseInputs = false` but still runs `attemptSnapshot`, and `SuiteSnapshotBuilder` populates `requestName`, `additionalRequests`, `testCaseSchema` (from the dataset) and `overallScore`. The `"{}"` at `TestSuiteRunService:183` is the `runConfigJson` argument of `createAndSaveRun`, not the snapshot. `buildRequestLabels(resolveSnapshot(run))` therefore returns real labels for imported runs. See Decision 5.

## Goals / Non-Goals

**Goals:**

- Make the CSV import contract lossless for row identity — both the four position columns and the per-repetition grouping — without breaking any CSV produced against the current 15-column contract.
- Make import-time `testCaseData` validation correct for datasets that declare per-turn fields.
- Activate the engine's existing per-turn detection in the CLI by supplying the one missing input.
- Keep the CLI's persisted fetch bundle forward- and backward-loadable across this format change.
- Give the CLI the backend's MCP/multi-turn rejection so both refuse the same combination.

**Non-Goals:**

- No change to `evaluation-runner-core`. If a task appears to need one, that is a signal the diagnosis is wrong — stop and re-check.
- No new analytics columns, no Flyway migration, no new configuration property. All four identity columns already exist on `test_case_run_results`.
- No change to how a *live* run behaves. Every default in this design is chosen so a single-request, single-turn run's CSV and rows stay byte-identical.
- No fetch/run parity for `disabledTestCaseIds` or a suite's `testCaseFilter`. The backend selects runnable cases via `RunnableTestCaseSelector`; the CLI runs every fetched case. Closing that gap is separate work.
- No MCP request chaining, and no fix for GH #120 (`perTurn` dropped on the CSV OVERRIDE import path) — a different code path, tracked separately.

## Decisions

### Decision 1: Four optional reserved CSV columns, defaulted rather than required

Add `requestIndex`, `totalRequests`, `turnIndex`, `totalTurns` to `EvalResultsCsvParser.RESERVED_COLUMNS` (15 → 19) as **optional** columns: an absent header or a blank cell yields the same values a single-request single-turn row already has (`0`, `1`, `0`, `1`) — which are also the runner model's builder defaults.

*Why:* the import endpoint is a public contract with existing callers. Making the columns required would break every 15-column CSV in existence for zero benefit — those files describe genuinely single-row repetitions, so the defaults are not a guess but the correct value. Optionality makes the change additive and lets the CLI and backend versions skew freely in either direction.

*Alternative rejected:* a CSV format version column or a separate v2 endpoint. Both add a contract surface to maintain for a change that is naturally backward-compatible.

### Decision 2: Identity parsing and sanity checks live in the parser, batch-shape checks in the import service

The four cells are parsed and range-checked in `EvalResultsCsvParser` alongside the other per-row inline checks (`runIndex` range, `testCaseName` length, required-field presence), accumulating into the same all-or-nothing collected-violations list. `EvalResultsImportService.validateBatch` only changes its duplicate key.

*Why:* this preserves the existing split the `eval-results-import` spec already describes — per-row field constraints in the parser, batch-shape constraints in the import service — and reuses the multi-row error accumulation rather than introducing a second failure style. Per AGENTS.md, these are explicit inline checks on a programmatically built domain object, not `jakarta.validation.Validator` over a DTO; there is no DTO on this path by prior design.

Checks applied per row, against the **effective** values after defaulting (so a supplied `requestIndex = 2` with a blank `totalRequests` is rejected against the defaulted total of 1): each of the four parses as an integer when non-blank; `requestIndex >= 0`; `turnIndex >= 0`; `totalRequests >= 1`; `totalTurns >= 1`; `requestIndex < totalRequests`; `turnIndex < totalTurns`. Cross-row consistency of `totalRequests`/`totalTurns` per identity is deliberately **not** enforced — `totalTurns` legitimately differs per request within one chain, so a global per-identity invariant would be wrong.

*Alternative rejected:* a new injectable `RowIdentityParser` component. The logic is four `Integer.parseInt` calls plus a handful of comparisons, structurally identical to the `runIndex` check sitting next to it; extracting a component here would be ceremony, not testability.

### Decision 3: Duplicate key becomes the full row identity `(identity, runIndex, requestIndex, turnIndex)`

*Why:* this is the actual uniqueness invariant of a run's result set — `(request_index, turn_index)` are orthogonal dimensions and together with `runIndex` they identify a row within a test case. The current key is not a weaker version of this; it is the special case where both extra dimensions are pinned to zero.

The default-to-`0` behavior of Decision 1 means a legacy 15-column CSV produces exactly the key it produces today. Note that the key only starts doing real work for id-less CSVs once Decision 4 lands: until identity is stable per test case, every row carries a distinct random id and no key can collide.

*Alternative rejected:* dropping in-batch duplicate detection for multi-row suites. Duplicate detection is the only guard against a caller double-appending a batch; weakening it to fix a key bug would trade one correctness problem for another.

### Decision 4: One synthesized identifier per distinct `testCaseName` per file

When a row has no `testCaseId`, `EvalResultsCsvParser` resolves its identifier through a file-scoped `Map<String, UUID>` keyed by `testCaseName`, so every row naming the same test case shares one generated `test_case_id`. Rows that do supply `testCaseId` are unaffected. A row with neither `testCaseId` nor `testCaseName` gets no derived identifier (no null-keyed map entry): its `testCaseId` stays null so `EvalResultsImportService.testCaseIdentity`'s existing "Either testCaseId or testCaseName is required" check rejects the batch with a clean 400 before any run exists — today's per-row random UUID masks that check and lets such a row die on the `test_case_name NOT NULL` constraint after the run is already created.

*Why:* this is what makes the import contract's promise of live-run equivalence true. Without it the four position columns are carried but useless — each row is its own test case, so `(test_case_id, run_index)` no longer groups a repetition and the eval-summary natural key cannot reconstruct which rows belong together. It also fixes **every** caller of the endpoint, not just the CLI, and needs no CSV format change: `testCaseName` is already the documented fallback identity, and this simply makes the synthesized id agree with it. Scoping the map to one parse call keeps ids from leaking between requests, preserving the existing "identity is a label, not a reference" contract.

*Alternative rejected:* have the CLI emit the source-side `testCaseId`. It is safe on its own terms — the import path never resolves ids against a dataset — but it fixes only the CLI while leaving every other id-less caller broken, and it ships source-environment identifiers into the destination's analytics, which is exactly what `CsvResultBatchWriter` omits `testCaseId` to avoid. It also leaves the backend contract self-inconsistent: a documented name-only import would still fragment.

*Alternative rejected:* deriving the id deterministically from the name (e.g. a UUIDv5 over `testCaseName`). It would make ids collide across separate imports of the same suite, silently merging two runs' rows in any query that groups by `test_case_id` alone.

### Decision 5: No work on imported-run request labels — assert the existing behavior with a test

Imported runs already capture a real suite snapshot and already resolve request labels from it (see Context). The behavior was blocked only by `requestIndex` being pinned to `0` on every imported row, which Decision 1 fixes. Once rows carry their true `requestIndex`, `MetricEvaluationContext.requestLabelAt(requestIndex)` indexes the snapshot's label list correctly with no code change.

Persisting a minimal snapshot at import, or falling back to the live destination suite when the stored snapshot is empty, are both unnecessary — the first is already the actual behavior, and the second would be worse, since reading the live suite at Phase 2 time means a suite edited after import silently relabels an already-imported run's results.

This is covered by a **functional test**, not by an implementation task: import a multi-request CSV into a chain-configured suite and assert that a `request.name`-pinned conditional metric runs on exactly the intended chain position. Without that test the claim rests on reading.

### Decision 6: Import-time schema validation enforces required-ness only for shared fields

`EvalResultsCsvParser` splits the dataset schema through the existing `TestCaseFieldScopeResolver.splitSchema(schema)` and builds its validation schema so that:

- **shared** fields keep today's behavior — present in `properties`, and in `required` when declared required;
- **per-turn** fields stay in `properties` (so a present value is still type-checked) but are **never** added to `required`.

*Why:* a row's `testCaseData` is the effective view for one `(request, turn)` position, and a legitimate row may carry shared fields only — a chain position that binds no per-turn field executes once from shared data, and a single-turn case in a per-turn dataset persists shared-only data. Enforcing per-turn required-ness on every row therefore rejects correct imports. Type-checking a per-turn field when present is still right: a wrong-typed value is wrong regardless of scope. Reusing `TestCaseFieldScopeResolver` keeps scope semantics (`perTurn == TRUE` only; null/false is shared) defined in exactly one place, per AGENTS.md.

*Alternative rejected:* validating per-turn required-ness by first grouping the file's rows per test case and checking the union. It couples row validation to batch shape, needs the whole file in hand before any row can be judged, and still cannot distinguish "this chain position binds no per-turn field" from "the caller forgot a field".

*Alternative rejected:* skipping per-turn fields entirely (dropping them from `properties`). That would silently accept a per-turn field carrying a wrong-typed value, trading one over-strict bug for an under-strict one.

### Decision 7: Absent bundle schema is a hard failure only when it would change results

A bundle persisted by an older CLI has no `testCaseSchema` field; Jackson leaves it null and the bundle loads. Rather than silently running every case as single-turn, `RunOrchestrationService` fails fast when the schema is absent **and** at least one fetched test case carries `multiTurnData`, instructing the user to re-run `fetch`.

*Why:* a stale bundle for an all-single-turn suite is genuinely equivalent to a fresh one, so failing there would be gratuitous. A stale bundle for a multi-turn suite would produce wrong results that look successful — exactly the silent-wrong-answer failure this change exists to remove. The guard is placed where the two facts (schema, test cases) are both in hand.

*Alternative rejected:* auto-re-fetching on a stale bundle. `run` is documented as invocable standalone against previously fetched data; making it issue source-EF calls would break that contract and surprise offline users.

### Decision 8: The MCP guard is a pre-flight check in the CLI's existing validation seam

`RunOrchestrationService.run` already calls `suiteContractValidator.validate(bundle.getSuite())` before execution. The MCP/multi-turn rejection needs both the suite and the test cases, so it goes alongside that call, before `EvaluationContextFactory.create` and before any target invocation — mirroring `TestSuiteRunService`'s backend guard, which throws `InvalidOperationException("Cannot create a run: MCP suites do not support multi-turn test cases")` → 409.

The CLI has no HTTP status to return; it fails the command with a non-zero exit code and an error naming the combination, consistent with how other CLI pre-flight failures surface.

### Decision 9: The CLI binds the dataset schema through a minimal local response record

`DatasetResponseDto` is a backend-only type in `service.domain.dto` — `eval-cli` cannot import it, and mirroring it wholesale is exactly the duplication the module's DTO-consolidation policy forbids. `DatasetApiClient` instead declares a minimal local response record exposing only the field it needs:

```java
record DatasetSchemaResponse(List<FieldDefinitionDto> testCaseSchema) {}
```

`FieldDefinitionDto` is already a shared `runner.dto` type used by both sides, so no DTO is duplicated — only the one-field envelope is local, and unknown JSON properties are ignored. This mirrors the precedent already set by `client.source.dto.TestSuiteUpdateResultDto`, the module's one deliberate response subset.

*Alternative rejected:* promoting `DatasetResponseDto` into `runner.dto`. It carries visibility, validation warnings, versioning and audit fields that the runner module has no business knowing about, purely to serve one client's need for one field.

*Alternative rejected:* extending `TestSuiteResponseDto` with the dataset schema so `fetch` needs no second call. That changes a public backend API response shape to serve one client, and duplicates data the dataset endpoint already owns. The extra GET is one call per suite per fetch.

*Alternative rejected:* deriving `perTurn` by inspecting which fields appear in test cases' `multiTurnData`. It infers scope from data instead of reading the declaration, and would answer wrongly for a per-turn field that happens to be absent from every turn map.

### Decision 10: Column order — append the four at the end of the CLI's CSV header

`CsvResultBatchWriter.HEADERS` becomes the current 14 followed by `requestIndex, totalRequests, turnIndex, totalTurns`. The parser resolves columns by header name, not position, so order is free; appending keeps the diff minimal and keeps the pre-existing columns at their current indices for anyone eyeballing or diffing produced files.

The writer's Javadoc paragraph asserting that `turnIndex`/`totalTurns` "are not part of `EvalResultsCsvParser.RESERVED_COLUMNS` at all" becomes false with this change and must be rewritten in the same task, not left to drift.

## Risks / Trade-offs

- **A CLI upgraded ahead of its backend corrupts data silently** → an old backend ignores the four unknown headers and persists `0`/`1`/`0`/`1` with a random id per row, producing a run that looks successful and is wrong. There is no error to alert on. This is why deployment order is a hard requirement rather than a note (see Migration Plan), and why `eval-cli/README.md` must state the minimum backend version.

- **Row counts multiply against `analytics.results.batch.max-items`** → a chain of R requests over T turns produces R×T rows per repetition where one row was produced before, so a suite that fit under the cap can now exceed it. Not resized here (out of scope per the proposal); documented in `eval-cli/README.md` so operators can raise the property deliberately rather than discover it as a 400.

- **Bundle format grows** → mitigated by Decision 7: old bundles load, and only fail when the missing field would actually change the answer. New bundles read by an older CLI would carry an unknown property; this is a forward-compat case the CLI does not support today and this change does not make worse.

- **Cloned datasets must preserve `perTurn` for the CLI's fetched schema to be meaningful** → the CLI fetches the *source* dataset's schema and runs against it, then imports into the destination clone, whose dataset schema drives `testCaseData` validation at import. If suite cloning dropped `perTurn`, the CLI would execute correctly but Decision 6's scope split would classify formerly-per-turn fields as shared and re-introduce the required-field rejection. GH #120 is a different path (CSV OVERRIDE import) and is out of scope, but the clone path's `perTurn` preservation is a verification item in the tasks, not an assumption.

- **`totalRequests`/`totalTurns` are caller-supplied and only locally checked** → a caller can submit internally consistent but factually wrong totals (e.g. `totalTurns = 5` for a 3-turn case). This matches the endpoint's established caller-trusted posture for `testCaseData` and identity; cross-checking against the destination suite's live chain would reintroduce exactly the resolution coupling the import contract deliberately avoids.

- **Name-keyed identity makes `testCaseName` load-bearing for id-less imports** → two genuinely different test cases sharing a name in one file now collapse into one identifier, where previously they stayed separate (by accident, via random ids). This is the correct reading of `testCaseName` as the documented fallback identity, and the widened duplicate key still rejects any resulting exact-position collision rather than silently merging rows.

## Migration Plan

No data migration and no schema change.

**Deployment order is a hard requirement:** the backend's import-contract extension MUST be deployed before the CLI change is released. A CLI running against an older backend does not fail — it silently produces the corrupt shape described in Risks. `eval-cli/README.md` states the minimum backend version, and the CLI change must not ship ahead of it.

Rollback is independent per side. Reverting the CLI leaves the backend accepting a superset of what it accepted before, with no behavior change for existing callers. Reverting the backend restores today's behavior for 15-column CSVs, silently ignores the four columns on 19-column ones, and returns id-less imports to per-row random identity — so a backend rollback should be paired with a CLI rollback.
