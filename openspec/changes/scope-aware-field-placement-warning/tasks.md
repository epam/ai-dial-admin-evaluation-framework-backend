## 1. Warning code

- [ ] 1.1 Add `INVALID_SCOPE` to `evaluation-runner-core/src/main/java/com/epam/aidial/evaluation/runner/dto/ValidationWarningCode.java` (done: constant present; `./gradlew :evaluation-runner-core:compileJava` clean)
- [ ] 1.2 Confirm no consumer needs updating — grep `src/main`, `evaluation-runner-core/src/main`, `eval-cli/src/main` for any `switch` over `ValidationWarningCode` and for code-keyed maps (done: no exhaustive switch found, or the one found handles the new constant)

## 2. Data scope resolver

- [ ] 2.1 Create `src/main/java/com/epam/aidial/evaluation/service/domain/TestCaseDataScopeResolver.java` — `@Component @LogExecution @RequiredArgsConstructor`, injecting `TestCaseFieldScopeResolver`; nested `ScopePlacement` record `(shared, turns, misplacedFields, warnings)` (done: compiles, class carries `@LogExecution` per `LoggingConventionTest`)
- [ ] 2.2 Implement `inspect(data, turns, schema)` — detect declared-per-turn keys in `data` and declared-shared keys in each turn map, build one `INVALID_SCOPE` `ValidationWarningDto` per occurrence (message = existing 400 wording verbatim; `fieldName` set; `path` `$.data.<field>` / `$.multiTurnData[<i>].<field>`; `turnIndex` set for turn occurrences only), and return the buckets with those keys removed plus the set of misplaced field names (done: unit-tested in 5.1)
- [ ] 2.3 Guarantee `inspect` returns **new** maps and never mutates its inputs, and returns turns 1:1 with the input list in input order (done: unit test asserts caller-visible input maps are unchanged and `turns.size()` is preserved — `validateMultiTurn` relies on both for the max-turns count and turn-index stamping)
- [ ] 2.4 Implement `requireCorrectScope(data, turns, schema)` — delegate to `inspect`, throw `ValidationException` with the first warning's message when any warning is produced; check `data` before the turn maps so a both-directions violation throws the `data`-side message, matching today's ordering (done: the two existing 400 message strings are produced by this single code path, not duplicated)
- [ ] 2.5 Make `inspect` null-safe for `data == null`, `turns == null`, null turn elements, and an empty/null schema (done: covered by unit tests in 5.1)

## 3. Wire into validation

- [ ] 3.1 `MultiTurnFieldsValidator` — inject the resolver, delegate the placement half of `validateStructure` to `requireCorrectScope`, keep the empty-`multiTurnData` 400, the `multiTurnData == null` early return **before** the delegation, and `getMaxTurns()` in place; drop the now-duplicated placement loops (done: class no longer references `perTurnFieldNames`/`sharedFieldNames` directly; existing 400 behaviour unchanged; `MultiTurnFieldsValidatorTest`'s single-turn skip case still passes)
- [ ] 3.2 `TestCaseValidationService.validateMultiTurn` — inject the resolver, call `inspect(...)` right after `splitSchema`, append `placement.warnings()` to the warning list, and validate `placement.shared()` / `placement.turns()` instead of the raw maps (done: signature unchanged)
- [ ] 3.3 In the same method, filter **both** sub-schemas by `placement.misplacedFields()` before validating — this is what suppresses the `Required field '<x>' is missing` twin, which stripping the map alone does NOT suppress (the required check iterates the sub-schema and reads the bucket map, `TestCaseValidationService.java:147-158`) (done: a misplaced *required* field in either direction yields the `INVALID_SCOPE` warning and nothing else)
- [ ] 3.4 Extract a named private method in `TestCaseValidationService` that stamps a turn warning: sets `turnIndex` and rewrites `path` from `$.data.<field>` to `$.multiTurnData[<i>].<field>`. Leave every warning **message** untouched — the bucket is carried by `path`, so the `Unknown data field '<x>'` text is unchanged (done: applied at the existing stamping loop; no inline logic left there)
- [ ] 3.5 Verify max-turns and warning-truncation behaviour still apply after the scope warnings are appended (done: over-cap case still invalid; total warnings capped at `validation.max-warnings-per-case`)

## 4. Verify untouched paths

- [ ] 4.1 Confirm `DurableWarningMerger` is unchanged and `INVALID_SCOPE` is NOT carried forward (done: merger still filters `INVALID_INPUT` only; covered behaviourally by the clearing phase of test 5.4)
- [ ] 4.2 Confirm the recomputation callers still **serialize before they validate** — `RevalidationService.processMultiTurnCase` (`:322-328` write, `:339-346` validate) and `CsvImportService.fixupMultiTurnCase` (`:880-881` write, `:886-887` validate) — so the maps `inspect` receives are already persisted; note these ARE the same objects, which is why 2.3's no-mutation guarantee is the actual safety property (done: both call orders unchanged)

## 5. Tests

- [ ] 5.1 New unit test `TestCaseDataScopeResolverTest` — shared-in-turn, per-turn-in-data, both directions at once (both reported, `data`-side message thrown), undeclared key untouched, empty schema, null/empty inputs, stripped-bucket contents, `misplacedFields` contents, inputs not mutated, turn arity preserved, throw-mode message equality with warn-mode (done: `./gradlew test --tests "*TestCaseDataScopeResolverTest"` passes)
- [ ] 5.2 Update `MultiTurnFieldsValidatorTest` for the new constructor collaborator; keep existing 400 assertions **including the single-turn (`multiTurnData` null) skip case**, and add one asserting the exact message text for each direction (done: `./gradlew test --tests "*MultiTurnFieldsValidatorTest"` passes)
- [ ] 5.3 Update `TestCaseValidationServiceMultiTurnTest` and `TestCaseValidationServiceTypeTest` constructors; add cases for: shared **required** field inside a turn → single `INVALID_SCOPE` warning with `turnIndex` and `$.multiTurnData[i].x` path and **no** `Required ... missing from data` twin; per-turn **required** field in `data` → single `INVALID_SCOPE` warning and **no** required-missing twin on any turn; both directions at once; undeclared key → unknown-field warning retaining its message verbatim with the bucket-identifying `path` (done: `./gradlew test --tests "*TestCaseValidationService*"` passes)
- [ ] 5.4 Functional test for the #137 flow — create a dataset with a `perTurn: true` field, a multi-turn case holding per-turn values, PUT the dataset schema flipping that field to shared, await revalidation, then assert the case is invalid with an `INVALID_SCOPE` warning per turn carrying the misplacement message, and that the stored turn values were not relocated (done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$<NestedTests>"` passes)
- [ ] 5.5 Extend 5.4 with a clearing phase — flip the schema field back to `perTurn: true`, await revalidation, assert the `INVALID_SCOPE` warning is gone and the case is valid (done: proves the warning is recomputed, not carried forward, i.e. the behavioural half of 4.1)
- [ ] 5.6 Functional coverage for the opposite direction — a shared field re-scoped to `perTurn: true` while cases hold its value in `data` → `INVALID_SCOPE` warning against `$.data.<field>`, no required-missing on any turn (done: same suite as 5.4 passes)
- [ ] 5.7 Assert the CSV fixup path reports the same warning shape — import a CSV that persists a schema whose scope disagrees with stored values, then assert the fixed-up case carries `INVALID_SCOPE`, not an unknown-field warning (done: `./gradlew test --tests "*MultiTurnCsvFunctionalTests*"` passes)
- [ ] 5.8 Add an accept-case for an undeclared key on the write path — a POST/PUT placing an undeclared key in a turn map SHALL return 2xx with `valid=false`, not 400 (done: covered in the functional test-case suite)
- [ ] 5.9 Run the CSV suites that touch multi-turn validation to confirm no regression, including the `Unknown data field` prefix assertion (done: `./gradlew test --tests "*CsvImport*" --tests "*MultiTurn*"` passes)

## 6. Docs and specs

- [ ] 6.1 Sync the `validation_warnings` documentation in `docs/database-schema.md` (~line 342) with the actual enum — it currently lists only `REQUIRED|TYPE|FORMAT|PATTERN|ENUM|ADDITIONAL|UNKNOWN`, missing `UNRESOLVED_REFERENCE`, `INVALID_OUTPUT_SCHEMA`, `REQUEST_BODY_EVALUATION_ERROR`, `INVALID_INPUT` — and add `INVALID_SCOPE` plus the `turnIndex` field to the JSONB example (done: doc matches `ValidationWarningCode` and the serialized DTO)
- [ ] 6.2 Update `docs/patterns/multi-turn-test-cases.md` (the "a misplaced field → **400**" sentence under *Field scope*) to state the split rule: 400 on a write carrying `multiTurnData` for a **declared** field, `INVALID_SCOPE` warning on recomputation paths, and name `TestCaseDataScopeResolver` as the owner (done: the pattern doc no longer contradicts the implementation)
- [ ] 6.3 Sync the delta specs into `openspec/specs/test-cases/spec.md` and `openspec/specs/multi-turn-test-case/spec.md` (done: `/opsx:sync` applied or archive performs it; requirement text matches the deltas)
- [ ] 6.4 Note the new warning code for the frontend in the PR description so #137's tooltip can render a dedicated treatment (done: PR body lists the code, its message shapes, and the `path` change on turn warnings)

## 7. Build gates

- [ ] 7.1 `./gradlew spotlessApply` (done: no formatting diff left)
- [ ] 7.2 `./gradlew checkstyleMain checkstyleTest` (done: clean)
- [ ] 7.3 `./gradlew clean build` (done: full suite green, including `LoggingConventionTest`, `LayeredArchitectureTest`, and `RunnerModuleConstraintsTest`)
