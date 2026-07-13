## 1. Database & jOOQ (additive — build stays green)

> Note: `test_cases` and `test_case_run_inputs` are already in `development`, so these are NEW forward migrations (additive), not in-place reshapes. Dropping `test_suites.multi_turn` (branch-only `V1.25`) is coupled to the array-path code removal and is deferred to Group 7 (atomic cutover) to keep each iteration compiling.

- [x] 1.1 New forward migration (`V1.27__AddConversationColumnsToTestCases.sql`): add `test_cases.conversation_id VARCHAR(36)` (nullable) and `turn_index INTEGER` (nullable); add index `(dataset_id, conversation_id)`; add partial unique `(dataset_id, conversation_id, turn_index) WHERE conversation_id IS NOT NULL`. (done: applies cleanly on a fresh DB)
- [x] 1.2 New forward migration (`V1.28__AddConversationColumnsToTestCaseRunInputs.sql`): add nullable `conversation_id VARCHAR(36)`, `total_turns INTEGER`, `turns JSONB` (assembled ordered turns), and `broken BOOLEAN NOT NULL DEFAULT false` (broken-conversation marker). Existing scalar columns unchanged (single-turn path untouched). (done: table supports one input per execution unit)
- [x] 1.3 Run `./gradlew generateJooq` and commit regenerated sources under `src/main/java-generated/`. (done: generated meta tables reflect 1.1–1.2; JooqSchemaDriftTest passes)
- [x] 1.4 Update `docs/database-schema.md` for the `test_cases` and `test_case_run_inputs` additions. (done: doc matches migrations)

## 2. Test-case model, DTOs & validation (authoring)

- [x] 2.1 Add `conversationId` (UUID) and `turnIndex` (Integer) to the `TestCase` model + `TestCaseRecordMapper`. (done: fields mapped from the new columns)
- [x] 2.2 Add the two fields to `TestCaseRequestDto`, `TestCaseResponseDto`, `TestCaseBatchPutItemDto` with OpenAPI `@Schema` examples; keep them top-level (never inside `data`). (done: DTOs expose the fields with examples)
- [x] 2.3 Extend `TestCaseValidationService` / `TestCaseService` with per-row write validation: both-or-neither, valid UUID, `turnIndex >= 0`, `turnIndex < MAX_CONVERSATION_TURNS` (→ 400); duplicate `(conversationId, turnIndex)` → 409. `data` still validated against the dataset schema (scalars). (done: invalid inputs rejected with correct codes)
- [x] 2.4 Keep the bulk-patch whitelist at `{testCaseName, data}` (conversationId/turnIndex not patchable); confirm mapper/repository unaffected. (done: bulk patch cannot mutate grouping fields)
- [x] 2.5 Reuse/confirm `MAX_CONVERSATION_TURNS` in the constants class for both the write cap and the snapshot cap. (done: single source of truth)

## 3. CSV import/export

- [x] 3.1 Add reserved headers `conversationId` and `turnIndex` in `CsvImportService` (same mechanism as `TEST_CASE_NAME_HEADER`); non-reserved columns still map into `data`; apply the per-row write validation from 2.3. (done: CSV round-trips the two fields; malformed values rejected per row)
- [x] 3.2 Add the two columns to CSV export. (done: export → import round-trip reproduces conversations)

## 4. Selection, snapshot & counting

- [x] 4.1 Make the runnable selector conversation-aware: single-turn rows filtered per-row as today; a conversation is runnable only if all its turns are valid, form a contiguous enabled prefix (tail-only disable), and (if `testCaseFilter` set) ALL turns match — aggregate per `conversation_id` (`RunnableTestCaseSelector` / `QueryDslRunnableTestCaseSelector` / `PostgresTestCaseRepository`). (done: selector returns whole runnable conversations)
- [x] 4.2 Make `RunnableTestCaseCounter` / guard #4 count runnable **conversations**; set `number_of_test_cases` to the conversation count. (done: zero-runnable guard and totals count conversations)
- [x] 4.3 In `TestSuiteEvaluationJob.attemptSnapshot`: page by distinct `conversation_id`, group + order turns, validate contiguity/completeness (missing turn 0 / gap / dup index / any invalid turn / over-cap → broken), and freeze each conversation into one assembled `test_case_run_inputs` unit with deterministic `position`; write a broken marker for broken conversations. (done: snapshot materializes assembled inputs and markers)

## 5. Execution

- [x] 5.1 Dispatch one worker task per assembled input (× `runIndex`) in `InProcessEvaluationExecutor`; drop the array-projection branch. (done: execution unit is a conversation)
- [x] 5.2 Rework `MultiTurnConversationExecutor` to iterate the frozen ordered turns (scalar `data` per turn) instead of `TurnPlan.project`; re-send accumulated `messages`, append `choices[0].message`; emit one `TestCaseRunResult` per surviving turn with `turn_index` (authored) and `total_turns` = surviving count. (done: per-turn rows produced from discrete turns)
- [x] 5.3 Emit one ERROR result row (sentinel `turn_index=0, total_turns=0`) for a broken-marker input without calling the model; ensure the run continues. (done: broken conversation is visible and non-fatal)
- [x] 5.4 Add the run-creation guard in `TestSuiteRunService.createRun`: MCP_TOOL suite bound to a dataset containing any conversation rows → 409 INVALID_OPERATION (cheap `EXISTS`). (done: MCP + conversation rows rejected)

## 6. Metric / condition layer

- [x] 6.1 Roll back the JSONata/array-binding metric machinery; `BindingResolver` resolves each binding to the raw scalar column value per turn-result. (done: metric binding is scalar-only)
- [x] 6.2 Source `ConditionContext.turnIndex`/`totalTurns` from the result row's grouping-derived turn position (unchanged condition behavior). (done: conditions evaluate per turn with correct turn position)

## 7. Removals (atomic cutover — do 7.1–7.3 together so the build stays green)

- [x] 7.1 Remove the suite `multiTurn` flag from `TestSuite*Dto`, `TestSuiteMapper`, `TestSuiteRecordMapper`, `TestSuite` model, `PostgresTestSuiteRepository`, and the `SuiteSnapshotDto` field (+ `SuiteSnapshotBuilder`, `EvaluationContext.isSnapshotMultiTurn`). (done: no suite-level multi-turn flag remains)
- [x] 7.2 Delete `TurnPlan`, `ConversationTurnPlanner`, the array-projection code path, and `SuiteValidationService.validateMultiTurnBody`. (done: array-era classes/paths removed)
- [x] 7.3 Reshape branch-only `V1.25` in place to drop the `test_suites.multi_turn` addition (no throwaway column promoted), then `./gradlew generateJooq` and commit. Do this in the SAME batch as 7.1–7.2. (done: column gone, generated sources updated, build compiles clean)

## 8. Docs & conventions

- [x] 8.1 Update `AGENTS.md` inline conventions ("Multi-turn conversation" and the turn-context part of "Conditional metric execution") to the row-based model per AGENTS.md Maintenance guidelines. (done: conventions describe rows, not arrays/flag)
- [x] 8.2 Update `openspec/specs/README.md` per Spec Index Maintenance Policy if the `multi-turn-conversation` summary is now inaccurate. (done: index reflects the row-based model)
- [x] 8.3 Update OpenAPI examples for test-case endpoints (conversation rows) and remove `multiTurn` from suite examples. (done: examples reflect the new contract)

## 9. Tests

- [x] 9.1 Unit tests: write-time validation (both-or-neither, UUID, turnIndex bounds, dup index), CSV reserved-column parsing, per-turn scalar binding resolution. (done: unit tests pass)
- [x] 9.2 Functional tests (Testcontainers): authoring + CSV round-trip; snapshot grouping/contiguity/broken-conversation ERROR row; tail-only disable truncation; invalid-turn breaks conversation; filter all-match include/exclude; runnable-conversation counting + zero-runnable guard; MCP + conversation rows → 409; per-turn metric/condition evaluation incl. `turn.last`. (done: functional suite passes)
- [x] 9.3 Run `./gradlew spotlessApply checkstyleMain checkstyleTest test` and fix violations; confirm `LayeredArchitectureTest`, `JooqSchemaDriftTest`, `LoggingConventionTest` pass. (done: full build green)
