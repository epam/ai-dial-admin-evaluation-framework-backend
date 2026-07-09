## 1. Data model — migrations & jOOQ

- [ ] 1.1 Add `V1.12__AddTurnColumnsToTestCaseRunResults.sql`: `ADD COLUMN turn_index INTEGER NOT NULL DEFAULT 0`, `total_turns INTEGER NOT NULL DEFAULT 1`; drop `uq_results_run_case_index` and recreate as `(test_suite_run_id, test_case_id, run_index, turn_index, created_at_ms)` (use `CREATE UNIQUE INDEX CONCURRENTLY` + non-transactional migration if analytics tables are large). Done: migration applies cleanly on a populated table, existing rows read `0/1`.
- [ ] 1.2 Add `V1.13__AddTurnColumnsToEvalSummaries.sql`: same two columns; recreate `uq_eval_summaries_natural_key` as `(test_suite_run_id, test_case_id, run_index, turn_index, computation_id, created_at_ms)`. Done: migration applies; existing rows read `0/1`.
- [ ] 1.3 Edit `V1.25__AddMultiStepToTestSuites.sql` in place to name the column `multi_turn` (branch-only; note `flyway repair` for local DBs). Done: fresh migrate yields `test_suites.multi_turn`.
- [ ] 1.4 Run `./gradlew generateJooq`; commit the generated diff under `src/main/java-generated/`. Done: generated tables/records carry `turn_index`/`total_turns` and `multi_turn`, `spotlessCheck` excludes generated sources.
- [ ] 1.5 Update `docs/database-schema.md` for the two analytics tables (columns, new unique indexes) and `test_suites.multi_turn`. Done: doc matches migrations.

## 2. Analytics models, mappers, repositories

- [ ] 2.1 Add `turnIndex`/`totalTurns` (`int`) to `data.db.analytics.model.TestCaseRunResult` and `EvalSummary`. Done: fields present; Lombok builders compile.
- [ ] 2.2 Update the result and summary `*RecordMapper`s to map the two columns. Done: unit round-trip maps `0/1` and `i/N`.
- [ ] 2.3 Update `TestCaseRunResultRepository` and eval-summary batch-write insert column lists **and** the `ON CONFLICT (<natural key>)` target to include `turn_index`. Done: idempotent re-insert of a turn row is a no-op; distinct turns coexist.

## 3. Executor — per-turn emission

- [ ] 3.1 Change `EvaluationWorker.execute(...)` to return `List<TestCaseRunResult>`; single-turn/MCP paths return a one-element list with `turnIndex=0,totalTurns=1`. Done: unit test asserts one-element list for single-turn.
- [ ] 3.2 Rename `MultiStepConversationExecutor` → `MultiTurnConversationExecutor` and rewrite `execute` to return one scalar `TestCaseRunResult` per turn (reuse `ResponseColumnExtractor` + `TestCaseRunResultFactory`); set `turn_index=i`, `total_turns=planned N`, `test_case_data=turnPlan.project(data,i)`, `request_body`=full accumulated request. Done: unit test on a 3-turn case yields 3 scalar rows with correct turn fields.
- [ ] 3.3 Implement abort semantics: `k` SUCCESS rows + 1 ERROR row (`total_turns=N`); data-shape failure → single ERROR row `turn_index=0,total_turns=0`. Done: unit tests for mid-abort and each data-shape failure.
- [ ] 3.4 Delete `MultiStepColumnAccumulator`, `MultiStepWarningAccumulator`, `MultiStepResultAssembler`. Done: classes removed, no references remain.
- [ ] 3.5 Update `InProcessEvaluationExecutor` to call `resultBatchWriter.addResults(buffer, workerResults)`; drop the single-result path. Done: functional run persists all turn rows.

## 4. Progress reporting

- [ ] 4.1 Replace `ResultBatchWriter.addResult` with `addResults(buffer, List<TestCaseRunResult>)`: +1 completed-conversation counter per call, buffer all rows, flush by row batch size, `notifyProgress(conversationsCompleted, totalCases)`. Done: unit test — 3-turn convo buffers 3 rows, advances progress by 1.

## 5. Metric evaluation — JSONata/array rollback + turn context

- [ ] 5.1 Remove `jsonataExpression` from `ResponseBindingSourceDto` and `TestCaseBindingSourceDto` (+ OpenAPI schema). Done: fields gone; DTOs compile.
- [ ] 5.2 Simplify `BindingResolver.resolveSource` to return the scalar column value directly; remove `applyJsonataSelector` and the `JsonataEvaluationService` dependency. Done: unit tests for scalar Response/TestCase/Constant + missing-column fail-fast + present-null.
- [ ] 5.3 Remove `ArrayBindingTypeMismatchDetector` and its use in `MetricEvaluationWorker`. Done: class removed, no references.
- [ ] 5.4 Remove the `INVALID_EXPRESSION` checks (3b/4b) from `MetricDefinitionValidationService` (and drop the now-unused `ValidationWarningCode.INVALID_EXPRESSION`). Done: validation no longer emits `INVALID_EXPRESSION`.
- [ ] 5.5 Remove `ExtractionWarningDto.stepIndex` (and any `ConversationOutcome` stamping). Done: warnings serialize without `stepIndex`.
- [ ] 5.6 Add `turnIndex`/`totalTurns` (`int`) to `ConditionContext`; populate them in `InProcessMetricEvaluationExecutor` from the result row. Done: unit test — context carries turn position; single-turn `0/1`.
- [ ] 5.7 Update `EvalSummaryBatchWriteItemDto` + `buildItem` to carry `turnIndex`/`totalTurns` and store `extractedColumns` verbatim (scalar). Make the two DTO fields **optional** (nullable `Integer`, not primitive `int`), defaulting to `0`/`1` when omitted so external single-turn callers of `POST /api/v1/analytics/eval-summaries` stay byte-compatible (a primitive `int` would send `0` for `totalTurns` and override the DB default of `1`). Done: one summary per turn with turn fields; an item omitting the fields persists `turn_index=0,total_turns=1`.

## 6. API & export exposure

- [ ] 6.1 Add `turnIndex`/`totalTurns` to `TestCaseRunResultResponseDto` and `EvalSummaryResponseDto` (+ MapStruct mappers, `@Schema` examples). Done: list responses include the fields.
- [ ] 6.2 Add `turn_index ASC` to the within-conversation default ordering for the results/summaries read paths. Done: multi-turn rows returned turn-ordered.
- [ ] 6.3 Add `turnIndex`/`totalTurns` CSV export identity columns immediately after `runIndex`. Done: export header + cells present; single-turn shows `0/1`.

## 7. Vocabulary rename (turn everywhere)

- [ ] 7.1 Rename `multi_step`/`multiStep`→`multi_turn`/`multiTurn` across `TestSuite` model, `TestSuiteRequestDto`/`ResponseDto`, `SuiteSnapshotDto`, mappers, and `EvaluationContext.isSnapshotMultiStep()`→`isSnapshotMultiTurn()`. Done: no `multiStep` identifiers remain.
- [ ] 7.2 Rename `MAX_CONVERSATION_STEPS`→`MAX_CONVERSATION_TURNS` in `constants/ValidationConstants.java` and update all references (`service/domain/job/ConversationTurnPlanner.java`, `SuiteValidationService`); rename remaining `step`/`stepIndex` identifiers→`turn`/`turnIndex` across the multi-turn executor/planner path. Done: no residual `step` vocabulary in the multi-turn path; constant defined once in `ValidationConstants` and referenced by name everywhere.
- [ ] 7.3 Update `SuiteValidationService` multi-turn validation to reference `multiTurn` + `MAX_CONVERSATION_TURNS`. Done: multi-turn suite validation tests pass.

## 8. Tests

- [ ] 8.1 Unit tests: `MultiTurnConversationExecutor` (per-turn rows, abort, data-error), `BindingResolver` (scalar-direct), record mappers (turn columns), `ResultBatchWriter.addResults`, `ConditionContext` population. Done: `./gradlew test --tests "*"` for the new/updated unit classes passes.
- [ ] 8.2 Functional (`@PostgresFunctionalTests`): multi-turn run writes N unique per-turn result + summary rows (unique index holds); conversation-granular progress ≤100%; CSV export per-turn rows with `turnIndex`/`totalTurns`; condition evaluated per turn. Done: functional suite passes.
- [ ] 8.3 Functional regression: a non-multi-turn suite behaves byte-identically end-to-end with `turn_index=0,total_turns=1`. Done: single-turn regression test passes.

## 9. Docs, specs, config

- [ ] 9.1 Update AGENTS.md per AGENTS.md Maintenance guidelines (done: rewrite the multi-step inline convention in turn vocabulary; reflect per-turn rows, `MultiTurnConversationExecutor`, `MAX_CONVERSATION_TURNS`, removed jsonata/array machinery).
- [ ] 9.2 Update `openspec/specs/README.md` per Spec Index Maintenance Policy (done: entries reflect the renamed `multi-turn-conversation` capability and current summaries).
- [ ] 9.3 During sync/archive: rename spec folder `multi-step-conversation`→`multi-turn-conversation` and **all** `step`→`turn` requirement headers (not just the "Multi-step" prefix): "Multi-step conversation contract"→"Multi-turn conversation contract", "Per-step turn loop with full-history resend"→"Per-turn loop with full-history resend", "Fail-fast on step failure"→"Fail-fast on turn failure". **Also rewrite the affected requirement BODIES (not just headers + folder)** so the vocabulary matches the code: replace `step`→`turn` and `MAX_CONVERSATION_STEPS`→`MAX_CONVERSATION_TURNS` inside the `multi-step-conversation` requirements "Multi-step conversation contract", "Turn count derived per test case" (which cites `MAX_CONVERSATION_STEPS`), and "Assistant reply extraction", and inside the `eval-execution-engine` "One concurrency permit per conversation" body. Verify delta merge lost no requirements via `git diff`. Done: main specs renamed, requirement bodies use turn vocabulary, no requirement loss.
- [ ] 9.4 Run `./gradlew spotlessApply checkstyleMain checkstyleTest test` (and `clean build`); fix violations. Done: green build, Checkstyle clean.
