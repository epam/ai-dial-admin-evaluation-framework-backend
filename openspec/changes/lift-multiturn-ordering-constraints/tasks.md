# Tasks — lift-multiturn-ordering-constraints

Ordered by dependency. Each group is one iteration-sized unit. Do NOT mark a task `[x]` until its tests have actually run and passed (AGENTS.md Test Execution Discipline).

## 1. Analytics schema — `last_turn_index`

- [x] 1.1 Add Flyway analytics migration `src/main/resources/db/migration/analytics/POSTGRES/V1.15__AddLastTurnIndexToTestCaseRunResults.sql`: `ALTER TABLE test_case_run_results ADD COLUMN last_turn_index INTEGER NOT NULL DEFAULT 0;` (comment: max authored surviving turn index of the conversation; `0` for single-turn). Do NOT touch any unique/idempotency index (it already includes `turn_index`).
- [x] 1.2 Run `./gradlew generateJooq`; commit the `src/main/java-generated/` diff (analytics `TestCaseRunResults` table/record gain `LAST_TURN_INDEX`).
- [x] 1.3 Add `lastTurnIndex` (`int`) to the `TestCaseRunResult` analytics model; update its RecordMapper (read) and the batch-write insert (write) in the analytics results repository to round-trip the column.
- [x] 1.4 Verify `JooqSchemaDriftTest` passes: `./gradlew test --tests "*JooqSchemaDriftTest"`.

## 2. Assembler — drop contiguity, add `lastTurnIndex`, narrow `broken`

- [x] 2.1 `ConversationAssembler`: delete `isContiguousFromZero` and the `contiguous` term. Set `broken = anyInvalid || survivors.size() > MAX_CONVERSATION_TURNS`. Keep survivors sorted ascending by authored `turnIndex`; keep the empty-survivors → `Optional.empty()` drop.
- [x] 2.2 `ConversationAssembler.AssembledConversation`: add `int lastTurnIndex` (max authored surviving `turnIndex`; `0` when broken/empty is irrelevant since broken carries no turns). Populate it in `assemble`. `turnsJson` keeps authored `turnIndex` per turn (no renumbering).
- [x] 2.3 Run `./gradlew test --tests "*ConversationAssemblerTest"` after updating that test (see group 6).

## 3. Executor + worker — carry `lastTurnIndex`, reword sentinel

- [x] 3.1 `MultiTurnConversationExecutor`: thread the conversation's `lastTurnIndex` (max authored surviving index across the frozen turns) into every `TestCaseRunResult` via `buildTurnRow` (keep `turnIndex` authored, `totalTurns` = surviving count). Set it on the degenerate/abort error rows too.
- [x] 3.2 `EvaluationWorker.buildBrokenConversationResult`: set `lastTurnIndex = 0` on the `0/0` sentinel row and reword the `BROKEN_CONVERSATION` message to reference only "an invalid turn, or too many turns" (remove turn-0 / contiguity / duplicate-index wording).
- [x] 3.3 Run `./gradlew test --tests "*MultiTurnConversationExecutorTest" --tests "*EvaluationWorkerTest"` after updating those tests (group 6).

## 4. Write-time validation — drop the cap bound

- [x] 4.1 `ConversationFieldsValidator`: remove the `turnIndex >= MAX_CONVERSATION_TURNS` → 400 check. Keep both-or-neither, valid UUID, `turnIndex >= 0`. Leave `MAX_CONVERSATION_TURNS` in `ValidationConstants` (now enforced only at assembly).
- [x] 4.2 Run `./gradlew test --tests "*ConversationFieldsValidatorTest"` after updating that test (group 6).

## 5. Condition context + evaluator — redefine `turn.last`

- [x] 5.1 `ConditionContext`: add `int lastTurnIndex` to the record; update the builder/callers.
- [x] 5.2 `InProcessMetricEvaluationExecutor`: populate `lastTurnIndex(result.getLastTurnIndex())` at both `ConditionContext` build sites.
- [x] 5.3 `ConditionExpressionEvaluator`: change `turn.put(TURN_LAST, ...)` from `(turnIndex + 1) == totalTurns` to `turnIndex == lastTurnIndex`. Update the Javadoc line describing `turn.last`.
- [x] 5.4 Run `./gradlew test --tests "*ConditionExpressionEvaluatorTest"` after updating that test (group 6).

## 6. Tests

- [x] 6.1 `ConversationAssemblerTest`: flip contiguity cases (missing turn 0, gap, middle hole) from broken→runnable with authored indices preserved; assert `lastTurnIndex`; keep invalid-surviving-turn and over-cap → broken; keep empty→`Optional.empty()`.
- [x] 6.2 `SnapshotInputWriterTest`: assert a middle-disabled / filtered-out conversation is materialized as a runnable ordered-turns input (not a broken marker); assert fully-disabled conversation is dropped.
- [x] 6.3 `MultiTurnConversationExecutorTest`: assert emitted rows carry authored `turnIndex` and the conversation `lastTurnIndex`; add a non-contiguous survivors case.
- [x] 6.4 `EvaluationWorkerTest`: assert the broken sentinel carries `last_turn_index = 0` and the reworded message.
- [x] 6.5 `ConversationFieldsValidatorTest`: remove the over-cap 400 case; add a "large turnIndex accepted" case; keep both-or-neither / UUID / negative.
- [x] 6.6 `ConditionExpressionEvaluatorTest`: add the non-contiguous `turn.last` case (survivors `0,3` → false on `0`, true on `3`); keep single-turn `0/1/true`.
- [~] 6.7 `MultiTurnConversationRunFunctionalTests`: flipped the gap and filter-middle-hole cases to runnable (survivors run, authored indices preserved, `last_turn_index` asserted) and added an invalid-surviving-turn case yielding the `0/0` sentinel; added `LAST_TURN_INDEX` to the analytics test-data helper projection. Compiles (`compileTestJava` green). **NOT executed** — Testcontainers requires Docker, unavailable in this environment. `turn.last`-gated-metric end-to-end coverage deferred to the unit test (`ConditionExpressionEvaluatorTest` non-contiguous case). Run before merge: `./gradlew test --tests "*MultiTurnConversationRunFunctionalTests"`.

## 7. Docs

- [x] 7.1 Update the AGENTS.md multi-turn inline-conventions paragraph: drop contiguity/prefix language; state ordering lifted, authored indices preserved, `broken` = invalid-surviving-turn ∨ over-cap, `last_turn_index` carrier for `turn.last`.
- [N/A] 7.2 `docs/patterns/suite-run-snapshot.md` contains no multi-turn/conversation content (verified via grep) — nothing to reword. The snapshot-integrity behavior is documented in AGENTS.md (7.1) and the delta specs instead.
- [x] 7.3 Update `docs/database-schema.md`: add `last_turn_index` to the `test_case_run_results` table (analytics), note V1.15 migration.

## 8. Verification

- [x] 8.1 `./gradlew spotlessApply` then `./gradlew checkstyleMain checkstyleTest`.
- [~] 8.2 Full `./gradlew clean build` **NOT run** — it executes the Testcontainers functional suite, which requires Docker (unavailable here). Ran instead and green: `compileJava`/`compileTestJava`, `checkstyleMain`/`checkstyleTest`, and targeted `test` for all touched unit tests + `LayeredArchitectureTest` + `LoggingConventionTest` + `JooqSchemaDriftTest`. Run `./gradlew clean build` on a Docker-enabled machine/CI before merge.
- [x] 8.3 `/opsx:verify` static check, then `openspec validate lift-multiturn-ordering-constraints --strict`.
